// Package b64url decodes base64url in its one canonical spelling (docs/contracts/token-spelling.md):
// the URL-safe alphabet of RFC 4648 section 5 only (no '=' padding, no '+' or '/', no whitespace or
// line break), a length that is not 1 mod 4, and the spare bits of the last character zero (RFC 4648
// section 3.5). Every verifier of a value the framework or the platform issues (tokens, DPoP proofs,
// nonces, sealed cookies, push tickets and stream tokens) decodes with [Decode], so a value verifies
// only in the spelling it was issued in.
//
// base64.RawURLEncoding alone ignores the spare bits, so several strings decode to the same bytes;
// its Strict mode refuses those but still skips CR and LF anywhere in the input. [Decode] checks the
// alphabet first and then decodes strictly.
package b64url

import (
	"encoding/base64"
	"errors"
)

// ErrNonCanonical is returned for text that is not canonical base64url.
var ErrNonCanonical = errors.New("b64url: not canonical base64url")

var strict = base64.RawURLEncoding.Strict()

// Decode decodes canonical base64url. For any text it accepts, [Encode] of the result gives the text
// back. The text is taken as received: nothing is trimmed.
func Decode(text string) ([]byte, error) {
	for i := range len(text) {
		if !isAlphabet(text[i]) {
			return nil, ErrNonCanonical
		}
	}
	b, err := strict.DecodeString(text)
	if err != nil {
		return nil, ErrNonCanonical
	}
	return b, nil
}

// Encode encodes bytes canonically: the URL-safe alphabet without padding.
func Encode(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func isAlphabet(c byte) bool {
	return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'
}
