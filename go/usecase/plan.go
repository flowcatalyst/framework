package usecase

import (
	"context"
	"fmt"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

// Step is one write of a plan: an aggregate's [Insert], [Update] or [Delete], or an [Append] of
// an append-only row. Only this package makes steps.
//
//sumtype:decl
type Step interface {
	step()
	// aggregate returns the client and audit entity of an aggregate step; false for an append.
	aggregate() (fw.ClientID, Entity, bool)
	// apply writes the step; false when the aggregate was stale.
	apply(ctx context.Context, tx *database.Tx) (bool, error)
}

type insertStep[A Aggregate, I comparable] struct {
	fresh Fresh[A]
	repo  Repository[A, I]
}

type updateStep[A Aggregate, I comparable] struct {
	loaded Loaded[A]
	repo   Repository[A, I]
}

type deleteStep[A Aggregate, I comparable] struct {
	loaded Loaded[A]
	repo   Repository[A, I]
}

// Insert stores a new aggregate at version 1, for the Context's client.
func Insert[A Aggregate, I comparable](f Fresh[A], r Repository[A, I]) Step {
	return insertStep[A, I]{fresh: f, repo: r}
}

// Update writes a loaded aggregate: version Loaded.Version() + 1, matching the loaded version.
func Update[A Aggregate, I comparable](l Loaded[A], r Repository[A, I]) Step {
	return updateStep[A, I]{loaded: l, repo: r}
}

// Delete deletes a loaded aggregate, matching the loaded version.
func Delete[A Aggregate, I comparable](l Loaded[A], r Repository[A, I]) Step {
	return deleteStep[A, I]{loaded: l, repo: r}
}

func (insertStep[A, I]) step()            {}
func (s insertStep[A, I]) entity() Entity { return s.fresh.value.AuditEntity() }
func (s insertStep[A, I]) aggregate() (fw.ClientID, Entity, bool) {
	return s.fresh.client, s.entity(), true
}

func (s insertStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	return true, s.repo.Insert(ctx, tx, s.fresh.client, s.fresh.value)
}

func (updateStep[A, I]) step()            {}
func (s updateStep[A, I]) entity() Entity { return s.loaded.value.AuditEntity() }
func (s updateStep[A, I]) aggregate() (fw.ClientID, Entity, bool) {
	return s.loaded.client, s.entity(), true
}

func (s updateStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	n, err := s.repo.Update(ctx, tx, s.loaded.client, s.loaded.value,
		VersionCheck{Expected: s.loaded.version, Next: s.loaded.version + 1})
	return exactlyOne(n, err, "update", s.entity())
}

func (deleteStep[A, I]) step()            {}
func (s deleteStep[A, I]) entity() Entity { return s.loaded.value.AuditEntity() }
func (s deleteStep[A, I]) aggregate() (fw.ClientID, Entity, bool) {
	return s.loaded.client, s.entity(), true
}

func (s deleteStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	n, err := s.repo.Delete(ctx, tx, s.loaded.client, s.loaded.value,
		VersionCheck{Expected: s.loaded.version, Next: s.loaded.version + 1})
	return exactlyOne(n, err, "delete", s.entity())
}

// Appender writes one kind of append-only row (an activity entry, a log line, a projection) in a
// plan's transaction: an INSERT the application owns. The row carries everything it stores,
// including its client (take it from the Context when the plan is built).
type Appender[R any] interface {
	Append(ctx context.Context, tx *database.Tx, row R) error
}

type appendStep[R any] struct {
	row      R
	appender Appender[R]
}

// Append writes an append-only row in the plan's transaction, in plan order beside the aggregate
// steps: committed or rolled back with them, written again by a retried attempt only, never
// version-checked and never the audit row's subject (that is the plan's first aggregate step,
// wherever the appends are). A plan of appends only, with no aggregate step, is refused (INTERNAL
// APPEND_ONLY_PLAN): the appends belong to an aggregate change, which the audit row names. Use an
// aggregate step for anything that is loaded, changed or deleted later; use Append for rows that
// are only ever inserted.
func Append[R any](row R, a Appender[R]) Step { return appendStep[R]{row: row, appender: a} }

func (appendStep[R]) step()                                  {}
func (appendStep[R]) aggregate() (fw.ClientID, Entity, bool) { return fw.ClientID{}, Entity{}, false }

func (s appendStep[R]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	if s.appender == nil {
		return false, fmt.Errorf("usecase.Append of %T without an Appender", s.row)
	}
	return true, s.appender.Append(ctx, tx, s.row)
}

func exactlyOne(n int64, err error, op string, e Entity) (bool, error) {
	switch {
	case err != nil:
		return false, err
	case n == 0:
		return false, nil
	case n > 1:
		return false, fmt.Errorf("%s of %s %s affected %d rows", op, e.Type, e.ID, n)
	}
	return true, nil
}

// Plan is what a use case decided to write: the steps in order, the events (the first is the
// use case's result) and dispatch jobs. Every plan carries an event.
type Plan[E event.Event] struct {
	first  E
	events []event.Event
	steps  []Step
	jobs   []DispatchJob
}

// NewPlan returns a plan whose result is first, with the writes in order.
func NewPlan[E event.Event](first E, steps ...Step) *Plan[E] {
	return &Plan[E]{first: first, events: []event.Event{first}, steps: steps}
}

// Emit adds another event, written to the outbox after the earlier ones.
func (p *Plan[E]) Emit(ev event.Event) *Plan[E] {
	p.events = append(p.events, ev)
	return p
}

// Dispatch adds a dispatch job, written to the outbox in the same transaction, after the events
// and before the audit row (write-path contract section 7a).
func (p *Plan[E]) Dispatch(job DispatchJob) *Plan[E] {
	p.jobs = append(p.jobs, job)
	return p
}
