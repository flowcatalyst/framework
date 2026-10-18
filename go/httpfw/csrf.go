package httpfw

import (
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// CSRFGuard applies the platform's F16 rules for unsafe requests (sessions-and-login contract
// section 7.2). There is no synchronizer token: Fetch Metadata, the Origin check, SameSite=Lax and
// JSON-only bodies leave nothing for one to add. No CORS is configured anywhere in the framework,
// so no credentialed cross-origin request is ever permitted.
//
// [SessionsGuard] is applied by the sessions module to every unsafe request authenticated by the
// session cookie; [PublicFormGuard] is the variant for an application's own public unsafe routes (a
// sign-up form).
type CSRFGuard struct {
	origin                  string
	refuseWithoutProvenance bool
}

// SessionsGuard returns the guard for cookie-authenticated requests: Sec-Fetch-Site must be
// same-origin or none, else Origin must be the origin of publicBaseURL, and a request with neither
// header is refused (403 CROSS_ORIGIN_REQUEST); then a body must be JSON (415).
func SessionsGuard(publicBaseURL *url.URL) *CSRFGuard {
	return &CSRFGuard{origin: OriginOf(publicBaseURL), refuseWithoutProvenance: true}
}

// PublicFormGuard returns the guard for an application's public unsafe routes (the platform's
// CredentialCSRF rule): the provenance check as above, but a request with neither header passes,
// and the body must be JSON for every caller.
func PublicFormGuard(publicBaseURL *url.URL) *CSRFGuard {
	return &CSRFGuard{origin: OriginOf(publicBaseURL)}
}

// Check applies the rules to a request; a safe method (GET, HEAD, OPTIONS) always passes. It
// returns 403 CROSS_ORIGIN_REQUEST or 415 UNSUPPORTED_MEDIA_TYPE errors; a refusal is logged at
// INFO with the method, the path and the two header values (cut and cleaned).
func (g *CSRFGuard) Check(r *http.Request) error {
	if !UnsafeMethod(r.Method) {
		return nil
	}
	site := r.Header.Get("Sec-Fetch-Site")
	sent := r.Header.Get("Origin")
	var allowed bool
	switch {
	case site != "":
		s := strings.ToLower(strings.TrimSpace(site))
		allowed = s == "same-origin" || s == "none"
	case sent != "":
		allowed = g.origin == normalisedOrigin(sent)
	default:
		allowed = !g.refuseWithoutProvenance
	}
	if !allowed {
		if st, ok := r.Context().Value(stateKey{}).(*reqState); ok {
			st.logger.Info("refused a cross-origin request", slog.String("method", r.Method),
				slog.String("path", r.URL.Path), slog.String("sec_fetch_site", logSafe(site)), slog.String("origin", logSafe(sent)))
		}
		return NewError(http.StatusForbidden, "CROSS_ORIGIN_REQUEST", "cross-origin requests are not allowed here")
	}
	if hasBody(r) && !isJSON(r.Header.Get("Content-Type")) {
		return NewError(http.StatusUnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "content type must be application/json")
	}
	return nil
}

// Middleware is the guard as route or global middleware, for public routes.
func (g *CSRFGuard) Middleware() Middleware {
	return func(next HandlerFunc) HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			if err := g.Check(r); err != nil {
				return err
			}
			return next(w, r)
		}
	}
}

// UnsafeMethod reports whether a method changes state in the CSRF sense: anything but GET, HEAD
// and OPTIONS.
func UnsafeMethod(method string) bool {
	return method != http.MethodGet && method != http.MethodHead && method != http.MethodOptions
}

// hasBody reports a request that has a body: a declared length above zero, a Content-Length header
// above zero (or that cannot be read), or a Transfer-Encoding.
func hasBody(r *http.Request) bool {
	if r.ContentLength > 0 {
		return true
	}
	if length := r.Header.Get("Content-Length"); length != "" {
		n, err := strconv.ParseInt(strings.TrimSpace(length), 10, 64)
		if err != nil || n > 0 {
			return true
		}
	}
	return r.Header.Get("Transfer-Encoding") != "" || len(r.TransferEncoding) > 0
}

// isJSON accepts application/json and application/*+json.
func isJSON(contentType string) bool {
	if contentType == "" {
		return false
	}
	media, _, err := mime.ParseMediaType(contentType)
	if err != nil {
		media = strings.ToLower(strings.TrimSpace(strings.SplitN(contentType, ";", 2)[0]))
	}
	return media == "application/json" || (strings.HasPrefix(media, "application/") && strings.HasSuffix(media, "+json"))
}

// OriginOf is the origin of a URL as browsers compare it: scheme and host in lower case, and the
// port unless it is the scheme's default.
func OriginOf(u *url.URL) string {
	scheme := strings.ToLower(u.Scheme)
	host := strings.ToLower(u.Hostname())
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	port := u.Port()
	if port == "" || (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		return scheme + "://" + host
	}
	return scheme + "://" + host + ":" + port
}

// normalisedOrigin parses an Origin header value; "" when it is not an origin (no scheme or host,
// or a path), which never equals a configured one.
func normalisedOrigin(value string) string {
	u, err := url.Parse(strings.TrimSpace(value))
	if err != nil || u.Scheme == "" || u.Hostname() == "" || u.Path != "" {
		return ""
	}
	return OriginOf(u)
}

// logSafe cuts a header value to 100 characters and replaces control characters, for a log line.
func logSafe(value string) string {
	if value == "" {
		return "-"
	}
	if len(value) > 100 {
		value = value[:100]
	}
	return strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return '?'
		}
		return r
	}, value)
}
