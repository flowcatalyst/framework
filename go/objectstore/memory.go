package objectstore

import (
	"context"
	"slices"
	"sync"
)

// memoryStore keeps values in this process only: for tests and throwaway use.
type memoryStore struct {
	name     string
	maxBytes int

	mu     sync.RWMutex
	values map[string][]byte
}

func (m *memoryStore) Name() string { return m.name }

func (m *memoryStore) Put(_ context.Context, key string, content []byte) error {
	if err := CheckKey(key); err != nil {
		return err
	}
	if err := CheckSize(len(content), m.maxBytes); err != nil {
		return err
	}
	c := slices.Clone(content)
	if c == nil {
		c = []byte{}
	}
	m.mu.Lock()
	m.values[key] = c
	m.mu.Unlock()
	return nil
}

func (m *memoryStore) Get(_ context.Context, key string) ([]byte, bool, error) {
	if err := CheckKey(key); err != nil {
		return nil, false, err
	}
	m.mu.RLock()
	v, ok := m.values[key]
	m.mu.RUnlock()
	if !ok {
		return nil, false, nil
	}
	c := slices.Clone(v)
	if c == nil {
		c = []byte{}
	}
	return c, true, nil
}

func (m *memoryStore) Delete(_ context.Context, key string) error {
	if err := CheckKey(key); err != nil {
		return err
	}
	m.mu.Lock()
	delete(m.values, key)
	m.mu.Unlock()
	return nil
}
