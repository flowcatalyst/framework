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

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// DriverName is the driver's name in configuration.
const DriverName = "redis"

// DefaultPrefix is the key prefix unless an instance sets prefix.
const DefaultPrefix = "fw:lock:"

//go:embed lua/acquire.lua
var acquireLua string

//go:embed lua/refresh.lua
var refreshLua string

//go:embed lua/release.lua
var releaseLua string

// Scripts returns the shared scripts as embedded, for the conformance tests.
func Scripts() (acquire, refresh, release string) { return acquireLua, refreshLua, releaseLua }

var (
	keyConnection = config.String("connection", "The Redis instance (default: the default instance).")
	keyPrefix     = config.String("prefix", "Key prefix.").WithDefault(DefaultPrefix)
)

// Driver returns the driver's spec for locks.Declare.
func Driver() locks.DriverSpec {
	return locks.DriverSpec{Name: DriverName, Keys: []config.Decl{keyConnection, keyPrefix}}
}

// With returns the build option that makes the redis driver's instances on clients.
func With(clients *redisfw.Clients) locks.BuildOption {
	return locks.WithFactory(DriverName, func(ctx context.Context, _ *fw.Boot, in config.Instance) (locks.Store, error) {
		c, ok := clients.Default()
		if name, set := keyConnection.Lookup(in); set {
			named, err := clients.Get(name)
			if err != nil {
				return nil, err
			}
			c, ok = named, true
		}
		if !ok {
			return nil, errors.New("no redis instance is configured")
		}
		return NewStore(ctx, c.Redis(), keyPrefix.Get(in))
	})
}

// Store is the Redis driver.
type Store struct {
	client  *redis.Client
	prefix  string
	counter string
	acquire *redis.Script
	refresh *redis.Script
	release *redis.Script
}

// NewStore returns the driver on client with a key prefix, loading the scripts.
func NewStore(ctx context.Context, client *redis.Client, prefix string) (*Store, error) {
	s := &Store{
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
func (s *Store) TryAcquire(ctx context.Context, key, owner string, leaseMillis int64) (int64, bool, error) {
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
func (s *Store) Refresh(ctx context.Context, key, owner string, fence, leaseMillis int64) (bool, error) {
	v, err := s.refresh.Run(ctx, s.client, []string{s.prefix + key},
		owner+":"+strconv.FormatInt(fence, 10), strconv.FormatInt(leaseMillis, 10)).Int64()
	if err != nil {
		return false, unavailable(err)
	}
	return v == 1, nil
}

// Release implements locks.Store.
func (s *Store) Release(ctx context.Context, key, owner string, fence int64) (bool, error) {
	v, err := s.release.Run(ctx, s.client, []string{s.prefix + key}, owner+":"+strconv.FormatInt(fence, 10)).Int64()
	if err != nil {
		return false, unavailable(err)
	}
	return v == 1, nil
}

// ExpireNow ends a key's current lease at once (a test hook): the key is deleted.
func (s *Store) ExpireNow(ctx context.Context, key string) error {
	return s.client.Del(ctx, s.prefix+key).Err()
}

func unavailable(err error) error {
	return fmt.Errorf("%w: Redis lock operation failed: %w", locks.ErrStoreUnavailable, err)
}
