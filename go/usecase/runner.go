package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/dbtx"
	"github.com/flowcatalyst/framework/go/metrics"
)

// The step codes of INTERNAL failures (write-path contract section 4).
const (
	stepValidate  = "VALIDATE"
	stepAuthorize = "AUTHORIZE"
	stepLoad      = "LOAD"
	stepExecute   = "EXECUTE"
	stepBegin     = "BEGIN"
	stepPersist   = "PERSIST"
	stepOutbox    = "OUTBOX"
	stepAudit     = "AUDIT"
	stepCommit    = "COMMIT"
	stepSavepoint = "SAVEPOINT" // the release of a joined run's savepoint
)

// Runner applies use cases' plans atomically on the default database instance: the only place
// that begins, commits or rolls back a use case's transaction. Safe for concurrent use.
type Runner struct {
	db        *database.DB
	outbox    *OutboxWriter
	clock     fw.Clock
	log       *slog.Logger
	wait      func(ctx context.Context, d time.Duration) error
	runs      *metrics.Counter
	durations *metrics.Timer
}

// RunnerOption configures [NewRunner].
type RunnerOption func(*Runner)

// WithWait replaces how the runner waits between attempts of a retried use case (tests). The
// function returns ctx's error when ctx ends first.
func WithWait(wait func(ctx context.Context, d time.Duration) error) RunnerOption {
	return func(r *Runner) { r.wait = wait }
}

// NewRunner returns the runner writing to db (the default instance) and to its outbox, with the
// application's clock, ids, logger and metrics (fw_usecase_runs_total,
// fw_usecase_duration_seconds). d is the outbox module's declaration, the proof that the outbox
// migrations are part of the application.
func NewRunner(b *fw.Boot, d *Declared, db *database.DB, opts ...RunnerOption) (*Runner, error) {
	if d == nil || db == nil {
		return nil, errors.New("usecase.NewRunner needs the outbox declaration and a database")
	}
	runs, err := b.Metrics().Counter("fw_usecase_runs_total",
		"Use case runs by use case and outcome: committed, or the failure's kind.", "usecase", "outcome")
	if err != nil {
		return nil, err
	}
	durations, err := b.Metrics().Timer("fw_usecase_duration_seconds",
		"Use case run duration, all attempts included.", "usecase")
	if err != nil {
		return nil, err
	}
	r := &Runner{
		db: db, outbox: NewOutboxWriter(b.Ids(), b.Clock()), clock: b.Clock(), log: b.Logger("usecase"),
		wait: sleep, runs: runs, durations: durations,
	}
	for _, o := range opts {
		o(r)
	}
	return r, nil
}

func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// errConflict marks an attempt that met a conflict (stale aggregate, 40001, 40P01).
var errConflict = errors.New("conflict")

func run[C any, E event.Event](ctx context.Context, d *Defined[C, E], cx fw.Context, cmd C, joined *database.Tx) (E, *Error) {
	var zero E
	r := d.runner
	if cx.IsZero() {
		r.log.Error("BUG: use case run with the zero Context", slog.String("usecase", d.name))
		return zero, internal("NO_CONTEXT", fw.ErrZeroContext)
	}
	log := r.log.With(slog.String("usecase", d.name)).With(attrsAny(cx.LogAttrs())...)
	for attempt := 1; ; attempt++ {
		ev, err := attemptOnce(ctx, d, cx, cmd, log, joined)
		if !errors.Is(err, errConflict) {
			var ue *Error
			if err != nil {
				ue, _ = AsError(err)
			}
			return ev, ue
		}
		if attempt >= d.policy.attempts {
			log.Info("use case conflicted; giving up", slog.Int("attempt", attempt), slog.Int("attempts", d.policy.attempts))
			return zero, staleAggregate()
		}
		log.Debug("use case conflicted; running again", slog.Int("attempt", attempt))
		factor := 0.5 + rand.Float64() //nolint:gosec // jitter needs no cryptographic randomness
		if err := r.wait(ctx, time.Duration(float64(d.policy.backoff)*factor)); err != nil {
			return zero, internal("INTERRUPTED", err)
		}
	}
}

