// Package usecasetest checks that an application's repository keeps the write-path contract
// (section 2): Insert stores version 1 under the client; Find filters by client; Update and Delete
// match id, client and version and report the root row's count, so a stale write affects 0 rows.
// The runner relies on these rules; a repository that ignores the version silently loses
// updates, which only a test like this one finds.
package usecasetest

import (
	"testing"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/dbtx"
	"github.com/flowcatalyst/framework/go/usecase"
)

// Harness supplies the repository under test and its aggregates.
type Harness[A any, I comparable] struct {
	// Repo is the repository.
	Repo usecase.Repository[A, I]
	// DB is a database holding the repository's tables (a fresh one per test).
	DB *database.DB
	// New returns a new aggregate and its id; each call a different one.
	New func() (A, I)
	// Change returns a changed copy of an aggregate (what an update writes).
	Change func(A) A
	// Equal reports whether two aggregates hold the same values.
	Equal func(a, b A) bool
}

// Repository runs the repository contract against h, one subtest per rule.
func Repository[A any, I comparable](t *testing.T, h Harness[A, I]) {
	t.Helper()
	acme, other := fw.MustClientID("acme"), fw.MustClientID("other")
	inTx := func(t *testing.T, f func(tx *database.Tx) error) {
		t.Helper()
		if err := pgx.BeginFunc(t.Context(), h.DB.Pool(), func(tx pgx.Tx) error { return f(dbtx.New(tx)) }); err != nil {
			t.Fatal(err)
		}
	}
	find := func(t *testing.T, client fw.ClientID, id I) (usecase.Loaded[A], bool) {
		t.Helper()
		l, ok, err := h.Repo.Find(t.Context(), h.DB, client, id)
		if err != nil {
			t.Fatal(err)
		}
		return l, ok
	}
	insert := func(t *testing.T) (A, I) {
		t.Helper()
		a, id := h.New()
		inTx(t, func(tx *database.Tx) error { return h.Repo.Insert(t.Context(), tx, acme, a) })
		return a, id
	}

	t.Run("insert stores version 1 under the client, and find reads it back", func(t *testing.T) {
		a, id := insert(t)
		l, ok := find(t, acme, id)
		if !ok || l.Version() != 1 || l.Client() != acme || !h.Equal(l.Get(), a) {
			t.Fatalf("found=%v version=%d client=%s equal=%v", ok, l.Version(), l.Client(), ok && h.Equal(l.Get(), a))
		}
	})
	t.Run("find filters by client", func(t *testing.T) {
		_, id := insert(t)
		if _, ok := find(t, other, id); ok {
			t.Fatal("another client's aggregate was found")
		}
	})
	t.Run("update at the loaded version writes the next one", func(t *testing.T) {
		_, id := insert(t)
		l, _ := find(t, acme, id)
		changed := h.Change(l.Get())
		var n int64
		inTx(t, func(tx *database.Tx) (err error) {
			n, err = h.Repo.Update(t.Context(), tx, acme, changed, usecase.VersionCheck{Expected: 1, Next: 2})
			return err
		})
		l2, _ := find(t, acme, id)
		if n != 1 || l2.Version() != 2 || !h.Equal(l2.Get(), changed) {
			t.Fatalf("rows %d, version %d, changed %v", n, l2.Version(), h.Equal(l2.Get(), changed))
		}
	})
	t.Run("a stale update or delete affects no row and changes nothing", func(t *testing.T) {
		a, id := insert(t)
		var u, d int64
		inTx(t, func(tx *database.Tx) (err error) {
			if u, err = h.Repo.Update(t.Context(), tx, acme, h.Change(a), usecase.VersionCheck{Expected: 7, Next: 8}); err != nil {
				return err
			}
			d, err = h.Repo.Delete(t.Context(), tx, acme, a, usecase.VersionCheck{Expected: 7, Next: 8})
			return err
		})
		l, ok := find(t, acme, id)
		if u != 0 || d != 0 || !ok || l.Version() != 1 || !h.Equal(l.Get(), a) {
			t.Fatalf("stale update %d rows, delete %d rows, still there %v at version %d", u, d, ok, l.Version())
		}
	})
	t.Run("update and delete of another client's row affect no row", func(t *testing.T) {
		a, id := insert(t)
		var u, d int64
		inTx(t, func(tx *database.Tx) (err error) {
			if u, err = h.Repo.Update(t.Context(), tx, other, h.Change(a), usecase.VersionCheck{Expected: 1, Next: 2}); err != nil {
				return err
			}
			d, err = h.Repo.Delete(t.Context(), tx, other, a, usecase.VersionCheck{Expected: 1, Next: 2})
			return err
		})
		if _, ok := find(t, acme, id); u != 0 || d != 0 || !ok {
			t.Fatalf("cross-client update %d rows, delete %d rows", u, d)
		}
	})
	t.Run("delete at the loaded version removes it", func(t *testing.T) {
		a, id := insert(t)
		var n int64
		inTx(t, func(tx *database.Tx) (err error) {
			n, err = h.Repo.Delete(t.Context(), tx, acme, a, usecase.VersionCheck{Expected: 1, Next: 2})
			return err
		})
		if _, ok := find(t, acme, id); n != 1 || ok {
			t.Fatalf("delete %d rows, still found %v", n, ok)
		}
	})
}
