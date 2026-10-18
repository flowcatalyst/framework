package clientsettings

import (
	"context"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
)

// Store reads settings straight from the database, without a cache (contract section 4): an
// application that reads a setting on a hot path caches it itself.
type Store struct{ db *database.DB }

// NewStore returns the store reading from db (the default instance).
func NewStore(db *database.DB) *Store { return &Store{db: db} }

// Read returns the client's value of the setting (Java's store.get), the default when the client has no row or no value
// for the key. A stored value that does not decode is an error wrapping [ErrDecode] and naming the
// key and the client.
func Read[T any](ctx context.Context, s *Store, client fw.ClientID, set Setting[T]) (T, error) {
	loaded, found, err := read(ctx, s.db.Pool(), client)
	if err != nil {
		return set.def, err
	}
	if !found {
		return set.def, nil
	}
	return Get(loaded.Get(), set)
}
