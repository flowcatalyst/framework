// Package dblock is the database lock driver (locks contract section 5): PostgreSQL, the fw_lock
// table and fw_lock_fence sequence (migration set "locks-database", the Java module's file byte
// for byte) and the three shared statements of conformance/sql/locks/, each in autocommit and
// using clock_timestamp(), so Java and Go replicas exclude each other on one database.
package dblock

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/sqltext"
	"github.com/flowcatalyst/framework/go/locks"
)

// ModuleID is the module that contributes the lock table's migration set (the Java module's id,
// so the history rows are the same).
const ModuleID = "locks-database"

// DriverName is the driver's name in configuration.
const DriverName = "database"

//go:embed sql/acquire.sql
var acquireSQL string

//go:embed sql/refresh.sql
var refreshSQL string

//go:embed sql/release.sql
var releaseSQL string

//go:embed migrations/*.sql
var migrationFiles embed.FS

// The statements as sent: the shared text with numbered placeholders.
var (
	acquire = sqltext.Number(acquireSQL)
	refresh = sqltext.Number(refreshSQL)
	release = sqltext.Number(releaseSQL)
)

// Statements returns the shared statements as embedded (with JDBC placeholders), for the
// conformance tests.
func Statements() (acquire, refresh, release string) { return acquireSQL, refreshSQL, releaseSQL }

// Migrations returns the lock table's migration set as embedded.
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // the directory is embedded above
	}
	return sub
}

var keyDatabase = config.String("database", "The database instance holding fw_lock (default: the default instance).")

// Declare contributes the lock table's migration set (module locks-database) and returns the
// driver's spec for locks.Declare.
func Declare(app *fw.App) locks.DriverSpec {
	app.Module(ModuleID).Migrations(Migrations())
	return locks.DriverSpec{Name: DriverName, Keys: []config.Decl{keyDatabase}}
}

// With returns the build option that makes the database driver's instances on dbs. An instance
// on a database other than the default one checks at start-up that fw_lock exists there, since
// migrations run on the default instance only.
func With(dbs *database.Databases) locks.BuildOption {
	return locks.WithFactory(DriverName, func(ctx context.Context, _ *fw.Boot, in config.Instance) (locks.Store, error) {
		def, hasDefault := dbs.Default()
		db := def
		if name, ok := keyDatabase.Lookup(in); ok {
			named, err := dbs.Get(name)
			if err != nil {
				return nil, err
			}
			db = named
		}
		if db == nil {
			return nil, errors.New("no database instance is configured")
		}
		if !hasDefault || db != def {
			if err := db.RequireTable(ctx, "fw_lock", fmt.Sprintf("the database lock driver (instance '%s')", in.Name)); err != nil {
				return nil, err
			}
		}
		return NewStore(db), nil
	})
}

// Store is the database driver.
type Store struct {
	db *database.DB
}

// NewStore returns the driver on db (which must hold fw_lock).
func NewStore(db *database.DB) *Store { return &Store{db: db} }

// TryAcquire implements locks.Store.
func (s *Store) TryAcquire(ctx context.Context, key, owner string, leaseMillis int64) (int64, bool, error) {
	var fence int64
	err := s.db.Pool().QueryRow(ctx, acquire, key, owner, leaseMillis).Scan(&fence)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, unavailable("acquire", err)
	}
	return fence, true, nil
}

// Refresh implements locks.Store.
func (s *Store) Refresh(ctx context.Context, key, owner string, fence, leaseMillis int64) (bool, error) {
	tag, err := s.db.Pool().Exec(ctx, refresh, leaseMillis, key, owner, fence)
	if err != nil {
		return false, unavailable("refresh", err)
	}
	return tag.RowsAffected() == 1, nil
}

// Release implements locks.Store.
func (s *Store) Release(ctx context.Context, key, owner string, fence int64) (bool, error) {
	tag, err := s.db.Pool().Exec(ctx, release, key, owner, fence)
	if err != nil {
		return false, unavailable("release", err)
	}
	return tag.RowsAffected() == 1, nil
}

// ExpireNow ends a key's current lease at once (a test hook): expires_at in the past.
func (s *Store) ExpireNow(ctx context.Context, key string) error {
	_, err := s.db.Pool().Exec(ctx, "UPDATE fw_lock SET expires_at = clock_timestamp() - interval '1 second' WHERE lock_key = $1", key)
	return err
}

func unavailable(what string, err error) error {
	return fmt.Errorf("%w: database lock %s failed: %w", locks.ErrStoreUnavailable, what, err)
}
