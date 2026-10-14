// Package locks gives one holder at a time the right to do some work for a bounded time (the
// lease), with a fencing token that grows with each acquisition (locks contract). The drivers
// are packages of their own, compiled only when imported: the memory driver here, dblock
// (PostgreSQL, the shared fw_lock table and statements) and redislock (the shared Lua scripts).
//
//	lk := locks.Declare(app, locks.MemoryDriver(), dblock.Declare(app), redislock.Driver()) // in main
//	all, err := locks.Build(ctx, b, lk, dblock.With(dbs), redislock.With(clients))      // in build
//	res, err := locks.With(ctx, lock, key, 10*time.Second, 5*time.Second, work)
package locks

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// Limits on the arguments of every operation (locks contract section 2).
const (
	MinLease = 100 * time.Millisecond
	MaxLease = 24 * time.Hour
	MaxWait  = 24 * time.Hour
)

// ErrStoreUnavailable wraps every infrastructure failure of a store (unreachable, query error,
// timeout). Not getting the lock is never an error.
var ErrStoreUnavailable = errors.New("lock store unavailable")

// ErrInvalidArgument is returned for a lease or wait outside the limits, a zero key, or a lease
// passed to a Lock that did not issue it.
var ErrInvalidArgument = errors.New("invalid lock argument")

// Store is the driver contract (Java's LockDriver): three operations on stored keys, each acting
// atomically on the store's clock. Implementations wrap failures with ErrStoreUnavailable.
type Store interface {
	// TryAcquire takes key for owner for lease milliseconds unless an unexpired lease exists,
	// returning a fencing token greater than every token issued before for the key.
	TryAcquire(ctx context.Context, key, owner string, leaseMillis int64) (fence int64, ok bool, err error)
	// Refresh extends the lease to leaseMillis from now when owner and fence hold it unexpired.
	Refresh(ctx context.Context, key, owner string, fence, leaseMillis int64) (bool, error)
	// Release ends the lease when owner and fence hold it unexpired.
	Release(ctx context.Context, key, owner string, fence int64) (bool, error)
}

// Lease is a held lock: its key, owner token, fencing token and local deadline (the holder's
// clock reading taken before the request was sent, plus the lease length, so never later than
// the store's own expiry).
type Lease struct {
	key      Key
	owner    string
	fence    int64
	deadline time.Time
	issuer   *Lock
}

// Key returns the lease's key.
func (l Lease) Key() Key { return l.key }

// Fence returns the fencing token: pass it to whatever the protected work writes to.
func (l Lease) Fence() int64 { return l.fence }

// Deadline returns the local deadline.
func (l Lease) Deadline() time.Time { return l.deadline }

// Acquisition is the result of acquiring: [Acquired] or [Busy].
//
//sumtype:decl
type Acquisition interface{ acquisition() }

// Acquired holds the new lease.
type Acquired struct{ Lease Lease }

// Busy says someone else holds the key (or the wait ran out).
type Busy struct{}

func (Acquired) acquisition() {}
func (Busy) acquisition()     {}

// Lock is the user-facing lock API on one store (one named instance). Safe for concurrent use.
type Lock struct {
	store Store
	clock fw.Clock
	log   *slog.Logger
}

// Option configures [New].
type Option func(*Lock)

// WithClock sets the clock that local deadlines and waits are measured with (default the system
// clock). The memory driver must use the same clock.
func WithClock(c fw.Clock) Option { return func(l *Lock) { l.clock = c } }

// WithLogger sets the logger for refresh failures and release failures.
func WithLogger(log *slog.Logger) Option { return func(l *Lock) { l.log = log } }

// New returns the lock API over a store.
func New(store Store, opts ...Option) *Lock {
	l := &Lock{store: store, clock: fw.SystemClock(), log: slog.New(slog.DiscardHandler)}
	for _, o := range opts {
		o(l)
	}
	return l
}

// Store returns the driver.
func (l *Lock) Store() Store { return l.store }

