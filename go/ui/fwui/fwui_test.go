package fwui_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/a-h/templ"
	"github.com/andybalholm/brotli"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/fwui"
	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
	"github.com/flowcatalyst/framework/go/usecase"
)

const origin = "https://app.example"

type rig struct {
	t       *testing.T
	module  *fwui.Module
	auth    *auth.Module
	handler http.Handler
	cookie  string
}

type setup struct {
	env  string
	opts []fwui.Option
}

func start(t *testing.T, s setup) (*rig, error) {
	t.Helper()
	if s.env == "" {
		s.env = "test"
	}
	clock := fwtest.NewClock(time.Date(2026, 11, 2, 8, 0, 0, 0, time.UTC))
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	provider.Client("app-client", "", origin+"/auth/callback")
	env := map[string]string{
		"APP_ENV": s.env, "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0",
		"AUTH_ISSUER": provider.Issuer(), "AUTH_CLIENT_ID": "app-client", "AUTH_PUBLIC_BASE_URL": origin,
	}
	app := fw.New("fwui-test", fw.WithEnvironment(env), fw.WithConfigDir(t.TempDir()), fw.WithClock(clock),
		fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slog.New(slog.DiscardHandler)))
	web := httpfw.Declare(app)
	ad := auth.Declare(app, web)
	ud := fwui.Declare(app)
	out := &rig{t: t}
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		am, err := ad.Build(ctx, b, auth.SessionStore(auth.MemoryStoreFactory()))
		if err != nil {
			return nil, err
		}
		out.auth = am
		um, err := ud.Build(b, append([]fwui.Option{fwui.WithViews(
			views.WithTitleSuffix(" - Demo"),
			views.WithLogin("/auth/login", ""),
			views.WithFlashes(map[string]kit.FlashMessageProps{"saved": {Kind: kit.FlashSuccess, Summary: "Saved."}}),
			views.WithShell(func(r *http.Request) kit.AppShellProps {
				u, _ := fwui.ShellUser(r)
				return kit.AppShellProps{LogoHref: "/", User: u, Version: "1", SignOutAction: "/auth/logout", ProfileHref: "/profile"}
			}),
		)}, s.opts...)...)
		if err != nil {
			return nil, err
		}
		out.module = um
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{am.Routes(), um.Routes(), pages(out)}, httpfw.WithResolver(am.Resolver()))
		if err != nil {
			return nil, err
		}
		out.handler = srv.Handler()
		return srv, nil
	})
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	testing, err := out.auth.Testing()
	if err != nil {
		return out, nil //nolint:nilerr // outside APP_ENV=test there is no test sign-in; the caller sees an empty cookie
	}
	out.cookie, err = testing.Cookie(t.Context(), auth.User("prn_1", "orders:clerk"))
	if err != nil {
		t.Fatal(err)
	}
	return out, nil
}

func mustStart(t *testing.T, s setup) *rig {
	t.Helper()
	r, err := start(t, s)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return r
}

func pages(r *rig) *httpfw.Routes {
	rt := httpfw.NewRoutes("pages")
	form := templ.ComponentFunc(func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, `<form method="post" action="/orders"><input name="item"></form>`)
		return err
	})
	rt.HandleContext("GET /orders", func(w http.ResponseWriter, req *http.Request, _ fw.Context) error {
		return r.module.Views().Page(w, req, "Orders", form)
	})
	// a native form post: success is 303 with a flash, a use-case refusal re-renders with 422
	rt.HandleContext("POST /orders", func(w http.ResponseWriter, req *http.Request, _ fw.Context) error {
		if err := req.ParseForm(); err != nil {
			return httpfw.Validation("bad form")
		}
		var err error
		switch req.PostForm.Get("item") {
		case "":
			err = usecase.Validation(usecase.MustErrorCode("ITEM_REQUIRED"), "An item is required.")
		case "forbidden":
			err = usecase.Authorization(usecase.MustErrorCode("NOT_ALLOWED"), "You may not order that.")
		case "boom":
			err = errors.New("pq: connection refused")
		default:
			return r.module.Views().SeeOther(w, req, "/orders", "saved")
		}
		f, answered := r.module.Fail(w, req, err)
		if answered {
			return nil
		}
		if !f.Rerender() {
			return err
		}
		return r.module.Views().Page(w, req, "Orders", form, views.WithStatus(f.FormStatus()),
			views.WithPageFlashes(kit.FlashMessageProps{Kind: kit.FlashError, Summary: f.Title(), Detail: f.Message}))
	}, httpfw.AcceptForms())
	// the same handler's twin without AcceptForms: a form body is refused
	rt.HandleContext("POST /plain", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
		return httpfw.JSON(w, 200, map[string]bool{"ok": true})
	})
	return rt
}

