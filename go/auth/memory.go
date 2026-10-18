package auth

import (
	"context"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// MemoryStore is the memory store: one process only, lost on restart. For tests and
// single-process development; use authdb or authredis when there is more than one replica.
type MemoryStore struct {
	clock fw.Clock

	mu      sync.Mutex
	entries map[string]memoryEntry
	creates uint64
}

type memoryEntry struct {
	data      []byte
	expiresAt time.Time
}

// NewMemoryStore returns an empty store whose expiry is decided by clock.
func NewMemoryStore(clock fw.Clock) *MemoryStore {
	return &MemoryStore{clock: clock, entries: map[string]memoryEntry{}}
}

// Create implements Store. Every 1024 creations the expired records are removed.
func (s *MemoryStore) Create(_ context.Context, kind Kind, data []byte, expiresAt time.Time) (string, error) {
	handle := NewHandle()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[memoryKey(kind, handle)] = memoryEntry{data: append([]byte(nil), data...), expiresAt: expiresAt}
	s.creates++
	if s.creates%1024 == 0 {
		now := s.clock.Now()
		for k, e := range s.entries {
			if !now.Before(e.expiresAt) {
				delete(s.entries, k)
			}
		}
	}
	return handle, nil
}

// Read implements Store; an expired record is removed when read.
func (s *MemoryStore) Read(_ context.Context, kind Kind, handle string) ([]byte, bool, error) {
	if !IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	key := memoryKey(kind, handle)
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.entries[key]
	if !ok {
		return nil, false, nil
	}
	if !s.clock.Now().Before(e.expiresAt) {
		delete(s.entries, key)
		return nil, false, nil
	}
	return append([]byte(nil), e.data...), true, nil
}

// Take implements Store.
func (s *MemoryStore) Take(_ context.Context, kind Kind, handle string) ([]byte, bool, error) {
	if !IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	key := memoryKey(kind, handle)
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.entries[key]
	delete(s.entries, key)
	if !ok || !s.clock.Now().Before(e.expiresAt) {
		return nil, false, nil
	}
	return e.data, true, nil
}

// Update implements Store; it replaces a record that exists and returns the same handle.
func (s *MemoryStore) Update(_ context.Context, kind Kind, handle string, data []byte, expiresAt time.Time) (string, error) {
	if IsWellFormedHandle(handle) {
		key := memoryKey(kind, handle)
		s.mu.Lock()
		if _, ok := s.entries[key]; ok {
			s.entries[key] = memoryEntry{data: append([]byte(nil), data...), expiresAt: expiresAt}
		}
		s.mu.Unlock()
	}
	return handle, nil
}

// Delete implements Store.
func (s *MemoryStore) Delete(_ context.Context, kind Kind, handle string) error {
	if IsWellFormedHandle(handle) {
		s.mu.Lock()
		delete(s.entries, memoryKey(kind, handle))
		s.mu.Unlock()
	}
	return nil
}

// Revocable implements Store.
func (*MemoryStore) Revocable() bool { return true }

// Size is how many records are held, expired ones not yet removed included; for tests.
func (s *MemoryStore) Size() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.entries)
}

func memoryKey(kind Kind, handle string) string { return string(kind) + ":" + HandleHash(handle) }
