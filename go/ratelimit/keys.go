package ratelimit

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/netip"
	"strings"

	"github.com/flowcatalyst/framework/go/httpfw"
)

// KeyFunc decides whose budget a request uses. It returns "" when the request has nothing to be
// keyed by; the middleware then puts it in one shared bucket (never unlimited: a caller must not
// escape the limit by omitting what the key is made of).
type KeyFunc func(r *http.Request) string

// ClientAddress keys by the connection's peer address, IPv6 grouped by its /64 prefix (one host
// commonly holds a whole /64). It never reads a header.
func ClientAddress() KeyFunc {
	return func(r *http.Request) string { return normalise(peer(r)) }
}

// ForwardedFor keys by the client address for an application behind trustedProxies (1 to 10)
// proxies that each append to X-Forwarded-For: the entry trustedProxies from the right, which is
// the address the outermost trusted proxy saw. Entries to its left are written by the caller and
// are ignored. When the header has fewer entries, or the chosen entry is not an IP literal, the
// connection's peer address is used. Names are never resolved; several header lines are read in
// order as one list.
func ForwardedFor(trustedProxies int) (KeyFunc, error) {
	if trustedProxies < 1 || trustedProxies > 10 {
		return nil, errors.New("trustedProxies must be 1 to 10")
	}
	return func(r *http.Request) string {
		var entries []string
		for _, line := range r.Header.Values("X-Forwarded-For") {
			for part := range strings.SplitSeq(line, ",") {
				entries = append(entries, strings.TrimSpace(part))
			}
		}
		if len(entries) >= trustedProxies {
			if chosen := normalise(entries[len(entries)-trustedProxies]); chosen != "" {
				return chosen
			}
		}
		return normalise(peer(r))
	}, nil
}

// Header keys by a header's value, hashed with SHA-256 so an API key never becomes a Redis key, a
// log line or a metric label. A missing or empty header has no key.
func Header(name string) KeyFunc {
	return func(r *http.Request) string {
		v := r.Header.Get(name)
		if v == "" {
			return ""
		}
		return "h:" + sha256Hex(v)
	}
}

// Client keys by the route's client (fw.Context.Client): for route middleware on a route that
// requires a context; on a request without one it has no key.
func Client() KeyFunc {
	return func(r *http.Request) string {
		cx, ok := httpfw.ContextIfAny(r)
		if !ok {
			return ""
		}
		return "c:" + cx.Client().String()
	}
}

// Principal keys by the route's principal: for route middleware on a route that requires a
// context; without a context or a principal it has no key.
func Principal() KeyFunc {
	return func(r *http.Request) string {
		cx, ok := httpfw.ContextIfAny(r)
		if !ok {
			return ""
		}
		p, ok := cx.Principal()
		if !ok {
			return ""
		}
		return "p:" + p.String()
	}
}

// peer is the connection's remote address as sent by the server: "ip:port" or a bare literal.
func peer(r *http.Request) string {
	if ap, err := netip.ParseAddrPort(r.RemoteAddr); err == nil {
		return ap.Addr().String()
	}
	return r.RemoteAddr
}

// normalise returns an IP literal in canonical form ("ip:" + the address), IPv6 cut to its /64
// ("ip6:" + the first eight bytes in hex + "::/64"); "" when text is not a literal.
func normalise(text string) string {
	if text == "" || len(text) > 64 {
		return ""
	}
	literal := text
	if strings.HasPrefix(text, "[") && strings.HasSuffix(text, "]") {
		literal = text[1 : len(text)-1]
	}
	addr, err := netip.ParseAddr(literal)
	if err != nil {
		return ""
	}
	addr = addr.WithZone("").Unmap()
	if addr.Is6() {
		b := addr.As16()
		return "ip6:" + hex.EncodeToString(b[:8]) + "::/64"
	}
	return "ip:" + addr.String()
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}
