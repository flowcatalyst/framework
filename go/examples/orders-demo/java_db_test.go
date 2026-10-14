package main

import (
	"encoding/json/v2"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// TestRunsOnADatabaseTheJavaDemoMigrated builds the database the Java orders-demo leaves (the
// outbox and orders tables made by the same files, and the Java runner's history rows from
// conformance/migrations/outbox.json and orders.json) and runs the Go demo on it: nothing is
// migrated again and the scenario works (design decision 10).
func TestRunsOnADatabaseTheJavaDemoMigrated(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	db, err := database.Open(t.Context(), "main", url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Pool().Exec(t.Context(), database.CreateHistorySQL); err != nil {
		t.Fatal(err)
	}
	for _, set := range []struct {
		vector string
		files  fs.FS
	}{{"outbox.json", usecase.OutboxMigrations()}, {"orders.json", orders.Migrations()}} {
		raw, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "migrations", set.vector))
		if err != nil {
			t.Fatal(err)
		}
		var doc struct {
			Module string `json:"module"`
			Rows   []struct {
				Version     int    `json:"version"`
				Description string `json:"description"`
				Checksum    string `json:"checksum"`
			} `json:"rows"`
		}
		if err := json.Unmarshal(raw, &doc); err != nil {
			t.Fatal(err)
		}
		entries, _ := fs.ReadDir(set.files, ".")
		for _, e := range entries {
			b, _ := fs.ReadFile(set.files, e.Name())
			conn, err := db.Pool().Acquire(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			_, err = conn.Conn().PgConn().Exec(t.Context(), string(b)).ReadAll()
			conn.Release()
			if err != nil {
				t.Fatal(err)
			}
		}
		for _, r := range doc.Rows {
			if _, err := db.Pool().Exec(t.Context(), "INSERT INTO fw_schema_history VALUES ($1, $2, $3, $4, now())",
				doc.Module, r.Version, r.Description, r.Checksum); err != nil {
				t.Fatal(err)
			}
		}
	}
	s := start(t, url, fw.MustClientID("demo"))
	if !strings.Contains(s.out.String(), "add after cancel: BUSINESS_RULE ORDER_NOT_OPEN") {
		t.Errorf("scenario on the Java-migrated database:\n%s", s.out)
	}
	var history int
	_ = db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM fw_schema_history").Scan(&history)
	if history != 3 {
		t.Errorf("%d history rows, want the Java runner's 3 (nothing applied again)", history)
	}
}
