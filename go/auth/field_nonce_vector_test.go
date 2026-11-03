package auth

import (
	"encoding/base64"
	"testing"
	"time"
)

// TestFieldNonceVector pins the application nonce bytes (sessions-and-login contract 15.4) so the Go
// and Java frameworks interoperate: key bytes 0..31, 2026-11-03T12:00:00Z. The Java test
// (framework-auth FieldWorkersTest) pins the same value.
func TestFieldNonceVector(t *testing.T) {
	raw := make([]byte, 32)
	for i := range raw {
		raw[i] = byte(i)
	}
	keys, err := ParseCookieKeys("k1:" + base64.RawURLEncoding.EncodeToString(raw))
	if err != nil {
		t.Fatal(err)
	}
	n, err := newFieldNonces(keys)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 3, 12, 0, 0, 0, time.UTC)
	if got, want := n.issue(now), "AAAAAABbO5BhEQP-hvoGRRcossz6o4Nz"; got != want {
		t.Fatalf("nonce %q, want %q", got, want)
	}
	if !n.valid("AAAAAABbO5BhEQP-hvoGRRcossz6o4Nz", now.Add(4*time.Minute)) {
		t.Fatal("the pinned nonce is not valid in its own window")
	}
}
