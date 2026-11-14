// Package partitions manages range-partitioned tables (partitions contract): partitions are
// created ahead of time, old ones are dropped whole (or written to an object store first), and a
// health check goes down when the next partition is missing. A large append-mostly table cannot be
// cleaned with DELETE at volume; dropping a partition is O(1).
//
// A table is partitioned on its framework id ([ByID]: "<prefix>_<TSID>", fixed width and time
// ordered, so the primary key stays "id" and an id lookup prunes to one partition) or on a
// timestamptz column ([ByColumn]). Which tables are partitioned and how long they are kept are
// code, given to [Build]:
//
//	d := partitions.Declare(app)                                  // in main
//	err = database.Migrate(ctx, b, dbs)                           // in build, first
//	ps, err := partitions.Build(ctx, b, d, dbs,
//		partitions.WithTable(partitions.Table{
//			Name: "readings", By: partitions.ByID("rdg"),
//			Interval: partitions.Month, Ahead: 3,
//			Retention: partitions.DropAfter(90 * 24 * time.Hour),
//		}),
//		partitions.WithLocks(locksInstance)) // one replica runs the loop
package partitions

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/metrics"
	"github.com/flowcatalyst/framework/go/objectstore"
)

// ModuleID is the partitions module's id; it contributes the migration set of fw_partition_archives.
const ModuleID = "partitions"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the module's migration set as embedded (fw_partition_archives, the manifest of
// archived partitions).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // the directory is embedded above
	}
	return sub
}

// Declared is the partitions module's declaration, from [Declare].
type Declared struct{}

// Declare declares the partitions module: it contributes the migration set of fw_partition_archives,
// which the framework runs on the default database instance. It declares no setting: the tables
// are code, given to [Build].
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).Migrations(Migrations())
	return &Declared{}
}

const (
	// DefaultEvery is the default wait between passes of the loop.
	DefaultEvery = time.Hour
	// LoopName is the name of the background loop (health check background:partitions).
	LoopName = "partitions"
	// LockName is the service lock that admits one replica to a pass, when [WithLocks] is given.
	LockName = "fw-partitions"

	// firstPassDelay is the wait before the loop's first pass: Build already ran an ensure pass, so
	// the first loop pass is for retention (at most this long after a start).
	firstPassDelay      = time.Minute
	lockLease           = 5 * time.Minute
	defaultSegmentBytes = 16 << 20
	ddlLockTimeout      = "10s"
)

// Option configures [Build].
type Option func(*options)

type options struct {
	tables       []Table
	stores       *objectstore.Stores
	locks        *locks.Locks
	every        time.Duration
	segmentBytes int
}

// WithTable declares a partitioned table. A table given twice is a start-up error.
func WithTable(t Table) Option { return func(o *options) { o.tables = append(o.tables, t) } }

// WithObjectStores gives the stores that [ArchiveThenDropAfter] names; a table that names one not
// in it, or any when this option is absent, is a start-up error.
func WithObjectStores(s *objectstore.Stores) Option { return func(o *options) { o.stores = s } }

// WithLocks gates the loop with the service lock "fw-partitions" on the default lock instance, so
// one replica runs each pass. Without it every replica runs the passes; they are safe to run
// concurrently (creation is serialised by an advisory lock, a drop is one transaction) but waste
// work, and two replicas may both archive the same partition (the archive is rewritten under the
// same keys).
func WithLocks(l *locks.Locks) Option { return func(o *options) { o.locks = l } }

// Every sets the wait between passes (default [DefaultEvery]; 10ms to 24h as for any loop).
func Every(d time.Duration) Option { return func(o *options) { o.every = d } }

// ArchiveSegmentBytes sets the compressed size at which an archive is cut into another object
// (default 16 MiB). An object store takes whole values only (object-stores contract), so a large
// partition is written as several objects; keep this below the stores' MaxBytes.
func ArchiveSegmentBytes(n int) Option { return func(o *options) { o.segmentBytes = n } }

type target struct {
	Table
	db        *database.DB
	schema    string
	rel       string
	qualified string
	store     objectstore.Store

	ahead    *metrics.GaugeSeries
	dropped  *metrics.CounterSeries
	archived *metrics.CounterSeries
	rows     *metrics.CounterSeries
}

func (tg *target) ident() string { return quoteIdent(tg.schema, tg.rel) }

