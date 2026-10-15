package httpfw

import (
	"fmt"
	"log/slog"
	"net/http"
	"slices"
	"sync"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/metrics"
)

// HandlerFunc is the one handler shape: write the response to w and return nil, or return an error
// for the error-mapping middleware to answer (a [ServiceError] with its envelope, anything else as
// 500 INTERNAL; the cause is logged with the correlation id and never returned).
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// ContextHandlerFunc is a handler for a route that requires a context: the framework resolved the
// fw.Context (principal, client, permissions) before calling it.
type ContextHandlerFunc func(w http.ResponseWriter, r *http.Request, cx fw.Context) error

// Middleware wraps a handler. Application middleware runs inside the framework's own (correlation
// id, error mapping, access log) and, on a route, after context resolution. It may answer without
// calling next.
type Middleware func(next HandlerFunc) HandlerFunc

// ContextResolver turns a request into the fw.Context a route requires. It returns a
// 401 UNAUTHENTICATED [Error] when it cannot (the session and bearer resolver of sessions-and-login
// is one).
type ContextResolver interface {
	Resolve(r *http.Request) (fw.Context, error)
}

// RouteOption changes how a route is registered.
type RouteOption interface{ apply(*routeOptions) }

type routeOptions struct {
	requiresContext bool
	middleware      []Middleware
}

type routeOptionFunc func(*routeOptions)

func (f routeOptionFunc) apply(o *routeOptions) { f(o) }

// RequiresContext resolves the fw.Context before the handler runs (ContextOf reads it).
func RequiresContext() RouteOption {
	return routeOptionFunc(func(o *routeOptions) { o.requiresContext = true })
}

// With adds route middleware, inside the global middleware and after context resolution.
func With(mw ...Middleware) RouteOption {
	return routeOptionFunc(func(o *routeOptions) { o.middleware = append(o.middleware, mw...) })
}

type registration struct {
	pattern    routePattern
	registrant string
	handler    HandlerFunc
	opts       routeOptions
}

type spaMount struct {
	registrant string
	spa        *spa.SPA
}

type binding struct {
	registrant string
	desc       *opDesc
	mismatch   bool
}

// Routes is a module's route table. Several tables (one per module) are checked together when the
// server is built ([Compile], [Serve]); registering after that panics.
type Routes struct {
	module string
	mu     sync.Mutex
	sealed bool
	regs   []registration
	global []Middleware
	spas   []spaMount
	binds  []binding
	issues []string
}

// NewRoutes returns an empty table whose registrations are attributed to a module id (named in
// duplicate-route problems).
func NewRoutes(module string) *Routes { return &Routes{module: module} }

func (rt *Routes) mustBeOpen() {
	if rt.sealed {
		panic("httpfw: routes cannot be registered after the server was built")
	}
}

// Handle registers a route: "POST /orders/{id}/lines". The method is GET, HEAD, POST, PUT, PATCH,
// DELETE or OPTIONS; segments are literals or {name} parameters (name [a-z][A-Za-z0-9]*), no
// wildcards. A malformed pattern or a duplicate (same method and shape, parameter names ignored)
// is a start-up problem, reported when the table is compiled.
func (rt *Routes) Handle(pattern string, h HandlerFunc, opts ...RouteOption) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.mustBeOpen()
	p, err := parsePattern(pattern)
	if err != nil {
		rt.issues = append(rt.issues, err.Error())
		return
	}
	var ro routeOptions
	for _, o := range opts {
		o.apply(&ro)
	}
	rt.regs = append(rt.regs, registration{pattern: p, registrant: rt.module, handler: h, opts: ro})
}

// HandleContext registers a route that requires a fw.Context: the ContextResolver supplies it. A
// table with such a route and no resolver is a start-up problem naming the route.
func (rt *Routes) HandleContext(pattern string, h ContextHandlerFunc, opts ...RouteOption) {
	rt.Handle(pattern, func(w http.ResponseWriter, r *http.Request) error {
		return h(w, r, ContextOf(r))
	}, append([]RouteOption{RequiresContext()}, opts...)...)
}

// Use adds global middleware, run in registration order inside the framework's own and outside
// context resolution.
func (rt *Routes) Use(mw Middleware) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.mustBeOpen()
	rt.global = append(rt.global, mw)
}

// SPA mounts the application's single-page application as the router's fallback: a request whose
// path matches no route is answered by it (static-assets contract). One per application.
func (rt *Routes) SPA(s *spa.SPA) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.mustBeOpen()
	rt.spas = append(rt.spas, spaMount{registrant: rt.module, spa: s})
}

// Option configures [Compile].
type Option func(*options)

type options struct {
	environment string
	maxBody     int64
	resolver    ContextResolver
	logger      *slog.Logger
	clock       fw.Clock
	metrics     *metrics.Registry
	declared    []Described
	declaredSet bool
}

// DefaultMaxBodyBytes is http.max-body-bytes' default.
const DefaultMaxBodyBytes = 1 << 20

// WithEnvironment sets APP_ENV, which decides whether an SPA without index.html may start (local,
// test). Unset, a missing index.html is a start-up problem.
func WithEnvironment(env string) Option { return func(o *options) { o.environment = env } }

