package ratelimit_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/metrics"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

type rig struct {
	t       *testing.T
	d       *httpfw.Dispatcher
	handled atomic.Int32
}

type resolver func(r *http.Request) (fw.Context, error)

func (f resolver) Resolve(r *http.Request) (fw.Context, error) { return f(r) }

// newRig serves GET /x behind the middleware as route middleware, and GET /g behind it as global.
func newRig(t *testing.T, mw httpfw.Middleware, requiresContext bool) *rig {
	t.Helper()
	g := &rig{t: t}
	rt := httpfw.NewRoutes("test")
	h := func(w http.ResponseWriter, _ *http.Request) error {
		g.handled.Add(1)
		httpfw.Text(w, 200, "ok")
		return nil
	}
	opts := []httpfw.RouteOption{httpfw.With(mw)}
	if requiresContext {
		opts = append(opts, httpfw.RequiresContext())
	}
	rt.Handle("GET /x", h, opts...)
	d, err := httpfw.Compile([]*httpfw.Routes{rt}, httpfw.WithResolver(resolver(func(*http.Request) (fw.Context, error) {
		return fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("u1")), nil
	})))
	if err != nil {
		t.Fatal(err)
	}
	g.d = d
	return g
}

type reply struct {
	status int
	h      http.Header
	body   string
}

