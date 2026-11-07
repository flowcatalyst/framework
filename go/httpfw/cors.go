package httpfw

import (
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

// The defaults of the http.cors.* settings.
var (
	corsMethods = []string{"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"}
	corsHeaders = []string{"Authorization", "Content-Type", "DPoP", "Idempotency-Key", "Last-Event-ID", correlationHeader}
	corsExposed = []string{correlationHeader, "Idempotency-Replayed"}
)

// corsSettings are the http.cors.* settings.
type corsSettings struct {
	origins     config.Handle[[]string]
	methods     config.Handle[[]string]
	headers     config.Handle[[]string]
	exposed     config.Handle[[]string]
	credentials config.Handle[bool]
	maxAge      config.Handle[time.Duration]
}

func declareCORS(m config.Declarer) corsSettings {
	return corsSettings{
		origins: config.Declare(m, config.StringList("http.cors.allowed-origins",
			"Origins (scheme://host[:port]) whose pages may call this API from a browser, or * for any; empty turns CORS off.").
			WithDefault([]string{}).Checked(checkOrigins)),
		methods: config.Declare(m, config.StringList("http.cors.allowed-methods", "Methods a cross-origin request may use.").
			WithDefault(corsMethods).Checked(checkMethods)),
		headers: config.Declare(m, config.StringList("http.cors.allowed-headers", "Request headers a cross-origin request may send.").
			WithDefault(corsHeaders).Checked(checkTokens)),
		exposed: config.Declare(m, config.StringList("http.cors.exposed-headers", "Response headers a cross-origin page may read.").
			WithDefault(corsExposed).Checked(checkTokens)),
		credentials: config.Declare(m, config.Bool("http.cors.allow-credentials",
			"Allow cross-origin requests with credentials (cookies); not with the origin *.").WithDefault(false)),
		maxAge: config.Declare(m, config.Duration("http.cors.max-age", "How long a browser may keep a preflight's answer.").
			WithDefault(10*time.Minute).Checked(config.Between(time.Duration(0), 24*time.Hour))),
	}
}

func checkOrigins(origins []string) string {
	for _, o := range origins {
		if o == "*" {
			if len(origins) > 1 {
				return "* allows any origin and cannot be listed with others"
			}
			continue
		}
		u, err := url.Parse(o)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || o != u.Scheme+"://"+u.Host {
			return "'" + o + "' is not an origin: scheme://host[:port], nothing after it"
		}
	}
	return ""
}

func checkMethods(methods []string) string {
	for _, m := range methods {
		if !isMethod(m) {
			return "'" + m + "' is not one of GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS"
		}
	}
	return ""
}

func checkTokens(names []string) string {
	for _, n := range names {
		if n == "" || strings.ContainsFunc(n, func(r rune) bool {
			return r <= ' ' || r > '~' || strings.ContainsRune(`"(),/:;<=>?@[\]{}`, r)
		}) {
			return "'" + n + "' is not a header name"
		}
	}
	return ""
}

// cors answers cross-origin requests from the configured origins (nil: CORS is off).
type cors struct {
	any         bool
	origins     []string
	methods     string
	headers     string
	exposed     string
	credentials bool
	maxAge      string
}

// newCORS reads the settings; nil when no origin is configured. A problem names the setting.
func newCORS(origins, methods, headers, exposed []string, credentials bool, maxAge time.Duration) (*cors, string) {
	if len(origins) == 0 {
		return nil, ""
	}
	c := &cors{
		any: slices.Contains(origins, "*"), origins: origins, methods: strings.Join(methods, ", "),
		headers: strings.Join(headers, ", "), exposed: strings.Join(exposed, ", "), credentials: credentials,
		maxAge: strconv.FormatInt(int64(maxAge/time.Second), 10),
	}
	if c.any && credentials {
		return nil, "http.cors.allow-credentials cannot be true with the origin *: name the origins"
	}
	return c, ""
}

func (c *cors) allows(origin string) bool {
	return origin != "" && (c.any || slices.Contains(c.origins, origin))
}

// step is the CORS step of the dispatcher: outside the application's middleware and context
// resolution, so a preflight is answered without authenticating it (a browser sends no credentials
// with one). A request from another origin, or without an Origin, passes through untouched.
func (c *cors) step(next HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		h := w.Header()
		h.Add("Vary", "Origin")
		origin := r.Header.Get("Origin")
		if !c.allows(origin) {
			return next(w, r)
		}
		allow := origin
		if c.any {
			allow = "*"
		}
		h.Set("Access-Control-Allow-Origin", allow)
		if c.credentials {
			h.Set("Access-Control-Allow-Credentials", "true")
		}
		if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
			stateOf(r).route = "(preflight)"
			h.Set("Access-Control-Allow-Methods", c.methods)
			if c.headers != "" {
				h.Set("Access-Control-Allow-Headers", c.headers)
			}
			h.Set("Access-Control-Max-Age", c.maxAge)
			w.WriteHeader(http.StatusNoContent)
			return nil
		}
		if c.exposed != "" {
			h.Set("Access-Control-Expose-Headers", c.exposed)
		}
		return next(w, r)
	}
}

// withCORS gives the dispatcher the CORS step built from the http.cors.* settings.
func withCORS(c *cors) Option { return func(o *options) { o.cors = c } }
