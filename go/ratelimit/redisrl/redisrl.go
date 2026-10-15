// Package redisrl is the Redis rate limiter (rate-limiting contract section 2): one GCRA decision
// per request in a Lua script, on the Redis server's clock in microseconds, so replicas' clocks
// are never compared and every replica (Java and Go alike, with the same name and prefix) shares
// one budget. An allowed request sets the key with a TTL equal to the time until the bucket is
// full again, so idle keys expire on their own and Redis memory stays bounded by active callers.
// Standalone Redis or Valkey; if the server loses data the affected buckets start full again.
package redisrl

import (
	"context"
	_ "embed"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/ratelimit"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// DefaultPrefix is the key prefix unless [Prefix] sets one. A key is <prefix><name>:<key>.
const DefaultPrefix = "fw:rl:"

//go:embed lua/gcra.lua
var script string

// Script returns the Lua script as embedded, for the conformance tests: KEYS[1] the bucket; ARGV
// the interval (microseconds), burst and cost of this one decision; it returns
// {allowed, remaining or retry, reset}.
func Script() string { return script }

// Option configures [New].
type Option func(*options)

type options struct{ prefix string }

// Prefix sets the key prefix (default [DefaultPrefix]).
func Prefix(p string) Option { return func(o *options) { o.prefix = p } }

// Limiter is the Redis limiter. It implements ratelimit.PolicyLimiter.
type Limiter struct {
	name   string
	policy ratelimit.Policy
	client redis.Scripter
	prefix string
	script *redis.Script
}

// New builds a limiter named name ([a-z][a-z0-9_-]*, at most 63 characters; the same in every
// replica) with a default policy on a Redis client (redisfw.Client.Redis), and loads its script, so
// it needs the server: an unreachable server is an error wrapping ratelimit.ErrStore. Build it in
// the application's build function, after the Redis connections were made.
func New(ctx context.Context, name string, client *redis.Client, policy ratelimit.Policy, opts ...Option) (*Limiter, error) {
	return newLimiter(ctx, name, client, policy, opts)
}

// FromClient is New on a redisfw client.
func FromClient(ctx context.Context, name string, c *redisfw.Client, policy ratelimit.Policy, opts ...Option) (*Limiter, error) {
	return newLimiter(ctx, name, c.Redis(), policy, opts)
}

func newLimiter(ctx context.Context, name string, client redis.Scripter, policy ratelimit.Policy, opts []Option) (*Limiter, error) {
	o := options{prefix: DefaultPrefix}
	for _, f := range opts {
		f(&o)
	}
	if err := ratelimit.CheckName(name); err != nil {
		return nil, err
	}
	if policy.IsZero() {
		return nil, fmt.Errorf("rate limiter %s: the zero Policy is invalid", name)
	}
	l := &Limiter{name: name, policy: policy, client: client, prefix: o.prefix, script: redis.NewScript(script)}
	if err := l.script.Load(ctx, client).Err(); err != nil {
		return nil, fmt.Errorf("%w: cannot load the rate limit script: %w", ratelimit.ErrStore, err)
	}
	return l, nil
}

// Name implements ratelimit.Limiter.
func (l *Limiter) Name() string { return l.name }

// Policy implements ratelimit.Limiter.
func (l *Limiter) Policy() ratelimit.Policy { return l.policy }

// Acquire implements ratelimit.Limiter.
func (l *Limiter) Acquire(ctx context.Context, key string, cost int) (ratelimit.Decision, error) {
	return l.AcquireWith(ctx, key, cost, l.policy)
}

// AcquireWith implements ratelimit.PolicyLimiter.
func (l *Limiter) AcquireWith(ctx context.Context, key string, cost int, p ratelimit.Policy) (ratelimit.Decision, error) {
	if p.IsZero() {
		return nil, fmt.Errorf("rate limiter %s: the zero Policy is invalid", l.name)
	}
	if err := ratelimit.CheckCost(cost, p); err != nil {
		return nil, err
	}
	intervalMicros := int64(p.Period()) / int64(p.Limit()) / 1000
	reply, err := l.script.Run(ctx, l.client, []string{l.prefix + l.name + ":" + key},
		strconv.FormatInt(intervalMicros, 10), strconv.Itoa(p.Burst()), strconv.Itoa(cost)).Slice()
	if err != nil {
		return nil, fmt.Errorf("%w: Redis rate limit call failed for limiter %s: %w", ratelimit.ErrStore, l.name, err)
	}
	if len(reply) != 3 {
		return nil, fmt.Errorf("%w: unexpected Redis reply for limiter %s", ratelimit.ErrStore, l.name)
	}
	var n [3]int64
	for i, v := range reply {
		x, ok := v.(int64)
		if !ok {
			return nil, fmt.Errorf("%w: unexpected Redis reply for limiter %s", ratelimit.ErrStore, l.name)
		}
		n[i] = x
	}
	if n[0] == 1 {
		return ratelimit.Allowed{Remaining: n[1], ResetAfter: micros(n[2])}, nil
	}
	return ratelimit.Limited{RetryAfter: micros(n[1]), ResetAfter: micros(n[2])}, nil
}

func micros(n int64) time.Duration { return time.Duration(n) * time.Microsecond }
