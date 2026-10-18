package auth

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json/v2"
	"math"
	"time"
)

const (
	maxTokenLength = 16_384
	skew           = 60 * time.Second
)

// claimSet is a JSON object as decoded: strings, float64, bools, []any, map[string]any, nil.
type claimSet map[string]any

// checked is the outcome of the signature checks: claims when signed by the platform (not yet
// checked), else the contract's refusal reason.
type checked struct {
	claims claimSet
	reason string
}

// verifyJWT is the compact JWS checks shared by id_tokens and access tokens (contract section
// 5.5, steps 1 to 4): RS256 only, decided before any key is looked at; the key from the
// platform's own key set by kid; nothing in the header is fetched. It returns an error only when
// the platform's keys cannot be read.
func verifyJWT(ctx context.Context, token string, p *platform) (checked, error) {
	if token == "" || len(token) > maxTokenLength {
		return checked{reason: "malformed"}, nil
	}
	parts := splitDots(token)
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" {
		return checked{reason: "malformed"}, nil
	}
	header := object(parts[0])
	if header == nil {
		return checked{reason: "malformed"}, nil
	}
	if alg, ok := header["alg"].(string); !ok || alg != "RS256" {
		return checked{reason: "alg"}, nil
	}
	if _, has := header["crit"]; has {
		return checked{reason: "crit"}, nil
	}
	kid, ok := header["kid"].(string)
	if !ok || kid == "" {
		return checked{reason: "kid"}, nil
	}
	key, err := p.key(ctx, kid)
	if err != nil {
		return checked{}, err
	}
	if key == nil {
		return checked{reason: "unknown_kid"}, nil
	}
	signature, ok := decodeB64(parts[2])
	if !ok || len(signature) == 0 || !signatureMatches(key, parts[0]+"."+parts[1], signature) {
		return checked{reason: "signature"}, nil
	}
	claims := object(parts[1])
	if claims == nil {
		return checked{reason: "malformed"}, nil
	}
	return checked{claims: claims}, nil
}

func splitDots(s string) []string {
	var out []string
	start := 0
	for i := range len(s) {
		if s[i] == '.' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}

func signatureMatches(key *rsa.PublicKey, signed string, signature []byte) bool {
	sum := sha256.Sum256([]byte(signed))
	return rsa.VerifyPKCS1v15(key, crypto.SHA256, sum[:], signature) == nil
}

// times applies the time rules: exp required, now before it plus the allowance; nbf optional, now
// at or after it minus the allowance; iat optional, not after now plus the allowance. It returns
// the refusal reason, or "".
func times(claims claimSet, now time.Time) string {
	exp, ok := claims["exp"].(float64)
	if !ok || !now.Before(seconds(exp).Add(skew)) {
		return "expired"
	}
	if v, has := claims["nbf"]; has {
		nbf, ok := v.(float64)
		if !ok || now.Before(seconds(nbf).Add(-skew)) {
			return "not_yet_valid"
		}
	}
	if v, has := claims["iat"]; has {
		iat, ok := v.(float64)
		if !ok || seconds(iat).After(now.Add(skew)) {
			return "iat"
		}
	}
	return ""
}

// seconds turns a numeric claim into a time: whole seconds rounded down, and a value that is
// absurd (NaN, beyond +-1e12) becomes the far future or the epoch rather than overflowing.
func seconds(v float64) time.Time {
	if math.IsNaN(v) || v > 1e12 || v < -1e12 {
		if v > 0 {
			return time.Unix(1<<40, 0).UTC()
		}
		return time.Unix(0, 0).UTC()
	}
	return time.Unix(int64(math.Floor(v)), 0).UTC()
}

// texts reads a claim that is a string or a list of them; other elements are dropped.
func texts(v any) []string {
	switch t := v.(type) {
	case string:
		return []string{t}
	case []any:
		out := make([]string, 0, len(t))
		for _, e := range t {
			if s, ok := e.(string); ok {
				out = append(out, s)
			}
		}
		return out
	}
	return []string{}
}

// text reads a non-empty string claim, "" otherwise.
func text(claims claimSet, name string) string {
	s, _ := claims[name].(string)
	return s
}

// object decodes one base64url part as a JSON object; nil when it is not one.
func object(part string) claimSet {
	b, ok := decodeB64(part)
	if !ok {
		return nil
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil || m == nil {
		return nil
	}
	return m
}

// decodeB64 decodes base64url without padding, strictly: only the alphabet, no '=' and no
// whitespace (Go's decoder would skip newlines).
func decodeB64(part string) ([]byte, bool) {
	for i := range len(part) {
		c := part[i]
		if (c < 'A' || c > 'Z') && (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' && c != '_' {
			return nil, false
		}
	}
	b, err := base64.RawURLEncoding.DecodeString(part)
	if err != nil {
		return nil, false
	}
	return b, true
}
