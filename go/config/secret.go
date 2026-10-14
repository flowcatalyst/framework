package config

import (
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"log/slog"
)

// redacted is what every formatting path prints for a Secret.
const redacted = "Secret[****]"

// Secret is a configuration value that must not leak: every formatting path prints Secret[****],
// slog prints Secret[****], and JSON encoding fails. The value is read with Reveal.
type Secret struct {
	// value is a pointer so that fmt's fallback for a verb that cannot format the struct
	// (%p) prints an address, not the text.
	value *string
}

// NewSecret wraps a value.
func NewSecret(value string) Secret { return Secret{value: &value} }

// Reveal returns the value. Do not log or print it.
func (s Secret) Reveal() string {
	if s.value == nil {
		return ""
	}
	return *s.value
}

// String returns Secret[****].
func (s Secret) String() string { return redacted }

// GoString returns Secret[****], so %#v does not print the value either.
func (s Secret) GoString() string { return redacted }

// Format implements [fmt.Formatter]: whatever the verb and flags, it prints Secret[****].
func (s Secret) Format(f fmt.State, _ rune) {
	_, _ = f.Write([]byte(redacted))
}

// LogValue implements [slog.LogValuer]: a Secret in a log record is Secret[****].
func (s Secret) LogValue() slog.Value { return slog.StringValue(redacted) }

var errNotSerialisable = errors.New("config: a Secret is not serialisable; call Reveal where the value is needed")

// MarshalJSON always fails: a secret is not serialised by accident.
func (s Secret) MarshalJSON() ([]byte, error) { return nil, errNotSerialisable }

// MarshalText always fails: a secret is not serialised by accident.
func (s Secret) MarshalText() ([]byte, error) { return nil, errNotSerialisable }

// Matches reports whether candidate equals the secret, in constant time: both are hashed with
// SHA-256 and the digests compared, so neither the content nor the length shows in the timing.
// Use it to check a credential a caller presents.
func (s Secret) Matches(candidate string) bool {
	a, b := sha256.Sum256([]byte(s.Reveal())), sha256.Sum256([]byte(candidate))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}

// Equal reports whether two secrets hold the same value, in constant time.
func (s Secret) Equal(other Secret) bool { return s.Matches(other.Reveal()) }
