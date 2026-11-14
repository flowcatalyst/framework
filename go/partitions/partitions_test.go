package partitions_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/objectstore"
	"github.com/flowcatalyst/framework/go/partitions"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{{Module: partitions.ModuleID, Files: partitions.Migrations()}},
			slog.New(slog.DiscardHandler))
	}))
}

var t0 = time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)

const (
	idTable  = `CREATE TABLE readings (id text COLLATE "C" PRIMARY KEY, recorded_at timestamptz NOT NULL DEFAULT now(), payload text) PARTITION BY RANGE (id)`
	colTable = `CREATE TABLE samples (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)`
)

type logBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *logBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *logBuf) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

type rig struct {
	t     *testing.T
	url   string
	db    *database.DB
	clock *fwtest.Clock
	logs  *logBuf
}

func newRig(t *testing.T) *rig {
	t.Helper()
	d := fwtest.NewDatabase(t)
	db, err := database.Open(t.Context(), "main", d.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return &rig{t: t, url: d.URL, db: db, clock: fwtest.NewClock(t0), logs: &logBuf{}}
}

func (r *rig) configDir() string {
	dir := r.t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte("database:\n  instances:\n    main:\n      url: set-by-the-test\n"), 0o600); err != nil {
		r.t.Fatal(err)
	}
	return dir
}

func (r *rig) exec(sql string, args ...any) {
	r.t.Helper()
	if _, err := r.db.Pool().Exec(r.t.Context(), sql, args...); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
}

func (r *rig) strings(sql string, args ...any) []string {
	r.t.Helper()
	rows, err := r.db.Pool().Query(r.t.Context(), sql, args...)
	if err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			r.t.Fatal(err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		r.t.Fatal(err)
	}
	return out
}

func (r *rig) count(sql string, args ...any) int {
	r.t.Helper()
	var n int
	if err := r.db.Pool().QueryRow(r.t.Context(), sql, args...).Scan(&n); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
	return n
}

// parts lists the names of the partitions attached to a table, sorted.
func (r *rig) parts(table string) []string {
	r.t.Helper()
	out := r.strings(`SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = $1::regclass ORDER BY 1`, table)
	if out == nil {
		out = []string{}
	}
	return out
}

type config struct {
	tables  []partitions.Table
	store   objectstore.StoreFactory
	every   time.Duration
	segment int
	lock    bool
	// lockStore, when set, is the lock store shared by replicas (instead of a private memory store)
	lockStore locks.Store
}

type started struct {
	ps     *partitions.Partitions
	run    *fw.Running
	b      *fw.Boot
	stores *objectstore.Stores
}

func (r *rig) start(c config) (*started, error) {
	r.t.Helper()
	app := fw.New("partitions-test",
		fw.WithEnvironment(map[string]string{"APP_ENV": "test", "DATABASE_INSTANCES_MAIN_URL": r.url}),
		fw.WithConfigDir(r.configDir()), fw.WithClock(r.clock),
		fw.WithLogger(slog.New(slog.NewTextHandler(r.logs, &slog.HandlerOptions{Level: slog.LevelInfo}))),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	dbDecl := database.Declare(app)
	pd := partitions.Declare(app)
	od := objectstore.Declare(app)
	ld := locks.Declare(app)
	out := &started{}
	run, err := app.Start(r.t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		out.b = b
		dbs, err := database.Connect(ctx, b, dbDecl)
		if err != nil {
			return nil, err
		}
		var opts []partitions.Option
		for _, tb := range c.tables {
			opts = append(opts, partitions.WithTable(tb))
		}
		if c.store != nil {
			out.stores, err = objectstore.Build(ctx, b, od, objectstore.WithStore("archive", c.store))
			if err != nil {
				return nil, err
			}
			opts = append(opts, partitions.WithObjectStores(out.stores))
		}
		if c.every > 0 {
			opts = append(opts, partitions.Every(c.every))
		}
		if c.segment > 0 {
			opts = append(opts, partitions.ArchiveSegmentBytes(c.segment))
		}
		if c.lock || c.lockStore != nil {
			factory := locks.Memory()
			if c.lockStore != nil {
				factory = func(context.Context, *fw.Boot) (locks.Store, error) { return c.lockStore, nil }
			}
			l, err := locks.Build(ctx, b, ld, locks.WithStore("main", factory))
			if err != nil {
				return nil, err
			}
			opts = append(opts, partitions.WithLocks(l))
		}
		out.ps, err = partitions.Build(ctx, b, pd, dbs, opts...)
		return nil, err
	})
	if err != nil {
		return nil, err
	}
	r.t.Cleanup(func() { _ = run.Shutdown() })
	out.run = run
	return out, nil
}

