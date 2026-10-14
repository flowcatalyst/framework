package locks_test

import (
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
