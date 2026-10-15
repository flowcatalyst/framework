package ratelimit

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

const (
	// DefaultMaxKeys is the most keys a memory limiter holds unless [MaxKeys] says otherwise.
	DefaultMaxKeys = 100_000

	overflowKey = "\x00overflow"
	purgeEvery  = int64(time.Second)
	warnEvery   = int64(time.Minute)
)

// Memory is the memory limiter: one process only, so with several replicas each has its own
// budget and the effective limit is multiplied by the replica count (contract section 2). It is
// bounded: when the map is full, keys whose bucket is full again are purged (at most once a
// second); if it is still full, new keys share one overflow bucket, so a flood of distinct keys
// neither grows memory nor resets the limits of existing keys.
type Memory struct {
	name    string
	policy  Policy
	maxKeys int
	clock   fw.Clock
	origin  time.Time
	log     *slog.Logger

	mu        sync.Mutex
	tats      map[string]int64
	lastPurge int64
	purged    bool
	lastWarn  int64
	warned    bool
}

// MemoryOption configures [NewMemory].
type MemoryOption func(*Memory)

// MaxKeys sets the most keys held, 1 to 10 000 000 (default [DefaultMaxKeys]).
func MaxKeys(n int) MemoryOption { return func(m *Memory) { m.maxKeys = n } }

// MemoryClock sets the clock time is read from (tests; default [fw.SystemClock]). Durations are
// measured against the first reading, so the system clock's monotonic reading is used.
func MemoryClock(c fw.Clock) MemoryOption { return func(m *Memory) { m.clock = c } }

// MemoryLogger sets the logger of the overflow warning (default: discard).
func MemoryLogger(l *slog.Logger) MemoryOption { return func(m *Memory) { m.log = l } }

// NewMemory builds a memory limiter named name ([a-z][a-z0-9_-]*, at most 63 characters) with a
// default policy.
func NewMemory(name string, policy Policy, opts ...MemoryOption) (*Memory, error) {
	m := &Memory{name: name, policy: policy, maxKeys: DefaultMaxKeys, tats: make(map[string]int64)}
	for _, o := range opts {
		o(m)
	}
	if m.clock == nil {
		m.clock = fw.SystemClock()
	}
	if m.log == nil {
		m.log = slog.New(slog.DiscardHandler)
	}
	if err := checkName(name); err != nil {
		return nil, err
	}
	if policy.IsZero() {
		return nil, fmt.Errorf("rate limiter %s: the zero Policy is invalid", name)
	}
	if m.maxKeys < 1 || m.maxKeys > 10_000_000 {
		return nil, fmt.Errorf("rate limiter %s: MaxKeys must be 1 to 10000000", name)
	}
	m.origin = m.clock.Now()
	return m, nil
}

// Name implements [Limiter].
func (m *Memory) Name() string { return m.name }

// Policy implements [Limiter].
func (m *Memory) Policy() Policy { return m.policy }

// Acquire implements [Limiter].
func (m *Memory) Acquire(ctx context.Context, key string, cost int) (Decision, error) {
	return m.AcquireWith(ctx, key, cost, m.policy)
}

// AcquireWith implements [PolicyLimiter].
func (m *Memory) AcquireWith(_ context.Context, key string, cost int, p Policy) (Decision, error) {
	if p.IsZero() {
		return nil, fmt.Errorf("rate limiter %s: the zero Policy is invalid", m.name)
	}
	if err := checkCost(cost, p); err != nil {
		return nil, err
	}
	now := int64(m.clock.Now().Sub(m.origin))
	m.mu.Lock()
	defer m.mu.Unlock()
	k := key
	if _, held := m.tats[k]; !held && len(m.tats) >= m.maxKeys {
		m.purge(now)
		if len(m.tats) >= m.maxKeys {
			k = overflowKey
			m.warnFull(now)
		}
	}
	stored, held := m.tats[k]
	if !held {
		stored = now
	}
	s := gcra(stored, now, p.interval(), int64(p.burst), int64(cost))
	if s.store {
		m.tats[k] = s.newTat
	}
	return s.decision(1), nil
}

// purge removes keys whose bucket is full again; they would start full anyway. The caller holds
// the lock.
func (m *Memory) purge(now int64) {
	if m.purged && now-m.lastPurge < purgeEvery {
		return
	}
	m.purged, m.lastPurge = true, now
	for k, tat := range m.tats {
		if k != overflowKey && tat-now <= 0 {
			delete(m.tats, k)
		}
	}
}

func (m *Memory) warnFull(now int64) {
	if m.warned && now-m.lastWarn < warnEvery {
		return
	}
	m.warned, m.lastWarn = true, now
	m.log.Warn(fmt.Sprintf("rate limiter %s holds %d keys (max keys); new callers share one overflow bucket", m.name, m.maxKeys))
}

// Size returns the number of keys held, for tests and diagnostics.
func (m *Memory) Size() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.tats)
}
