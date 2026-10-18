package auth

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

var kidPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,16}$`)

const (
	ivBytes  = 12
	tagBytes = 16
)

// CookieKeys are the keys that seal cookies (contract section 6.5): AES-256-GCM, value
// "v1.<kid>.<base64url(iv || ciphertext || tag)>", additional data "fw:<kind>". The first key
// seals, every key opens, so a key can be rotated without signing everyone out.
type CookieKeys struct {
	current string
	order   []string
	aead    map[string]cipher.AEAD
}

// ParseCookieKeys parses auth.cookie-keys: "<kid>:<base64url of 32 bytes>[,<kid>:<key>...]". The
// first key seals. An empty or malformed list, a key that is not 32 bytes and a repeated kid are
// errors; the error never contains key material.
func ParseCookieKeys(value string) (*CookieKeys, error) {
	keys := &CookieKeys{aead: map[string]cipher.AEAD{}}
	for i, entry := range strings.Split(value, ",") {
		position := i + 1
		trimmed := strings.TrimSpace(entry)
		kid, key, found := strings.Cut(trimmed, ":")
		if !found {
			return nil, fmt.Errorf("auth.cookie-keys entry %d is not <kid>:<key>", position)
		}
		if !kidPattern.MatchString(kid) {
			return nil, fmt.Errorf("auth.cookie-keys entry %d: the kid must match [A-Za-z0-9_-]{1,16}", position)
		}
		raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(strings.TrimSpace(key), "="))
		if err != nil {
			return nil, fmt.Errorf("auth.cookie-keys key '%s' is not base64url", kid)
		}
		if len(raw) != 32 {
			return nil, fmt.Errorf("auth.cookie-keys key '%s' must be 32 bytes, not %d", kid, len(raw))
		}
		if _, dup := keys.aead[kid]; dup {
			return nil, fmt.Errorf("auth.cookie-keys lists the kid '%s' twice", kid)
		}
		block, err := aes.NewCipher(raw)
		if err != nil {
			return nil, errors.New("auth.cookie-keys: cannot use key '" + kid + "'")
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return nil, errors.New("auth.cookie-keys: cannot use key '" + kid + "'")
		}
		keys.aead[kid] = gcm
		keys.order = append(keys.order, kid)
	}
	keys.current = keys.order[0]
	return keys, nil
}

// GenerateCookieKey returns a new random key in the list format, for setting up auth.cookie-keys:
// "<kid>:<base64url of 32 random bytes>".
func GenerateCookieKey(kid string) (string, error) {
	if !kidPattern.MatchString(kid) {
		return "", errors.New("the kid must match [A-Za-z0-9_-]{1,16}")
	}
	return kid + ":" + random(32), nil
}

// Kids lists the ids of the keys, the sealing one first.
func (k *CookieKeys) Kids() []string { return append([]string(nil), k.order...) }

// String names the keys without showing them.
func (k *CookieKeys) String() string { return "CookieKeys" + fmt.Sprint(k.order) }

// seal encrypts bytes with the first key, binding the record kind into the additional data.
func (k *CookieKeys) seal(kind Kind, plaintext []byte) (string, error) {
	iv := make([]byte, ivBytes)
	if _, err := rand.Read(iv); err != nil {
		return "", StoreError("no random source to seal a cookie", nil)
	}
	gcm, ok := k.aead[k.current]
	if !ok {
		return "", StoreError("no sealing key", nil)
	}
	sealed := gcm.Seal(nil, iv, plaintext, aad(kind))
	return "v1." + k.current + "." + base64.RawURLEncoding.EncodeToString(append(iv, sealed...)), nil
}

// open decrypts a cookie value sealed by any of the keys for this kind; false when it is
// malformed, names an unknown key, was sealed for another kind or was changed.
func (k *CookieKeys) open(kind Kind, value string) ([]byte, bool) {
	parts := strings.Split(value, ".")
	if len(parts) != 3 || parts[0] != "v1" {
		return nil, false
	}
	gcm, ok := k.aead[parts[1]]
	if !ok {
		return nil, false
	}
	raw, ok := decodeB64(parts[2])
	if !ok || len(raw) < ivBytes+tagBytes {
		return nil, false
	}
	plain, err := gcm.Open(nil, raw[:ivBytes], raw[ivBytes:], aad(kind))
	if err != nil {
		return nil, false
	}
	return plain, true
}

func aad(kind Kind) []byte { return []byte("fw:" + string(kind)) }
