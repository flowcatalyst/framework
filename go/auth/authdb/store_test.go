package authdb_test

import (
	"context"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authdb"
	"github.com/flowcatalyst/framework/go/auth/storetest"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{{Module: authdb.ModuleID, Files: authdb.Migrations()}},
			slog.New(slog.DiscardHandler))
	}))
}

func open(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return db
}

func TestDatabaseStoreMeetsTheContract(t *testing.T) {
	t.Parallel()
	storetest.Run(t, func(t *testing.T, clock *fwtest.Clock) auth.Store { return authdb.NewStore(open(t), clock) })
}

func TestOnlyTheHashOfAHandleIsStored(t *testing.T) {
	t.Parallel()
	db := open(t)
	clock := fwtest.NewClock(storetest.Start)
	s := authdb.NewStore(db, clock)
	h, err := s.Create(t.Context(), auth.KindSession, []byte(`{"v":1}`), clock.Now().Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	var id, kind, data string
	if err := db.Pool().QueryRow(t.Context(), "SELECT id_hash, kind, data::text FROM fw_session").Scan(&id, &kind, &data); err != nil {
		t.Fatal(err)
	}
	if id != auth.HandleHash(h) || strings.Contains(id, h) || kind != "session" {
		t.Fatalf("row %q %q (handle %q)", id, kind, h)
	}
}

func TestPurgeDeletesExpiredRowsInBatches(t *testing.T) {
	t.Parallel()
	db := open(t)
	clock := fwtest.NewClock(storetest.Start)
	s := authdb.NewStore(db, clock)
	for range 5 {
		if _, err := s.Create(t.Context(), auth.KindLogin, []byte(`{}`), clock.Now().Add(time.Minute)); err != nil {
			t.Fatal(err)
		}
	}
	live, _ := s.Create(t.Context(), auth.KindSession, []byte(`{}`), clock.Now().Add(time.Hour))
	clock.Advance(2 * time.Minute)
	if n, err := s.Purge(t.Context(), 3); err != nil || n != 3 {
		t.Fatalf("first batch: %d %v", n, err)
	}
	if n, err := s.Purge(t.Context(), 3); err != nil || n != 2 {
		t.Fatalf("second batch: %d %v", n, err)
	}
	if n, _ := s.Purge(t.Context(), 3); n != 0 {
		t.Fatal("nothing left to purge")
	}
	if _, ok, _ := s.Read(t.Context(), auth.KindSession, live); !ok {
		t.Fatal("a live record is never purged")
	}
}

func TestAStoreFailureIsAnErrorNeverNoSession(t *testing.T) {
	t.Parallel()
	db := open(t)
	s := authdb.NewStore(db, fwtest.NewClock(storetest.Start))
	h, _ := s.Create(t.Context(), auth.KindSession, []byte(`{}`), storetest.Start.Add(time.Hour))
	db.Close()
	if _, _, err := s.Read(t.Context(), auth.KindSession, h); err == nil || !strings.Contains(err.Error(), "could not read") {
		t.Fatalf("read: %v", err)
	}
	if _, err := s.Create(t.Context(), auth.KindSession, []byte(`{}`), storetest.Start.Add(time.Hour)); err == nil {
		t.Fatal("create must fail")
	}
}

func TestFactoryNeedsADefaultDatabase(t *testing.T) {
	t.Parallel()
	_, err := authdb.Factory(&database.Databases{})(t.Context(), nil)
	if err == nil || !strings.Contains(err.Error(), "default database instance") {
		t.Fatalf("%v", err)
	}
}
