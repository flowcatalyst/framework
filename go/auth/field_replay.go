package auth

import (
	"context"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// FieldReplayWindow is how long a proof's jti is remembered: the nonce window (two 5-minute
// buckets) plus one minute (contract section 15.4).
const FieldReplayWindow = 11 * time.Minute

// FieldReplayCache remembers the jti of the DPoP proofs it has seen, per device key (jkt), so a
// captured proof cannot be used twice inside the nonce window. Remember records the pair and
// reports whether it was new; an error means the cache could not be asked, and the request is
// answered 503 (a replay check that cannot be made is not skipped). Implementations: the memory
// cache ([NewMemoryFieldReplayCache], the default) and the Redis cache (authredis.NewReplayCache).
// With more than one replica, use a shared one.
type FieldReplayCache interface {
	Remember(ctx context.Context, jkt, jti string, ttl time.Duration) (first bool, err error)
}

// MemoryFieldReplayCache is the in-process replay cache. It is only correct for one replica.
type MemoryFieldReplayCache struct {
	clock fw.Clock

	mu      sync.Mutex
	seen    map[string]time.Time
	inserts uint64
}

// NewMemoryFieldReplayCache returns an empty cache whose expiry is decided by clock.
func NewMemoryFieldReplayCache(clock fw.Clock) *MemoryFieldReplayCache {
	return &MemoryFieldReplayCache{clock: clock, seen: map[string]time.Time{}}
}

// Remember implements [FieldReplayCache]. Every 1024 insertions the expired entries are removed.
func (c *MemoryFieldReplayCache) Remember(_ context.Context, jkt, jti string, ttl time.Duration) (bool, error) {
	now := c.clock.Now()
	key := jkt + "\x00" + jti
	c.mu.Lock()
	defer c.mu.Unlock()
	if until, ok := c.seen[key]; ok && now.Before(until) {
		return false, nil
	}
	c.seen[key] = now.Add(ttl)
	c.inserts++
	if c.inserts%1024 == 0 {
		for k, until := range c.seen {
			if !now.Before(until) {
				delete(c.seen, k)
			}
		}
	}
	return true, nil
}
