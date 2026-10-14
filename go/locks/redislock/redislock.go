// Package redislock is the Redis lock driver (locks contract section 5): one standalone Redis or
// Valkey server, the lock at <prefix><stored key> holding <owner>:<fence> with a millisecond TTL,
// the counter at <prefix>__fence, and the three shared Lua scripts of conformance/lua/locks/ run
// with EVALSHA (loaded at start-up; go-redis falls back to EVAL, which reloads, on NOSCRIPT). Java
// and Go replicas on one server exclude each other. Not Redlock: a server that loses data can let a
// lock be held twice.
package redislock

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// DefaultPrefix is the key prefix unless [Prefix] sets one.
const DefaultPrefix = "fw:lock:"

// MaxPrefixLength is the longest prefix accepted.
const MaxPrefixLength = 100

//go:embed lua/acquire.lua
var acquireLua string

//go:embed lua/refresh.lua
var refreshLua string

//go:embed lua/release.lua
var releaseLua string

// Scripts returns the shared scripts as embedded, for the conformance tests.
func Scripts() (acquire, refresh, release string) { return acquireLua, refreshLua, releaseLua }

// Option configures [Store].
type Option func(*options)

type options struct {
	connection string
	prefix     string
}

// Connection uses the named Redis instance (default: the default instance).
func Connection(name string) Option { return func(o *options) { o.connection = name } }

// Prefix sets the key prefix (default [DefaultPrefix]; not blank, at most [MaxPrefixLength]
// characters).
func Prefix(p string) Option { return func(o *options) { o.prefix = p } }

// Store returns the factory of the Redis store on clients, for locks.Store. Options are checked
// when the factory runs.
func Store(clients *redisfw.Clients, opts ...Option) locks.StoreFactory {
	o := options{prefix: DefaultPrefix}
	for _, f := range opts {
		f(&o)
	}
	return func(ctx context.Context, _ *fw.Boot) (locks.Store, error) {
		if strings.TrimSpace(o.prefix) == "" {
			return nil, errors.New("the Redis lock prefix must not be blank")
		}
		if n := utf8.RuneCountInString(o.prefix); n > MaxPrefixLength {
			return nil, fmt.Errorf("the Redis lock prefix must be at most %d characters, was %d", MaxPrefixLength, n)
		}
		c, ok := clients.Default()
		if o.connection != "" {
			named, err := clients.Get(o.connection)
			if err != nil {
				return nil, err
			}
			c, ok = named, true
		}
		if !ok {
			return nil, errors.New("no redis instance is configured")
		}
		return NewStore(ctx, c.Redis(), o.prefix)
	}
}

// Driver is the Redis lock driver.
type Driver struct {
	client  *redis.Client
	prefix  string
	counter string
	acquire *redis.Script
	refresh *redis.Script
	release *redis.Script
}

// NewStore returns the driver on client with a key prefix, loading the scripts.
func NewStore(ctx context.Context, client *redis.Client, prefix string) (*Driver, error) {
	s := &Driver{
		client: client, prefix: prefix, counter: prefix + "__fence",
		acquire: redis.NewScript(acquireLua), refresh: redis.NewScript(refreshLua), release: redis.NewScript(releaseLua),
	}
	for _, sc := range []*redis.Script{s.acquire, s.refresh, s.release} {
		if err := sc.Load(ctx, client).Err(); err != nil {
			return nil, fmt.Errorf("%w: cannot load the Redis lock scripts: %w", locks.ErrStoreUnavailable, err)
		}
	}
	return s, nil
}

// TryAcquire implements locks.Store.
func (s *Driver) TryAcquire(ctx context.Context, key, owner string, leaseMillis int64) (int64, bool, error) {
	v, err := s.acquire.Run(ctx, s.client, []string{s.prefix + key, s.counter}, owner, strconv.FormatInt(leaseMillis, 10)).Result()
	if errors.Is(err, redis.Nil) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, unavailable(err)
	}
	fence, ok := v.(int64)
	if !ok {
		return 0, false, fmt.Errorf("%w: unexpected acquire reply %v", locks.ErrStoreUnavailable, v)
	}
	return fence, true, nil
}

// Refresh implements locks.Store.
func (s *Driver) Refresh(ctx context.Context, key, owner string, fence, leaseMillis int64) (bool, error) {
	v, err := s.refresh.Run(ctx, s.client, []string{s.prefix + key},
		owner+":"+strconv.FormatInt(fence, 10), strconv.FormatInt(leaseMillis, 10)).Int64()
	if err != nil {
		return false, unavailable(err)
	}
	return v == 1, nil
}

// Release implements locks.Store.
func (s *Driver) Release(ctx context.Context, key, owner string, fence int64) (bool, error) {
	v, err := s.release.Run(ctx, s.client, []string{s.prefix + key}, owner+":"+strconv.FormatInt(fence, 10)).Int64()
	if err != nil {
		return false, unavailable(err)
	}
	return v == 1, nil
}

// ExpireNow ends a key's current lease at once (a test hook): the key is deleted.
func (s *Driver) ExpireNow(ctx context.Context, key string) error {
	return s.client.Del(ctx, s.prefix+key).Err()
}

func unavailable(err error) error {
	return fmt.Errorf("%w: Redis lock operation failed: %w", locks.ErrStoreUnavailable, err)
}
