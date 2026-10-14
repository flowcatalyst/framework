package locks

import (
	"context"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// MemoryStore is the memory driver: locks within one process only, for a single instance, local
// development and tests (two processes using it do not exclude each other). Expiry is measured
// with the given clock, so tests move time without sleeping; fencing tokens come from one counter
// per store.
type MemoryStore struct {
	clock fw.Clock

	mu           sync.Mutex
	held         map[string]memoryHeld
	counter      int64
	acquisitions int
}

type memoryHeld struct {
	owner   string
	fence   int64
	expires time.Time
}

const memorySweepEvery = 4096

// NewMemoryStore returns an empty memory store reading clock.
func NewMemoryStore(clock fw.Clock) *MemoryStore {
	return &MemoryStore{clock: clock, held: make(map[string]memoryHeld)}
}

// TryAcquire implements [Store].
func (m *MemoryStore) TryAcquire(_ context.Context, key, owner string, leaseMillis int64) (int64, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.clock.Now()
	if cur, ok := m.held[key]; ok && cur.expires.After(now) {
		return 0, false, nil
	}
	m.acquisitions++
	if m.acquisitions%memorySweepEvery == 0 {
		for k, h := range m.held {
			if !h.expires.After(now) {
				delete(m.held, k)
			}
		}
	}
	m.counter++
	m.held[key] = memoryHeld{owner: owner, fence: m.counter, expires: now.Add(time.Duration(leaseMillis) * time.Millisecond)}
	return m.counter, true, nil
}

// Refresh implements [Store].
func (m *MemoryStore) Refresh(_ context.Context, key, owner string, fence, leaseMillis int64) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.clock.Now()
	if !m.matches(key, owner, fence, now) {
		return false, nil
	}
	m.held[key] = memoryHeld{owner: owner, fence: fence, expires: now.Add(time.Duration(leaseMillis) * time.Millisecond)}
	return true, nil
}

// Release implements [Store].
func (m *MemoryStore) Release(_ context.Context, key, owner string, fence int64) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.clock.Now()
	if !m.matches(key, owner, fence, now) {
		return false, nil
	}
	m.held[key] = memoryHeld{owner: owner, fence: fence, expires: now}
	return true, nil
}

// ExpireNow ends a key's current lease at once, as if it had expired (a test hook).
func (m *MemoryStore) ExpireNow(key string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if cur, ok := m.held[key]; ok {
		cur.expires = m.clock.Now()
		m.held[key] = cur
	}
}

func (m *MemoryStore) matches(key, owner string, fence int64, now time.Time) bool {
	cur, ok := m.held[key]
	return ok && cur.owner == owner && cur.fence == fence && cur.expires.After(now)
}
