package locks_test

import (
	"os"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/lockstest"
)

func TestMemoryDriverContract(t *testing.T) {
	t.Parallel()
	lockstest.Run(t, lockstest.Harness{New: func(t *testing.T) lockstest.Env {
		clock := &lockstest.OffsetClock{}
		store := locks.NewMemoryStore(clock)
		return lockstest.Env{
			Lock:   locks.New(store, locks.WithClock(clock)),
			Expire: func(_ *testing.T, k locks.Key) { store.ExpireNow(k.Stored()) },
			Pass:   func(_ *testing.T, d time.Duration) { clock.Advance(d) },
		}
	}})
}

func writeFile(t *testing.T, path, text string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}
}