// attemptOnce runs one attempt. It returns the event, a *Error, or errConflict.
func attemptOnce[C any, E event.Event](ctx context.Context, d *Defined[C, E], cx fw.Context, cmd C, log *slog.Logger, joined *database.Tx) (ev E, err error) {
	var zero E
	step := stepValidate
	defer func() {
		if p := recover(); p != nil {
			log.Error("use case panicked", slog.String("step", step), slog.Any("panic", p))
			ev, err = zero, internal(step, fmt.Errorf("panic: %v", p))
		}
	}()
	if err := d.uc.Validate(ctx, cx, cmd); err != nil {
		return zero, failure(log, step, err)
	}
	step = stepAuthorize
	grant, err := d.uc.Authorize(ctx, cx, Valid[C]{cmd: cmd, ok: true})
	if err != nil {
		return zero, failure(log, step, err)
	}
	if !grant.ok {
		log.Warn("Authorize returned the zero Grant without an error; refusing")
		return zero, Authorization(MustErrorCode("NOT_AUTHORIZED"), "Not authorized.")
	}
	plan, err := d.uc.run(ctx, cx, Authorized[C]{cmd: cmd, ok: true}, d.runner.db, &step)
	if err != nil {
		return zero, failure(log, step, err)
	}
	if plan == nil {
		log.Error("BUG: Execute returned no plan and no error")
		return zero, internal(step, errors.New("no plan"))
	}
	for _, e := range plan.events {
		if e == nil || e.Metadata().IsZero() {
			log.Error("BUG: a planned event has no metadata (use an event.Factory)")
			return zero, internal(step, errors.New("event without metadata"))
		}
	}
	for _, s := range plan.steps {
		if s == nil {
			return zero, internal(step, errors.New("nil step"))
		}
		if s.client() != cx.Client() {
			log.Error("BUG: the plan writes an aggregate of another client", slog.String("aggregate_client", s.client().String()))
			return zero, internal("CROSS_CLIENT_WRITE", nil)
		}
	}
	if joined != nil {
		return persistJoined(ctx, d, cx, cmd, plan, log, &step, joined)
	}
	return persist(ctx, d, cx, cmd, plan, log, &step)
}

func persist[C any, E event.Event](ctx context.Context, d *Defined[C, E], cx fw.Context, cmd C, plan *Plan[E], log *slog.Logger, step *string) (E, error) {
	var zero E
	r := d.runner
	*step = stepBegin
	tx, err := r.db.Pool().BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return zero, failure(log, *step, err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback(context.WithoutCancel(ctx))
		}
	}()
	wrapped := dbtx.New(tx)
	if ok, err := write(ctx, d, cx, cmd, plan, wrapped, log, step); err != nil {
		return zero, err
	} else if !ok {
		return zero, errConflict
	}
	*step = stepCommit
	if err := tx.Commit(ctx); err != nil {
		return zero, failure(log, *step, err)
	}
	committed = true
	return plan.first, nil
}

// failure classifies an error from a step: a *Error is the result; a serialization failure or
// deadlock is a conflict; a unique violation is CONFLICT / UNIQUE_VIOLATION; anything else is
// INTERNAL with the step's code, the cause logged and never shown.
func failure(log *slog.Logger, step string, err error) error {
	if pg, ok := errors.AsType[*pgconn.PgError](err); ok {
		switch pg.Code {
		case "40001", "40P01":
			log.Debug("use case hit a conflict", slog.String("sqlstate", pg.Code), slog.String("step", step))
			return errConflict
		case "23505":
			log.Debug("use case violated a unique constraint", slog.String("step", step))
			e := Conflict(MustErrorCode("UNIQUE_VIOLATION"), "A record with the same unique value already exists.")
			if pg.ConstraintName != "" {
				e = e.WithDetails(map[string]string{"constraint": pg.ConstraintName})
			}
			return e
		}
	}
	if ue, ok := AsError(err); ok {
		return ue
	}
	log.Error("use case failed", slog.String("step", step), slog.String("error", err.Error()))
	return internal(step, err)
}

func staleAggregate() *Error {
	return Conflict(MustErrorCode("STALE_AGGREGATE"), "The record was changed by someone else; reload it and try again.")
}

