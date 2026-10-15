package ratelimit

import (
	"context"
	"errors"
	"time"
)

// Decision is what a limiter decided for one request: [Allowed] or [Limited].
//
//sumtype:decl
type Decision interface{ decision() }

// Allowed means the request may go ahead.
type Allowed struct {
	// Remaining is the number of further requests allowed right now.
	Remaining int64
	// ResetAfter is the time until the bucket is full again.
	ResetAfter time.Duration
}

// Limited means the request is over the limit; nothing was used up.
type Limited struct {
	// RetryAfter is the time until a request of this cost would be allowed.
	RetryAfter time.Duration
	// ResetAfter is the time until the bucket is full again.
	ResetAfter time.Duration
}

func (Allowed) decision() {}
func (Limited) decision() {}

// ErrStore is wrapped by the error a limiter returns when its store cannot answer (Redis is
// down, the script cannot be loaded): the middleware lets the request through or refuses it with
// 503 as [OnStoreFailure] says.
var ErrStore = errors.New("rate limit store failure")

// Limiter decides whether a request with a key may go ahead, with a name that namespaces its keys
// and labels its metrics, and a default policy. Build one with [NewMemory] or redisrl.New.
type Limiter interface {
	// Name returns the limiter's name: [a-z][a-z0-9_-]*, at most 63 characters.
	Name() string
	// Policy returns the default policy, the one the limiter was built with.
	Policy() Policy
	// Acquire decides a request of the given cost (1 to the default policy's burst) under the
	// default policy. A store that cannot answer returns an error wrapping [ErrStore].
	Acquire(ctx context.Context, key string, cost int) (Decision, error)
}

// PolicyLimiter is a [Limiter] that can decide a request under a policy of its own (contract
// section 3a): the stored state is the same either way. Both shipped limiters implement it.
type PolicyLimiter interface {
	Limiter
	// AcquireWith decides a request of the given cost (1 to p's burst) under p instead of the
	// default policy.
	AcquireWith(ctx context.Context, key string, cost int, p Policy) (Decision, error)
}
