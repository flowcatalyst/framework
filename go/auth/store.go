package auth

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// Kind is the kind of a stored record.
type Kind string

const (
	// KindSession is a browser session.
	KindSession Kind = "session"
	// KindLogin is a login in progress.
	KindLogin Kind = "login"
)

// ErrStore is wrapped by every failure of a session store: a store that cannot answer is an
// error (the request gets 500 INTERNAL), never "no session".
var ErrStore = errors.New("auth: the session store failed")

// StoreError wraps a store failure so it matches [ErrStore] and keeps the cause.
func StoreError(what string, cause error) error {
	if cause == nil {
		return fmt.Errorf("%w: %s", ErrStore, what)
	}
	return fmt.Errorf("%w: %s: %w", ErrStore, what, cause)
}

// Store is where sessions and login records live (contract section 6.3). A record has a kind, a
// JSON document and an expiry; the store hands back a handle that is the cookie value. Drivers:
// authdb (the default), authredis, [NewCookieStore] and [NewMemoryStore]. Expiry is decided by the
// application's clock, so every store is given the fw.Clock.
//
// Server stores keep only HandleHash of a handle, answer "none" for a handle that is not
// well-formed ([IsWellFormedHandle]) without a store call, and are safe for concurrent use.
type Store interface {
	// Create stores a new record and returns its handle, the value of the cookie.
	Create(ctx context.Context, kind Kind, data []byte, expiresAt time.Time) (handle string, err error)
	// Read returns the record; false when it is absent, expired, of another kind or the handle is
	// malformed.
	Read(ctx context.Context, kind Kind, handle string) (data []byte, found bool, err error)
	// Take reads and deletes a record in one atomic step, so it can be used once (the cookie store
	// cannot: see its documentation).
	Take(ctx context.Context, kind Kind, handle string) (data []byte, found bool, err error)
	// Update replaces a record and its expiry and returns the handle to send from now on: the same
	// for server stores, a new one for the cookie store. A deleted record is not brought back.
	Update(ctx context.Context, kind Kind, handle string, data []byte, expiresAt time.Time) (string, error)
	// Delete removes a record; absent is not an error.
	Delete(ctx context.Context, kind Kind, handle string) error
	// Revocable reports whether Delete revokes: false for the cookie store, which keeps nothing.
	Revocable() bool
}

// StoreFactory builds a session store when the application starts, from the Boot (clock, logger,
// loops). Which factory backs the store is a fact of the code: [MemoryStoreFactory],
// [CookieStoreFactory], authdb.Factory, authredis.Factory.
type StoreFactory func(ctx context.Context, b *fw.Boot) (Store, error)

// MemoryStoreFactory is the factory of the memory store: one process only (tests, a single
// replica).
func MemoryStoreFactory() StoreFactory {
	return func(_ context.Context, b *fw.Boot) (Store, error) { return NewMemoryStore(b.Clock()), nil }
}
