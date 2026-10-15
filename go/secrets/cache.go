package secrets

import (
	"container/list"
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// MaxCacheTTL and MaxCacheEntries bound a [CachePolicy].
const (
	MaxCacheTTL     = 24 * time.Hour
	MaxCacheEntries = 1_000_000
)

// CachePolicy is how long a found secret is kept and how many are kept (the least recently used
// goes first). Not-found is never cached. A stale secret after rotation is bounded by the TTL; a
// value can be evicted per (client, reference) or entirely.
type CachePolicy struct {
	// TTL is how long a value is kept, 0 (no cache) to 24h.
	TTL time.Duration
	// MaxEntries is the most values kept, 1 to 1 000 000.
	MaxEntries int
}

// DefaultCache is 5 minutes and 10 000 entries.
func DefaultCache() CachePolicy { return CachePolicy{TTL: 5 * time.Minute, MaxEntries: 10_000} }

// NoCache turns the cache off.
func NoCache() CachePolicy { return CachePolicy{TTL: 0, MaxEntries: 1} }

// NewCachePolicy checks the ranges.
func NewCachePolicy(ttl time.Duration, maxEntries int) (CachePolicy, error) {
	p := CachePolicy{TTL: ttl, MaxEntries: maxEntries}
	return p, p.Validate()
}

// Validate checks the ranges.
func (p CachePolicy) Validate() error {
	if p.TTL < 0 || p.TTL > MaxCacheTTL {
		return fmt.Errorf("ttl must be 0 to 24h, was %v", p.TTL)
	}
	if p.MaxEntries < 1 || p.MaxEntries > MaxCacheEntries {
		return fmt.Errorf("maxEntries must be 1 to %d, was %d", MaxCacheEntries, p.MaxEntries)
	}
	return nil
}

// cached wraps a store in a cache of found secrets.
type cached struct {
	inner Store
	ttl   time.Duration
	max   int
	clock fw.Clock

	mu      sync.Mutex
	entries map[cacheKey]*list.Element
	order   *list.List // front = most recently used
}

type cacheKey struct{ client, name string }

type cacheEntry struct {
	key       cacheKey
	result    Found
	expiresAt time.Time
}

func newCached(inner Store, p CachePolicy, clock fw.Clock) *cached {
	return &cached{inner: inner, ttl: p.TTL, max: p.MaxEntries, clock: clock, entries: map[cacheKey]*list.Element{}, order: list.New()}
}

func (c *cached) Find(ctx context.Context, client fw.ClientID, name string) (Result, error) {
	key := cacheKey{client.String(), name}
	c.mu.Lock()
	if el, ok := c.entries[key]; ok {
		e := el.Value.(*cacheEntry) //nolint:forcetypeassert // the list holds only *cacheEntry
		if c.clock.Now().Before(e.expiresAt) {
			c.order.MoveToFront(el)
			c.mu.Unlock()
			return e.result, nil
		}
		c.remove(el)
	}
	c.mu.Unlock()
	res, err := c.inner.Find(ctx, client, name) // outside the lock: it may do I/O
	if err != nil {
		return nil, err
	}
	if found, ok := res.(Found); ok {
		c.mu.Lock()
		defer c.mu.Unlock()
		if el, exists := c.entries[key]; exists {
			c.remove(el)
		}
		c.entries[key] = c.order.PushFront(&cacheEntry{key: key, result: found, expiresAt: c.clock.Now().Add(c.ttl)})
		for c.order.Len() > c.max {
			c.remove(c.order.Back())
		}
	}
	return res, nil
}

func (c *cached) remove(el *list.Element) {
	e := el.Value.(*cacheEntry) //nolint:forcetypeassert // the list holds only *cacheEntry
	delete(c.entries, e.key)
	c.order.Remove(el)
}

func (c *cached) Evict(client fw.ClientID, name string) {
	c.mu.Lock()
	if el, ok := c.entries[cacheKey{client.String(), name}]; ok {
		c.remove(el)
	}
	c.mu.Unlock()
	c.inner.Evict(client, name)
}

func (c *cached) EvictAll() {
	c.mu.Lock()
	c.entries = map[cacheKey]*list.Element{}
	c.order.Init()
	c.mu.Unlock()
	c.inner.EvictAll()
}

func (c *cached) String() string { return "Cached" + fmt.Sprint(c.inner) }
