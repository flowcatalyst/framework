package httpfw_test

import (
	"net/http"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
)

const indexHTML = `<!doctype html><title>app</title><div id="app"></div>`

func build(extra map[string]string) fstest.MapFS {
	m := fstest.MapFS{
		"index.html":           {Data: []byte(indexHTML)},
		"assets/app-1a2b.js":   {Data: []byte("console.log(1)")},
		"assets/app-1a2b.css":  {Data: []byte("body{}")},
		"favicon.ico":          {Data: []byte("ico")},
		".vite/manifest.json":  {Data: []byte("{}")},
		"assets/.hidden/x.js":  {Data: []byte("x")},
		"assets/nested/c.mjs":  {Data: []byte("export {}")},
		"robots.txt":           {Data: []byte("User-agent: *\n")},
		"data/report.final.js": {Data: []byte("report")},
	}
	for k, v := range extra {
		m[k] = &fstest.MapFile{Data: []byte(v)}
	}
	return m
}

func mount(t *testing.T, fsys fstest.MapFS, opts spa.Options, routes func(*httpfw.Routes)) (*httpfw.Dispatcher, error) {
	t.Helper()
	s, err := spa.New(fsys, opts)
	if err != nil {
		t.Fatalf("spa.New: %v", err)
	}
	rt := httpfw.NewRoutes("app")
	if routes != nil {
		routes(rt)
	}
	rt.SPA(s)
	return httpfw.Compile([]*httpfw.Routes{rt}, httpfw.WithEnvironment("test"))
}

func TestSPAClientRoutesAssetsAndAPIPrefixes(t *testing.T) {
	t.Parallel()
	d, err := mount(t, build(nil), spa.Options{}, func(rt *httpfw.Routes) {
		rt.Handle("GET /orders/{id}", echoRoute("order"))
		rt.Handle("POST /runtime/v1/poll", echoRoute("poll"))
	})
	if err != nil {
		t.Fatal(err)
	}
	// a client route, with and without a dot in a segment that is not a static-asset extension
	for _, path := range []string{"/", "/index.html", "/dashboard", "/ingestions/acme.orders", "/x.unknown", "/apiary", "/apiaryy/sub"} {
		r := do(d, "GET", path, "")
		if r.status != 200 || r.body != indexHTML || r.header.Get("Cache-Control") != "no-cache" || !strings.HasPrefix(etagOf(r.header), `"`) {
			t.Errorf("%s: %d cache=%q etag=%q", path, r.status, r.header.Get("Cache-Control"), etagOf(r.header))
		}
	}
	// hashed assets are immutable, an unhashed file revalidates
	if r := do(d, "GET", "/assets/app-1a2b.js", ""); r.header.Get("Cache-Control") != "public, max-age=31536000, immutable" ||
		r.header.Get("Content-Type") != "text/javascript; charset=utf-8" || r.body != "console.log(1)" {
		t.Errorf("hashed asset: %v", r.header)
	}
	if r := do(d, "GET", "/assets/nested/c.mjs", ""); r.header.Get("Cache-Control") != "public, max-age=31536000, immutable" {
		t.Errorf("nested hashed asset: %v", r.header)
	}
	if r := do(d, "GET", "/favicon.ico", ""); r.header.Get("Cache-Control") != "no-cache" || r.header.Get("Content-Type") != "image/x-icon" {
		t.Errorf("unhashed file: %v", r.header)
	}
	// dotfiles are not indexed: the manifest is not served, and (an asset extension) it is a 404
	if r := do(d, "GET", "/.vite/manifest.json", ""); r.status != 404 {
		t.Errorf("dotfile: %d", r.status)
	}
	// a missing asset or any missing file with an asset extension is a JSON 404, never index.html
	for _, path := range []string{"/assets/gone.js", "/assets", "/assets/", "/missing.css", "/a/b/c.JS", "/data/report.js"} {
		r := do(d, "GET", path, "")
		if r.status != 404 || r.header.Get("Cache-Control") != "no-store" || !strings.Contains(r.body, `"NOT_FOUND"`) || strings.Contains(r.body, "<") {
			t.Errorf("%s: %d cache=%q %s", path, r.status, r.header.Get("Cache-Control"), r.body)
		}
	}
	// an API prefix, by whole segment, derived from the routes, for every method
	for _, method := range []string{"GET", "POST", "DELETE", "PUT"} {
		for _, path := range []string{"/api/nope", "/auth/nope", "/orders/7/edit", "/runtime/other", "/orders"} {
			r := do(d, method, path, "")
			if r.status != 404 || r.header.Get("Content-Type") != "application/json" || r.header.Get("Cache-Control") != "no-store" {
				t.Errorf("%s %s: %d %v", method, path, r.status, r.header)
			}
		}
	}
	// a path matching a route with another method keeps the router's 405
	if r := do(d, "DELETE", "/orders/7", ""); r.status != 405 || r.header.Get("Allow") != "GET, HEAD, OPTIONS" {
		t.Errorf("router 405: %d %q", r.status, r.header.Get("Allow"))
	}
	// the SPA answers only GET and HEAD
	for _, method := range []string{"POST", "PUT", "PATCH", "DELETE", "OPTIONS"} {
		r := do(d, method, "/dashboard", "")
		if r.status != 405 || r.header.Get("Allow") != "GET, HEAD" || r.header.Get("Cache-Control") != "no-store" {
			t.Errorf("%s /dashboard: %d allow=%q", method, r.status, r.header.Get("Allow"))
		}
	}
}

