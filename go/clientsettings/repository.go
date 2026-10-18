package clientsettings

import (
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

const (
	selectRow = "SELECT version, settings::text FROM fw_client_settings WHERE client_id = $1"
	insertRow = "INSERT INTO fw_client_settings (client_id, version, settings) VALUES ($1, 1, $2::jsonb)"
	updateRow = "UPDATE fw_client_settings SET version = $1, settings = $2::jsonb WHERE client_id = $3 AND version = $4"
	deleteRow = "DELETE FROM fw_client_settings WHERE client_id = $1 AND version = $2"
)

// Repository is the [usecase.Repository] of [Settings]: id = the client's code, a find of another
// client's id is a miss, writes are version-checked.
type Repository struct{}

var _ usecase.Repository[Settings, string] = Repository{}

// Find reads the client's row; a miss when the id is not the client's code or there is no row.
func (Repository) Find(ctx context.Context, db *database.DB, client fw.ClientID, id string) (usecase.Loaded[Settings], bool, error) {
	if id != client.String() {
		return usecase.Loaded[Settings]{}, false, nil
	}
	return read(ctx, db.Pool(), client)
}

type queryRower interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func read(ctx context.Context, q queryRower, client fw.ClientID) (usecase.Loaded[Settings], bool, error) {
	var version int64
	var text string
	err := q.QueryRow(ctx, selectRow, client.String()).Scan(&version, &text)
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[Settings]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[Settings]{}, false, err
	}
	values := map[string]jsontext.Value{}
	if err := json.Unmarshal([]byte(text), &values); err != nil {
		return usecase.Loaded[Settings]{}, false, fmt.Errorf("client settings of %s: the stored JSON is not an object: %w", client, err)
	}
	return usecase.LoadedFrom(client, version, Settings{client: client, values: values}), true, nil
}

func write(s Settings) (string, error) {
	b, err := json.Marshal(s.values, json.Deterministic(true))
	return string(b), err
}

// Insert stores a client's first row at version 1. Two first writes at once: the second fails with a
// unique violation (the runner answers CONFLICT / UNIQUE_VIOLATION, not retried).
func (Repository) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, s Settings) error {
	text, err := write(s)
	if err != nil {
		return err
	}
	_, err = tx.Conn().Exec(ctx, insertRow, client.String(), text)
	return err
}

// Update writes the settings, matching the loaded version.
func (Repository) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, s Settings, v usecase.VersionCheck) (int64, error) {
	text, err := write(s)
	if err != nil {
		return 0, err
	}
	tag, err := tx.Conn().Exec(ctx, updateRow, v.Next, text, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

// Delete deletes the client's row, matching the loaded version.
func (Repository) Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, _ Settings, v usecase.VersionCheck) (int64, error) {
	tag, err := tx.Conn().Exec(ctx, deleteRow, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

// Current is a client's settings as a use case found them: the stored row, or an empty version-0
// aggregate when the client has none.
type Current struct {
	loaded usecase.Loaded[Settings]
	found  bool
	empty  Settings
}

// Settings returns the stored settings, or the empty ones.
func (c Current) Settings() Settings {
	if c.found {
		return c.loaded.Get()
	}
	return c.empty
}

// Version returns the stored version, 0 when the client has no row.
func (c Current) Version() int64 {
	if c.found {
		return c.loaded.Version()
	}
	return 0
}

// ForClient returns the client's current settings, or an empty version-0 aggregate (the first write
// inserts it). It reads outside the transaction, like any Find.
func (Repository) ForClient(ctx context.Context, db *database.DB, client fw.ClientID) (Current, error) {
	loaded, found, err := read(ctx, db.Pool(), client)
	if err != nil {
		return Current{}, err
	}
	return Current{loaded: loaded, found: found, empty: Empty(client)}, nil
}

// Step is the plan step that saves changed (derived from c.Settings() with [With] or [Without]): an
// insert at version 1 when the client had no row, else a version-checked update. A change for
// another client than the Context's is not insertable (a Fresh is always the caller's), so the step
// is an update of that client's aggregate, which the runner refuses as CROSS_CLIENT_WRITE.
func (r Repository) Step(cx fw.Context, c Current, changed Settings) usecase.Step {
	switch {
	case c.found:
		return usecase.Update(c.loaded.With(changed), r)
	case changed.client == cx.Client():
		return usecase.Insert(usecase.NewFresh(cx, changed), r)
	default:
		return usecase.Update(usecase.LoadedFrom(changed.client, 0, changed), r)
	}
}
