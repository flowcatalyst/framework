package auth

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
)

// cookieNames builds the session and login-binding cookies (contract section 5.3): names,
// attributes and reading. Over https the names carry the __Host- prefix and Secure is sent; over
// plain http (development only) neither, because browsers drop Secure cookies and refuse the prefix.
type cookieNames struct{ secure bool }

func (c cookieNames) sessionName() string {
	if c.secure {
		return "__Host-fw_session"
	}
	return "fw_session"
}

// loginName is the binding cookie of one login: the first 16 hex characters of SHA-256 of the
// state, so several logins may be in flight in one browser.
func (c cookieNames) loginName(state string) string {
	sum := sha256.Sum256([]byte(state))
	h := hex.EncodeToString(sum[:])[:16]
	if c.secure {
		return "__Host-fw_login_" + h
	}
	return "fw_login_" + h
}

// set is a Set-Cookie value.
func (c cookieNames) set(name, value string, maxAgeSeconds int64) string {
	s := name + "=" + value + "; Path=/; Max-Age=" + strconv.FormatInt(max(0, maxAgeSeconds), 10) + "; HttpOnly; SameSite=Lax"
	if c.secure {
		s += "; Secure"
	}
	return s
}

// clear is a Set-Cookie value that removes the cookie.
func (c cookieNames) clear(name string) string { return c.set(name, "", 0) }

// readCookie returns the value of a cookie sent exactly once, else "". A name sent twice is
// treated as absent, so a sibling host cannot shadow ours with a second value; a value in double
// quotes is the value without them (RFC 6265 allows it).
func readCookie(r *http.Request, name string) string {
	count, found := 0, ""
	for _, header := range r.Header.Values("Cookie") {
		for pair := range strings.SplitSeq(header, ";") {
			pair = strings.TrimSpace(pair)
			n, v, ok := strings.Cut(pair, "=")
			if !ok || n == "" || strings.TrimSpace(n) != name {
				continue
			}
			count++
			found = unquote(strings.TrimSpace(v))
		}
	}
	if count != 1 {
		return ""
	}
	return found
}

func unquote(v string) string {
	if len(v) >= 2 && strings.HasPrefix(v, `"`) && strings.HasSuffix(v, `"`) {
		return v[1 : len(v)-1]
	}
	return v
}