// WithMaxBodyBytes sets the largest request body accepted (default [DefaultMaxBodyBytes]).
func WithMaxBodyBytes(n int64) Option { return func(o *options) { o.maxBody = n } }

// WithResolver sets the ContextResolver for routes that require a context.
func WithResolver(r ContextResolver) Option { return func(o *options) { o.resolver = r } }

// WithLogger sets the logger for the access log and for failures (default: discard).
func WithLogger(l *slog.Logger) Option { return func(o *options) { o.logger = l } }

// WithClock sets the clock access-log durations are measured with (default fw.SystemClock).
func WithClock(c fw.Clock) Option { return func(o *options) { o.clock = c } }

// WithMetrics records fw_http_server_requests_total and fw_http_server_request_duration_seconds in
// the registry.
func WithMetrics(m *metrics.Registry) Option { return func(o *options) { o.metrics = m } }

// WithDeclared tells Compile which operations the application declared, so it can check that each
// is bound exactly once and that every bound one was declared.
func WithDeclared(ops ...Described) Option {
	return func(o *options) { o.declared, o.declaredSet = append(o.declared, ops...), true }
}

// Compile checks the tables together, seals them and builds the dispatcher. Every problem is
// reported at once as a *fw.StartupError: a malformed or duplicate route, a route that requires a
// context with no resolver, an operation bound twice, undeclared or declared but not bound, and
// the SPA's own checks.
func Compile(tables []*Routes, opts ...Option) (*Dispatcher, error) {
	o := options{maxBody: DefaultMaxBodyBytes}
	for _, f := range opts {
		f(&o)
	}
	if o.logger == nil {
		o.logger = slog.New(slog.DiscardHandler)
	}
	if o.clock == nil {
		o.clock = fw.SystemClock()
	}
	var (
		regs     []registration
		global   []Middleware
		spas     []spaMount
		binds    []binding
		problems []fw.Problem
	)
	for _, t := range tables {
		t.mu.Lock()
		t.sealed = true
		regs = append(regs, t.regs...)
		global = append(global, t.global...)
		spas = append(spas, t.spas...)
		binds = append(binds, t.binds...)
		for _, msg := range t.issues {
			problems = append(problems, config.Problem{Module: t.module, Message: msg})
		}
		t.mu.Unlock()
	}
	problems = append(problems, checkOperations(o, binds)...)

	rt := newRouter()
	var routes []*compiled
	var lines []string
	seen := map[string]registration{}
	for _, r := range regs {
		key := r.pattern.method + " " + r.pattern.shape()
		label := r.pattern.method + " " + r.pattern.text
		if first, dup := seen[key]; dup {
			problems = append(problems, config.Problem{Module: r.registrant, Key: label, Message: fmt.Sprintf(
				"duplicate route: %s (module '%s') collides with %s %s (module '%s')",
				label, r.registrant, first.pattern.method, first.pattern.text, first.registrant)})
			continue
		}
		seen[key] = r
		if r.opts.requiresContext && o.resolver == nil {
			problems = append(problems, config.Problem{
				Module: r.registrant, Key: label,
				Message: "route " + label + " requires a context but no ContextResolver is provided",
			})
			continue
		}
		h := r.handler
		for _, mw := range slices.Backward(r.opts.middleware) {
			h = mw(h)
		}
		c := &compiled{pattern: r.pattern, registrant: r.registrant, requiresContext: r.opts.requiresContext, handler: h}
		rt.add(c)
		routes = append(routes, c)
		lines = append(lines, label+" ("+r.registrant+")")
	}

	var spaHandler *spa.Handler
	if len(spas) > 0 {
		first := spas[0]
		for _, other := range spas[1:] {
			problems = append(problems, config.Problem{Module: other.registrant, Key: "spa", Message: fmt.Sprintf(
				"a second SPA: module '%s' mounts one at %s but module '%s' already mounted one at %s; an application serves one SPA",
				other.registrant, other.spa.PrefixText(), first.registrant, first.spa.PrefixText())})
		}
		infos := make([]spa.Route, len(routes))
		for i, c := range routes {
			infos[i] = spa.Route{Method: c.pattern.method, Pattern: c.pattern.text, Segments: c.pattern.segs, Registrant: c.registrant}
		}
		h, ps, line := spa.Mount(first.spa, first.registrant, infos, o.environment, o.logger)
		for _, p := range ps {
			problems = append(problems, config.Problem{Module: first.registrant, Key: "spa", Message: p})
		}
		if h != nil {
			spaHandler = h
			lines = append(lines, line)
		}
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	d := &Dispatcher{router: rt, global: global, spa: spaHandler, lines: lines, opts: o}
	if o.metrics != nil {
		var err error
		d.requests, err = o.metrics.Counter("fw_http_server_requests_total", "HTTP requests by method, route pattern and status.", "method", "route", "status")
		if err == nil {
			d.durations, err = o.metrics.Timer("fw_http_server_request_duration_seconds", "HTTP request duration by method and route pattern.", "method", "route")
		}
		if err != nil {
			return nil, &fw.StartupError{Problems: []fw.Problem{{Module: "http", Message: err.Error()}}}
		}
	}
	return d, nil
}

// RouteLines returns one line per route and the SPA, as logged at start-up.
func (d *Dispatcher) RouteLines() []string { return append([]string(nil), d.lines...) }
