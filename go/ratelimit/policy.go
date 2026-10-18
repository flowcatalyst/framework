package ratelimit

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"time"
)

// ErrInvalidPolicy is wrapped by every error [NewPolicy] returns.
var ErrInvalidPolicy = errors.New("invalid rate limit policy")

const (
	// MaxLimit is the largest limit and burst of a policy.
	MaxLimit = 1_000_000

	// maxWindow is the largest window burst*period/limit in nanoseconds (about 73 years): it
	// leaves room for now+window plus a cost without overflowing an int64.
	maxWindow = math.MaxInt64 / 4
)

// Policy is limit requests per period, with up to burst at once (rate-limiting contract section
// 1). The zero Policy is invalid: build one with [NewPolicy] or [PolicyOf].
type Policy struct {
	limit     int
	period    time.Duration
	burst     int
	unlimited bool
}

// Unlimited is "no limit", for a [PolicyFunc] to return for a caller that has none (rate-limiting
// contract section 3a): the middleware lets the request through without computing a decision,
// reading or writing state, or sending RateLimit-* headers. Only this value means that
// ([Policy.IsUnlimited]): a policy built with [NewPolicy] from the same numbers is an ordinary
// policy, and ok=false or an error from the function is still the limiter's default policy, never
// unlimited. Limiters called directly do not know it.
var Unlimited = Policy{limit: MaxLimit, period: time.Second, burst: MaxLimit, unlimited: true}

// IsUnlimited reports whether p is [Unlimited].
func (p Policy) IsUnlimited() bool { return p.unlimited }

// NewPolicy checks the ranges: limit and burst 1 to 1 000 000, period 1ms to 1 day, period/limit
// at least 1 microsecond, and the window burst*period/limit at most about 73 years.
func NewPolicy(limit int, period time.Duration, burst int) (Policy, error) {
	switch {
	case limit < 1 || limit > MaxLimit:
		return Policy{}, fmt.Errorf("%w: limit must be 1 to %d", ErrInvalidPolicy, MaxLimit)
	case burst < 1 || burst > MaxLimit:
		return Policy{}, fmt.Errorf("%w: burst must be 1 to %d", ErrInvalidPolicy, MaxLimit)
	case period < time.Millisecond || period > 24*time.Hour:
		return Policy{}, fmt.Errorf("%w: period must be 1ms to 1 day", ErrInvalidPolicy)
	case int64(period)/int64(limit) < 1000:
		return Policy{}, fmt.Errorf("%w: period / limit must be at least 1 microsecond", ErrInvalidPolicy)
	case int64(burst) > maxWindow/(int64(period)/int64(limit)):
		return Policy{}, fmt.Errorf("%w: burst * period / limit must be at most 73 years", ErrInvalidPolicy)
	}
	return Policy{limit: limit, period: period, burst: burst}, nil
}

// PolicyOf is a policy whose burst equals its limit.
func PolicyOf(limit int, period time.Duration) (Policy, error) {
	return NewPolicy(limit, period, limit)
}

// MustPolicy is [NewPolicy] for literals in source; it panics on a bad value.
func MustPolicy(limit int, period time.Duration, burst int) Policy {
	p, err := NewPolicy(limit, period, burst)
	if err != nil {
		panic(err)
	}
	return p
}

// Limit returns the requests per period.
func (p Policy) Limit() int { return p.limit }

// Period returns the period.
func (p Policy) Period() time.Duration { return p.period }

// Burst returns the requests allowed at once.
func (p Policy) Burst() int { return p.burst }

// IsZero reports whether p is the zero Policy, which is invalid.
func (p Policy) IsZero() bool { return p.limit == 0 }

// interval is the time one request's worth of budget takes to come back: period / limit.
func (p Policy) interval() int64 { return int64(p.period) / int64(p.limit) }

var limiterName = regexp.MustCompile(`^[a-z][a-z0-9_-]{0,62}$`)

// checkName checks a limiter's name: [a-z][a-z0-9_-]*, at most 63 characters.
func checkName(name string) error {
	if !limiterName.MatchString(name) {
		return fmt.Errorf("a rate limiter name must match [a-z][a-z0-9_-]* (at most 63 characters): %q", name)
	}
	return nil
}

// CheckName checks a limiter's name for drivers outside this package.
func CheckName(name string) error { return checkName(name) }

// checkCost checks cost against a policy's burst.
func checkCost(cost int, p Policy) error {
	if cost < 1 || cost > p.burst {
		return fmt.Errorf("cost must be 1 to the burst (%d)", p.burst)
	}
	return nil
}

// CheckCost checks that cost is 1 to the policy's burst, for drivers outside this package.
func CheckCost(cost int, p Policy) error { return checkCost(cost, p) }
