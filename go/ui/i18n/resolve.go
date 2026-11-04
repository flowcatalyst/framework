package i18n

import (
	"net/http"
	"slices"
	"sort"
	"strconv"
	"strings"
)

// Config configures [Middleware] (contract section 3).
type Config struct {
	// Supported are the tags the application ships; "en" is always added.
	Supported []string
	// User is the user's preference (for FlowCatalyst applications the
	// principal's locale), or nil. It returns "" for none.
	User func(*http.Request) string
	// Tenant is the tenant's default (the client's default locale), or nil.
	Tenant func(*http.Request) string
	// Bundle is the application's catalogs layered over the kit's; nil is the
	// kit's own.
	Bundle *Bundle
}

// Resolver resolves the locale of a request: the user's preference, the
// tenant's default, Accept-Language by quality, then English, keeping the
// first that names a language the application supports. The result is one of
// the supported tags: the one equal to the candidate, else the first
// supported tag of the candidate's language.
type Resolver struct {
	supported []string
	user      func(*http.Request) string
	tenant    func(*http.Request) string
}

// NewResolver builds the resolver of a configuration.
func NewResolver(cfg Config) *Resolver {
	var sup []string
	for _, t := range cfg.Supported {
		if n := NormalizeTag(t); n != "" && !slices.Contains(sup, n) {
			sup = append(sup, n)
		}
	}
	if !slices.Contains(sup, DefaultLocale) {
		sup = append(sup, DefaultLocale)
	}
	return &Resolver{supported: sup, user: cfg.User, tenant: cfg.Tenant}
}

// Supported are the tags the resolver can return.
func (r *Resolver) Supported() []string { return slices.Clone(r.supported) }

// Match is the supported tag a candidate names (the full tag, then its
// language), or "" for none.
func (r *Resolver) Match(candidate string) string {
	tag := NormalizeTag(candidate)
	if tag == "" {
		return ""
	}
	if slices.Contains(r.supported, tag) {
		return tag
	}
	lang := Language(tag)
	for _, s := range r.supported {
		if Language(s) == lang {
			return s
		}
	}
	return ""
}

// Resolve is the request's locale.
func (r *Resolver) Resolve(req *http.Request) string {
	if r.user != nil {
		if t := r.Match(r.user(req)); t != "" {
			return t
		}
	}
	if r.tenant != nil {
		if t := r.Match(r.tenant(req)); t != "" {
			return t
		}
	}
	for _, c := range ParseAcceptLanguage(req.Header.Get("Accept-Language")) {
		if t := r.Match(c); t != "" {
			return t
		}
	}
	return DefaultLocale
}

// ParseAcceptLanguage lists the language tags of an Accept-Language header in
// quality order (ties keep their order); q=0 entries, "*" and malformed tags
// are left out.
func ParseAcceptLanguage(header string) []string {
	type entry struct {
		tag string
		q   float64
	}
	var es []entry
	for part := range strings.SplitSeq(header, ",") {
		tag, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		tag = strings.TrimSpace(tag)
		if tag == "" || tag == "*" {
			continue
		}
		q := 1.0
		for p := range strings.SplitSeq(params, ";") {
			k, v, ok := strings.Cut(strings.TrimSpace(p), "=")
			if ok && strings.EqualFold(strings.TrimSpace(k), "q") {
				f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
				if err != nil || f < 0 || f > 1 {
					q = -1
				} else {
					q = f
				}
			}
		}
		n := NormalizeTag(tag)
		if n == "" || q <= 0 {
			continue
		}
		es = append(es, entry{n, q})
	}
	sort.SliceStable(es, func(i, j int) bool { return es[i].q > es[j].q })
	out := make([]string, len(es))
	for i, e := range es {
		out[i] = e.tag
	}
	return out
}

// Middleware resolves the request's locale once and stores it, with the
// bundle, in the request context for [FromContext] and [T].
func Middleware(cfg Config) func(http.Handler) http.Handler {
	res := NewResolver(cfg)
	bundle := cfg.Bundle
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ctx := WithLocale(r.Context(), res.Resolve(r))
			if bundle != nil {
				ctx = WithBundle(ctx, bundle)
			}
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}
