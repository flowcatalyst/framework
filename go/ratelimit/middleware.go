package ratelimit

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"sync"
	"time"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/metrics"
)

const (
	sharedKey = "-"
	keyMax    = 200
)

// OnStoreFailure says what the middleware does when a limiter's store cannot answer, or a policy
// cannot be resolved.
type OnStoreFailure int

const (
	// Allow lets the request through (the default); a request whose policy could not be resolved
	// is decided with the limiter's default policy, never unlimited.
	Allow OnStoreFailure = iota
	// Deny answers 503 UNAVAILABLE.
	Deny
)

// PolicyFunc gives the policy a request is decided with (contract section 3a). It runs on every
// request, after the key function: it must be an in-memory lookup, never a network call. ok=false
// means "no policy of its own for this request": the limiter's default policy applies (an answer,
// not a failure, so it is not counted). An error, or a zero or too-small policy, is a policy
// failure: counted, logged, and then [OnStoreFailure] decides.
type PolicyFunc func(r *http.Request) (p Policy, ok bool, err error)

// Option configures [Middleware].
type Option func(*mwConfig)

type mwConfig struct {
	cost      int
	headers   bool
	onFailure OnStoreFailure
	policy    PolicyFunc
	registry  *metrics.Registry
	log       *slog.Logger
	clock     fw.Clock
}

// WithCost sets what one request costs, 1 to the limiter's burst (default 1).
func WithCost(n int) Option { return func(c *mwConfig) { c.cost = n } }

// WithHeaders says whether answers carry RateLimit-Limit, RateLimit-Remaining and RateLimit-Reset
// (default true).
func WithHeaders(on bool) Option { return func(c *mwConfig) { c.headers = on } }

// WithOnStoreFailure sets what happens when the store cannot answer (default [Allow]).
func WithOnStoreFailure(f OnStoreFailure) Option { return func(c *mwConfig) { c.onFailure = f } }

// WithPolicy decides each request with the policy f returns for it; the limiter must implement
// [PolicyLimiter].
func WithPolicy(f PolicyFunc) Option { return func(c *mwConfig) { c.policy = f } }

// WithMetrics records fw_http_rate_limited_total, fw_http_rate_limit_store_failures_total and,
// with a policy function, fw_http_rate_limit_policy_failures_total in the registry. Without it
// nothing is recorded.
func WithMetrics(r *metrics.Registry) Option { return func(c *mwConfig) { c.registry = r } }

// WithLogger sets the logger of the store and policy failure warnings (default: discard); each is
// logged at most once a minute per middleware.
func WithLogger(l *slog.Logger) Option { return func(c *mwConfig) { c.log = l } }

// WithClock sets the clock the once-a-minute warning is paced by (default [fw.SystemClock]).
func WithClock(clock fw.Clock) Option { return func(c *mwConfig) { c.clock = clock } }

// throttle lets one event through per minute.
type throttle struct {
	mu   sync.Mutex
	last time.Time
	seen bool
}

func (t *throttle) due(now time.Time) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.seen && now.Sub(t.last) < time.Duration(warnEvery) {
		return false
	}
	t.seen, t.last = true, now
	return true
}

// Middleware builds the rate-limiting middleware (contract section 3). A limited request is
// answered 429 RATE_LIMITED with Retry-After in whole seconds (rounded up, at least 1) and the
// handler is not called. As global middleware (httpfw.Routes.Use) it runs before routing and
// before context resolution, so it sees no route and no context; as route middleware
// (httpfw.With) it runs after context resolution, so [Client] and [Principal] work there.
func Middleware(l Limiter, keys KeyFunc, opts ...Option) (httpfw.Middleware, error) {
	cfg := mwConfig{cost: 1, headers: true, onFailure: Allow}
	for _, o := range opts {
		o(&cfg)
	}
	if cfg.log == nil {
		cfg.log = slog.New(slog.DiscardHandler)
	}
	if cfg.clock == nil {
		cfg.clock = fw.SystemClock()
	}
	if l == nil || keys == nil {
		return nil, errors.New("rate limiting needs a limiter and a key function")
	}
	if err := checkCost(cfg.cost, l.Policy()); err != nil {
		return nil, err
	}
	var pl PolicyLimiter
	if cfg.policy != nil {
		var ok bool
		if pl, ok = l.(PolicyLimiter); !ok {
			return nil, fmt.Errorf("rate limiter %s does not support per-request policies", l.Name())
		}
	}
	if cfg.registry == nil {
		var err error
		if cfg.registry, err = metrics.NewRegistry(metrics.Noop()); err != nil {
			return nil, err
		}
	}
	m := &mw{cfg: cfg, limiter: l, policyLimiter: pl, keys: keys}
	var err error
	if m.limited, m.storeFailures, m.policyFailures, err = m.counters(); err != nil {
		return nil, err
	}
	return m.wrap, nil
}

type mw struct {
	cfg           mwConfig
	limiter       Limiter
	policyLimiter PolicyLimiter
	keys          KeyFunc

	limited, storeFailures, policyFailures *metrics.CounterSeries
	warnStore, warnPolicy                  throttle
}

