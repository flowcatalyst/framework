package httpclient

import (
	"fmt"
	"math/rand/v2"
	"slices"
	"strconv"
	"strings"
	"time"
)

// RetryPolicy says when a request is sent again (http-client contract section 3). Only requests
// that are safe to repeat are retried: idempotent methods, requests with an idempotency key, and
// requests marked Retryable(true). POST and PATCH are never retried otherwise.
type RetryPolicy struct {
	// MaxAttempts is the attempts in total, 1 to 10 (1 = no retry).
	MaxAttempts int
	// InitialBackoff is the first wait; the wait doubles with full jitter.
	InitialBackoff time.Duration
	// MaxBackoff is the longest wait.
	MaxBackoff time.Duration
	// MaxRetryAfter is the longest Retry-After honoured; a longer one returns the response at once.
	MaxRetryAfter time.Duration
	// RetryOnStatuses are the statuses that are retried.
	RetryOnStatuses []int
}

// DefaultRetry is three attempts, 200 ms doubling to 5 s with jitter, Retry-After up to 30 s, on
// 429, 502, 503 and 504.
func DefaultRetry() RetryPolicy {
	return RetryPolicy{
		MaxAttempts: 3, InitialBackoff: 200 * time.Millisecond, MaxBackoff: 5 * time.Second,
		MaxRetryAfter: 30 * time.Second, RetryOnStatuses: []int{429, 502, 503, 504},
	}
}

// NoRetry is one attempt only.
func NoRetry() RetryPolicy { return RetryPolicy{MaxAttempts: 1} }

// WithMaxAttempts returns a copy with another number of attempts.
func (p RetryPolicy) WithMaxAttempts(n int) RetryPolicy { p.MaxAttempts = n; return p }

// WithBackoff returns a copy with other backoffs.
func (p RetryPolicy) WithBackoff(initial, maxWait time.Duration) RetryPolicy {
	p.InitialBackoff, p.MaxBackoff = initial, maxWait
	return p
}

// WithMaxRetryAfter returns a copy with another Retry-After cap.
func (p RetryPolicy) WithMaxRetryAfter(d time.Duration) RetryPolicy { p.MaxRetryAfter = d; return p }

// Validate checks the ranges.
func (p RetryPolicy) Validate() error {
	switch {
	case p.MaxAttempts < 1 || p.MaxAttempts > 10:
		return fmt.Errorf("maxAttempts must be 1 to 10, was %d", p.MaxAttempts)
	case p.InitialBackoff < 0 || p.MaxBackoff < p.InitialBackoff:
		return fmt.Errorf("backoffs must be zero or more, and max at least initial")
	case p.MaxRetryAfter < 0:
		return fmt.Errorf("maxRetryAfter must not be negative")
	}
	return nil
}

func (p RetryPolicy) retries(status int) bool { return slices.Contains(p.RetryOnStatuses, status) }

// backoff is the wait before attempt+1: exponential, capped, with the jitter given.
func (p RetryPolicy) backoff(attempt int, jitter func(bound int64) int64) time.Duration {
	capNanos := int64(p.MaxBackoff)
	exp := int64(p.InitialBackoff) << min(attempt-1, 20)
	bound := capNanos
	if exp >= 0 {
		bound = min(capNanos, exp)
	}
	if bound <= 0 {
		return 0
	}
	return time.Duration(jitter(bound))
}

// fullJitter is a uniform wait from 0 to bound nanoseconds.
func fullJitter(bound int64) int64 {
	return rand.Int64N(bound + 1) //nolint:gosec // jitter, not a security decision
}

// retryAfter parses a Retry-After header: whole seconds (1 to 9 digits) or an HTTP date.
func retryAfter(value string, now time.Time) (time.Duration, bool) {
	v := strings.TrimSpace(value)
	if v == "" {
		return 0, false
	}
	if len(v) <= 9 && strings.Trim(v, "0123456789") == "" {
		n, _ := strconv.ParseInt(v, 10, 64)
		return time.Duration(n) * time.Second, true
	}
	for _, layout := range []string{"Mon, 2 Jan 2006 15:04:05 MST", "Mon, 2 Jan 2006 15:04:05 -0700"} {
		if at, err := time.Parse(layout, v); err == nil {
			return max(0, at.Sub(now)), true
		}
	}
	return 0, false
}