func (r *rig) mustStart(c config) *started {
	r.t.Helper()
	s, err := r.start(c)
	if err != nil {
		r.t.Fatal(err)
	}
	return s
}

func idAt(at time.Time) string {
	return fw.NewIds(fwtest.NewClock(at)).Prefixed(fw.MustIDPrefix("rdg"))
}

func monthly(name string, by partitions.Key, ret partitions.Retention) partitions.Table {
	return partitions.Table{Name: name, By: by, Interval: partitions.Month, Ahead: 3, Retention: ret}
}

func days(n int) time.Duration { return time.Duration(n) * 24 * time.Hour }

func metric(t *testing.T, b *fw.Boot, name string, labels ...string) float64 {
	t.Helper()
	for _, f := range b.Metrics().Snapshot().Families {
		if f.Name != name {
			continue
		}
		for _, s := range f.Samples {
			if slices.Equal(s.LabelValues, labels) {
				return s.Value
			}
		}
	}
	return 0
}

func eventually(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestStartupEnsuresCurrentAndAheadForBothKeys(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.exec(colTable)
	s := r.mustStart(config{tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90))),
		{Name: "public.samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Week, Ahead: 2, Retention: partitions.KeepForever()},
	}})
	want := []string{"readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201"}
	if got := r.parts("readings"); !slices.Equal(got, want) {
		t.Errorf("readings partitions %v, want %v", got, want)
	}
	// 2026-11-14 is a Saturday: the week starts Monday 2026-11-09
	wantWeeks := []string{"samples_p20261109", "samples_p20261116", "samples_p20261123"}
	if got := r.parts("samples"); !slices.Equal(got, wantWeeks) {
		t.Errorf("samples partitions %v, want %v", got, wantWeeks)
	}
	if got := metric(t, s.b, "fw_partitions_ahead", "public.readings"); got != 3 {
		t.Errorf("ahead gauge %v, want 3", got)
	}
	if got := metric(t, s.b, "fw_partitions_ahead", "public.samples"); got != 2 {
		t.Errorf("ahead gauge %v, want 2", got)
	}
	// the first inserts find their partitions, and land in the right ones
	id := idAt(t0)
	r.exec("INSERT INTO readings (id) VALUES ($1)", id)
	if got := r.strings("SELECT tableoid::regclass::text FROM readings WHERE id = $1", id); !slices.Equal(got, []string{"readings_p20261101"}) {
		t.Errorf("row went to %v", got)
	}
	r.exec("INSERT INTO samples (id, recorded_at) VALUES ('a', $1)", time.Date(2026, 11, 22, 23, 59, 59, 0, time.UTC))
	if got := r.strings("SELECT tableoid::regclass::text FROM samples"); !slices.Equal(got, []string{"samples_p20261116"}) {
		t.Errorf("row went to %v", got)
	}
	rep := s.run.Health(t.Context())
	for _, n := range []string{"partitions:readings", "partitions:public.samples"} {
		if res, ok := rep.Result(n); !ok || res.Status != fw.Up || res.Criticality != fw.Critical {
			t.Errorf("%s: %+v (found %v)", n, res, ok)
		}
	}
	if !strings.Contains(r.logs.String(), "partition created") {
		t.Errorf("no create log line:\n%s", r.logs.String())
	}
}

func explain(t *testing.T, r *rig, sql string) string {
	t.Helper()
	return strings.Join(r.strings("EXPLAIN (COSTS OFF) "+sql), "\n")
}

func scannedPartitions(plan, table string) []string {
	seen := map[string]bool{}
	for _, f := range strings.FieldsFunc(plan, func(c rune) bool { return c == ' ' || c == '\n' || c == '(' || c == ')' || c == '.' || c == '=' }) {
		if strings.HasPrefix(f, table+"_p") && !strings.Contains(f, "pkey") {
			seen[strings.TrimSuffix(f, "_pkey")] = true
		}
	}
	out := make([]string, 0, len(seen))
	for k := range seen {
		out = append(out, k)
	}
	slices.Sort(out)
	return out
}

