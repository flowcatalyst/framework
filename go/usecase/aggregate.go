package usecase

import (
	"context"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
)

// Entity names an aggregate for the audit row: its type ("Order") and id.
type Entity struct {
	Type, ID string
}

// Aggregate is what a plan step writes: any type that names itself for the audit row. The
// aggregate knows nothing of storage; its client and version live in [Fresh] and [Loaded].
type Aggregate interface {
	AuditEntity() Entity
}

// Fresh is an aggregate made in this attempt, for the Context's client: an [Insert] stores it
// at version 1. A Fresh can only be made for the caller's client, so a new aggregate never
// belongs to another client.
type Fresh[A any] struct {
	value  A
	client fw.ClientID
}

// NewFresh wraps a new aggregate for the Context's client.
func NewFresh[A any](cx fw.Context, a A) Fresh[A] {
	return Fresh[A]{value: a, client: cx.Client()}
}

// Get returns the aggregate.
func (f Fresh[A]) Get() A { return f.value }

// Client returns the client it belongs to.
func (f Fresh[A]) Client() fw.ClientID { return f.client }

// Loaded is an aggregate a repository read, with the client and version of its row: an [Update]
// or [Delete] writes it with WHERE ... AND client_id = ... AND version = ....
type Loaded[A any] struct {
	value   A
	client  fw.ClientID
	version int64
}

// LoadedFrom is for repositories: the aggregate with its row's client and version.
func LoadedFrom[A any](client fw.ClientID, version int64, a A) Loaded[A] {
	return Loaded[A]{value: a, client: client, version: version}
}

// Get returns the aggregate.
func (l Loaded[A]) Get() A { return l.value }

// With returns a loaded aggregate with a new value and the same client and version: the
// changed aggregate to update.
func (l Loaded[A]) With(a A) Loaded[A] {
	l.value = a
	return l
}

// Client returns the row's client.
func (l Loaded[A]) Client() fw.ClientID { return l.client }

// Version returns the row's version.
func (l Loaded[A]) Version() int64 { return l.version }

// VersionCheck is what an update or delete matches and writes: WHERE version = Expected, SET
// version = Next. The runner computes it.
type VersionCheck struct {
	Expected, Next int64
}

// Repository reads and writes one aggregate type with explicit SQL (write-path contract
// section 2). Find reads outside the transaction and filters by client; the writes run in the
// attempt's transaction: Insert stores version 1, Update and Delete match id, client_id and
// version and return the root row's row count (0: stale; above 1: a bug). The contract tests in
// package usecasetest check an implementation.
type Repository[A any, I comparable] interface {
	Find(ctx context.Context, db *database.DB, client fw.ClientID, id I) (Loaded[A], bool, error)
	Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, a A) error
	Update(ctx context.Context, tx *database.Tx, client fw.ClientID, a A, v VersionCheck) (int64, error)
	Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, a A, v VersionCheck) (int64, error)
}
