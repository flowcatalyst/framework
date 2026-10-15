package flowcatalyst

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// The signature rules (flowcatalyst contract section 4 and 4a): hex HMAC-SHA256 over the raw
// timestamp header then the raw body with the signing secret; the timestamp is ISO-8601 or Unix
// seconds, compared in whole seconds (older than 300 s expired, more than 60 s ahead in the
// future); the signature is compared in constant time; an empty secret fails closed; an optional
// bearer gate is AND-ed. Ported from the Java module, not from fcsdk's webhook package, whose rules
// differ (the clock, the refusal codes, the duplicate-header rule, the bearer gate).

const (
	pastTolerance   = 300 // seconds
	futureTolerance = 60
	maxTimestampLen = 64
	signatureHexLen = 64
	signatureHeader = "X-FlowCatalyst-Signature"
	timestampHeader = "X-FlowCatalyst-Timestamp"
)

var unixSeconds = regexp.MustCompile(`^[0-9]{1,18}$`)

// failure is why a delivery was refused: the code in the 401's details.reason.
type failure string

const (
	missingSecret    failure = "MISSING_SECRET"
	missingSignature failure = "MISSING_SIGNATURE"
	missingTimestamp failure = "MISSING_TIMESTAMP"
	invalidTimestamp failure = "INVALID_TIMESTAMP"
	timestampExpired failure = "TIMESTAMP_EXPIRED"
	timestampFuture  failure = "TIMESTAMP_IN_FUTURE"
	invalidSignature failure = "INVALID_SIGNATURE"
	missingBearer    failure = "MISSING_BEARER"
	invalidBearer    failure = "INVALID_BEARER"
	duplicateHeader  failure = "DUPLICATE_HEADER"
)

// hmacHex is the signature of a delivery: hex(HMAC-SHA256(secret, timestamp + body)).
func hmacHex(secret, timestamp string, body []byte) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(timestamp))
	m.Write(body)
	return hex.EncodeToString(m.Sum(nil))
}

// checkSignature returns "" for a valid delivery, else the failure. The body is asked for only once
// every check that needs no body has passed: a refusal does not read it.
func checkSignature(body func() ([]byte, error), signature, timestamp, secret, expectedBearer, authorization string, now time.Time) (failure, error) {
	if secret == "" {
		return missingSecret, nil
	}
	if signature == "" {
		return missingSignature, nil
	}
	if timestamp == "" {
		return missingTimestamp, nil
	}
	seconds, ok := parseTimestamp(timestamp)
	if !ok {
		return invalidTimestamp, nil
	}
	nowSeconds := now.Unix()
	if seconds < nowSeconds-pastTolerance {
		return timestampExpired, nil
	}
	if seconds > nowSeconds+futureTolerance {
		return timestampFuture, nil
	}
	if len(signature) != signatureHexLen {
		return invalidSignature, nil
	}
	b, err := body()
	if err != nil {
		return "", err
	}
	expected := hmacHex(secret, timestamp, b)
	if subtle.ConstantTimeCompare([]byte(expected), []byte(strings.ToLower(signature))) != 1 {
		return invalidSignature, nil
	}
	return checkBearer(expectedBearer, authorization), nil
}

// checkBearer applies the optional bearer gate: the presented token and the expected one are
// compared as SHA-256 digests, in constant time and without revealing either length.
func checkBearer(expected, authorization string) failure {
	if expected == "" {
		return ""
	}
	if !strings.HasPrefix(authorization, "Bearer ") {
		return missingBearer
	}
	presented := sha256.Sum256([]byte(authorization[len("Bearer "):]))
	want := sha256.Sum256([]byte(expected))
	if subtle.ConstantTimeCompare(presented[:], want[:]) != 1 {
		return invalidBearer
	}
	return ""
}

// parseTimestamp reads Unix seconds (ASCII digits) or an ISO-8601 instant (RFC 3339), as whole
// seconds since the epoch.
func parseTimestamp(ts string) (int64, bool) {
	if len(ts) > maxTimestampLen {
		return 0, false
	}
	if unixSeconds.MatchString(ts) {
		n, err := strconv.ParseInt(ts, 10, 64)
		return n, err == nil
	}
	t, err := time.Parse(time.RFC3339, ts)
	if err != nil {
		return 0, false
	}
	return t.Unix(), true
}

// gate is one receiver's signature check, with its effective secret and bearer token.
type gate struct {
	signing    config.Secret
	bearer     config.Secret
	hasSigning bool
	hasBearer  bool
	require    bool
	clock      fw.Clock
}

// verify checks a delivery's headers and body. "" is valid; the error is the body failing to be
// read (too large, broken).
func (g gate) verify(h http.Header, body func() ([]byte, error)) (failure, error) {
	for _, name := range []string{signatureHeader, timestampHeader, "Authorization"} {
		if len(h.Values(name)) > 1 {
			return duplicateHeader, nil
		}
	}
	signature, timestamp, authorization := h.Get(signatureHeader), h.Get(timestampHeader), h.Get("Authorization")
	secret, bearer := "", ""
	if g.hasSigning {
		secret = g.signing.Reveal()
	}
	if g.hasBearer {
		bearer = g.bearer.Reveal()
	}
	unsigned := signature == "" && timestamp == ""
	if !g.require && (unsigned || secret == "") {
		return checkBearer(bearer, authorization), nil
	}
	return checkSignature(body, signature, timestamp, secret, bearer, authorization, g.clock.Now())
}
