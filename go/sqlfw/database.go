package sqlfw

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/url"
	"os"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

// withDatabase runs fn with the URL of an empty database migrated by the framework's own runner:
// $FW_SQL_DATABASE_URL when set, otherwise an embedded PostgreSQL 18 that is stopped and removed
// when fn returns.
func (t *tool) withDatabase(ctx context.Context, fn func(dsn string) error) error {
	raw := t.cfg.DatabaseURL
	if raw == "" {
		raw = os.Getenv(EnvDatabaseURL) //nolint:forbidigo // a tool reads its own setting from the environment
	}
	if raw != "" {
		if err := pgembed.CheckURL(EnvDatabaseURL, raw); err != nil {
			return err
		}
	} else {
		fmt.Fprintln(t.out, "starting an embedded PostgreSQL 18 ...")
		var stop func()
		var err error
		raw, stop, err = pgembed.Start(ctx)
		if err != nil {
			return err
		}
		defer stop()
	}
	dsn, err := withSSLDisabled(raw)
	if err != nil {
		return err
	}
	if err := t.migrate(ctx, dsn); err != nil {
		return err
	}
	return fn(dsn)
}

// migrate requires an empty database and applies every set with the framework's runner.
func (t *tool) migrate(ctx context.Context, dsn string) error {
	db, err := database.Open(ctx, "fwsql", dsn)
	if err != nil {
		return fmt.Errorf("connect to the database: %w", err)
	}
	defer db.Close()
	var relations int
	err = db.Pool().QueryRow(ctx, `SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')`).Scan(&relations)
	if err != nil {
		return fmt.Errorf("inspect the database: %w", err)
	}
	if relations > 0 {
		return fmt.Errorf("the database is not empty (%d relations); %s must point at an empty database", relations, EnvDatabaseURL)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	if err := database.ApplyMigrations(ctx, db, t.cfg.Sets, log); err != nil {
		return fmt.Errorf("apply the migration sets: %w", err)
	}
	return nil
}

// withSSLDisabled adds sslmode=disable unless the URL sets a mode, as the database is local.
func withSSLDisabled(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("%s does not parse: %w", EnvDatabaseURL, err)
	}
	q := u.Query()
	if q.Get("sslmode") == "" {
		q.Set("sslmode", "disable")
		u.RawQuery = q.Encode()
	}
	return u.String(), nil
}
