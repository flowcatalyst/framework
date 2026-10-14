package usecase

import (
	"context"
	"fmt"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

// Step is one write of a plan: [Insert], [Update] or [Delete]. Only this package makes steps.
//
//sumtype:decl
type Step interface {
	step()
	client() fw.ClientID
	entity() Entity
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

func (insertStep[A, I]) step()                 {}
func (s insertStep[A, I]) client() fw.ClientID { return s.fresh.client }
func (s insertStep[A, I]) entity() Entity      { return s.fresh.value.AuditEntity() }
func (s insertStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	return true, s.repo.Insert(ctx, tx, s.fresh.client, s.fresh.value)
}

func (updateStep[A, I]) step()                 {}
func (s updateStep[A, I]) client() fw.ClientID { return s.loaded.client }
func (s updateStep[A, I]) entity() Entity      { return s.loaded.value.AuditEntity() }
func (s updateStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	n, err := s.repo.Update(ctx, tx, s.loaded.client, s.loaded.value,
		VersionCheck{Expected: s.loaded.version, Next: s.loaded.version + 1})
	return exactlyOne(n, err, "update", s.entity())
}

func (deleteStep[A, I]) step()                 {}
func (s deleteStep[A, I]) client() fw.ClientID { return s.loaded.client }
func (s deleteStep[A, I]) entity() Entity      { return s.loaded.value.AuditEntity() }
func (s deleteStep[A, I]) apply(ctx context.Context, tx *database.Tx) (bool, error) {
	n, err := s.repo.Delete(ctx, tx, s.loaded.client, s.loaded.value,
		VersionCheck{Expected: s.loaded.version, Next: s.loaded.version + 1})
	return exactlyOne(n, err, "delete", s.entity())
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