func (m *mw) counters() (limited, store, policy *metrics.CounterSeries, err error) {
	name := m.limiter.Name()
	c1, err := m.cfg.registry.Counter("fw_http_rate_limited_total", "Requests answered 429 by a rate limiter.", "limiter")
	if err != nil {
		return nil, nil, nil, err
	}
	c2, err := m.cfg.registry.Counter("fw_http_rate_limit_store_failures_total",
		"Requests a rate limiter could not decide because its store failed.", "limiter")
	if err != nil {
		return nil, nil, nil, err
	}
	limited, store = c1.With(name), c2.With(name)
	if m.cfg.policy != nil {
		c3, err := m.cfg.registry.Counter("fw_http_rate_limit_policy_failures_total",
			"Requests whose rate limit policy could not be resolved.", "limiter")
		if err != nil {
			return nil, nil, nil, err
		}
		policy = c3.With(name)
	}
	return limited, store, policy, nil
}

func (m *mw) wrap(next httpfw.HandlerFunc) httpfw.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		key := resolveKey(m.keys, r)
		policy := m.limiter.Policy()
		if m.policyLimiter != nil {
			resolved, problem := m.resolvePolicy(r)
			switch {
			case problem != "":
				m.policyFailures.Inc()
				if m.warnPolicy.due(m.cfg.clock.Now()) {
					m.cfg.log.Warn(fmt.Sprintf("rate limiter %s: %s; requests are %s", m.limiter.Name(), problem, m.failureVerb("decided with the default policy", "refused")))
				}
				if m.cfg.onFailure == Deny {
					return httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "rate limiting is unavailable")
				}
			case resolved.IsUnlimited():
				return next(w, r)
			case !resolved.IsZero():
				policy = resolved
			}
		}
		var (
			decision Decision
			err      error
		)
		if m.policyLimiter != nil {
			decision, err = m.policyLimiter.AcquireWith(r.Context(), key, m.cfg.cost, policy)
		} else {
			decision, err = m.limiter.Acquire(r.Context(), key, m.cfg.cost)
		}
		if err != nil {
			if !errors.Is(err, ErrStore) {
				return err // a programming error (a bad cost), not an outage
			}
			m.storeFailures.Inc()
			if m.warnStore.due(m.cfg.clock.Now()) {
				m.cfg.log.Warn(fmt.Sprintf("rate limiter %s: the store failed (%v); requests are %s", m.limiter.Name(), err, m.failureVerb("let through", "refused")))
			}
			if m.cfg.onFailure == Deny {
				return httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "rate limiting is unavailable")
			}
			return next(w, r)
		}
		burst := policy.Burst()
		switch d := decision.(type) {
		case Allowed:
			if m.cfg.headers {
				setHeaders(w, burst, d.Remaining, d.ResetAfter)
			}
			return next(w, r)
		case Limited:
			m.limited.Inc()
			w.Header().Set("Retry-After", strconv.FormatInt(seconds(d.RetryAfter), 10))
			if m.cfg.headers {
				setHeaders(w, burst, 0, d.ResetAfter)
			}
			return httpfw.NewError(http.StatusTooManyRequests, "RATE_LIMITED", "too many requests")
		}
		return errors.New("ratelimit: unknown decision")
	}
}

// resolvePolicy asks the policy function; a non-empty problem is a policy failure.
func (m *mw) resolvePolicy(r *http.Request) (Policy, string) {
	p, ok, err := m.cfg.policy(r)
	switch {
	case err != nil:
		return Policy{}, "the policy function failed: " + err.Error()
	case !ok:
		return Policy{}, ""
	case p.IsZero():
		return Policy{}, "the policy function returned the zero Policy"
	case p.Burst() < m.cfg.cost:
		return Policy{}, fmt.Sprintf("the resolved burst %d is below the cost %d", p.Burst(), m.cfg.cost)
	}
	return p, ""
}

func (m *mw) failureVerb(allow, deny string) string {
	if m.cfg.onFailure == Allow {
		return allow
	}
	return deny
}

// resolveKey applies the key function: no key is the shared bucket, a long one is hashed.
func resolveKey(keys KeyFunc, r *http.Request) string {
	key := keys(r)
	if key == "" {
		return sharedKey
	}
	if len(utf16.Encode([]rune(key))) > keyMax {
		return "sha256:" + sha256Hex(key)
	}
	return key
}

func setHeaders(w http.ResponseWriter, burst int, remaining int64, reset time.Duration) {
	h := w.Header()
	h.Set("RateLimit-Limit", strconv.Itoa(burst))
	h.Set("RateLimit-Remaining", strconv.FormatInt(remaining, 10))
	h.Set("RateLimit-Reset", strconv.FormatInt(seconds(reset), 10))
}

// seconds is whole seconds, rounded up, at least 1.
func seconds(d time.Duration) int64 {
	return max(1, (int64(d)+999_999_999)/1_000_000_000)
}
