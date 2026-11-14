package partitions

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
)

// PassResult is what one table's pass did.
type PassResult struct {
	// Table is the schema-qualified table.
	Table string
	// Created, Dropped and Archived count the partitions the pass created, removed and wrote to the
	// object store (a partition that was archived and then failed to drop is counted as archived).
	Created, Dropped, Archived int
	// Failed lists the steps that failed, in step order of the pass; empty when none did. The log
	// has the cause.
	Failed []Step
}

// Passed reports whether no step failed.
func (r PassResult) Passed() bool { return len(r.Failed) == 0 }

// stepStatus is the status of a step in the last-pass record.
const (
	statusOK      = "OK"
	statusFailed  = "FAILED"
	statusSkipped = "SKIPPED"
)

// stepStatuses returns the three statuses of a pass for the record: SKIPPED when the step does not
// apply to the table (retention for KeepForever, archive unless the retention archives).
func (tg *target) stepStatuses(res PassResult) (ensure, retention, archive string) {
	pick := func(step Step, applies bool) string {
		switch {
		case !applies:
			return statusSkipped
		case slices.Contains(res.Failed, step):
			return statusFailed
		}
		return statusOK
	}
	return pick(StepEnsure, true), pick(StepRetention, tg.Retention.kind != retKeep), pick(StepArchive, tg.Retention.kind == retArchive)
}

// auxTables reports whether the module's two tables exist on the table's database instance. The
// migration creates them on the default instance only.
func (tg *target) auxTables(ctx context.Context) (manifest, passes bool, err error) {
	err = tg.db.Pool().QueryRow(ctx,
		"SELECT to_regclass('fw_partition_archives') IS NOT NULL, to_regclass('fw_partition_passes') IS NOT NULL").Scan(&manifest, &passes)
	return manifest, passes, err
}

// recordPass upserts the table's last pass into its own database, so every replica (and the admin
// section) sees the pass whichever replica ran it. A failure is logged and counted, never a failure
// of the pass; an instance without fw_partition_passes records nothing (start-up said so).
func (p *Partitions) recordPass(ctx context.Context, tg *target, res PassResult) {
	// the pass's own context may be cancelled (shutdown): the record is still worth a short try
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	_, passes, err := tg.auxTables(ctx)
	if err == nil && !passes {
		return
	}
	if err == nil {
		ensure, retention, archive := tg.stepStatuses(res)
		_, err = tg.db.Pool().Exec(ctx, `INSERT INTO fw_partition_passes
(table_name, ended_at, ensure_status, retention_status, archive_status, created, dropped, archived)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
ON CONFLICT (table_name) DO UPDATE SET ended_at = EXCLUDED.ended_at, ensure_status = EXCLUDED.ensure_status,
  retention_status = EXCLUDED.retention_status, archive_status = EXCLUDED.archive_status,
  created = EXCLUDED.created, dropped = EXCLUDED.dropped, archived = EXCLUDED.archived`,
			tg.qualified, p.clock.Now(), ensure, retention, archive, res.Created, res.Dropped, res.Archived)
	}
	if err != nil {
		p.failures.With(tg.qualified, "record").Inc()
		p.log.Error("could not record the last partition pass", slog.String("table", tg.qualified), slog.String("error", err.Error()))
	}
}

// warnMissingPassTables says, once at start-up and only for an instance that has a declared table,
// that fw_partition_passes is missing there: the migration runs on the default instance only, so a
// table on another instance needs it by hand, and without it its last pass is not recorded.
func (p *Partitions) warnMissingPassTables(ctx context.Context) {
	said := map[string]bool{}
	for _, tg := range p.targets {
		name := tg.db.Name()
		if said[name] {
			continue
		}
		if _, passes, err := tg.auxTables(ctx); err == nil && !passes {
			said[name] = true
			p.log.Warn("table fw_partition_passes does not exist in database instance '" + name + "': the last pass of its partitioned tables " +
				"is not recorded (migrations run on the default instance only; create the table by hand, as in migrations/V0002__create_partition_passes_table.sql)")
		}
	}
}