func TestIDLookupAndIDRangePruneToTheMatchingPartitions(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})

	id := idAt(time.Date(2026, 12, 5, 8, 0, 0, 0, time.UTC))
	plan := explain(t, r, "SELECT * FROM readings WHERE id = '"+id+"'")
	if got := scannedPartitions(plan, "readings"); !slices.Equal(got, []string{"readings_p20261201"}) {
		t.Errorf("id lookup scans %v, want one partition:\n%s", got, plan)
	}

	lo, hi := partitions.IDRange("rdg", time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC), time.Date(2026, 11, 20, 0, 0, 0, 0, time.UTC))
	plan = explain(t, r, "SELECT * FROM readings WHERE id >= '"+lo+"' AND id < '"+hi+"'")
	if got := scannedPartitions(plan, "readings"); !slices.Equal(got, []string{"readings_p20261101"}) {
		t.Errorf("window inside a month scans %v:\n%s", got, plan)
	}
	lo, hi = partitions.IDRange("rdg", time.Date(2026, 11, 20, 0, 0, 0, 0, time.UTC), time.Date(2026, 12, 10, 0, 0, 0, 0, time.UTC))
	plan = explain(t, r, "SELECT * FROM readings WHERE id >= '"+lo+"' AND id < '"+hi+"'")
	if got := scannedPartitions(plan, "readings"); !slices.Equal(got, []string{"readings_p20261101", "readings_p20261201"}) {
		t.Errorf("window over two months scans %v:\n%s", got, plan)
	}
	// and the window selects the rows that carry a millisecond inside it
	in, out := idAt(time.Date(2026, 11, 25, 0, 0, 0, 0, time.UTC)), idAt(time.Date(2026, 12, 30, 0, 0, 0, 0, time.UTC))
	r.exec("INSERT INTO readings (id) VALUES ($1), ($2)", in, out)
	if got := r.strings("SELECT id FROM readings WHERE id >= $1 AND id < $2", lo, hi); !slices.Equal(got, []string{in}) {
		t.Errorf("window returned %v, want [%s]", got, in)
	}
}

