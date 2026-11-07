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
	values map[string]Object
}

func (m *memoryStore) Name() string { return m.name }

func (m *memoryStore) Put(ctx context.Context, key string, content []byte) error {
	return m.PutObject(ctx, key, Object{Content: content})
}

func (m *memoryStore) PutObject(_ context.Context, key string, o Object) error {
	if err := CheckKey(key); err != nil {
		return err
	}
	if err := CheckContentType(o.ContentType); err != nil {
		return err
	}
	if err := CheckSize(len(o.Content), m.maxBytes); err != nil {
		return err
	}
	c := slices.Clone(o.Content)
	if c == nil {
		c = []byte{}
	}
	m.mu.Lock()
	m.values[key] = Object{Content: c, ContentType: ContentTypeOrDefault(o.ContentType)}
	m.mu.Unlock()
	return nil
}

func (m *memoryStore) Get(ctx context.Context, key string) ([]byte, bool, error) {
	o, ok, err := m.GetObject(ctx, key)
	return o.Content, ok, err
}

func (m *memoryStore) GetObject(_ context.Context, key string) (Object, bool, error) {
	if err := CheckKey(key); err != nil {
		return Object{}, false, err
	}
	m.mu.RLock()
	v, ok := m.values[key]
	m.mu.RUnlock()
	if !ok {
		return Object{}, false, nil
	}
	c := slices.Clone(v.Content)
	if c == nil {
		c = []byte{}
	}
	return Object{Content: c, ContentType: v.ContentType}, true, nil
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