func (tg *target) childIdent(name string) string { return quoteIdent(tg.schema, name) }

// Partitions manages the declared tables. Build returns it; tests and tools may run a pass or
// ensure a range by hand.
type Partitions struct {
	log      *slog.Logger
	clock    fw.Clock
	targets  []*target
	byName   map[string]*target
	segment  int
	failures *metrics.Counter

	strayMu sync.Mutex
	stray   map[string]bool
}

// Tables returns the declared table names, in declaration order.
func (p *Partitions) Tables() []string {
	out := make([]string, len(p.targets))
	for i, t := range p.targets {
		out[i] = t.Name
	}
	return out
}

// Build checks the declarations, validates every table against its database (it exists, is
// PARTITION BY RANGE on exactly the declared key, and for a [ByID] table the key column is
// COLLATE "C"), runs one ensure pass so the first insert finds its partition, adds the health check
// partitions:<table> (critical) to each, and registers the background loop "partitions". Call it
// after database.Migrate. Every problem is returned at once as a *fw.StartupError.
func Build(ctx context.Context, b *fw.Boot, _ *Declared, dbs *database.Databases, opts ...Option) (*Partitions, error) {
	o := options{every: DefaultEvery, segmentBytes: defaultSegmentBytes}
	for _, f := range opts {
		f(&o)
	}
	p := &Partitions{
		log: b.Logger(ModuleID), clock: b.Clock(), byName: map[string]*target{},
		segment: o.segmentBytes, stray: map[string]bool{},
	}
	var problems []fw.Problem
	problem := func(table, msg string) {
		problems = append(problems, fw.Problem{Module: ModuleID, Key: "table " + table, Message: msg})
	}
	if o.segmentBytes < 1 {
		problems = append(problems, fw.Problem{Module: ModuleID, Key: "ArchiveSegmentBytes", Message: "must be at least 1"})
	}

	reg := b.Metrics()
	ahead, err := reg.Gauge("fw_partitions_ahead", "Partitions that exist after the current interval, per table.", "table")
	if err != nil {
		return nil, err
	}
	failures, err := reg.Counter("fw_partition_failures_total",
		"Failed steps of partition passes: step is ensure, retention (drop) or archive.", "table", "step")
	if err != nil {
		return nil, err
	}
	dropped, err := reg.Counter("fw_partitions_dropped_total", "Partitions dropped by retention.", "table")
	if err != nil {
		return nil, err
	}
	archived, err := reg.Counter("fw_partitions_archived_total", "Partitions written to an object store and verified.", "table")
	if err != nil {
		return nil, err
	}
	rows, err := reg.Counter("fw_partition_rows_archived_total", "Rows written to archives.", "table")
	if err != nil {
		return nil, err
	}
	p.failures = failures

	seenDecl := map[string]bool{}
	for _, t := range o.tables {
		if seenDecl[t.Name] {
			problem(t.Name, "declared twice")
			continue
		}
		seenDecl[t.Name] = true
		if msgs := t.check(); len(msgs) > 0 {
			for _, m := range msgs {
				problem(t.Name, m)
			}
			continue
		}
		tg := &target{Table: t}
		if t.Retention.kind == retArchive {
			if o.stores == nil {
				problem(t.Name, fmt.Sprintf("retention names object store %q, but partitions.WithObjectStores was not given", t.Retention.store))
				continue
			}
			s, err := o.stores.Get(t.Retention.store)
			if err != nil {
				problem(t.Name, "retention: "+err.Error())
				continue
			}
			tg.store = s
		}
		var db *database.DB
		if t.Database == "" {
			def, ok := dbs.Default()
			if !ok {
				problem(t.Name, "no database instance is configured")
				continue
			}
			db = def
		} else if db, err = dbs.Get(t.Database); err != nil {
			problem(t.Name, err.Error())
			continue
		}
		tg.db = db
		p.targets = append(p.targets, tg)
		p.byName[t.Name] = tg
	}

	// Against the database: resolve names, find tables declared twice under different spellings,
	// check the shape.
	seenRel := map[string]string{}
	var valid []*target
	for _, tg := range p.targets {
		msgs := tg.validate(ctx)
		for _, m := range msgs {
			problem(tg.Name, m)
		}
		if len(msgs) > 0 {
			continue
		}
		id := tg.db.Name() + "/" + tg.qualified
		if first, dup := seenRel[id]; dup {
			problem(tg.Name, fmt.Sprintf("is the same table as %s: declared twice", first))
			continue
		}
		seenRel[id] = tg.Name
		valid = append(valid, tg)
	}
	p.targets = valid
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}

	for _, tg := range p.targets {
		tg.ahead = ahead.With(tg.qualified)
		tg.dropped = dropped.With(tg.qualified)
		tg.archived = archived.With(tg.qualified)
		tg.rows = rows.With(tg.qualified)
		if err := p.ensureCurrent(ctx, tg, p.clock.Now()); err != nil {
			problem(tg.Name, "cannot create its partitions: "+err.Error())
		}
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}

	for _, tg := range p.targets {
		b.Health("partitions:"+tg.Name, fw.Critical, p.healthCheck(tg))
	}
	if len(p.targets) > 0 {
		lo := background.Options{Interval: o.every, InitialDelay: min(o.every, firstPassDelay)}
		if o.locks != nil {
			gate, err := locks.ServiceGate(o.locks, LockName, lockLease)
			if err != nil {
				return nil, fmt.Errorf("partitions: %w", err)
			}
			lo.Gate = gate
		}
		b.Loop(LoopName, lo, func(ctx context.Context, _ background.Tick) error { return p.Pass(ctx) })
	}
	return p, nil
}