func TestSPAConditionalRequestsAndHead(t *testing.T) {
	t.Parallel()
	d, err := mount(t, build(nil), spa.Options{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	etag := etagOf(do(d, "GET", "/", "").header)
	if len(etag) != 34 || !strings.HasPrefix(etag, `"`) || strings.HasPrefix(etag, `W/`) {
		t.Fatalf("a strong ETag of 32 hex characters, got %q", etag)
	}
	for _, inm := range []string{etag, "W/" + etag, `"other", ` + etag, "*"} {
		r := do(d, "GET", "/dashboard", "", "If-None-Match", inm)
		if r.status != 304 || r.body != "" || etagOf(r.header) != etag || r.header.Get("Content-Type") != "" ||
			r.header.Get("Cache-Control") != "no-cache" || r.header.Get("X-Frame-Options") != "DENY" {
			t.Errorf("If-None-Match %q: %d %v", inm, r.status, r.header)
		}
	}
	if r := do(d, "GET", "/", "", "If-None-Match", `"other"`); r.status != 200 {
		t.Errorf("a different tag: %d", r.status)
	}
	if r := do(d, "GET", "/", "", "Range", "bytes=0-3", "If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT"); r.status != 200 || r.body != indexHTML {
		t.Errorf("Range and If-Modified-Since are ignored: %d", r.status)
	}
	head := do(d, "HEAD", "/", "")
	if head.status != 200 || head.body != "" || head.header.Get("Content-Length") != strconv.Itoa(len(indexHTML)) {
		t.Errorf("HEAD: %d %q length=%q", head.status, head.body, head.header.Get("Content-Length"))
	}
	if lm := do(d, "GET", "/", "").header.Get("Last-Modified"); lm != "" {
		t.Errorf("no Last-Modified: %q", lm)
	}
}

func TestSPAHeadersStrictByDefault(t *testing.T) {
	t.Parallel()
	d, err := mount(t, build(nil), spa.Options{}, func(rt *httpfw.Routes) { rt.Handle("GET /api/x", echoRoute("x")) })
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/", "/api/missing", "/assets/missing.js"} {
		h := do(d, "GET", path, "").header
		for k, v := range map[string]string{
			"X-Content-Type-Options":     "nosniff",
			"Referrer-Policy":            "same-origin",
			"X-Frame-Options":            "DENY",
			"Cross-Origin-Opener-Policy": "same-origin",
			"Permissions-Policy":         "camera=(), microphone=(), geolocation=()",
		} {
			if h.Get(k) != v {
				t.Errorf("%s %s: %q, want %q", path, k, h.Get(k), v)
			}
		}
		csp := h.Get("Content-Security-Policy")
		for _, want := range []string{"default-src 'self'", "script-src 'self';", "style-src 'self'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'self'", "object-src 'none'"} {
			if !strings.Contains(csp, want) {
				t.Errorf("%s: policy %q lacks %q", path, csp, want)
			}
		}
	}
	if h := do(d, "GET", "/api/x", "").header; h.Get("Content-Security-Policy") != "" || h.Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("an API response has nosniff but not the SPA's policy: %v", h)
	}
}