// auditEntry builds the audit row's entry: the entity from the first step's aggregate, or from
// the first event's subject when the plan has no aggregate (domain.aggregate.id).
func auditEntry[C any, E event.Event](operation string, cx fw.Context, cmd C, plan *Plan[E], now time.Time) AuditEntry {
	first := plan.first.Metadata()
	var entity Entity
	if len(plan.steps) > 0 {
		entity = plan.steps[0].entity()
	} else {
		parts := strings.SplitN(first.Subject(), ".", 3)
		if len(parts) == 3 {
			entity = Entity{Type: parts[1], ID: parts[2]}
		} else {
			entity = Entity{Type: first.Subject(), ID: first.Subject()}
		}
	}
	entry := AuditEntry{
		EntityType: entity.Type, EntityID: entity.ID, Operation: operation,
		PerformedAt: now, Source: first.Source(), CorrelationID: cx.Correlation().String(), Client: cx.Client(),
	}
	if a, ok := any(cmd).(AuditData); ok {
		entry.Data = a.AuditData()
		entry.HasData = true
	}
	if p, ok := cx.Principal(); ok {
		entry.PrincipalID = p.String()
	}
	if app, ok := cx.Application(); ok {
		entry.ApplicationCode = app.String()
	}
	return entry
}

func attrsAny(attrs []slog.Attr) []any {
	out := make([]any, len(attrs))
	for i, a := range attrs {
		out[i] = a
	}
	return out
}

// write applies the plan's steps in order, then the events, the dispatch jobs and the audit row on
// tx, setting *step before each. false means an aggregate was stale (a conflict). The caller owns the
// transaction: begin, commit or release, and rollback.
func write[C any, E event.Event](ctx context.Context, d *Defined[C, E], cx fw.Context, cmd C, plan *Plan[E], tx *database.Tx, log *slog.Logger, step *string) (bool, error) {
	r := d.runner
	*step = stepPersist
	for _, s := range plan.steps {
		ok, err := s.apply(ctx, tx)
		if err != nil {
			return false, failure(log, *step, err)
		}
		if !ok {
			return false, nil
		}
	}
	*step = stepOutbox
	if err := r.outbox.WriteEvents(ctx, tx, cx, plan.events); err != nil {
		return false, failure(log, *step, err)
	}
	if len(plan.jobs) > 0 {
		if err := r.outbox.WriteDispatchJobs(ctx, tx, cx, plan.jobs); err != nil {
			return false, failure(log, *step, err)
		}
	}
	*step = stepAudit
	if err := r.outbox.WriteAudit(ctx, tx, auditEntry(d.name, cx, cmd, plan, r.clock.Now())); err != nil {
		return false, failure(log, *step, err)
	}
	return true, nil
}

// persistJoined writes the plan inside the caller's transaction under a savepoint (write-path
// contract 4a): a failed or conflicting attempt is rolled back to the savepoint, so the caller's
// earlier work is kept and its transaction stays usable. It never commits or rolls back the
// caller's transaction.
func persistJoined[C any, E event.Event](ctx context.Context, d *Defined[C, E], cx fw.Context, cmd C, plan *Plan[E], log *slog.Logger, step *string, joined *database.Tx) (E, error) {
	var zero E
	*step = stepBegin
	sp, err := joined.Conn().Begin(ctx) // a savepoint: pgx nests a Begin on a transaction
	if err != nil {
		return zero, failure(log, *step, err)
	}
	released := false
	defer func() {
		if !released {
			if err := sp.Rollback(context.WithoutCancel(ctx)); err != nil {
				log.Warn("rollback to savepoint failed", slog.String("error", err.Error()))
			}
		}
	}()
	ok, err := write(ctx, d, cx, cmd, plan, dbtx.New(sp), log, step)
	if err != nil {
		return zero, err
	}
	if !ok {
		return zero, errConflict
	}
	*step = stepSavepoint
	if err := sp.Commit(ctx); err != nil { // RELEASE SAVEPOINT
		return zero, failure(log, *step, err)
	}
	released = true
	return plan.first, nil
}