// Pass runs one ensure-then-retention pass over every table now. A table's failure does not stop
// the others; the errors are joined. The background loop calls it.
func (p *Partitions) Pass(ctx context.Context) error {
	var errs []error
	for _, tg := range p.targets {
		if err := p.passTable(ctx, tg, p.clock.Now()); err != nil {
			errs = append(errs, fmt.Errorf("table %s: %w", tg.Name, err))
		}
	}
	return errors.Join(errs...)
}

func (p *Partitions) passTable(ctx context.Context, tg *target, now time.Time) error {
	var errs []error
	if err := p.ensureCurrent(ctx, tg, now); err != nil {
		p.failures.With(tg.qualified, "ensure").Inc()
		p.log.Error("partition ensure failed", slog.String("table", tg.qualified), slog.String("error", err.Error()))
		errs = append(errs, fmt.Errorf("ensure: %w", err))
	}
	if err := p.retain(ctx, tg, now); err != nil {
		errs = append(errs, err)
	}
	return errors.Join(errs...)
}

// healthCheck is the check partitions:<table>: down when the partition for the next interval does
// not exist, read from the catalog, because inserts fail from the boundary on.
func (p *Partitions) healthCheck(tg *target) func(context.Context) error {
	return func(ctx context.Context) error {
		kids, err := tg.children(ctx, tg.db.Pool())
		if err != nil {
			return errors.New("cannot read the catalog")
		}
		next := tg.Interval.Next(tg.Interval.Start(p.clock.Now()))
		want := PartitionName(tg.rel, next)
		if !slices.ContainsFunc(kids, func(c child) bool { return c.name == want }) {
			return fmt.Errorf("the partition for the next interval (from %s) does not exist; inserts will fail at the boundary",
				next.Format("2006-01-02"))
		}
		return nil
	}
}

// Ensure creates the partitions that cover [from, to] (both inclusive instants, in any time zone)
// for the declared table, for imports and backfills that write outside the window the loop keeps.
// It refuses more than [MaxEnsureRange] partitions in one call, and a window before 1970. Note that
// retention removes a partition the next pass if it is older than the retention.
func (p *Partitions) Ensure(ctx context.Context, table string, from, to time.Time) (created int, err error) {
	tg, ok := p.byName[table]
	if !ok {
		return 0, fmt.Errorf("table %q is not declared; declared: %s", table, strings.Join(p.Tables(), ", "))
	}
	if to.Before(from) {
		return 0, errors.New("to is before from")
	}
	if from.Before(time.Unix(0, 0)) {
		return 0, errors.New("from is before 1970-01-01")
	}
	var starts []time.Time
	for s := tg.Interval.Start(from); !s.After(to); s = tg.Interval.Next(s) {
		if len(starts) >= MaxEnsureRange {
			return 0, fmt.Errorf("that range needs more than %d partitions; ensure it in several calls", MaxEnsureRange)
		}
		starts = append(starts, s)
	}
	created, err = p.ensureStarts(ctx, tg, starts)
	if err == nil {
		p.setAhead(ctx, tg, p.clock.Now())
	}
	return created, err
}
