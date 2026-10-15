package spa

import (
	"fmt"
	"log/slog"
	"slices"
	"strings"
)

// Route describes a registered route for [Mount]: its method, its pattern text, its segments
// (parameters written {name}) and who registered it.
type Route struct {
	Method, Pattern string
	Segments        []string
	Registrant      string
}

func (r Route) isParam(i int) bool { return strings.HasPrefix(r.Segments[i], "{") }

// alwaysAPI are the API prefixes whether or not a route exists under them.
var alwaysAPI = []string{"api", "auth"}

// Handler is a mounted SPA: the rules of contract section 4 in their order, with the API prefixes
// worked out from the route table.
type Handler struct {
	spa         *SPA
	apiPrefixes map[string]bool
	apiList     []string
	notBuilt    bool
	headers     [][2]string
}

// Mount checks the SPA against the route table and mounts it (contract sections 2, 3 and 5).
// registrant is the module that mounted it; environment is APP_ENV: in "local" or "test" an SPA
// without index.html starts (logging a warning) and answers the "not built" 404, anywhere else it
// is a problem. Every problem is returned; the handler is nil when there are any. line is the
// start-up log line.
func Mount(s *SPA, registrant string, routes []Route, environment string, log *slog.Logger) (h *Handler, problems []string, line string) {
	notBuilt := false
	if !s.HasIndex() {
		if environment == "local" || environment == "test" {
			log.Warn("SPA not built: run the web build; serving 404", slog.String("environment", environment))
			notBuilt = true
		} else {
			env := environment
			if env == "" {
				env = "unknown"
			}
			problems = append(problems, fmt.Sprintf("SPA not built: no index.html in %s (APP_ENV %s); run the web build before packaging. Only APP_ENV local or test start without it", s.source, env))
		}
	}

	// where each API prefix comes from, for the problems below
	origin := map[string]string{}
	var order []string
	add := func(prefix, from string) {
		if _, ok := origin[prefix]; !ok {
			order = append(order, prefix)
			origin[prefix] = from
		}
	}
	for _, a := range alwaysAPI {
		add(a, "always an API prefix")
	}
	for _, a := range s.apiPrefixes {
		add(a, "Options.APIPrefixes (module '"+registrant+"')")
	}
	k := len(s.prefix)
	for _, r := range routes {
		if len(r.Segments) < k || !covers(r, s.prefix) {
			continue
		}
		route := r.Method + " " + r.Pattern + " (module '" + r.Registrant + "')"
		atPrefix := len(r.Segments) == k || (len(r.Segments) == k+1 && r.Segments[k] == "")
		switch {
		case atPrefix:
			problems = append(problems, fmt.Sprintf("route %s shadows the SPA's index.html at %s (module '%s'); move the route under an API prefix",
				route, s.PrefixText(), registrant))
		case r.isParam(k):
			problems = append(problems, fmt.Sprintf("route %s has a parameter as its first segment under the SPA's prefix %s: every first segment would be an API prefix",
				route, s.PrefixText()))
		default:
			add(r.Segments[k], "route "+route)
		}
	}
	first := map[string]bool{}
	for name := range s.files {
		head, _, _ := strings.Cut(name, "/")
		first[head] = true
	}
	where := s.PrefixText()
	if where != "/" {
		where += "/"
	}
	for _, p := range order {
		if first[p] {
			problems = append(problems, fmt.Sprintf("API prefix '%s' (%s) hides the SPA's files under %s%s: every one of them would answer the JSON 404", p, origin[p], where, p))
		}
	}
	if len(problems) > 0 {
		return nil, problems, ""
	}
	api := slices.Clone(order)
	slices.Sort(api)
	set := map[string]bool{}
	for _, p := range api {
		set[p] = true
	}
	hs := [][2]string{
		{"X-Content-Type-Options", "nosniff"},
		{"Referrer-Policy", "same-origin"},
		{"X-Frame-Options", "DENY"},
		{"Content-Security-Policy", s.csp.value},
	}
	if s.csp.strict {
		hs = append(hs, [2]string{"Cross-Origin-Opener-Policy", "same-origin"}, [2]string{"Permissions-Policy", "camera=(), microphone=(), geolocation=()"})
	}
	status := fmt.Sprintf("%d files", len(s.files))
	if notBuilt {
		status = "not built"
	}
	line = fmt.Sprintf("SPA %s (%s): %s, API prefixes %s", s.PrefixText(), registrant, status, strings.Join(api, ", "))
	return &Handler{spa: s, apiPrefixes: set, apiList: api, notBuilt: notBuilt, headers: hs}, nil, line
}

// covers reports whether a route's first segments can match the SPA's prefix (a literal equal to
// it, or a parameter).
func covers(r Route, prefix []string) bool {
	for i, p := range prefix {
		if !r.isParam(i) && r.Segments[i] != p {
			return false
		}
	}
	return true
}

// APIPrefixes returns the API prefixes, sorted.
func (h *Handler) APIPrefixes() []string { return slices.Clone(h.apiList) }