type answer struct{ *httptest.ResponseRecorder }

func (r *rig) send(method, target, body string, headers ...string) answer {
	r.t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	if r.cookie != "" {
		req.Header.Set("Cookie", r.cookie)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	return answer{rec}
}

func (a answer) body() string { return a.Body.String() }

var formHeaders = []string{"Sec-Fetch-Site", "same-origin", "Content-Type", "application/x-www-form-urlencoded"}

func TestStaticFilesAndTheSpriteAliasAreServedOnTheRoutes(t *testing.T) {
	r := mustStart(t, setup{})
	css := r.send("GET", r.module.Assets().URL("kit.css"), "")
	if css.Code != 200 || !strings.HasPrefix(css.Header().Get("Content-Type"), "text/css") || !strings.Contains(css.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("css: %d %v", css.Code, css.Header())
	}
	if nm := r.send("GET", r.module.Assets().URL("kit.css"), "", "If-None-Match", css.Header().Get("ETag")); nm.Code != 304 {
		t.Errorf("304: %d", nm.Code)
	}
	if got := r.send("GET", "/icons/lucide-sprite.svg", ""); got.Code != 200 || got.Header().Get("Content-Type") != "image/svg+xml" {
		t.Errorf("alias: %d %v", got.Code, got.Header())
	}
	if got := r.send("GET", "/fw-ui/lucide-sprite.svg", ""); got.Code != 200 {
		t.Errorf("sprite: %d", got.Code)
	}
	if got := r.send("HEAD", "/fw-ui/datastar.js", ""); got.Code != 200 || got.Body.Len() != 0 {
		t.Errorf("HEAD: %d %d", got.Code, got.Body.Len())
	}
	if got := r.send("GET", "/fw-ui/nope.js", ""); got.Code != 404 {
		t.Errorf("unknown: %d", got.Code)
	}
	if got := r.send("POST", "/fw-ui/kit.css", "", formHeaders...); got.Code != 405 {
		t.Errorf("POST: %d", got.Code)
	}
	// the files need no sign-in
	r.cookie = ""
	if got := r.send("GET", "/fw-ui/kit.css", ""); got.Code != 200 {
		t.Errorf("anonymous: %d", got.Code)
	}
}

func TestAPrefixOtherThanTheDefaultAndCompressedAssets(t *testing.T) {
	r := mustStart(t, setup{opts: []fwui.Option{fwui.WithAssets(assets.WithPrefix("/ui/assets/")), fwui.CompressAssets()}})
	url := r.module.Assets().URL("kit.css")
	if !strings.HasPrefix(url, "/ui/assets/kit.css?v=") {
		t.Fatalf("url %q", url)
	}
	plain := r.send("GET", url, "")
	for _, coding := range []string{"br", "gzip"} {
		got := r.send("GET", url, "", "Accept-Encoding", coding)
		suffix := map[string]string{"br": `-br"`, "gzip": `-gz"`}[coding]
		if got.Code != 200 || got.Header().Get("Content-Encoding") != coding || !strings.HasSuffix(got.Header().Get("ETag"), suffix) ||
			got.Header().Get("Vary") != "Accept-Encoding" || got.Header().Get("Content-Length") != strconv.Itoa(got.Body.Len()) {
			t.Fatalf("%s: %d %v", coding, got.Code, got.Header())
		}
		raw, err := io.ReadAll(decoder(t, coding, got.Body))
		if err != nil || !bytes.Equal(raw, plain.Body.Bytes()) {
			t.Errorf("%s: decoded %d, want %d, %v", coding, len(raw), plain.Body.Len(), err)
		}
		if nm := r.send("GET", url, "", "Accept-Encoding", coding, "If-None-Match", got.Header().Get("ETag")); nm.Code != 304 {
			t.Errorf("%s conditional: %d", coding, nm.Code)
		}
	}
	// the default prefix is not served
	if got := r.send("GET", "/fw-ui/kit.css", ""); got.Code != 404 {
		t.Errorf("default prefix: %d", got.Code)
	}
}

func TestAPageUsesTheShellUserFromTheSession(t *testing.T) {
	r := mustStart(t, setup{})
	got := r.send("GET", "/orders", "")
	if got.Code != 200 || !strings.Contains(got.body(), "Test User") || !strings.Contains(got.body(), "prn_1@example.test") ||
		!strings.Contains(got.body(), `<span class="fc-avatar">TU</span>`) {
		t.Fatalf("%d %s", got.Code, got.body())
	}
	if !strings.Contains(got.Header().Get("Content-Security-Policy"), "script-src 'self' 'unsafe-eval';") {
		t.Errorf("CSP %q", got.Header().Get("Content-Security-Policy"))
	}
	if !strings.Contains(got.body(), "returnTo=") {
		t.Error("the session-expired listener should send returnTo")
	}
	// icons follow the assets' sprite URL
	if !strings.Contains(got.body(), "/fw-ui/lucide-sprite.svg#i-") {
		t.Error("sprite URL")
	}
	r.cookie = ""
	if got := r.send("GET", "/orders", ""); got.Code != 401 {
		t.Errorf("anonymous page: %d", got.Code)
	}
}

func TestShellUserFallbacksAndInitials(t *testing.T) {
	for in, want := range map[string]string{
		"Ada Lovelace": "AL", "ada": "A", "ada.lovelace@example.org": "AL", "  grace   brewster   hopper ": "GB",
		"prn_0HZXEQ": "P0", "": "?", "@x": "?", "élan vital": "ÉV", "___": "?",
	} {
		if got := fwui.Initials(in); got != want {
			t.Errorf("Initials(%q) = %q, want %q", in, got, want)
		}
	}
	if _, ok := fwui.ShellUser(httptest.NewRequest("GET", "/", http.NoBody)); ok {
		t.Error("an unauthenticated request has no shell user")
	}
}

// The form route (httpfw.AcceptForms) through the real sessions CSRF check.
func TestFormRoutePostsPassTheSessionsGuardOnlyWithProvenance(t *testing.T) {
	r := mustStart(t, setup{})
	body := url.Values{"item": {"widget"}}.Encode()
	ok := r.send("POST", "/orders", body, formHeaders...)
	if ok.Code != 303 || ok.Header().Get("Location") != "/orders" {
		t.Fatalf("form post: %d %v %s", ok.Code, ok.Header(), ok.body())
	}
	var flash *http.Cookie
	for _, c := range ok.Result().Cookies() {
		if c.Name == "fw_flash" {
			flash = c
		}
	}
	// APP_ENV=test: plain HTTP (httptest) gets a non-Secure flash cookie
	if flash == nil || flash.Value != "saved" || flash.Secure || !flash.HttpOnly || flash.MaxAge != 60 {
		t.Errorf("flash cookie %+v", flash)
	}
	// ... and the next document shows it and clears it
	next := r.send("GET", "/orders", "", "Cookie", r.cookie+"; fw_flash=saved")
	if !strings.Contains(next.body(), "Saved.") || !strings.Contains(strings.Join(next.Header().Values("Set-Cookie"), ";"), "Max-Age=0") {
		t.Errorf("next page: %s %v", next.body(), next.Header())
	}
	// matching Origin works as well
	if got := r.send("POST", "/orders", body, "Origin", origin, "Content-Type", "application/x-www-form-urlencoded"); got.Code != 303 {
		t.Errorf("Origin: %d", got.Code)
	}
	for name, headers := range map[string][]string{
		"cross-site": {"Sec-Fetch-Site", "cross-site", "Content-Type", "application/x-www-form-urlencoded"},
		"no headers": {"Content-Type", "application/x-www-form-urlencoded"},
		"bad Origin": {"Origin", "https://evil.example", "Content-Type", "application/x-www-form-urlencoded"},
	} {
		if got := r.send("POST", "/orders", body, headers...); got.Code != 403 || !strings.Contains(got.body(), "CROSS_ORIGIN_REQUEST") {
			t.Errorf("%s: %d %s", name, got.Code, got.body())
		}
	}
	if got := r.send("POST", "/orders", body, "Sec-Fetch-Site", "same-origin", "Content-Type", "text/plain"); got.Code != 415 {
		t.Errorf("text/plain on a form route: %d", got.Code)
	}
	if got := r.send("POST", "/plain", body, formHeaders...); got.Code != 415 {
		t.Errorf("a form body on a route without AcceptForms: %d", got.Code)
	}
}

func TestUseCaseErrorsBecomeAFlashForDatastarAndAReRenderForForms(t *testing.T) {
	r := mustStart(t, setup{})
	// native: a validation error re-renders the form with 422 and the message
	got := r.send("POST", "/orders", "item=", formHeaders...)
	if got.Code != 422 || !strings.Contains(got.body(), "An item is required.") || !strings.Contains(got.body(), `<form method="post" action="/orders">`) {
		t.Errorf("validation: %d %s", got.Code, got.body())
	}
	// native: an authorization error is not a form problem: the framework's envelope
	if got := r.send("POST", "/orders", "item=forbidden", formHeaders...); got.Code != 403 || !strings.Contains(got.body(), "NOT_ALLOWED") {
		t.Errorf("authorization: %d %s", got.Code, got.body())
	}
	// native: an unexpected error is a 500 without its message
	if got := r.send("POST", "/orders", "item=boom", formHeaders...); got.Code != 500 || strings.Contains(got.body(), "connection refused") {
		t.Errorf("unexpected: %d %s", got.Code, got.body())
	}
	// Datastar: always 200 with one error flash patch
	for body, want := range map[string]string{"item=": "An item is required.", "item=forbidden": "You may not order that."} {
		d := r.send("POST", "/orders", body, append(formHeaders, "Datastar-Request", "true")...)
		if d.Code != 200 || !strings.HasPrefix(d.Header().Get("Content-Type"), "text/event-stream") ||
			!strings.Contains(d.body(), "data: selector #flash-stack") || !strings.Contains(d.body(), "fc-flash-error") ||
			!strings.Contains(d.body(), want) || strings.Count(d.body(), "event: ") != 1 {
			t.Errorf("%s: %d %s", body, d.Code, d.body())
		}
	}
	d := r.send("POST", "/orders", "item=boom", append(formHeaders, "Datastar-Request", "true")...)
	if d.Code != 200 || strings.Contains(d.body(), "connection refused") || !strings.Contains(d.body(), "The request could not be completed") {
		t.Errorf("Datastar unexpected: %d %s", d.Code, d.body())
	}
}

func TestClassify(t *testing.T) {
	unwrapped := usecase.Conflict(usecase.MustErrorCode("STALE"), "Reload and try again.").WithDetails(map[string]string{"version": "3"})
	for name, c := range map[string]struct {
		err      error
		status   int
		code     string
		message  string
		expected bool
		rerender bool
		form     int
		title    string
	}{
		"validation":     {usecase.Validation(usecase.MustErrorCode("BAD"), "Bad."), 400, "BAD", "Bad.", true, true, 422, "Check the details."},
		"business rule":  {usecase.BusinessRule(usecase.MustErrorCode("CLOSED"), "Closed."), 409, "CLOSED", "Closed.", true, true, 422, "That could not be done."},
		"conflict wraps": {errors.Join(errors.New("ctx"), unwrapped), 409, "STALE", "Reload and try again.", true, true, 422, "That could not be done."},
		"authorization":  {usecase.Authorization(usecase.MustErrorCode("NO"), "No."), 403, "NO", "No.", true, false, 403, "Not allowed."},
		"not found":      {usecase.NotFound(usecase.MustErrorCode("GONE"), "Gone."), 404, "GONE", "Gone.", true, false, 404, "Not found."},
		"httpfw error":   {httpfw.Forbidden("CROSS_ORIGIN_REQUEST", "cross-origin"), 403, "CROSS_ORIGIN_REQUEST", "cross-origin", true, false, 403, "Not allowed."},
		"internal":       {usecase.Internal(usecase.MustErrorCode("DB_DOWN")), 500, "DB_DOWN", "The request could not be completed. Try again, or reload the page.", false, false, 500, "Something went wrong."},
		"plain error":    {errors.New("pq: password=hunter2"), 500, "INTERNAL", "The request could not be completed. Try again, or reload the page.", false, false, 500, "Something went wrong."},
	} {
		f := fwui.Classify(c.err)
		if f.Status != c.status || f.Code != c.code || f.Message != c.message || f.Expected() != c.expected ||
			f.Rerender() != c.rerender || f.FormStatus() != c.form || f.Title() != c.title {
			t.Errorf("%s: %+v (rerender %v, form %d, %q)", name, f, f.Rerender(), f.FormStatus(), f.Title())
		}
	}
	if f := fwui.Classify(unwrapped); f.Details["version"] != "3" {
		t.Errorf("details: %v", f.Details)
	}
}

func TestBuildFailuresAreStartupErrorsNamingTheModule(t *testing.T) {
	_, err := start(t, setup{opts: []fwui.Option{fwui.WithViews(views.WithLogin("//evil", ""))}})
	var se *fw.StartupError
	if !errors.As(err, &se) || len(se.Problems) != 1 || se.Problems[0].Module != "ui" || !strings.Contains(se.Problems[0].Message, "login path") {
		t.Errorf("err = %v", err)
	}
	_, err = start(t, setup{opts: []fwui.Option{fwui.WithAssets(assets.WithPrefix("fw-ui"))}})
	if !errors.As(err, &se) || !strings.Contains(se.Problems[0].Message, "prefix") {
		t.Errorf("prefix err = %v", err)
	}
	_, err = start(t, setup{opts: []fwui.Option{fwui.WithViews(views.WithImageSources("https://tile.openstreetmap.org/"))}})
	if !errors.As(err, &se) || se.Problems[0].Module != "ui" || !strings.Contains(se.Problems[0].Message, "image source") {
		t.Errorf("image source err = %v", err)
	}
}

// Extra image hosts (contract section 6 rule 1, amended 2026-11-12) reach the documents through
// WithViews, and widen img-src only.
func TestImageSourcesThroughWithViews(t *testing.T) {
	r := mustStart(t, setup{opts: []fwui.Option{fwui.WithViews(views.WithImageSources("https://tile.openstreetmap.org"))}})
	got := r.send("GET", "/orders", "")
	want, err := views.CSPWithImageSources(nil, []string{"https://tile.openstreetmap.org"})
	if err != nil {
		t.Fatal(err)
	}
	if csp := got.Header().Get("Content-Security-Policy"); got.Code != 200 || csp != want ||
		!strings.Contains(csp, "; img-src 'self' data: https://tile.openstreetmap.org; font-src 'self';") {
		t.Errorf("%d CSP %q", got.Code, csp)
	}
}

func decoder(t *testing.T, coding string, r io.Reader) io.Reader {
	t.Helper()
	if coding == "gzip" {
		z, err := gzip.NewReader(r)
		if err != nil {
			t.Fatal(err)
		}
		return z
	}
	return brotli.NewReader(r)
}
