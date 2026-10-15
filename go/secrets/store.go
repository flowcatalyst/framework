package secrets

import (
	"context"
	"errors"
	"fmt"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// Result is what a store found for a (client, name): [Found], [NotFound] or [Refused].
//
//sumtype:decl
type Result interface{ result() }

// Found carries the secret.
type Found struct{ Secret config.Secret }

// NotFound means the store has no such secret.
type NotFound struct{}

// Refused means the store cannot hold a secret for this client or name (for example a client id
// the env store cannot encode).
type Refused struct{ Reason string }

func (Found) result()    {}
func (NotFound) result() {}
func (Refused) result()  {}

// Store is a secret store: a driver (Env, File) behind an optional cache. Find returns an error
// only when the store cannot be read (an I/O error, permission denied, invalid UTF-8, a file too
// large): a [*StoreError] whose message has neither a value nor a file's content.
type Store interface {
	// Find looks up the secret name of a client.
	Find(ctx context.Context, client fw.ClientID, name string) (Result, error)
	// Evict drops the cached value of one secret.
	Evict(client fw.ClientID, name string)
	// EvictAll drops every cached value.
	EvictAll()
}

// StoreFactory makes one store when [Build] runs, from its name and the booted application. The
// shipped factories are [Env], [File] and [Platform]; an application may write its own.
type StoreFactory interface {
	Create(ctx context.Context, name string, b *fw.Boot) (Store, error)
}

// StoreError is a store that could not be read.
type StoreError struct {
	msg   string
	cause error
}

func (e *StoreError) Error() string { return e.msg }

// Unwrap returns the I/O error behind it, if any.
func (e *StoreError) Unwrap() error { return e.cause }

// The errors a [Resolver] returns.
var (
	// ErrNoStore means no secret store is given (or none is the default and the reference names
	// none).
	ErrNoStore = errors.New("no secret store is configured")
	// ErrUnknownStore means the reference names a store that is not given.
	ErrUnknownStore = errors.New("unknown secret store")
	// ErrNotFound is returned by Require for a secret that is NotFound or Refused.
	ErrNotFound = errors.New("secret not found")
	// ErrInvalidName means a secret name that does not match the grammar was given to a store.
	ErrInvalidName = errors.New("invalid secret name")
)

func notFoundf(format string, a ...any) error {
	return fmt.Errorf("%w: %s", ErrNotFound, fmt.Sprintf(format, a...))
}
