package auth_test

import (
	"context"
	"encoding/json/v2"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
)

const (
	origin   = "https://app.example"
	clientID = "app-client"
)

var begin = time.Date(2026, 10, 13, 8, 0, 0, 0, time.UTC)

// app is an application with the auth module against a test provider.
type app struct {
	t        *testing.T
	clock    *fwtest.Clock
	provider *authtest.Provider
	store    *auth.MemoryStore
	server   *httpfw.Server
	module   *auth.Module
}

type config struct {
	env     map[string]string
	options []auth.Option
	declare []auth.DeclareOption
	files   string // application.yaml
	store   auth.StoreFactory
}

// startApp starts the application; a start-up failure is returned.
func startApp(t *testing.T, c config) (*app, error) {
	t.Helper()
	clock := fwtest.NewClock(begin)
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	base := origin
	if v, ok := c.env["AUTH_PUBLIC_BASE_URL"]; ok {
		base = v
	}
	provider.Client(clientID, c.env["AUTH_CLIENT_SECRET"], base+"/auth/callback")
	env := map[string]string{
		"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0",
		"AUTH_ISSUER": provider.Issuer(), "AUTH_CLIENT_ID": clientID, "AUTH_PUBLIC_BASE_URL": origin,
	}
	for k, v := range c.env {
		if v == "" && k != "AUTH_CLIENT_SECRET" {
			delete(env, k)
		} else if v != "" {
			env[k] = v
		}
	}
	dir := t.TempDir()
	if c.files != "" {
		if err := writeFile(dir+"/application.yaml", c.files); err != nil {
			t.Fatal(err)
		}
	}
	a := fw.New("auth-test", fw.WithEnvironment(env), fw.WithConfigDir(dir), fw.WithClock(clock),
		fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(discard()))
	web := httpfw.Declare(a)
	d := auth.Declare(a, web, c.declare...)
	out := &app{t: t, clock: clock, provider: provider, store: auth.NewMemoryStore(clock)}
	factory := c.store
	if factory == nil {
		factory = func(context.Context, *fw.Boot) (auth.Store, error) { return out.store, nil }
	}
	opts := append([]auth.Option{
		auth.SessionStore(factory),
		auth.Permissions(auth.ScopesMapper().And(auth.ByRole(map[string][]string{"orders:clerk": {"orders:thing:create", "orders:thing:view"}}))),
	}, c.options...)
	run, err := a.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		m, err := d.Build(ctx, b, opts...)
		if err != nil {
			return nil, err
		}
		out.module = m
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{m.Routes(), testRoutes()}, httpfw.WithResolver(m.Resolver()))
		out.server = srv
		return srv, err
	})
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return out, nil
}

func mustStart(t *testing.T, c config) *app {
	t.Helper()
	a, err := startApp(t, c)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return a
}

func testRoutes() *httpfw.Routes {
	rt := httpfw.NewRoutes("test-routes")
	rt.HandleContext("GET /api/me", func(w http.ResponseWriter, r *http.Request, cx fw.Context) error {
		kind, _ := cx.PrincipalKind()
		principal, _ := cx.Principal()
		application, hasApp := cx.Application()
		perms := []string{}
		for _, p := range cx.Permissions() {
			perms = append(perms, p.String())
		}
		slices.Sort(perms)
		source := "none"
		if a, ok := auth.Current(r); ok {
			source = a.Source.String()
		}
		appText := "none"
		if hasApp {
			appText = application.String()
		}
		return httpfw.JSON(w, 200, map[string]any{
			"principal": principal.String(), "principalKind": kind.String(), "client": cx.Client().String(),
			"application": appText, "permissions": perms, "source": source,
		})
	})
	rt.HandleContext("GET /page", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
		httpfw.HTML(w, 200, "<h1>hello</h1>")
		return nil
	})
	rt.HandleContext("POST /api/things", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
		return httpfw.JSON(w, 201, map[string]any{"created": true})
	}, httpfw.With(auth.Require("orders:thing:create")))
	rt.HandleContext("POST /api/echo", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
		return httpfw.JSON(w, 200, map[string]any{"ok": true})
	})
	rt.Handle("GET /public", func(http.ResponseWriter, *http.Request) error { return httpfw.Unauthenticated("handler says no") })
	return rt
}