func TestStartupValidationFailures(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name  string
		ddl   []string
		table partitions.Table
		want  string
	}{
		{"missing table", nil, monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "does not exist"},
		{
			"not partitioned",
			[]string{`CREATE TABLE readings (id text COLLATE "C" PRIMARY KEY)`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "not a partitioned table",
		},
		{
			"wrong key",
			[]string{`CREATE TABLE readings (id text COLLATE "C", recorded_at timestamptz NOT NULL, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "expected PARTITION BY RANGE (id)",
		},
		{
			"list partitioned",
			[]string{`CREATE TABLE readings (id text COLLATE "C" PRIMARY KEY) PARTITION BY LIST (id)`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "expected PARTITION BY RANGE (id)",
		},
		{
			"ByID without COLLATE C",
			[]string{`CREATE TABLE readings (id text PRIMARY KEY) PARTITION BY RANGE (id)`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), `COLLATE "C"`,
		},
		{
			"ByID on a non text column",
			[]string{`CREATE TABLE readings (id bigint PRIMARY KEY) PARTITION BY RANGE (id)`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "must be text or varchar",
		},
		{
			"ByColumn wrong type",
			[]string{`CREATE TABLE samples (id text, recorded_at timestamp NOT NULL, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)`},
			monthly("samples", partitions.ByColumn("recorded_at"), partitions.KeepForever()), "must be timestamptz",
		},
		{
			"default partition",
			[]string{idTable, `CREATE TABLE readings_default PARTITION OF readings DEFAULT`},
			monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()), "DEFAULT partition",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			for _, d := range c.ddl {
				r.exec(d)
			}
			_, err := r.start(config{tables: []partitions.Table{c.table}})
			if err == nil || !strings.Contains(err.Error(), c.want) || !strings.Contains(err.Error(), "table "+c.table.Name) {
				t.Fatalf("err %v, want a problem naming the table and %q", err, c.want)
			}
		})
	}
}

func TestDeclarationProblemsAreAllReportedAtStartup(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	good := monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))
	noRet := monthly("a", partitions.ByID("rdg"), partitions.Retention{})
	_, err := r.start(config{tables: []partitions.Table{
		good, good, noRet,
		monthly("b", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
	}})
	if err == nil {
		t.Fatal("no error")
	}
	for _, want := range []string{"key 'table readings': declared twice", "key 'table a': no retention", "key 'table b': retention names object store"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("missing %q in:\n%v", want, err)
		}
	}
	// the same table under two spellings is declared twice too
	_, err = r.start(config{tables: []partitions.Table{good, monthly("public.readings", partitions.ByID("rdg"), partitions.KeepForever())}})
	if err == nil || !strings.Contains(err.Error(), "declared twice") {
		t.Errorf("the same table twice under two names: %v", err)
	}
	// an unknown store
	_, err = r.start(config{store: objectstore.Memory(), tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "nope")),
	}})
	if err == nil || !strings.Contains(err.Error(), "no object store named 'nope'") {
		t.Errorf("unknown store: %v", err)
	}
}

func TestEnsureBackfillAndRetentionDrop(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	ctx := t.Context()

	created, err := s.ps.Ensure(ctx, "readings", time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC), time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC))
	if err != nil || created != 3 {
		t.Fatalf("Ensure: created %d, err %v; want May, June and July", created, err)
	}
	if created, err = s.ps.Ensure(ctx, "readings", time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC), time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)); err != nil || created != 0 {
		t.Errorf("a second Ensure: created %d, err %v", created, err)
	}
	for _, bad := range []struct {
		table    string
		from, to time.Time
		want     string
	}{
		{"readings", t0, t0.AddDate(0, 0, -1), "before from"},
		{"readings", time.Date(1969, 1, 1, 0, 0, 0, 0, time.UTC), t0, "before 1970"},
		{"readings", time.Date(1990, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), "more than 400"},
		{"nope", t0, t0, "not declared"},
	} {
		if _, err := s.ps.Ensure(ctx, bad.table, bad.from, bad.to); err == nil || !strings.Contains(err.Error(), bad.want) {
			t.Errorf("Ensure(%s, %v, %v): %v, want %q", bad.table, bad.from, bad.to, err, bad.want)
		}
	}
	r.exec("INSERT INTO readings (id) VALUES ($1), ($2), ($3)",
		idAt(time.Date(2026, 5, 20, 0, 0, 0, 0, time.UTC)), idAt(time.Date(2026, 7, 31, 23, 0, 0, 0, time.UTC)), idAt(t0))

	if err := s.ps.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	want := []string{"readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201"}
	if got := r.parts("readings"); !slices.Equal(got, want) {
		t.Errorf("after the pass: %v, want %v", got, want)
	}
	if n := r.count("SELECT count(*) FROM readings"); n != 1 {
		t.Errorf("%d rows left, want only the current one", n)
	}
	if got := metric(t, s.b, "fw_partitions_dropped_total", "public.readings"); got != 3 {
		t.Errorf("dropped metric %v, want 3", got)
	}
	if !strings.Contains(r.logs.String(), "partition dropped") {
		t.Error("no drop log line")
	}
}

func TestKeepForeverDropsNothing(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.KeepForever())}})
	if _, err := s.ps.Ensure(t.Context(), "readings", time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2024, 3, 1, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	before := r.parts("readings")
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := r.parts("readings"); !slices.Equal(got, before) {
		t.Errorf("partitions changed: %v -> %v", before, got)
	}
}

func TestStrayPartitionsAreLeftAloneAndLoggedOnce(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(colTable)
	r.exec(`CREATE TABLE samples_legacy PARTITION OF samples FOR VALUES FROM ('2000-01-01 00:00:00+00') TO ('2001-01-01 00:00:00+00')`)
	s := r.mustStart(config{tables: []partitions.Table{
		{Name: "samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.DropAfter(days(60))},
	}})
	for range 2 {
		if err := s.ps.Pass(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	if !slices.Contains(r.parts("samples"), "samples_legacy") {
		t.Error("the stray partition was dropped")
	}
	if n := strings.Count(r.logs.String(), "samples_legacy"); n != 1 {
		t.Errorf("the stray partition is mentioned %d times, want once:\n%s", n, r.logs.String())
	}
}

func TestHealthCheckIsDownWhenTheNextPartitionIsMissing(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	check := func() fw.HealthResult {
		res, ok := s.run.Health(t.Context()).Result("partitions:readings")
		if !ok {
			t.Fatal("no check")
		}
		return res
	}
	if res := check(); res.Status != fw.Up {
		t.Fatalf("%+v", res)
	}
	r.exec("DROP TABLE readings_p20261201") // the next interval, by hand
	res := check()
	if res.Status != fw.Down || res.Criticality != fw.Critical || !strings.Contains(res.Reason, "next interval") {
		t.Fatalf("%+v", res)
	}
	if s.run.Health(t.Context()).IsReady() {
		t.Error("the application is ready with the next partition missing")
	}
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if res := check(); res.Status != fw.Up {
		t.Errorf("after the next pass: %+v", res)
	}
}

func TestConcurrentEnsureOnOneTableCreatesOneSet(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	cfg := config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(900)))}}
	a, b := r.mustStart(cfg), r.mustStart(cfg) // two replicas
	var wg sync.WaitGroup
	errs := make(chan error, 16)
	for i := range 16 {
		ps := a.ps
		if i%2 == 1 {
			ps = b.ps
		}
		wg.Go(func() {
			_, err := ps.Ensure(t.Context(), "readings", time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2025, 12, 31, 0, 0, 0, 0, time.UTC))
			errs <- err
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Errorf("a concurrent Ensure failed: %v", err)
		}
	}
	if got := len(r.parts("readings")); got != 12+4 {
		t.Errorf("%d partitions, want 12 backfilled plus 4 current", got)
	}
}

func TestLoopCreatesAndDropsAsTheClockAdvances(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{
		every: 30 * time.Millisecond, lock: true,
		tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))},
	})
	if got := r.parts("readings"); got[0] != "readings_p20261101" {
		t.Fatalf("%v", got)
	}
	r.clock.Set(time.Date(2027, 3, 15, 12, 0, 0, 0, time.UTC)) // cutoff 2026-12-15: November ended 2026-12-01
	eventually(t, "the loop to create the new partitions and drop November", func() bool {
		got := r.parts("readings")
		return slices.Contains(got, "readings_p20270601") && !slices.Contains(got, "readings_p20261101")
	})
	got := r.parts("readings")
	if !slices.Contains(got, "readings_p20261201") {
		t.Errorf("December ended 2027-01-01, after the cutoff, and must stay: %v", got)
	}
	if res, ok := s.run.Health(t.Context()).Result("background:partitions"); !ok || res.Status != fw.Up {
		t.Errorf("loop check %+v", res)
	}
	if res, _ := s.run.Health(t.Context()).Result("partitions:readings"); res.Status != fw.Up {
		t.Errorf("partition check %+v", res)
	}
}

// ---- archive ----

func gunzipLines(t *testing.T, content []byte) []string {
	t.Helper()
	zr, err := gzip.NewReader(bytes.NewReader(content))
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.TrimSuffix(string(b), "\n")
	if text == "" {
		return nil
	}
	return strings.Split(text, "\n")
}

func insertOld(t *testing.T, r *rig, n int) map[string]string {
	t.Helper()
	want := map[string]string{}
	for i := range n {
		id := idAt(time.Date(2026, 5, 1+i%28, i%24, 0, 0, i, time.UTC))
		payload := fmt.Sprintf("row %d: line1\nline2 \"quoted\" back\\slash é %x", i, sha256.Sum256([]byte{byte(i), byte(i >> 8)}))
		want[id] = payload
		r.exec("INSERT INTO readings (id, payload) VALUES ($1, $2)", id, payload)
	}
	return want
}

func TestArchiveThenDropWritesVerifiedNDJSONAndManifest(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name    string
		segment int
		rows    int
		parts   func(int) bool
	}{
		{"one object", 0, 120, func(n int) bool { return n == 1 }},
		{"several objects", 1500, 400, func(n int) bool { return n > 1 }},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			r.exec(idTable)
			s := r.mustStart(config{store: objectstore.Memory(), segment: c.segment, tables: []partitions.Table{
				monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
			}})
			if _, err := s.ps.Ensure(t.Context(), "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
				t.Fatal(err)
			}
			want := insertOld(t, r, c.rows)
			if err := s.ps.Pass(t.Context()); err != nil {
				t.Fatal(err)
			}
			if slices.Contains(r.parts("readings"), "readings_p20260501") {
				t.Fatal("the archived partition was not dropped")
			}
			store, _ := s.stores.Get("archive")
			type mrow struct {
				part, rows, bytes int
				sha, key          string
			}
			rows, err := r.db.Pool().Query(t.Context(), `SELECT part, row_count, byte_count, sha256, object_key FROM fw_partition_archives
WHERE table_name = 'public.readings' AND partition_name = 'readings_p20260501' ORDER BY part`)
			if err != nil {
				t.Fatal(err)
			}
			defer rows.Close()
			var manifest []mrow
			for rows.Next() {
				var m mrow
				if err := rows.Scan(&m.part, &m.rows, &m.bytes, &m.sha, &m.key); err != nil {
					t.Fatal(err)
				}
				manifest = append(manifest, m)
			}
			if !c.parts(len(manifest)) {
				t.Fatalf("%d manifest rows (parts)", len(manifest))
			}
			if manifest[0].key != "partitions/public.readings/readings_p20260501.ndjson.gz" {
				t.Errorf("first key %q", manifest[0].key)
			}
			got := map[string]string{}
			total := 0
			for i, m := range manifest {
				if m.part != i+1 {
					t.Errorf("part numbers %v", manifest)
				}
				if i > 0 && m.key != fmt.Sprintf("partitions/public.readings/readings_p20260501.part%d.ndjson.gz", i+1) {
					t.Errorf("key of part %d: %q", i+1, m.key)
				}
				obj, found, err := store.GetObject(t.Context(), m.key)
				if err != nil || !found {
					t.Fatalf("%s: %v %v", m.key, found, err)
				}
				sum := sha256.Sum256(obj.Content)
				if len(obj.Content) != m.bytes || hex.EncodeToString(sum[:]) != m.sha || obj.ContentType != "application/gzip" {
					t.Errorf("%s: manifest says %d bytes %s, object has %d bytes %x (%s)", m.key, m.bytes, m.sha, len(obj.Content), sum, obj.ContentType)
				}
				lines := gunzipLines(t, obj.Content)
				if len(lines) != m.rows {
					t.Errorf("%s: manifest says %d rows, object has %d", m.key, m.rows, len(lines))
				}
				total += len(lines)
				for _, line := range lines {
					var rec struct{ ID, Payload string }
					if err := json.Unmarshal([]byte(line), &rec); err != nil {
						t.Fatalf("%s: not JSON: %q", m.key, line)
					}
					got[rec.ID] = rec.Payload
				}
			}
			if total != c.rows || len(got) != len(want) {
				t.Fatalf("archived %d rows (%d distinct), want %d", total, len(got), len(want))
			}
			for id, p := range want {
				if got[id] != p {
					t.Errorf("row %s: payload %q, want %q", id, got[id], p)
				}
			}
			if v := metric(t, s.b, "fw_partition_rows_archived_total", "public.readings"); int(v) != c.rows {
				t.Errorf("rows archived metric %v", v)
			}
			if v := metric(t, s.b, "fw_partitions_archived_total", "public.readings"); v != 1 {
				t.Errorf("archived metric %v", v)
			}
			if v := metric(t, s.b, "fw_partitions_dropped_total", "public.readings"); v != 1 {
				t.Errorf("dropped metric %v", v)
			}
		})
	}
}

// flaky wraps a store with failure injection.
type flaky struct {
	objectstore.Store
	mu       sync.Mutex
	putErr   error
	truncate bool
	afterPut func()
}

func (f *flaky) set(putErr error, truncate bool, afterPut func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.putErr, f.truncate, f.afterPut = putErr, truncate, afterPut
}

func (f *flaky) PutObject(ctx context.Context, key string, o objectstore.Object) error {
	f.mu.Lock()
	putErr, truncate, hook := f.putErr, f.truncate, f.afterPut
	f.mu.Unlock()
	if putErr != nil {
		return putErr
	}
	if truncate {
		o.Content = o.Content[:len(o.Content)/2]
	}
	if err := f.Store.PutObject(ctx, key, o); err != nil {
		return err
	}
	if hook != nil {
		hook()
	}
	return nil
}

// flakyFactory makes the store out wrap a memory store when the application builds it.
func flakyFactory(out *flaky) objectstore.StoreFactory {
	return func(ctx context.Context, b *fw.Boot, name string) (objectstore.Store, error) {
		inner, err := objectstore.Memory()(ctx, b, name)
		if err != nil {
			return nil, err
		}
		out.Store = inner
		return out, nil
	}
}

func TestArchiveFailureDropsNothingAndIsRetried(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name   string
		fail   func(f *flaky)
		logged string
	}{
		{"the store refuses the put", func(f *flaky) { f.set(fmt.Errorf("%w: disk on fire", objectstore.ErrStore), false, nil) }, "disk on fire"},
		{"the stored object is not what was written", func(f *flaky) { f.set(nil, true, nil) }, "stored"},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			r.exec(idTable)
			store := &flaky{}
			s := r.mustStart(config{store: flakyFactory(store), tables: []partitions.Table{
				monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
			}})
			if _, err := s.ps.Ensure(t.Context(), "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
				t.Fatal(err)
			}
			insertOld(t, r, 50)
			c.fail(store)
			for range 2 {
				if err := s.ps.Pass(t.Context()); err == nil {
					t.Fatal("a pass with a failing archive succeeded")
				}
			}
			if slices.Contains(r.parts("readings"), "readings_p20260501") {
				t.Fatal("the partition must stay detached (not re-attached) after a failed archive")
			}
			if n := r.count("SELECT count(*) FROM readings_p20260501"); n != 50 { // detached, not dropped
				t.Errorf("%d rows left", n)
			}
			if n := r.count("SELECT count(*) FROM fw_partition_archives"); n != 0 {
				t.Errorf("%d manifest rows for a failed archive", n)
			}
			if v := metric(t, s.b, "fw_partition_failures_total", "public.readings", "archive"); v != 2 {
				t.Errorf("failure metric %v, want 2 (one per failed pass)", v)
			}
			if logs := r.logs.String(); !strings.Contains(logs, "partition archive failed") || !strings.Contains(logs, c.logged) {
				t.Errorf("the failure is not logged:\n%s", logs)
			}
			if res, _ := s.run.Health(t.Context()).Result("partitions:readings"); res.Status != fw.Up {
				t.Errorf("a failing archive does not touch the next-partition check: %+v", res)
			}
			// the store heals; the next pass retries and succeeds
			store.set(nil, false, nil)
			if err := s.ps.Pass(t.Context()); err != nil {
				t.Fatal(err)
			}
			if r.count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'") != 0 {
				t.Error("the retry did not drop the detached table")
			}
			if n := r.count("SELECT sum(row_count)::int FROM fw_partition_archives"); n != 50 {
				t.Errorf("manifest holds %d rows", n)
			}
		})
	}
}

func TestWritesCannotReachADetachedPartitionSoTheArchiveIsExact(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	store := &flaky{}
	s := r.mustStart(config{store: flakyFactory(store), tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
	}})
	if _, err := s.ps.Ensure(t.Context(), "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	want := insertOld(t, r, 10)
	var victim string
	for id := range want {
		victim = id
	}
	late := idAt(time.Date(2026, 5, 31, 23, 0, 0, 0, time.UTC))
	var insertErr error
	var updated int64
	var once sync.Once
	store.set(nil, false, func() {
		once.Do(func() { // after the first object was written, before the drop
			_, insertErr = r.db.Pool().Exec(context.Background(), "INSERT INTO readings (id, payload) VALUES ($1, 'late')", late)
			tag, err := r.db.Pool().Exec(context.Background(), "UPDATE readings SET payload = 'changed' WHERE id = $1", victim)
			if err != nil {
				t.Errorf("update: %v", err)
			}
			updated = tag.RowsAffected()
		})
	})
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if insertErr == nil {
		t.Error("an insert into the range of a detached partition succeeded; it must fail loudly")
	}
	if updated != 0 {
		t.Errorf("an update through the parent reached the detached partition (%d rows)", updated)
	}
	store1, err := s.stores.Get("archive")
	if err != nil || store1 == nil {
		t.Fatal(err)
	}
	obj, found, err := store1.GetObject(t.Context(), "partitions/public.readings/readings_p20260501.ndjson.gz")
	if err != nil || !found {
		t.Fatal(found, err)
	}
	got := map[string]string{}
	for _, line := range gunzipLines(t, obj.Content) {
		var rec struct{ ID, Payload string }
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			t.Fatal(err)
		}
		got[rec.ID] = rec.Payload
	}
	if len(got) != len(want) || got[victim] != want[victim] {
		t.Errorf("the archive does not hold the final data: %d rows, victim %q", len(got), got[victim])
	}
	if r.count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'") != 0 {
		t.Error("not dropped")
	}
}

func TestRecoveryFinishesAPartitionDetachedByAnInterruptedPass(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{store: objectstore.Memory(), tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
	}})
	if _, err := s.ps.Ensure(t.Context(), "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	insertOld(t, r, 25)
	// the state a pass that crashed right after its detach leaves behind: detached, with the mark
	r.exec("ALTER TABLE readings DETACH PARTITION readings_p20260501")
	r.exec("COMMENT ON TABLE readings_p20260501 IS 'fw-partitions: detached from public.readings for archiving'")
	// a detached table that is not one of ours, or not due, is not touched
	r.exec("CREATE TABLE readings_p20261101_copy (id text)")
	// nor is a due table of a partition's name that this module did not detach (no mark)
	r.exec("CREATE TABLE readings_p20260401 (id text)")
	r.exec("INSERT INTO readings_p20260401 VALUES ('kept')")
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if r.count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'") != 0 {
		t.Error("the detached partition was not recovered (dropped)")
	}
	if r.count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20261101_copy'") != 1 {
		t.Error("a table that is not a due partition name was touched")
	}
	if r.count("SELECT count(*) FROM readings_p20260401") != 1 {
		t.Error("an unmarked table of a due partition's name was archived or dropped")
	}
	if r.count("SELECT count(*) FROM fw_partition_archives WHERE partition_name = 'readings_p20260401'") != 0 {
		t.Error("an unmarked table of a due partition's name was archived")
	}
	if n := r.count("SELECT sum(row_count)::int FROM fw_partition_archives WHERE partition_name = 'readings_p20260501'"); n != 25 {
		t.Errorf("manifest holds %d rows, want 25", n)
	}
}

func TestEnsureRefusesADetachedTableOfAPartitionsName(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.KeepForever())}})
	r.exec("ALTER TABLE readings DETACH PARTITION readings_p20261201") // a future partition left detached
	err := s.ps.Pass(t.Context())
	if err == nil || !strings.Contains(err.Error(), `"public"."readings_p20261201" exists but is not attached`) {
		t.Fatalf("pass: %v", err)
	}
	if v := metric(t, s.b, "fw_partition_failures_total", "public.readings", "ensure"); v != 1 {
		t.Errorf("ensure failures %v", v)
	}
	// and at start-up
	if _, err := r.start(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.KeepForever())}}); err == nil ||
		!strings.Contains(err.Error(), "exists but is not attached") {
		t.Errorf("start-up: %v", err)
	}
}

func TestKeyCollationVersusColumnCollation(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	// the key says C; the column is in the default collation (PostgreSQL refuses a primary key here)
	r.exec(`CREATE TABLE readings (id text, payload text) PARTITION BY RANGE (id COLLATE "C")`)
	r.exec(`CREATE TABLE readings_p20261101 PARTITION OF readings FOR VALUES FROM ('rdg_0') TO ('rdg_1')`)
	r.exec(`CREATE TABLE readings_p20261201 PARTITION OF readings FOR VALUES FROM ('rdg_1') TO ('rdg_2')`)
	plan := explain(t, r, "SELECT * FROM readings WHERE id = 'rdg_0000000000001'")
	t.Logf("plan:\n%s", plan)
	// documented in the contract: with only the key in C, an id lookup is NOT pruned
	if got := scannedPartitions(plan, "readings"); len(got) != 2 {
		t.Errorf("expected PostgreSQL to scan both partitions (no pruning), scanned %v", got)
	}
	_, err := r.start(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.KeepForever())}})
	if err == nil || !strings.Contains(err.Error(), `the column "id" has collation "default"`) || !strings.Contains(err.Error(), `COLLATE "C"`) {
		t.Errorf("start-up: %v", err)
	}
}

func TestWithLocksTheSecondReplicaSkipsAndDoesNoDDL(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	shared := locks.NewMemoryStore(r.clock)
	cfg := config{
		every: 20 * time.Millisecond, lockStore: shared,
		tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))},
	}
	a := r.mustStart(cfg)
	b := r.mustStart(cfg)
	_ = a
	// some other holder takes the loop's lock
	holder := locks.New(shared, locks.WithClock(r.clock))
	key, err := locks.ServiceKey(partitions.LockName)
	if err != nil {
		t.Fatal(err)
	}
	acq, err := holder.Acquire(t.Context(), key, time.Hour, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	held, ok := acq.(locks.Acquired)
	if !ok {
		t.Fatalf("could not take the lock: %T", acq)
	}
	r.exec("DROP TABLE readings_p20261201") // work only a pass would do
	skipped := func(s *started) float64 { return metric(t, s.b, "fw_background_runs_total", "partitions", "skipped") }
	base := skipped(a) + skipped(b)
	eventually(t, "both loops to be skipped", func() bool { return skipped(a)+skipped(b) >= base+6 })
	if slices.Contains(r.parts("readings"), "readings_p20261201") {
		t.Fatal("a replica did DDL without holding the lock")
	}
	if ok, err := holder.Release(t.Context(), held.Lease); err != nil || !ok {
		t.Fatal(ok, err)
	}
	eventually(t, "a replica to take the lock and recreate the partition", func() bool {
		return slices.Contains(r.parts("readings"), "readings_p20261201")
	})
}
