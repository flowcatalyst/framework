package dblock_test

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/locks/lockstest"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{{Module: dblock.ModuleID, Files: dblock.Migrations()}},
			slog.New(slog.DiscardHandler))
	}))
}

func TestDatabaseDriverContract(t *testing.T) {
	t.Parallel()
	lockstest.Run(t, lockstest.Harness{New: func(t *testing.T) lockstest.Env {
		db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(db.Close)
		store := dblock.NewStore(db)
		return lockstest.Env{
			Lock: locks.New(store),
			Expire: func(t *testing.T, k locks.Key) {
				if err := store.ExpireNow(t.Context(), k.Stored()); err != nil {
					t.Fatal(err)
				}
			},
			Pass: func(_ *testing.T, d time.Duration) { time.Sleep(d) },
		}
	}})
}

func TestStoreFailureIsUnavailable(t *testing.T) {
	t.Parallel()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	store := dblock.NewStore(db)
	db.Close()
	_, _, err = store.TryAcquire(t.Context(), "svc:x", "owner", 1000)
	if err == nil || !errorsIs(err, locks.ErrStoreUnavailable) {
		t.Fatalf("err %v, want ErrStoreUnavailable", err)
	}
}