// response is what the application answered.
type response struct {
	status int
	header http.Header
	body   string
}

func (r response) tree() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(r.body), &m)
	return m
}

func (r response) str(path ...string) string {
	var cur any = r.tree()
	for _, p := range path {
		m, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = m[p]
	}
	s, _ := cur.(string)
	return s
}

func (r response) cookies() []string { return r.header.Values("Set-Cookie") }

// do sends a request to the application in-process.
func (a *app) do(method, target, body string, headers ...string) response {
	a.t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	if body == "" {
		req.Body = http.NoBody
		req.ContentLength = 0
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Add(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	a.server.Handler().ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return response{status: rec.Code, header: rec.Result().Header, body: string(b)}
}

// browser is a cookie jar and the provider over real HTTP; the test follows redirects step by step.
type browser struct {
	app    *app
	origin string
	jar    map[string]string
}

func (a *app) browser() *browser { return &browser{app: a, origin: origin, jar: map[string]string{}} }

func (b *browser) cookieHeader() string {
	names := slices.Sorted(maps.Keys(b.jar))
	parts := make([]string, len(names))
	for i, n := range names {
		parts[i] = n + "=" + b.jar[n]
	}
	return strings.Join(parts, "; ")
}

func (b *browser) send(method, target, body string, headers ...string) response {
	b.app.t.Helper()
	if len(b.jar) > 0 {
		headers = append([]string{"Cookie", b.cookieHeader()}, headers...)
	}
	r := b.app.do(method, target, body, headers...)
	b.absorb(r)
	return r
}

func (b *browser) get(target string, headers ...string) response {
	b.app.t.Helper()
	return b.send("GET", target, "", headers...)
}

func (b *browser) post(target, body string, headers ...string) response {
	b.app.t.Helper()
	return b.send("POST", target, body, append([]string{"Content-Type", "application/json"}, headers...)...)
}

func (b *browser) absorb(r response) {
	for _, sc := range r.cookies() {
		first, _, _ := strings.Cut(sc, ";")
		name, value, _ := strings.Cut(first, "=")
		if value == "" || strings.Contains(sc, "Max-Age=0") {
			delete(b.jar, name)
		} else {
			b.jar[name] = value
		}
	}
}

// atProvider follows a provider URL once and returns its Location (the callback URL).
func (b *browser) atProvider(target string) string {
	b.app.t.Helper()
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, err := http.NewRequestWithContext(b.app.t.Context(), http.MethodGet, target, http.NoBody)
	if err != nil {
		b.app.t.Fatal(err)
	}
	resp, err := client.Do(req)
	if err != nil || resp == nil {
		b.app.t.Fatalf("provider: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusFound {
		body, _ := io.ReadAll(resp.Body)
		b.app.t.Fatalf("the provider answered %d: %s", resp.StatusCode, body)
	}
	return resp.Header.Get("Location")
}

func (b *browser) local(u string) string {
	b.app.t.Helper()
	rest, ok := strings.CutPrefix(u, b.origin+"/")
	if !ok {
		b.app.t.Fatalf("not an application URL: %s", u)
	}
	return "/" + rest
}

// startSignIn starts a sign-in and returns the callback URL the provider sent the browser to.
func (b *browser) startSignIn(returnTo string) string {
	b.app.t.Helper()
	login := b.get("/auth/login?returnTo=" + url.QueryEscape(returnTo))
	if login.status != http.StatusFound {
		b.app.t.Fatalf("login answered %d: %s", login.status, login.body)
	}
	return b.atProvider(login.header.Get("Location"))
}

// signIn is a whole sign-in; it returns the callback's answer.
func (b *browser) signIn(returnTo string) response {
	b.app.t.Helper()
	return b.get(b.local(b.startSignIn(returnTo)))
}

func query(t *testing.T, raw string) map[string]string {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]string{}
	for k, v := range u.Query() {
		if len(v) > 0 {
			out[k] = v[0]
		}
	}
	return out
}

func orderedKeys(t *testing.T, raw string) []string {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	var keys []string
	for pair := range strings.SplitSeq(u.RawQuery, "&") {
		k, _, _ := strings.Cut(pair, "=")
		keys = append(keys, k)
	}
	return keys
}