func TestSPAContentSecurityPolicyOptions(t *testing.T) {
	t.Parallel()
	hash := "sha256-Zm9vYmFy" + strings.Repeat("A", 29) + "="
	d, err := mount(t, build(nil), spa.Options{CSP: spa.Strict(hash)}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if csp := do(d, "GET", "/", "").header.Get("Content-Security-Policy"); !strings.Contains(csp, "script-src 'self' '"+hash+"';") {
		t.Errorf("hash not in %q", csp)
	}
	d, err = mount(t, build(nil), spa.Options{CSP: spa.Baseline()}, nil)
	if err != nil {
		t.Fatal(err)
	}
	h := do(d, "GET", "/", "").header
	if h.Get("Content-Security-Policy") != "frame-ancestors 'none'; object-src 'none'; base-uri 'self'" || h.Get("Cross-Origin-Opener-Policy") != "" {
		t.Errorf("baseline: %v", h)
	}
	defer func() {
		if recover() == nil {
			t.Error("a malformed script hash must panic")
		}
	}()
	spa.Strict("'sha256-quoted'")
}

func TestSPAMountedAtAPrefix(t *testing.T) {
	t.Parallel()
	d, err := mount(t, build(nil), spa.Options{Prefix: "/app"}, func(rt *httpfw.Routes) { rt.Handle("GET /app/api/x", echoRoute("x")) })
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		path   string
		status int
	}{
		{"/app", 200},
		{"/app/", 200},
		{"/app/anything/deep", 200},
		{"/app/assets/app-1a2b.js", 200},
		{"/app/api/missing", 404},
		{"/elsewhere", 404},
		{"/", 404},
		{"/application", 404},
	} {
		r := do(d, "GET", c.path, "")
		if r.status != c.status {
			t.Errorf("%s: %d, want %d", c.path, r.status, c.status)
		}
		if c.path == "/elsewhere" && r.header.Get("Content-Security-Policy") != "" {
			t.Errorf("outside the prefix is the framework's plain 404, not the SPA's")
		}
	}
}