// TryAcquire takes the key once, without waiting.
func (l *Lock) TryAcquire(ctx context.Context, k Key, lease time.Duration) (Acquisition, error) {
	ms, err := leaseMillis(lease)
	if err != nil {
		return nil, err
	}
	if k.IsZero() {
		return nil, fmt.Errorf("%w: the zero Key", ErrInvalidArgument)
	}
	owner := newOwnerToken()
	sentAt := l.clock.Now()
	fence, ok, err := l.store.TryAcquire(ctx, k.Stored(), owner, ms)
	if err != nil {
		return nil, err
	}
	if !ok {
		return Busy{}, nil
	}
	return Acquired{Lease: Lease{key: k, owner: owner, fence: fence, deadline: sentAt.Add(time.Duration(ms) * time.Millisecond), issuer: l}}, nil
}

// Acquire takes the key, retrying with backoff (10ms doubling to 250ms, with jitter) until
// acquired or maxWait has passed, then returns Busy. A cancelled ctx stops it with ctx's error.
func (l *Lock) Acquire(ctx context.Context, k Key, lease, maxWait time.Duration) (Acquisition, error) {
	if _, err := leaseMillis(lease); err != nil {
		return nil, err
	}
	if maxWait < 0 || maxWait > MaxWait {
		return nil, fmt.Errorf("%w: maxWait must be from zero to 24h: %v", ErrInvalidArgument, maxWait)
	}
	start := l.clock.Now()
	backoff := 10 * time.Millisecond
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		res, err := l.TryAcquire(ctx, k, lease)
		if err != nil {
			if ctxErr := ctx.Err(); ctxErr != nil {
				return nil, ctxErr // a cancellation surfacing in the driver is the cancellation
			}
			return nil, err
		}
		if _, ok := res.(Acquired); ok {
			return res, nil
		}
		left := maxWait - l.clock.Now().Sub(start)
		if left <= 0 {
			return res, nil
		}
		jittered := backoff/2 + rand.N(backoff/2+1) //nolint:gosec // jitter needs no cryptographic randomness
		wait := max(time.Millisecond, min(jittered, left))
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
		backoff = min(250*time.Millisecond, backoff*2)
	}
}

// Refresh extends a held lease to newLease from now. It returns the refreshed lease (same tokens,
// new deadline) and true, or false when the lease was lost (expired, released or taken).
func (l *Lock) Refresh(ctx context.Context, lease Lease, newLease time.Duration) (Lease, bool, error) {
	if err := l.own(lease); err != nil {
		return Lease{}, false, err
	}
	ms, err := leaseMillis(newLease)
	if err != nil {
		return Lease{}, false, err
	}
	sentAt := l.clock.Now()
	ok, err := l.store.Refresh(ctx, lease.key.Stored(), lease.owner, lease.fence, ms)
	if err != nil || !ok {
		return Lease{}, false, err
	}
	lease.deadline = sentAt.Add(time.Duration(ms) * time.Millisecond)
	return lease, true, nil
}

// Release ends a held lease. It returns true when it was released, false when it was no longer
// held (Java's NotHeld).
func (l *Lock) Release(ctx context.Context, lease Lease) (bool, error) {
	if err := l.own(lease); err != nil {
		return false, err
	}
	return l.store.Release(ctx, lease.key.Stored(), lease.owner, lease.fence)
}

func (l *Lock) own(lease Lease) error {
	if lease.issuer != l {
		return fmt.Errorf("%w: this lease was not issued by this lock", ErrInvalidArgument)
	}
	return nil
}

func leaseMillis(lease time.Duration) (int64, error) {
	if lease < MinLease || lease > MaxLease {
		return 0, fmt.Errorf("%w: lease must be from 100ms to 24h: %v", ErrInvalidArgument, lease)
	}
	return lease.Milliseconds(), nil
}

func newOwnerToken() string {
	var b [16]byte
	_, _ = crand.Read(b[:]) // crypto/rand.Read never fails (Go 1.24+)
	return hex.EncodeToString(b[:])
}
