package authredis

import (
	"context"
	"errors"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// ReplayPrefix is the key prefix of the field-worker proof replay cache: fw:dpop:{jkt}:{jti}.
const ReplayPrefix = "fw:dpop:"

// ReplayCache is the Redis [auth.FieldReplayCache] (sessions-and-login contract section 15.4): one
// SET key NX PX per proof, so every replica sees the proofs every other replica has seen.
type ReplayCache struct {
	client *redis.Client
	prefix string
}

// NewReplayCache returns the cache on a go-redis client (from redisfw.Client.Redis).
func NewReplayCache(client *redis.Client) *ReplayCache {
	return &ReplayCache{client: client, prefix: ReplayPrefix}
}

// ReplayCacheOf is the cache on the default Redis instance.
func ReplayCacheOf(clients *redisfw.Clients) (*ReplayCache, error) {
	c, ok := clients.Default()
	if !ok {
		return nil, errors.New("the Redis proof replay cache needs a default Redis instance, and none is configured")
	}
	return NewReplayCache(c.Redis()), nil
}

// WithPrefix returns the cache with another key prefix (tests share one server).
func (c *ReplayCache) WithPrefix(prefix string) *ReplayCache {
	cc := *c
	cc.prefix = prefix
	return &cc
}

// Remember implements [auth.FieldReplayCache].
func (c *ReplayCache) Remember(ctx context.Context, jkt, jti string, ttl time.Duration) (bool, error) {
	first, err := c.client.SetNX(ctx, c.prefix+jkt+":"+jti, 1, ttl).Result()
	if err != nil {
		return false, err
	}
	return first, nil
}

var _ auth.FieldReplayCache = (*ReplayCache)(nil)
