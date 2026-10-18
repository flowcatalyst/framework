package conformance_test

import (
	"context"
	"io/fs"
	"log/slog"
	"strconv"
	"testing"

	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/usecase"
)

type historyDoc struct {
	Module string       `json:"module"`
	Rows   []historyRow `json:"rows"`
}

type historyRow struct {
	Version     int    `json:"version"`
	Description string `json:"description"`
	Checksum    string `json:"checksum"`
}

// migrationSets maps each migration vector to the Go set it describes.
func migrationSets() map[string]fw.MigrationSet {
	return map[string]fw.MigrationSet{
		"migrations/locks-database.json":  {Module: dblock.ModuleID, Files: dblock.Migrations()},
		"migrations/outbox.json":          {Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
		"migrations/orders.json":          {Module: orders.ModuleID, Files: orders.Migrations()},
		"migrations/client-settings.json": {Module: clientsettings.ModuleID, Files: clientsettings.Migrations()},
	}
}

func openFresh(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return db
}

func history(t *testing.T, db *database.DB, module string) []historyRow {
	t.Helper()
	rows, err := db.Pool().Query(t.Context(),
		"SELECT version, description, checksum FROM fw_schema_history WHERE module = $1 ORDER BY version", module)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []historyRow
	for rows.Next() {
		var r historyRow
		if err := rows.Scan(&r.Version, &r.Description, &r.Checksum); err != nil {
			t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

// The Go runner writes the rows the Java runner writes for each set.
func TestMigrationHistoryRowsEqualJava(t *testing.T) {
	t.Parallel()
	for vector, set := range migrationSets() {
		t.Run(vector, func(t *testing.T) {
			t.Parallel()
			var doc historyDoc
			conformance.ReadJSON(t, vector, &doc)
			if doc.Module != set.Module {
				t.Fatalf("%s: module %q, the Go set is %q", vector, doc.Module, set.Module)
			}
			db := openFresh(t)
			if err := database.ApplyMigrations(t.Context(), db, []fw.MigrationSet{set}, slog.New(slog.DiscardHandler)); err != nil {
				t.Fatal(err)
			}
			got := history(t, db, set.Module)
			if len(got) != len(doc.Rows) {
				t.Fatalf("%s: %d rows, want %d: %+v", vector, len(got), len(doc.Rows), got)
			}
			for i, want := range doc.Rows {
				where := conformance.Where(vector, "row "+strconv.Itoa(i), "")
				if got[i] != want {
					t.Errorf("%s: got %+v, want %+v", where, got[i], want)
				}
			}
		})
	}
}

// A database holding the Java runner's history (the schema created by the same files and the
// vector's rows) is accepted: nothing is applied again and no problem is reported.
func TestJavaMigratedDatabaseIsAccepted(t *testing.T) {
	t.Parallel()
	for vector, set := range migrationSets() {
		t.Run(vector, func(t *testing.T) {
			t.Parallel()
			var doc historyDoc
			conformance.ReadJSON(t, vector, &doc)
			db := openFresh(t)
			ctx := t.Context()
			simulateJava(t, ctx, db, set, doc)
			if err := database.ApplyMigrations(ctx, db, []fw.MigrationSet{set}, slog.New(slog.DiscardHandler)); err != nil {
				t.Fatalf("the Go runner refused a Java-migrated database: %v", err)
			}
			if got := history(t, db, set.Module); len(got) != len(doc.Rows) {
				t.Fatalf("rows after the Go run: %+v", got)
			}
		})
	}
}

// simulateJava creates the schema with the set's files and writes the vector's history rows, as
// the Java runner leaves a database.
func simulateJava(t *testing.T, ctx context.Context, db *database.DB, set fw.MigrationSet, doc historyDoc) {
	t.Helper()
	if _, err := db.Pool().Exec(ctx, database.CreateHistorySQL); err != nil {
		t.Fatal(err)
	}
	entries, err := fs.ReadDir(set.Files, ".")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		b, err := fs.ReadFile(set.Files, e.Name())
		if err != nil {
			t.Fatal(err)
		}
		conn, err := db.Pool().Acquire(ctx)
		if err != nil {
			t.Fatal(err)
		}
		_, err = conn.Conn().PgConn().Exec(ctx, string(b)).ReadAll()
		conn.Release()
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, r := range doc.Rows {
		if _, err := db.Pool().Exec(ctx, "INSERT INTO fw_schema_history VALUES ($1, $2, $3, $4, now())",
			doc.Module, r.Version, r.Description, r.Checksum); err != nil {
			t.Fatal(err)
		}
	}
}
