package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"regexp"
)

var wellFormed = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

// NewHandle returns a new session handle: 32 random bytes from a CSPRNG, base64url without padding
// (43 characters of [A-Za-z0-9_-]). The server stores keep only [HandleHash] of it.
func NewHandle() string { return random(32) }

// IsWellFormedHandle reports whether a value has the shape of a handle; stores answer "none" for
// anything else without a store call.
func IsWellFormedHandle(value string) bool { return wellFormed.MatchString(value) }

// HandleHash is what a server store keeps in place of the handle, so a leaked table or dump holds
// no usable cookie: base64url of SHA-256 of the handle's ASCII bytes.
func HandleHash(handle string) string {
	sum := sha256.Sum256([]byte(handle))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// random returns n random bytes, base64url without padding. A failing system random source is
// not recoverable: it panics rather than hand out a predictable value.
func random(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(fmt.Sprintf("auth: no random source: %v", err))
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
