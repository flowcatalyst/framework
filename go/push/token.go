package push

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json/v2"
	"strings"
	"time"
)

// The 401 codes of GET {base}/push/stream (push contract 6.2).
const (
	codeTokenInvalid = "STREAM_TOKEN_INVALID"
	codeTokenExpired = "STREAM_TOKEN_EXPIRED" //nolint:gosec // an error code, not a credential
)

// streamClaims are what a stream token carries: the granted topics, the stream's client, who holds
// it (principal, field device and login family, for limits and revocation) and the expiry.
type streamClaims struct {
	V         int      `json:"v"`
	Client    string   `json:"c"`
	Principal string   `json:"p,omitempty"`
	Device    string   `json:"d,omitempty"`
	Session   string   `json:"s,omitempty"`
	Topics    []string `json:"t"`
	Exp       int64    `json:"exp"`
}

// streamTokens sign and verify stream tokens with push.stream.secret (HMAC-SHA256), which every
// instance of the host shares.
type streamTokens struct {
	secret []byte
}

const streamTokenContext = "fw-push-stream:" //nolint:gosec // the MAC's domain separation, not a credential

func (s streamTokens) mac(payload string) []byte {
	m := hmac.New(sha256.New, s.secret)
	m.Write([]byte(streamTokenContext + payload))
	return m.Sum(nil)
}

// sign returns base64url(claims JSON) "." base64url(HMAC).
func (s streamTokens) sign(c streamClaims) (string, error) {
	c.V = 1
	raw, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	payload := base64.RawURLEncoding.EncodeToString(raw)
	return payload + "." + base64.RawURLEncoding.EncodeToString(s.mac(payload)), nil
}

// verify returns the claims, or the 401 code.
func (s streamTokens) verify(token string, now time.Time) (streamClaims, string) {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || len(token) > 64*1024 {
		return streamClaims{}, codeTokenInvalid
	}
	got, err := base64.RawURLEncoding.DecodeString(sig)
	if err != nil || !hmac.Equal(got, s.mac(payload)) {
		return streamClaims{}, codeTokenInvalid
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	var c streamClaims
	if err != nil || json.Unmarshal(raw, &c) != nil || c.V != 1 || len(c.Topics) == 0 || c.Client == "" {
		return streamClaims{}, codeTokenInvalid
	}
	if !now.Before(time.Unix(c.Exp, 0)) {
		return streamClaims{}, codeTokenExpired
	}
	return c, ""
}
