// Package authdb is the database session store (sessions-and-login contract section 6.3), the
// default driver: table fw_session (migration set "auth-database", the Java module's file byte for
// byte) in the default database. Only the SHA-256 of a handle is stored. Expiry is decided by the
// application's clock, passed to every read, so it agrees with the session code's own checks. A
// background loop, auth-session-purge, deletes expired rows every 10 minutes, 1000 at a time.
package authdb

import (
	"context"
	"embed"
	"errors"
	"io/fs"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the module that contributes the session table's migration set (the Java module's
// id, so the history rows are the same).
const ModuleID = "auth-database"

// PurgeBatch is how many expired rows one purge statement deletes at most.
const PurgeBatch = 1000

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the session table's migration set as embedded.
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // the directory is embedded above
	}
	return sub
}

// Declare contributes the session table's migration set (module auth-database), which the
// framework runs on the default database instance.
func Declare(app *fw.App) {
	app.Module(ModuleID).Migrations(Migrations())
}

const (
	createSQL = "INSERT INTO fw_session (id_hash, kind, data, expires_at) VALUES ($1, $2, $3::jsonb, $4)"
	readSQL   = "SELECT data::text FROM fw_session WHERE id_hash = $1 AND kind = $2 AND expires_at > $3"
	takeSQL   = "DELETE FROM fw_session WHERE id_hash = $1 AND kind = $2 RETURNING data::text, expires_at"
	updateSQL = "UPDATE fw_session SET data = $1::jsonb, expires_at = $2 WHERE id_hash = $3 AND kind = $4 AND expires_at > $5"
	deleteSQL = "DELETE FROM fw_session WHERE id_hash = $1 AND kind = $2"
	purgeSQL  = "DELETE FROM fw_session WHERE id_hash IN (SELECT id_hash FROM fw_session WHERE expires_at <= $1 LIMIT $2)"
)

// Store is the database session store.
type Store struct {
	db    *database.DB
	clock fw.Clock
}

// NewStore returns the store on db (which must hold fw_session); clock decides expiry.
func NewStore(db *database.DB, clock fw.Clock) *Store { return &Store{db: db, clock: clock} }

// Factory is the auth.StoreFactory of the store on the default database instance: it checks that
// the instance is not left without the table when migrations do not run on it, starts the
// auth-session-purge loop and returns the store.
func Factory(dbs *database.Databases) auth.StoreFactory {
	return func(ctx context.Context, b *fw.Boot) (auth.Store, error) {
		db, ok := dbs.Default()
		if !ok {
			return nil, errors.New("the database session store needs a default database instance, and none is configured")
		}
		if !db.Migrates() {
			if err := db.RequireTable(ctx, "fw_session", "the database session store"); err != nil {
				return nil, err
			}
		}
		s := NewStore(db, b.Clock())
		b.Loop("auth-session-purge", background.Options{Interval: 10 * time.Minute}, func(ctx context.Context, tick background.Tick) error {
			total, deleted := 0, PurgeBatch
			for deleted == PurgeBatch && ctx.Err() == nil {
				var err error
				if deleted, err = s.Purge(ctx, PurgeBatch); err != nil {
					return err
				}
				total += deleted
			}
			if total > 0 {
				b.Logger(ModuleID).Debug("purged expired sessions and login records", "count", total)
			}
			return nil
		})
		return s, nil
	}
}

// Create implements auth.Store.
func (s *Store) Create(ctx context.Context, kind auth.Kind, data []byte, expiresAt time.Time) (string, error) {
	handle := auth.NewHandle()
	if _, err := s.db.Pool().Exec(ctx, createSQL, auth.HandleHash(handle), string(kind), string(data), expiresAt.UTC()); err != nil {
		return "", auth.StoreError("the session store could not create", err)
	}
	return handle, nil
}

// Read implements auth.Store.
func (s *Store) Read(ctx context.Context, kind auth.Kind, handle string) ([]byte, bool, error) {
	if !auth.IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	var doc string
	err := s.db.Pool().QueryRow(ctx, readSQL, auth.HandleHash(handle), string(kind), s.clock.Now().UTC()).Scan(&doc)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, auth.StoreError("the session store could not read", err)
	}
	return []byte(doc), true, nil
}

// Take implements auth.Store: one DELETE ... RETURNING, so a record is given to one caller.
func (s *Store) Take(ctx context.Context, kind auth.Kind, handle string) ([]byte, bool, error) {
	if !auth.IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	var (
		doc     string
		expires time.Time
	)
	err := s.db.Pool().QueryRow(ctx, takeSQL, auth.HandleHash(handle), string(kind)).Scan(&doc, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, auth.StoreError("the session store could not take", err)
	}
	if !s.clock.Now().Before(expires) {
		return nil, false, nil
	}
	return []byte(doc), true, nil
}

// Update implements auth.Store: only a record that still exists is replaced.
func (s *Store) Update(ctx context.Context, kind auth.Kind, handle string, data []byte, expiresAt time.Time) (string, error) {
	if !auth.IsWellFormedHandle(handle) {
		return handle, nil
	}
	if _, err := s.db.Pool().Exec(ctx, updateSQL, string(data), expiresAt.UTC(), auth.HandleHash(handle), string(kind), s.clock.Now().UTC()); err != nil {
		return "", auth.StoreError("the session store could not update", err)
	}
	return handle, nil
}

// Delete implements auth.Store.
func (s *Store) Delete(ctx context.Context, kind auth.Kind, handle string) error {
	if !auth.IsWellFormedHandle(handle) {
		return nil
	}
	if _, err := s.db.Pool().Exec(ctx, deleteSQL, auth.HandleHash(handle), string(kind)); err != nil {
		return auth.StoreError("the session store could not delete", err)
	}
	return nil
}

// Revocable implements auth.Store.
func (*Store) Revocable() bool { return true }

// Purge deletes expired records, at most limit of them, and returns how many.
func (s *Store) Purge(ctx context.Context, limit int) (int, error) {
	tag, err := s.db.Pool().Exec(ctx, purgeSQL, s.clock.Now().UTC(), limit)
	if err != nil {
		return 0, auth.StoreError("the session store could not purge", err)
	}
	return int(tag.RowsAffected()), nil
}
