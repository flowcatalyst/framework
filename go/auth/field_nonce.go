package auth

import (
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"time"
)

// Application nonces for field workers (sessions-and-login contract section 15.4): stateless, so
// no replica has to remember what another issued.
//
//	bucket = floor(unix seconds / 300), 8 bytes big-endian
//	nonce  = base64url(bucket || first 16 bytes of HMAC-SHA256(k, "fw-field-nonce" || bucket))
//	k      = HKDF-SHA256(secret = a cookie key's 32 bytes, salt empty, info "fw-field-nonce", 32 bytes)
//
// A nonce is valid in its own bucket and the next one (the current or the previous bucket when it
// is checked), under the key derived from any configured cookie key.
const (
	fieldNonceInfo   = "fw-field-nonce"
	nonceBucketSecs  = 300
	nonceMACBytes    = 16
	fieldNonceLength = 8 + nonceMACBytes
)

type fieldNonces struct{ keys [][]byte } // derived keys, the sealing key's first

func newFieldNonces(cookie *CookieKeys) (*fieldNonces, error) {
	n := &fieldNonces{}
	for _, kid := range cookie.order {
		k, err := hkdf.Key(sha256.New, cookie.raw[kid], nil, fieldNonceInfo, sha256.Size)
		if err != nil {
			return nil, err
		}
		n.keys = append(n.keys, k)
	}
	return n, nil
}

func nonceBucket(now time.Time) uint64 { return uint64(max(now.Unix(), 0)) / nonceBucketSecs }

func nonceMAC(key []byte, bucket []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(fieldNonceInfo))
	m.Write(bucket)
	return m.Sum(nil)[:nonceMACBytes]
}

// issue returns the nonce of the current bucket under the current key.
func (n *fieldNonces) issue(now time.Time) string {
	bucket := make([]byte, 8)
	binary.BigEndian.PutUint64(bucket, nonceBucket(now))
	return base64.RawURLEncoding.EncodeToString(append(bucket, nonceMAC(n.keys[0], bucket)...))
}

// valid reports whether a nonce was issued in the current or the previous bucket by any key.
func (n *fieldNonces) valid(nonce string, now time.Time) bool {
	raw, ok := decodeB64(nonce)
	if !ok || len(raw) != fieldNonceLength {
		return false
	}
	current := nonceBucket(now)
	got := binary.BigEndian.Uint64(raw[:8])
	if got != current && got+1 != current {
		return false
	}
	matched := false
	for _, k := range n.keys { // every key is tried, so the time taken does not say which one matched
		if hmac.Equal(raw[8:], nonceMAC(k, raw[:8])) {
			matched = true
		}
	}
	return matched
}