// RunPass runs a pass by hand, for an operator: the ensure-then-retention pass of the declared table
// (named as declared or schema-qualified), or of every table when table is "". by is the acting
// principal's Context, which the record names: an INFO line "partitions: manual pass" with the
// principal, the tables and the outcome (the framework has no audit store for an operator's action
// that is not an aggregate change, so the log line is the record).
//
// A manual pass never overlaps the loop's, here or on another replica: with a lock store
// ([WithLocks]) it tries the loop's service lock without waiting, and in this process it tries the
// pass mutex; when either is held it is refused with [ErrPassRunning], never queued. An unknown table
// is [ErrTableNotDeclared]. When the pass ran, the error is nil and a step that failed is in the
// result's Failed (the log has the cause); the results are in declaration order.
func (p *Partitions) RunPass(ctx context.Context, by fw.Context, table string) ([]PassResult, error) {
	targets := p.targets
	if table != "" {
		tg, ok := p.lookup(table)
		if !ok {
			err := p.notDeclared(table)
			p.recordManual(ctx, "partitions: manual pass", by, table, "refused: "+err.Error())
			return nil, err
		}
		targets = []*target{tg}
	}
	scope := table
	if scope == "" {
		scope = "all"
	}
	if !p.passMu.TryLock() {
		p.recordManual(ctx, "partitions: manual pass", by, scope, "refused: a pass is already running in this process")
		return nil, ErrPassRunning
	}
	defer p.passMu.Unlock()

	var results []PassResult
	lostLock := false
	run := func(ctx context.Context) error {
		var err error
		results, err = p.passAll(ctx, targets)
		return err
	}
	if p.lock != nil {
		res, err := locks.With(ctx, p.lock, p.lockKey, lockLease, 0, func(ctx context.Context, _ locks.Lease) (struct{}, error) {
			return struct{}{}, run(ctx)
		})
		if err != nil && res.Outcome == 0 { // the lock store failed: nothing ran
			p.recordManual(ctx, "partitions: manual pass", by, scope, "failed: "+err.Error())
			return nil, fmt.Errorf("partitions: the pass lock could not be taken: %w", err)
		}
		lostLock = res.Outcome == locks.Lost
		if res.Outcome == locks.NotAcquired {
			p.recordManual(ctx, "partitions: manual pass", by, scope, "refused: the pass lock is held (another replica's pass is running)")
			return nil, ErrPassRunning
		}
	} else {
		_ = run(ctx)
	}
	outcome := "ok"
	lost := p.lock != nil && lostLock
	var failed []string
	for _, r := range results {
		if !r.Passed() {
			failed = append(failed, r.Table)
		}
	}
	if len(failed) > 0 {
		outcome = "steps failed: " + strings.Join(failed, ", ")
	}
	if lost {
		outcome = "the pass lock was lost while the pass ran; " + outcome
	}
	var created, dropped, archived int
	for _, r := range results {
		created, dropped, archived = created+r.Created, dropped+r.Dropped, archived+r.Archived
	}
	p.recordManual(ctx, "partitions: manual pass", by, scope, outcome,
		slog.Int("created", created), slog.Int("dropped", dropped), slog.Int("archived", archived))
	return results, nil
}

// EnsureAs is [Partitions.Ensure] for an operator: the same refusals and result, and an INFO line
// "partitions: manual ensure" naming the principal of by, the table, the range and the outcome.
func (p *Partitions) EnsureAs(ctx context.Context, by fw.Context, table string, from, to time.Time) (EnsureResult, error) {
	res, err := p.Ensure(ctx, table, from, to)
	rng := []any{slog.Time("from", from.UTC()), slog.Time("to", to.UTC())}
	switch {
	case err == nil:
		p.recordManual(ctx, "partitions: manual ensure", by, table, "ok",
			append(rng, slog.Int("created", res.Created), slog.Int("already_due", res.AlreadyDue))...)
	case errors.Is(err, ErrTableNotDeclared), errors.Is(err, ErrRangeInvalid), errors.Is(err, ErrRangeTooLarge), errors.Is(err, ErrDetachedLeftover):
		p.recordManual(ctx, "partitions: manual ensure", by, table, "refused: "+err.Error(), rng...)
	default:
		p.recordManual(ctx, "partitions: manual ensure", by, table, "failed: "+err.Error(), rng...)
	}
	return res, err
}

// recordManual writes the record of an operator action: who (the Context's principal, kind, tier and
// correlation id), what and the outcome.
func (p *Partitions) recordManual(ctx context.Context, msg string, by fw.Context, table, outcome string, extra ...any) {
	attrs := []slog.Attr{slog.String("table", table), slog.String("outcome", outcome)}
	if id, ok := by.Principal(); ok {
		attrs = append(attrs, slog.String("principal", id.String()))
	} else {
		attrs = append(attrs, slog.String("principal", ""))
	}
	if kind, ok := by.PrincipalKind(); ok {
		attrs = append(attrs, slog.String("principal_kind", kind.String()))
	}
	if tier, ok := by.Tier(); ok {
		attrs = append(attrs, slog.String("tier", tier))
	}
	if !by.IsZero() {
		attrs = append(attrs, slog.String("correlation_id", by.Correlation().String()))
	}
	for _, e := range extra {
		if a, ok := e.(slog.Attr); ok {
			attrs = append(attrs, a)
		}
	}
	p.log.LogAttrs(ctx, slog.LevelInfo, msg, attrs...)
}
