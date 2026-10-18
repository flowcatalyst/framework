package auth

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// maxCookieValueLength is the length above which a sealed record does not fit in a cookie with
// its attributes.
const maxCookieValueLength = 3_800

// CookieStore is the cookie store (contract section 6.5): the record travels in the cookie,
// sealed with [CookieKeys]. Nothing is kept on the server, so logout cannot revoke a copy of the
// cookie taken before ([CookieStore.Revocable] is false) and Take cannot be atomic: the binding
// cookie is cleared and the platform's single-use code is what stops a replayed callback. Prefer
// authdb or authredis where revocation matters.
type CookieStore struct {
	keys  *CookieKeys
	clock fw.Clock
}

// NewCookieStore returns the store sealing with keys; clock decides expiry.
func NewCookieStore(keys *CookieKeys, clock fw.Clock) *CookieStore {
	return &CookieStore{keys: keys, clock: clock}
}

type sealedEnvelope struct {
	Exp  string         `json:"exp"`
	Data jsontext.Value `json:"data"`
}

// Create implements Store: the handle is the sealed record. A sealed value over 3 800 characters
// is an error (too many roles for a cookie).
func (s *CookieStore) Create(_ context.Context, kind Kind, data []byte, expiresAt time.Time) (string, error) {
	var v jsontext.Value = bytes.Clone(data)
	if err := v.Compact(); err != nil || !isObject(v) {
		return "", StoreError("a session record is not a JSON object", nil)
	}
	plain, err := json.Marshal(sealedEnvelope{Exp: javafmt.Instant(expiresAt), Data: v})
	if err != nil {
		return "", StoreError("cannot encode a session record", nil)
	}
	value, err := s.keys.seal(kind, plain)
	if err != nil {
		return "", err
	}
	if len(value) > maxCookieValueLength {
		return "", StoreError(fmt.Sprintf("a sealed %s record is %d characters, over %d; use the database or redis store", kind, len(value), maxCookieValueLength), nil)
	}
	return value, nil
}

// Read implements Store: the handle is opened with the keys.
func (s *CookieStore) Read(_ context.Context, kind Kind, handle string) ([]byte, bool, error) {
	plain, ok := s.keys.open(kind, handle)
	if !ok {
		return nil, false, nil
	}
	var env sealedEnvelope
	if err := json.Unmarshal(plain, &env); err != nil {
		return nil, false, nil
	}
	exp, err := time.Parse(time.RFC3339Nano, env.Exp)
	if err != nil || !isObject(env.Data) || !s.clock.Now().Before(exp) {
		return nil, false, nil
	}
	return bytes.Clone(env.Data), true, nil
}

// Take implements Store. It is not atomic: nothing is stored, so the caller clears the cookie.
func (s *CookieStore) Take(ctx context.Context, kind Kind, handle string) ([]byte, bool, error) {
	return s.Read(ctx, kind, handle)
}

// Update implements Store: the record is sealed again, so the handle changes.
func (s *CookieStore) Update(ctx context.Context, kind Kind, _ string, data []byte, expiresAt time.Time) (string, error) {
	return s.Create(ctx, kind, data, expiresAt)
}

// Delete implements Store: nothing is stored; the caller clears the cookie.
func (*CookieStore) Delete(context.Context, Kind, string) error { return nil }

// Revocable implements Store.
func (*CookieStore) Revocable() bool { return false }

func isObject(v jsontext.Value) bool { return v.Kind() == '{' }