func (g *rig) get(remote string, headers ...string) reply {
	g.t.Helper()
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	if remote != "" {
		r.RemoteAddr = remote
	}
	for i := 0; i+1 < len(headers); i += 2 {
		r.Header.Add(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	g.d.ServeHTTP(rec, r)
	b, _ := io.ReadAll(rec.Result().Body)
	return reply{rec.Code, rec.Result().Header, string(b)}
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func counter(reg *metrics.Registry, name string) float64 {
	var sum float64
	for _, f := range reg.Snapshot().Families {
		if f.Name == name {
			for _, s := range f.Samples {
				sum += s.Value
			}
		}
	}
	return sum
}

func TestALimitedRequestIs429WithRetryAfterAndTheHandlerDoesNotRun(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	reg := must(metrics.NewRegistry())
	mw := must(ratelimit.Middleware(memory(t, clock, 10, 10*time.Second, 2), ratelimit.ClientAddress(), ratelimit.WithMetrics(reg)))
	g := newRig(t, mw, false)
	first := g.get("")
	if first.status != 200 || first.h.Get("RateLimit-Limit") != "2" || first.h.Get("RateLimit-Remaining") != "1" || first.h.Get("RateLimit-Reset") != "1" {
		t.Fatalf("first: %d %v", first.status, first.h)
	}
	if g.get("").status != 200 {
		t.Fatal("second request limited")
	}
	limited := g.get("")
	if limited.status != 429 || limited.h.Get("Retry-After") != "1" || limited.h.Get("RateLimit-Remaining") != "0" {
		t.Fatalf("limited: %d %v", limited.status, limited.h)
	}
	if limited.body != `{"error":"RATE_LIMITED","message":"too many requests"}` {
		t.Fatalf("body = %s", limited.body)
	}
	if g.handled.Load() != 2 {
		t.Fatalf("handler ran %d times, want 2", g.handled.Load())
	}
	if g.get("10.0.0.2:5555").status != 200 { // another address has its own budget
		t.Fatal("another address shares the budget")
	}
	if n := counter(reg, "fw_http_rate_limited_total"); n != 1 {
		t.Fatalf("fw_http_rate_limited_total = %v, want 1", n)
	}
}

func TestRetryAfterIsRoundedUpToWholeSecondsAndHeadersCanBeOff(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	mw := must(ratelimit.Middleware(memory(t, clock, 1, 2500*time.Millisecond, 1), ratelimit.ClientAddress(), ratelimit.WithHeaders(false)))
	g := newRig(t, mw, false)
	if g.get("").h.Get("RateLimit-Limit") != "" {
		t.Fatal("headers off, but RateLimit-Limit sent")
	}
	limited := g.get("")
	if limited.h.Get("Retry-After") != "3" || limited.h.Get("RateLimit-Limit") != "" {
		t.Fatalf("limited headers %v", limited.h)
	}
}

func TestRequestsWithoutKeyMaterialShareOneBucketInsteadOfEscapingTheLimit(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	mw := must(ratelimit.Middleware(memory(t, clock, 1, time.Minute, 1), ratelimit.Header("X-Api-Key")))
	g := newRig(t, mw, false)
	want := []struct {
		headers []string
		status  int
	}{
		{nil, 200}, {nil, 429}, {[]string{"X-Api-Key", ""}, 429}, {[]string{"X-Api-Key", "k1"}, 200}, {[]string{"X-Api-Key", "k1"}, 429},
	}
	for i, w := range want {
		if got := g.get("", w.headers...).status; got != w.status {
			t.Fatalf("request %d: %d, want %d", i+1, got, w.status)
		}
	}
}

type brokenLimiter struct{}

func (brokenLimiter) Name() string             { return "broken" }
func (brokenLimiter) Policy() ratelimit.Policy { return ratelimit.MustPolicy(1, time.Second, 1) }
func (brokenLimiter) Acquire(context.Context, string, int) (ratelimit.Decision, error) {
	return nil, fmt.Errorf("%w: down", ratelimit.ErrStore)
}

func TestStoreFailuresFollowThePolicy(t *testing.T) {
	reg := must(metrics.NewRegistry())
	allow := newRig(t, must(ratelimit.Middleware(brokenLimiter{}, ratelimit.ClientAddress(), ratelimit.WithMetrics(reg))), false)
	if allow.get("").status != 200 || allow.handled.Load() != 1 {
		t.Fatal("ALLOW: the request was not let through")
	}
	deny := newRig(t, must(ratelimit.Middleware(brokenLimiter{}, ratelimit.ClientAddress(), ratelimit.WithOnStoreFailure(ratelimit.Deny))), false)
	r := deny.get("")
	if r.status != 503 || !strings.Contains(r.body, `"UNAVAILABLE"`) || deny.handled.Load() != 0 {
		t.Fatalf("DENY: %d %s handled=%d", r.status, r.body, deny.handled.Load())
	}
	if n := counter(reg, "fw_http_rate_limit_store_failures_total"); n != 1 {
		t.Fatalf("store failures counted %v, want 1", n)
	}
}

type captured struct{ lines atomic.Int32 }

func (c *captured) Enabled(context.Context, slog.Level) bool  { return true }
func (c *captured) Handle(context.Context, slog.Record) error { c.lines.Add(1); return nil }
func (c *captured) WithAttrs([]slog.Attr) slog.Handler        { return c }
func (c *captured) WithGroup(string) slog.Handler             { return c }

func TestAStoreFailureIsLoggedAtMostOncePerMinute(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	sink := &captured{}
	mw := must(ratelimit.Middleware(brokenLimiter{}, ratelimit.ClientAddress(),
		ratelimit.WithLogger(slog.New(sink)), ratelimit.WithClock(clock)))
	g := newRig(t, mw, false)
	for range 5 {
		g.get("")
	}
	if sink.lines.Load() != 1 {
		t.Fatalf("%d warnings in one minute, want 1", sink.lines.Load())
	}
	clock.Advance(time.Minute)
	g.get("")
	if sink.lines.Load() != 2 {
		t.Fatalf("%d warnings after a minute, want 2", sink.lines.Load())
	}
}

func TestLongKeysAreHashedAndEmptyOnesShareTheBucket(t *testing.T) {
	// observed through the limiter: the key the middleware hands it
	rec := &recordingLimiter{}
	long := ratelimit.KeyFunc(func(*http.Request) string { return strings.Repeat("k", 500) })
	for _, c := range []struct {
		keys ratelimit.KeyFunc
		want func(string) bool
		desc string
	}{
		{long, func(k string) bool { return strings.HasPrefix(k, "sha256:") && len(k) == 7+64 }, "a 500-character key is replaced by its SHA-256"},
		{func(*http.Request) string { return "" }, func(k string) bool { return k == "-" }, "no key is the shared bucket"},
		{func(*http.Request) string { return "short" }, func(k string) bool { return k == "short" }, "a short key is kept"},
		{func(*http.Request) string { return strings.Repeat("k", 200) }, func(k string) bool { return k == strings.Repeat("k", 200) }, "200 characters are kept"},
		{func(*http.Request) string { return strings.Repeat("k", 201) }, func(k string) bool { return strings.HasPrefix(k, "sha256:") }, "201 characters are hashed"},
	} {
		g := newRig(t, must(ratelimit.Middleware(rec, c.keys)), false)
		g.get("")
		if !c.want(rec.last.Load().(string)) {
			t.Errorf("%s: got %q", c.desc, rec.last.Load())
		}
	}
}

type recordingLimiter struct{ last atomic.Value }

func (r *recordingLimiter) Name() string             { return "rec" }
func (r *recordingLimiter) Policy() ratelimit.Policy { return ratelimit.MustPolicy(5, time.Second, 5) }

func (r *recordingLimiter) Acquire(_ context.Context, key string, _ int) (ratelimit.Decision, error) {
	r.last.Store(key)
	return ratelimit.Allowed{Remaining: 4, ResetAfter: time.Second}, nil
}

// --- key resolvers ------------------------------------------------------------------------------

func keyOf(k ratelimit.KeyFunc, remote string, headers ...string) string {
	r := httptest.NewRequest(http.MethodGet, "/k", nil)
	r.RemoteAddr = remote
	for i := 0; i+1 < len(headers); i += 2 {
		r.Header.Add(headers[i], headers[i+1])
	}
	return k(r)
}

func TestClientAddressNeverReadsForwardingHeaders(t *testing.T) {
	if got := keyOf(ratelimit.ClientAddress(), "203.0.113.7:4000", "X-Forwarded-For", "198.51.100.1"); got != "ip:203.0.113.7" {
		t.Fatalf("got %q", got)
	}
	if got := keyOf(ratelimit.ClientAddress(), "", ""); got != "" {
		t.Fatalf("no peer address: got %q, want no key", got)
	}
	if got := keyOf(ratelimit.ClientAddress(), "garbage"); got != "" {
		t.Fatalf("an unparsable peer: got %q, want no key", got)
	}
}

func TestForwardedForTakesOnlyTheEntryTheTrustedProxiesWrote(t *testing.T) {
	one := must(ratelimit.ForwardedFor(1))
	two := must(ratelimit.ForwardedFor(2))
	const peer = "10.0.0.1:80"
	for _, c := range []struct {
		name    string
		k       ratelimit.KeyFunc
		headers []string
		want    string
	}{
		{"the load balancer appended the address it saw", one, []string{"X-Forwarded-For", "1.1.1.1, 203.0.113.9"}, "ip:203.0.113.9"},
		{"several header lines are one list", one, []string{"X-Forwarded-For", "1.1.1.1", "X-Forwarded-For", "203.0.113.9"}, "ip:203.0.113.9"},
		{"two proxies", two, []string{"X-Forwarded-For", "6.6.6.6, 203.0.113.9, 10.0.0.5"}, "ip:203.0.113.9"},
		{"too few entries", two, []string{"X-Forwarded-For", "203.0.113.9"}, "ip:10.0.0.1"},
		{"garbage", one, []string{"X-Forwarded-For", "not-an-ip"}, "ip:10.0.0.1"},
		{"a name is never resolved", one, []string{"X-Forwarded-For", "localhost"}, "ip:10.0.0.1"},
		{"no header", one, nil, "ip:10.0.0.1"},
	} {
		if got := keyOf(c.k, peer, c.headers...); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
	for _, n := range []int{0, 11, -1} {
		if _, err := ratelimit.ForwardedFor(n); err == nil {
			t.Errorf("ForwardedFor(%d) accepted", n)
		}
	}
}

func TestIPv6AddressesAreGroupedByTheirSlash64(t *testing.T) {
	a := keyOf(ratelimit.ClientAddress(), "[2001:db8:1:2:aaaa::1]:80")
	b := keyOf(ratelimit.ClientAddress(), "[2001:db8:1:2:bbbb:cccc:dddd:eeee]:80")
	c := keyOf(ratelimit.ClientAddress(), "[2001:db8:1:3::1]:80")
	if a != b || a != "ip6:20010db800010002::/64" {
		t.Fatalf("a=%q b=%q", a, b)
	}
	if c == a {
		t.Fatal("another /64 shares the key")
	}
	if got := keyOf(must(ratelimit.ForwardedFor(1)), "10.0.0.1:80", "X-Forwarded-For", "[2001:db8:1:2::9]"); got != a {
		t.Fatalf("bracketed forwarded address: %q, want %q", got, a)
	}
	if got := keyOf(ratelimit.ClientAddress(), "[::ffff:203.0.113.7]:80"); got != "ip:203.0.113.7" {
		t.Fatalf("an IPv4-mapped address: %q", got)
	}
}

func TestHeaderValuesAreHashedNeverStored(t *testing.T) {
	k := keyOf(ratelimit.Header("X-Api-Key"), "10.0.0.1:80", "X-Api-Key", "sk_live_secret")
	if !strings.HasPrefix(k, "h:") || strings.Contains(k, "secret") || len(k) != 2+64 {
		t.Fatalf("key %q", k)
	}
}

func TestClientAndPrincipalNeedARouteContext(t *testing.T) {
	rec := &keyRecorder{}
	capture := func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			rec.client, rec.principal = ratelimit.Client()(r), ratelimit.Principal()(r)
			return next(w, r)
		}
	}
	g := newRig(t, capture, true)
	g.get("")
	if rec.client != "c:acme" || rec.principal != "p:u1" {
		t.Fatalf("with a context: %q, %q", rec.client, rec.principal)
	}
	g2 := newRig(t, capture, false)
	g2.get("")
	if rec.client != "" || rec.principal != "" {
		t.Fatalf("without a context: %q, %q", rec.client, rec.principal)
	}
}

type keyRecorder struct{ client, principal string }

// --- per-request policies -----------------------------------------------------------------------

type plane struct{ m map[string]ratelimit.Policy }

func (p *plane) fn() ratelimit.PolicyFunc {
	return func(r *http.Request) (ratelimit.Policy, bool, error) {
		ing := r.Header.Get("X-Ingestion")
		if ing == "boom" {
			return ratelimit.Policy{}, false, errors.New("control plane cache not loaded")
		}
		pol, ok := p.m[ing]
		return pol, ok, nil
	}
}

func TestTheResolversPolicyDecidesEachRequestAndTheHeadersReportIt(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	reg := must(metrics.NewRegistry())
	pl := &plane{m: map[string]ratelimit.Policy{"a": ratelimit.MustPolicy(10, time.Minute, 3)}}
	mw := must(ratelimit.Middleware(memory(t, clock, 1, time.Minute, 1), ratelimit.Header("X-Ingestion"),
		ratelimit.WithPolicy(pl.fn()), ratelimit.WithMetrics(reg)))
	g := newRig(t, mw, false)
	for remaining := 2; remaining >= 0; remaining-- {
		r := g.get("", "X-Ingestion", "a")
		if r.status != 200 || r.h.Get("RateLimit-Limit") != "3" || r.h.Get("RateLimit-Remaining") != fmt.Sprint(remaining) {
			t.Fatalf("remaining %d: %d %v", remaining, r.status, r.h)
		}
	}
	if r := g.get("", "X-Ingestion", "a"); r.status != 429 || r.h.Get("RateLimit-Limit") != "3" {
		t.Fatalf("limited: %d %v", r.status, r.h)
	}
	// the control plane raises the ingestion's burst while the application runs
	pl.m["a"] = ratelimit.MustPolicy(10, time.Minute, 5)
	raised := g.get("", "X-Ingestion", "a")
	if raised.status != 200 || raised.h.Get("RateLimit-Limit") != "5" || raised.h.Get("RateLimit-Remaining") != "1" {
		t.Fatalf("raised: %d %v", raised.status, raised.h)
	}
	// an ingestion the plane does not know: the limiter's default policy, never unlimited
	unknown := g.get("", "X-Ingestion", "b")
	if unknown.status != 200 || unknown.h.Get("RateLimit-Limit") != "1" || g.get("", "X-Ingestion", "b").status != 429 {
		t.Fatalf("unknown: %d %v", unknown.status, unknown.h)
	}
	if n := counter(reg, "fw_http_rate_limit_policy_failures_total"); n != 0 {
		t.Fatalf("policy failures = %v, want 0 (an unknown key is an answer, not a failure)", n)
	}
}

func TestAResolverThatFailsIsCountedAndDecidedWithTheDefaultPolicy(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	reg := must(metrics.NewRegistry())
	mw := must(ratelimit.Middleware(memory(t, clock, 1, time.Minute, 1), ratelimit.Header("X-Ingestion"),
		ratelimit.WithPolicy((&plane{}).fn()), ratelimit.WithMetrics(reg)))
	g := newRig(t, mw, false)
	first := g.get("", "X-Ingestion", "boom")
	if first.status != 200 || first.h.Get("RateLimit-Limit") != "1" {
		t.Fatalf("first: %d %v", first.status, first.h)
	}
	if g.get("", "X-Ingestion", "boom").status != 429 {
		t.Fatal("a failing resolver made the route unlimited")
	}
	if n := counter(reg, "fw_http_rate_limit_policy_failures_total"); n != 2 {
		t.Fatalf("policy failures = %v, want 2", n)
	}
	if g.handled.Load() != 1 {
		t.Fatalf("handler ran %d times, want 1", g.handled.Load())
	}
}

func TestAResolverFailureIs503WhenTheMiddlewareDenies(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	reg := must(metrics.NewRegistry())
	mw := must(ratelimit.Middleware(memory(t, clock, 1, time.Minute, 1), ratelimit.Header("X-Ingestion"),
		ratelimit.WithPolicy((&plane{}).fn()), ratelimit.WithOnStoreFailure(ratelimit.Deny), ratelimit.WithMetrics(reg)))
	g := newRig(t, mw, false)
	r := g.get("", "X-Ingestion", "boom")
	if r.status != 503 || !strings.Contains(r.body, "UNAVAILABLE") || g.handled.Load() != 0 {
		t.Fatalf("%d %s handled=%d", r.status, r.body, g.handled.Load())
	}
	if n := counter(reg, "fw_http_rate_limit_policy_failures_total"); n != 1 {
		t.Fatalf("policy failures = %v, want 1", n)
	}
}

func TestAPolicyWhoseBurstIsBelowTheCostIsAPolicyFailure(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	reg := must(metrics.NewRegistry())
	pl := &plane{m: map[string]ratelimit.Policy{"tiny": ratelimit.MustPolicy(10, time.Minute, 1)}}
	mw := must(ratelimit.Middleware(memory(t, clock, 10, time.Minute, 5), ratelimit.Header("X-Ingestion"),
		ratelimit.WithCost(2), ratelimit.WithPolicy(pl.fn()), ratelimit.WithMetrics(reg)))
	g := newRig(t, mw, false)
	r := g.get("", "X-Ingestion", "tiny")
	if r.status != 200 || r.h.Get("RateLimit-Limit") != "5" {
		t.Fatalf("%d %v", r.status, r.h)
	}
	if n := counter(reg, "fw_http_rate_limit_policy_failures_total"); n != 1 {
		t.Fatalf("policy failures = %v, want 1", n)
	}
	// the zero Policy from a resolver is a failure too
	zero := func(*http.Request) (ratelimit.Policy, bool, error) { return ratelimit.Policy{}, true, nil }
	g2 := newRig(t, must(ratelimit.Middleware(memory(t, clock, 10, time.Minute, 5), ratelimit.ClientAddress(), ratelimit.WithPolicy(zero), ratelimit.WithMetrics(reg))), false)
	if r := g2.get(""); r.status != 200 || r.h.Get("RateLimit-Limit") != "5" {
		t.Fatalf("zero policy: %d %v", r.status, r.h)
	}
}

type plainLimiter struct{ p ratelimit.Policy }

func (plainLimiter) Name() string               { return "legacy" }
func (l plainLimiter) Policy() ratelimit.Policy { return l.p }
func (plainLimiter) Acquire(context.Context, string, int) (ratelimit.Decision, error) {
	return ratelimit.Allowed{Remaining: 4, ResetAfter: time.Second}, nil
}

func TestBuildTimeChecks(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	legacy := plainLimiter{p: ratelimit.MustPolicy(5, time.Second, 5)}
	_, err := ratelimit.Middleware(legacy, ratelimit.ClientAddress(), ratelimit.WithPolicy(func(*http.Request) (ratelimit.Policy, bool, error) { return ratelimit.Policy{}, false, nil }))
	if err == nil || !strings.Contains(err.Error(), "legacy") {
		t.Fatalf("a limiter without per-request policies: %v", err)
	}
	if _, err := ratelimit.Middleware(memory(t, clock, 10, time.Second, 5), ratelimit.ClientAddress(), ratelimit.WithCost(6)); err == nil {
		t.Fatal("a cost above the burst accepted")
	}
	if _, err := ratelimit.Middleware(memory(t, clock, 10, time.Second, 5), ratelimit.ClientAddress(), ratelimit.WithCost(0)); err == nil {
		t.Fatal("cost 0 accepted")
	}
	if _, err := ratelimit.Middleware(nil, ratelimit.ClientAddress()); err == nil {
		t.Fatal("nil limiter accepted")
	}
	if _, err := ratelimit.Middleware(legacy, ratelimit.ClientAddress()); err != nil {
		t.Fatalf("a plain limiter without a policy function: %v", err)
	}
}

func TestLimitersAreBuiltInCodeFromTheApplicationsOwnSettings(t *testing.T) {
	// the application's own settings, read where it builds its limiters
	limit, period := 20, 10*time.Second
	api, err := ratelimit.NewMemory("api", must(ratelimit.PolicyOf(limit, period)))
	if err != nil {
		t.Fatal(err)
	}
	if api.Name() != "api" || api.Policy() != ratelimit.MustPolicy(20, 10*time.Second, 20) {
		t.Fatalf("%s %+v", api.Name(), api.Policy())
	}
}
