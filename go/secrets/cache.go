package secrets

import (
	"container/list"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// MaxCacheTTL and MaxCacheEntries bound a [CachePolicy].
const (
	MaxCacheTTL     = 24 * time.Hour
	MaxCacheEntries = 1_000_000
)

// DefaultStaleIfError is how long past its TTL a value is served while its store is failing,
// unless the policy says otherwise.
const DefaultStaleIfError = 5 * time.Minute

// CachePolicy is how long a found secret is kept and how many are kept (the least recently used
// goes first). Not-found is never cached. A stale secret after rotation is bounded by the TTL; a
// value can be evicted per (client, reference) or entirely.
type CachePolicy struct {
	// TTL is how long a value is kept, 0 (no cache) to 24h.
	TTL time.Duration
	// MaxEntries is the most values kept, 1 to 1 000 000.
	MaxEntries int
	// StaleIfError is how long past its TTL an expired value may still be served when the store
	// fails with a [*StoreError] (the platform is down, a 5xx, the network), 0 (off) to 24h. It
	// never applies after NotFound or Refused, which drop the entry.
	StaleIfError time.Duration
}

// DefaultCache is 5 minutes and 10 000 entries, stale for up to [DefaultStaleIfError] while the
// store fails.
func DefaultCache() CachePolicy {
	return CachePolicy{TTL: 5 * time.Minute, MaxEntries: 10_000, StaleIfError: DefaultStaleIfError}
}

// NoCache turns the cache off (and so serves nothing stale).
func NoCache() CachePolicy { return CachePolicy{TTL: 0, MaxEntries: 1} }

// NewCachePolicy checks the ranges; StaleIfError is [DefaultStaleIfError].
func NewCachePolicy(ttl time.Duration, maxEntries int) (CachePolicy, error) {
	p := CachePolicy{TTL: ttl, MaxEntries: maxEntries, StaleIfError: DefaultStaleIfError}
	return p, p.Validate()
}

// WithStaleIfError returns a copy with another stale-if-error window (0 turns it off).
func (p CachePolicy) WithStaleIfError(d time.Duration) CachePolicy {
	p.StaleIfError = d
	return p
}

// Validate checks the ranges.
func (p CachePolicy) Validate() error {
	if p.TTL < 0 || p.TTL > MaxCacheTTL {
		return fmt.Errorf("ttl must be 0 to 24h, was %v", p.TTL)
	}
	if p.MaxEntries < 1 || p.MaxEntries > MaxCacheEntries {
		return fmt.Errorf("maxEntries must be 1 to %d, was %d", MaxCacheEntries, p.MaxEntries)
	}
	if p.StaleIfError < 0 || p.StaleIfError > MaxCacheTTL {
		return fmt.Errorf("staleIfError must be 0 to 24h, was %v", p.StaleIfError)
	}
	return nil
}

// Canonicalizer is implemented by a store that knows two names for one client (the platform's
// clt_... id and the client's identifier). The cache keeps a value under the canonical form, asking
// before a lookup and again after a found one (so a store may learn the alias from its own answer);
// evicting under either name drops it. A store without it is keyed by the client as given.
type Canonicalizer interface {
	Canonical(client fw.ClientID) fw.ClientID
}

// Cached wraps a store in the framework's cache, for a driver written outside this package (the
// platform driver of the flowcatalyst module): found values are kept by policy (keyed by the
// canonical client, see [Canonicalizer]), concurrent misses for one secret make one call, and a
// [*StoreError] serves the last value for StaleIfError past its TTL, with a warning. A policy with
// a zero TTL returns the store itself.
func Cached(s Store, p CachePolicy, b *fw.Boot) Store {
	return withCache(s, p, b.Clock(), b.Logger(ModuleID))
}

func withCache(s Store, p CachePolicy, clock fw.Clock, log *slog.Logger) Store {
	if p.TTL == 0 {
		return s
	}
	return &cached{
		inner: s, ttl: p.TTL, stale: p.StaleIfError, max: p.MaxEntries, clock: clock, log: log,
		entries: map[cacheKey]*list.Element{}, order: list.New(), inFlight: map[cacheKey]*flight{},
	}
}

// cached wraps a store in a cache of found secrets.
type cached struct {
	inner Store
	ttl   time.Duration
	stale time.Duration
	max   int
	clock fw.Clock
	log   *slog.Logger

	mu       sync.Mutex
	entries  map[cacheKey]*list.Element
	order    *list.List // front = most recently used
	inFlight map[cacheKey]*flight
	epoch    uint64 // bumped by every evict, so a lookup that began before it keeps its answer out
}

type cacheKey struct{ client, name string }

type cacheEntry struct {
	key        cacheKey
	result     Found
	expiresAt  time.Time
	staleUntil time.Time
}

// flight is one lookup in progress; the others for the same key wait for it.
type flight struct {
	done chan struct{}
	res  Result
	err  error
}

func (c *cached) canonical(client fw.ClientID) fw.ClientID {
	if cz, ok := c.inner.(Canonicalizer); ok {
		return cz.Canonical(client)
	}
	return client
}

// Canonical passes the inner store's canonical form on.
func (c *cached) Canonical(client fw.ClientID) fw.ClientID { return c.canonical(client) }

func (c *cached) Find(ctx context.Context, client fw.ClientID, name string) (Result, error) {
	key := cacheKey{c.canonical(client).String(), name}
	c.mu.Lock()
	var old *cacheEntry
	if el, ok := c.entries[key]; ok {
		e := el.Value.(*cacheEntry) //nolint:forcetypeassert // the list holds only *cacheEntry
		now := c.clock.Now()
		switch {
		case now.Before(e.expiresAt):
			c.order.MoveToFront(el)
			c.mu.Unlock()
			return e.result, nil
		case c.stale > 0 && now.Before(e.staleUntil):
			old = e // expired, kept as the fallback for a failing store
		default:
			c.remove(el)
		}
	}
	if f, ok := c.inFlight[key]; ok {
		c.mu.Unlock()
		select {
		case <-f.done:
			return f.res, f.err
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	f := &flight{done: make(chan struct{})}
	c.inFlight[key] = f
	started := c.epoch
	c.mu.Unlock()

	defer func() {
		c.mu.Lock()
		if c.inFlight[key] == f {
			delete(c.inFlight, key)
		}
		c.mu.Unlock()
		close(f.done)
	}()
	res, err := c.inner.Find(ctx, client, name) // outside the lock: it may do I/O
	if _, failing := errors.AsType[*StoreError](err); failing {
		res, err = c.fallback(old, err, client, name, started)
	}
	if err == nil {
		c.remember(key, client, name, res, started)
	}
	f.res, f.err = res, err
	return res, err
}

// fallback is the stale value while the store is failing, or the failure.
func (c *cached) fallback(old *cacheEntry, err error, client fw.ClientID, name string, started uint64) (Result, error) {
	if old == nil {
		return nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.clock.Now()
	if c.epoch != started || !now.Before(old.staleUntil) {
		return nil, err
	}
	c.log.Warn(fmt.Sprintf("secret store %v could not be read (%s); serving the last value of secret '%s' of client '%s' for up to %d s more",
		c.inner, err.Error(), name, client, int64(old.staleUntil.Sub(now)/time.Second)))
	return old.result, nil
}

// remember keeps a found answer under the canonical client and drops the entry for any other.
func (c *cached) remember(key cacheKey, client fw.ClientID, name string, res Result, started uint64) {
	found, ok := res.(Found)
	canonical := cacheKey{c.canonical(client).String(), name} // the store may have learned the alias
	c.mu.Lock()
	defer c.mu.Unlock()
	if !ok {
		if el, exists := c.entries[key]; exists {
			c.remove(el)
		}
		return
	}
	if c.epoch != started {
		return // evicted meanwhile: the answer may predate the change
	}
	if el, exists := c.entries[canonical]; exists {
		e := el.Value.(*cacheEntry) //nolint:forcetypeassert // the list holds only *cacheEntry
		if e.result == found {
			return // the stale value served on error: it keeps its own expiry
		}
		c.remove(el)
	}
	if el, exists := c.entries[key]; exists {
		c.remove(el)
	}
	now := c.clock.Now()
	c.entries[canonical] = c.order.PushFront(&cacheEntry{
		key: canonical, result: found, expiresAt: now.Add(c.ttl), staleUntil: now.Add(c.ttl + c.stale),
	})
	for c.order.Len() > c.max {
		c.remove(c.order.Back())
	}
}

func (c *cached) remove(el *list.Element) {
	e := el.Value.(*cacheEntry) //nolint:forcetypeassert // the list holds only *cacheEntry
	delete(c.entries, e.key)
	c.order.Remove(el)
}

func (c *cached) Evict(client fw.ClientID, name string) {
	canonical := c.canonical(client)
	c.mu.Lock()
	c.epoch++
	for key, el := range c.entries {
		if key.name == name && sameClient(key.client, client, canonical) {
			c.remove(el)
		}
	}
	for key := range c.inFlight {
		if key.name == name && sameClient(key.client, client, canonical) {
			delete(c.inFlight, key)
		}
	}
	c.mu.Unlock()
	c.inner.Evict(client, name)
}

// sameClient reports whether a key's client is this client under either of its names. (An entry
// kept under an identifier before its stored id was known can no longer be reached once it is: a
// lookup keys by the canonical form, so it reads the store again.)
func sameClient(keyClient string, client, canonical fw.ClientID) bool {
	return keyClient == client.String() || keyClient == canonical.String()
}

func (c *cached) EvictAll() {
	c.mu.Lock()
	c.epoch++
	c.entries = map[cacheKey]*list.Element{}
	c.order.Init()
	c.inFlight = map[cacheKey]*flight{}
	c.mu.Unlock()
	c.inner.EvictAll()
}

func (c *cached) String() string { return "Cached" + fmt.Sprint(c.inner) }