func TestSPAStartupProblems(t *testing.T) {
	t.Parallel()
	compile := func(env string, fsys fstest.MapFS, opts spa.Options, routes func(*httpfw.Routes)) error {
		s, err := spa.New(fsys, opts)
		if err != nil {
			t.Fatal(err)
		}
		rt := httpfw.NewRoutes("app")
		if routes != nil {
			routes(rt)
		}
		rt.SPA(s)
		_, err = httpfw.Compile([]*httpfw.Routes{rt}, httpfw.WithEnvironment(env))
		return err
	}
	noIndex := fstest.MapFS{"assets/a.js": {Data: []byte("a")}}
	// not built: a deployment must not start and look healthy; local and test answer the 404
	if err := compile("production", noIndex, spa.Options{}, nil); err == nil || !strings.Contains(err.Error(), "SPA not built: no index.html") {
		t.Errorf("production without index.html: %v", err)
	}
	if err := compile("", noIndex, spa.Options{}, nil); err == nil {
		t.Errorf("an unknown environment is a deployed one")
	}
	for _, env := range []string{"local", "test"} {
		s, _ := spa.New(noIndex, spa.Options{})
		rt := httpfw.NewRoutes("app")
		rt.SPA(s)
		d, err := httpfw.Compile([]*httpfw.Routes{rt}, httpfw.WithEnvironment(env))
		if err != nil {
			t.Fatalf("%s: %v", env, err)
		}
		r := do(d, "GET", "/anything", "")
		if r.status != 404 || r.body != "Frontend not built into this application. Run the web build (pnpm --dir web build) and rebuild.\n" ||
			r.header.Get("Cache-Control") != "no-store" || !strings.HasPrefix(r.header.Get("Content-Type"), "text/plain") {
			t.Errorf("%s: not-built answer %d %q %v", env, r.status, r.body, r.header)
		}
		if r := do(d, "GET", "/api/x", ""); !strings.Contains(r.body, `"NOT_FOUND"`) {
			t.Errorf("an unknown API path keeps its JSON 404 even when not built: %s", r.body)
		}
	}
	for _, c := range []struct {
		name   string
		routes func(*httpfw.Routes)
		opts   spa.Options
		want   string
	}{
		{"a route at GET /", func(rt *httpfw.Routes) { rt.Handle("GET /", echoRoute("home")) }, spa.Options{}, "shadows the SPA's index.html at /"},
		{"a route at POST /", func(rt *httpfw.Routes) { rt.Handle("POST /", echoRoute("home")) }, spa.Options{}, "shadows the SPA's index.html at /"},
		{"a parameter first segment", func(rt *httpfw.Routes) { rt.Handle("GET /{tenant}/x", echoRoute("t")) }, spa.Options{}, "has a parameter as its first segment under the SPA's prefix /"},
		{"a route at the prefix", func(rt *httpfw.Routes) { rt.Handle("GET /app/", echoRoute("home")) }, spa.Options{Prefix: "/app"}, "shadows the SPA's index.html at /app"},
		{"an API prefix hiding files", func(rt *httpfw.Routes) { rt.Handle("GET /assets/list", echoRoute("a")) }, spa.Options{}, "API prefix 'assets' (route GET /assets/list (module 'app')) hides the SPA's files"},
		{"an added API prefix hiding files", nil, spa.Options{APIPrefixes: []string{"favicon.ico"}}, "API prefix 'favicon.ico' (Options.APIPrefixes (module 'app')) hides the SPA's files"},
	} {
		err := compile("test", build(nil), c.opts, c.routes)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: want %q, got %v", c.name, c.want, err)
		}
	}
	// a second SPA
	a, _ := spa.New(build(nil), spa.Options{})
	b, _ := spa.New(build(nil), spa.Options{Prefix: "/other"})
	r1, r2 := httpfw.NewRoutes("first"), httpfw.NewRoutes("second")
	r1.SPA(a)
	r2.SPA(b)
	if _, err := httpfw.Compile([]*httpfw.Routes{r1, r2}, httpfw.WithEnvironment("test")); err == nil ||
		!strings.Contains(err.Error(), "a second SPA: module 'second' mounts one at /other but module 'first' already mounted one at /") {
		t.Errorf("two SPAs: %v", err)
	}
}

func TestSPAIndexLimitsAndOptions(t *testing.T) {
	t.Parallel()
	if _, err := spa.New(build(nil), spa.Options{MaxFiles: 2}); err == nil || !strings.Contains(err.Error(), "more than 2 files") {
		t.Errorf("file limit: %v", err)
	}
	if _, err := spa.New(build(nil), spa.Options{MaxBytes: 10}); err == nil || !strings.Contains(err.Error(), "more than 10 bytes") {
		t.Errorf("byte limit: %v", err)
	}
	for _, o := range []spa.Options{{Prefix: "app"}, {Prefix: "/a b"}, {APIPrefixes: []string{"a/b"}}, {AssetsDirectory: ".x"}, {MaxFiles: -1}} {
		if _, err := spa.New(build(nil), o); err == nil {
			t.Errorf("%+v must be refused", o)
		}
	}
	s, err := spa.New(build(nil), spa.Options{})
	if err != nil {
		t.Fatal(err)
	}
	if n := s.Files(); n != 7 { // the two dot entries are not indexed
		t.Errorf("indexed %d files", n)
	}
	if !s.HasIndex() {
		t.Error("HasIndex")
	}
}

// etagOf reads the ETag as the contract spells it: the framework stores the name "ETag" in the
// header map as it is, where Header.Get would look for "Etag" (a client reading the wire sees either).
func etagOf(h http.Header) string {
	if v := h["ETag"]; len(v) > 0 { //nolint:staticcheck // the contract spells the name ETag
		return v[0]
	}
	return ""
}
