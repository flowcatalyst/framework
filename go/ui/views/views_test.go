package views_test

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"github.com/a-h/templ"
	"golang.org/x/net/html"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
)

const xss = "<script>alert(1)</script>"

func loadAssets(t *testing.T) *assets.Assets {
	t.Helper()
	a, err := assets.Load()
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func shell(*http.Request) kit.AppShellProps {
	return kit.AppShellProps{
		LogoHref: "/",
		Nav: []kit.NavGroup{{Label: "Main", Items: []kit.NavItem{
			{Label: "Orders", Href: "/orders", Icon: "inbox", Current: true},
		}}},
		User:          kit.ShellUser{Name: "Ada", Email: "ada@example.com", Initials: "A"},
		Version:       "1.0",
		ProfileHref:   "/profile",
		SignOutAction: "/auth/logout",
	}
}

// text is a component that writes already-escaped markup.
func text(markup string) templ.Component {
	return templ.ComponentFunc(func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, markup)
		return err
	})
}

// searched is the test page: an alert showing the q parameter.
func searched(r *http.Request) templ.Component {
	return kit.Alert(kit.AlertProps{Title: "Searched", Description: r.URL.Query().Get("q")})
}

var flashes = map[string]kit.FlashMessageProps{
	"saved": {Kind: kit.FlashSuccess, Summary: "Saved.", Detail: "The order was saved."},
	"gone":  {Kind: kit.FlashError, Summary: "Deleted."},
}

type rig struct {
	t       *testing.T
	assets  *assets.Assets
	views   *views.Views
	handler http.Handler
}

func newRig(t *testing.T, withShell bool, opts ...views.Option) *rig {
	t.Helper()
	a := loadAssets(t)
	base := []views.Option{
		views.WithTitleSuffix(" - Demo"), views.WithLogin("/auth/login", ""), views.WithFlashes(flashes),
		views.WithPlainHTTPCookies(),
	}
	if withShell {
		base = append(base, views.WithShell(shell))
	}
	v, err := views.New(a, append(base, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	handle := func(pattern string, fn func(http.ResponseWriter, *http.Request) error) {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			if err := fn(w, r); err != nil {
				t.Errorf("%s: %v", pattern, err)
			}
		})
	}
	handle("GET /orders", func(w http.ResponseWriter, r *http.Request) error {
		return v.Page(w, r, "Orders", searched(r))
	})
	handle("GET /orders/stream", func(w http.ResponseWriter, r *http.Request) error {
		return v.Stream(w, r, func(p *views.Patches) error {
			return errors.Join(
				p.Inner("#list", kit.Raw("&lt;b&gt;"+"&lt;script&gt;alert(1)&lt;/script&gt;")),
				p.Flash(kit.FlashSuccess, "Saved.", ""),
				p.Remove("#row-1"),
				p.Replace("#dlg", text(`<dialog id="dlg"></dialog>`)),
				p.Focus("opener"),
				p.CloseDialog("dlg"),
			)
		})
	})
	mux.Handle(a.Prefix(), a.Handler())
	return &rig{t: t, assets: a, views: v, handler: mux}
}

type answer struct {
	*httptest.ResponseRecorder
}

func (g *rig) do(method, target string, headers ...string) answer {
	g.t.Helper()
	req := httptest.NewRequest(method, target, http.NoBody)
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Add(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	g.handler.ServeHTTP(rec, req)
	return answer{rec}
}

func (g *rig) get(target string, headers ...string) answer { return g.do("GET", target, headers...) }

func (g *rig) datastar(target string, headers ...string) answer {
	return g.get(target, append([]string{"Datastar-Request", "true"}, headers...)...)
}

func (a answer) body() string { return a.Body.String() }

func parse(t *testing.T, markup string) *html.Node {
	t.Helper()
	doc, err := html.Parse(strings.NewReader(markup))
	if err != nil {
		t.Fatal(err)
	}
	return doc
}

func walk(n *html.Node, fn func(*html.Node)) {
	fn(n)
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		walk(c, fn)
	}
}

func attr(n *html.Node, name string) string {
	for _, a := range n.Attr {
		if a.Key == name {
			return a.Val
		}
	}
	return ""
}

func hasClass(n *html.Node, class string) bool {
	for f := range strings.FieldsSeq(attr(n, "class")) {
		if f == class {
			return true
		}
	}
	return false
}

// find returns the elements for which match is true.
func find(root *html.Node, match func(*html.Node) bool) []*html.Node {
	var out []*html.Node
	walk(root, func(n *html.Node) {
		if n.Type == html.ElementNode && match(n) {
			out = append(out, n)
		}
	})
	return out
}

func byTag(root *html.Node, tag string) []*html.Node {
	return find(root, func(n *html.Node) bool { return n.Data == tag })
}

// hasID reports whether an element with the id is under root.
func hasID(root *html.Node, id string) bool {
	return len(find(root, func(n *html.Node) bool { return attr(n, "id") == id })) > 0
}

// byID is the element with the id; the test fails (and gets an empty node) when there is none.
func byID(t *testing.T, root *html.Node, id string) *html.Node {
	t.Helper()
	for _, n := range find(root, func(n *html.Node) bool { return attr(n, "id") == id }) {
		return n
	}
	t.Errorf("no element with id %q", id)
	return &html.Node{}
}

// firstTag is the first element with the tag; the test fails (and gets an empty node) when there is none.
func firstTag(t *testing.T, root *html.Node, tag string) *html.Node {
	t.Helper()
	for _, n := range byTag(root, tag) {
		return n
	}
	t.Errorf("no <%s>", tag)
	return &html.Node{}
}

func textOf(n *html.Node) string {
	var b strings.Builder
	walk(n, func(c *html.Node) {
		if c.Type == html.TextNode {
			b.WriteString(c.Data)
		}
	})
	return b.String()
}

func render(t *testing.T, n *html.Node) string {
	t.Helper()
	var b strings.Builder
	if err := html.Render(&b, n); err != nil {
		t.Fatal(err)
	}
	return b.String()
}

var ws = regexp.MustCompile(`\s+`)

// patchedElements joins the "data: elements" lines of an event stream.
func patchedElements(body string) string {
	var b strings.Builder
	for line := range strings.SplitSeq(body, "\n") {
		if rest, ok := strings.CutPrefix(line, "data: elements "); ok {
			b.WriteString(rest)
		}
	}
	return b.String()
}

const wantCSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; style-src-attr 'unsafe-inline'; " +
	"img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
	"form-action 'self'; object-src 'none'"

func TestANavigationGetsTheFullDocumentWithThePagePolicy(t *testing.T) {
	g := newRig(t, true)
	r := g.get("/orders")
	if r.Code != 200 {
		t.Fatalf("status %d", r.Code)
	}
	if ct := r.Header().Get("Content-Type"); ct != "text/html; charset=utf-8" {
		t.Errorf("Content-Type %q", ct)
	}
	if !strings.HasPrefix(r.body(), "<!DOCTYPE html>") {
		t.Errorf("not a document: %.40q", r.body())
	}
	doc := parse(t, r.body())
	if got := textOf(firstTag(t, doc, "title")); got != "Orders - Demo" {
		t.Errorf("title %q", got)
	}
	links := find(doc, func(n *html.Node) bool { return n.Data == "link" && attr(n, "rel") == "stylesheet" })
	if len(links) != 1 || !strings.HasPrefix(attr(links[0], "href"), "/fw-ui/kit.css?v=") {
		t.Errorf("stylesheet link: %v", links)
	}
	modules := find(doc, func(n *html.Node) bool { return n.Data == "script" && attr(n, "type") == "module" })
	if len(modules) != 1 || !strings.HasPrefix(attr(modules[0], "src"), "/fw-ui/datastar.js?v=") {
		t.Errorf("module scripts: %d", len(modules))
	}
	if !strings.Contains(textOf(byID(t, doc, "fw-page")), "Searched") {
		t.Fatalf("no #fw-page with the content")
	}
	if n := len(find(doc, func(n *html.Node) bool { return attr(n, "id") == "flash-stack" })); n != 1 {
		t.Errorf("%d flash stacks", n)
	}
	side := byID(t, doc, "fc-sidebar")
	current := find(side, func(n *html.Node) bool { return n.Data == "a" && attr(n, "aria-current") == "page" })
	if len(current) != 1 || strings.TrimSpace(textOf(current[0])) != "Orders" {
		t.Errorf("current nav item: %v", current)
	}
	want := map[string]string{
		"Content-Security-Policy":    wantCSP,
		"Cross-Origin-Opener-Policy": "same-origin",
		"Permissions-Policy":         "camera=(), microphone=(), geolocation=()",
		"X-Content-Type-Options":     "nosniff",
		"Cache-Control":              "private, no-cache",
	}
	for k, v := range want {
		if got := r.Header().Get(k); got != v {
			t.Errorf("%s = %q, want %q", k, got, v)
		}
	}
}

func TestTheDocumentHasNoInlineScriptOrStyleElementOrEventHandlerAttribute(t *testing.T) {
	for _, withShell := range []bool{true, false} {
		doc := parse(t, newRig(t, withShell).get("/orders?q=x").body())
		if inline := find(doc, func(n *html.Node) bool { return n.Data == "script" && attr(n, "src") == "" }); len(inline) > 0 {
			t.Errorf("shell=%v: inline script", withShell)
		}
		if styles := byTag(doc, "style"); len(styles) > 0 {
			t.Errorf("shell=%v: style element", withShell)
		}
		walk(doc, func(n *html.Node) {
			for _, a := range n.Attr {
				if strings.HasPrefix(strings.ToLower(a.Key), "on") {
					t.Errorf("shell=%v: <%s %s>", withShell, n.Data, a.Key)
				}
			}
		})
	}
}

func TestADatastarRequestGetsOnlyAPatchOfThePageContent(t *testing.T) {
	r := newRig(t, true).datastar("/orders?q=abc")
	if r.Code != 200 {
		t.Fatalf("status %d", r.Code)
	}
	if ct := r.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Errorf("Content-Type %q", ct)
	}
	b := r.body()
	if !strings.HasPrefix(b, "event: datastar-patch-elements\n") {
		t.Errorf("event: %.60q", b)
	}
	if !strings.Contains(b, `data: elements <div id="fw-page">`) || strings.Contains(b, "<html") || strings.Contains(b, "<head") {
		t.Errorf("not a page patch: %s", b)
	}
	if strings.Count(b, "event: ") != 1 {
		t.Errorf("want exactly one event: %s", b)
	}
	if !strings.Contains(b, "abc") {
		t.Error("content missing")
	}
	if csp := r.Header().Get("Content-Security-Policy"); csp != "" {
		t.Errorf("a Datastar answer has no CSP: %q", csp)
	}
}

func TestADatastarAnswerIsAlways200EvenForAFormRerender(t *testing.T) {
	g := newRig(t, false)
	mux := http.NewServeMux()
	mux.HandleFunc("POST /form", func(w http.ResponseWriter, r *http.Request) {
		if err := g.views.Page(w, r, "Form", text("<p>bad</p>"), views.WithStatus(422)); err != nil {
			t.Error(err)
		}
	})
	for _, c := range []struct {
		datastar bool
		want     int
	}{{false, 422}, {true, 200}} {
		req := httptest.NewRequest("POST", "/form", http.NoBody)
		if c.datastar {
			req.Header.Set("Datastar-Request", "TRUE") // the value is case-insensitive
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != c.want {
			t.Errorf("datastar=%v: %d, want %d", c.datastar, rec.Code, c.want)
		}
	}
}

func TestTheSameTemplateAnswersBothWays(t *testing.T) {
	g := newRig(t, false)
	full := parse(t, g.get("/orders?q=same").body())
	fragment := ws.ReplaceAllString(render(t, byID(t, full, "fw-page")), " ")

	frag := parse(t, patchedElements(g.datastar("/orders?q=same").body()))
	patched := ws.ReplaceAllString(render(t, byID(t, frag, "fw-page")), " ")
	if fragment != patched {
		t.Errorf("fragments differ\n document: %s\n patch:    %s", fragment, patched)
	}
}

func TestAPageWithoutAShellHasNoSidebarButTheFlashStack(t *testing.T) {
	doc := parse(t, newRig(t, false).get("/orders").body())
	if n := len(byTag(doc, "aside")); n != 0 {
		t.Errorf("%d aside elements", n)
	}
	mains := byTag(doc, "main")
	if len(mains) != 1 || !hasID(mains[0], "flash-stack") || !hasClass(mains[0], "max-w-xl") {
		t.Errorf("want <main class=max-w-xl> with the flash stack")
	}
}

func TestRequestDataInAPageIsEscapedInBothAnswers(t *testing.T) {
	g := newRig(t, true)
	q := url.QueryEscape(xss)
	doc := parse(t, g.get("/orders?q="+q).body())
	if inline := find(doc, func(n *html.Node) bool { return n.Data == "script" && attr(n, "src") == "" }); len(inline) > 0 {
		t.Error("the payload became a script")
	}
	if !strings.Contains(textOf(byID(t, doc, "fw-page")), xss) {
		t.Error("the payload is not shown as text")
	}
	sse := g.datastar("/orders?q=" + q).body()
	if strings.Contains(sse, "<script>") || !strings.Contains(sse, "&lt;script&gt;") {
		t.Errorf("patch: %s", sse)
	}
}

func TestTitleIsEscaped(t *testing.T) {
	g := newRig(t, false)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/", http.NoBody)
	if err := g.views.Page(rec, req, "</title><script>x</script>", text("")); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(rec.Body.String(), "<script>x") {
		t.Error("title not escaped")
	}
}

func TestStreamHelpersWriteInnerAppendRemoveReplaceFocusAndClosePatches(t *testing.T) {
	body := newRig(t, false).datastar("/orders/stream").body()
	for _, want := range []string{
		"data: selector #list", "data: mode inner", "&lt;script&gt;",
		"data: selector #flash-stack", "data: mode append", "fc-flash-success",
		"data: selector #row-1", "data: mode remove",
		"data: selector #dlg", "data: mode replace",
		"document.getElementById(&#39;opener&#39;)?.focus()",
		"document.getElementById(&#39;dlg&#39;).close()",
		"data: selector body",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("missing %q in\n%s", want, body)
		}
	}
	if strings.Contains(body, "<script>") {
		t.Error("a script element in the stream")
	}
	if strings.Count(body, "event: datastar-patch-elements") != 6 {
		t.Errorf("want 6 events:\n%s", body)
	}
}

func TestPatchesOuterPagePrependRedirectReplaceURLAndSignals(t *testing.T) {
	g := newRig(t, false)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /s", func(w http.ResponseWriter, r *http.Request) {
		err := g.views.Stream(w, r, func(p *views.Patches) error {
			return errors.Join(
				p.Outer(text(`<div id="a">1</div>`)),
				p.Page(text("<p>x</p>")),
				p.Prepend("#list", text("<li>0</li>")),
				p.Append("#list", text("<li>9</li>")),
				p.Redirect("/orders?x=1"),
				p.ReplaceURL("/orders?sort=name"),
				p.Signals(map[string]any{"count": 3}),
			)
		})
		if err != nil {
			t.Error(err)
		}
	})
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", "/s", http.NoBody))
	for _, want := range []string{
		"data: elements <div id=\"a\">1</div>\n",
		"data: elements <div id=\"fw-page\"><p>x</p></div>\n",
		"data: mode prepend\n", "data: mode append\n",
		`window.location.assign(&quot;/orders?x=1&quot;)`,
		`history.replaceState(history.state, &#39;&#39;, &quot;/orders?sort=name&quot;)`,
		"event: datastar-patch-signals\ndata: signals {\"count\":3}\n",
	} {
		if !strings.Contains(rec.Body.String(), want) {
			t.Errorf("missing %q in\n%s", want, rec.Body.String())
		}
	}
	bad := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/s", http.NoBody)
	_ = g.views.Stream(bad, req, func(p *views.Patches) error {
		for _, path := range []string{"//evil.example", "/\\evil", "https://evil.example", "javascript:alert(1)"} {
			if p.Redirect(path) == nil || p.ReplaceURL(path) == nil {
				t.Errorf("%q should be refused", path)
			}
		}
		if p.Focus("a b") == nil || p.CloseDialog("x'") == nil {
			t.Error("a bad id should be refused")
		}
		return nil
	})
	if strings.Contains(bad.Body.String(), "evil") {
		t.Error("a refused path was sent")
	}
}

func TestThemeAndSidebarCookiesRenderOnHTML(t *testing.T) {
	g := newRig(t, true)
	htmlOf := func(cookie string) *html.Node {
		doc := parse(t, g.get("/orders", "Cookie", cookie).body())
		return firstTag(t, doc, "html")
	}
	cases := []struct {
		cookie string
		dark   bool
		theme  string
	}{
		{"fc_theme=dark", true, "dark"},
		{"fc_scheme=dark", true, "system"},
		{"fc_theme=light; fc_scheme=dark", false, "light"},
		{"fc_theme=purple; fc_scheme=dark", true, "system"},
		{"fc_theme=system", false, "system"},
		{"x=y", false, "system"},
	}
	for _, c := range cases {
		n := htmlOf(c.cookie)
		if hasClass(n, "dark") != c.dark {
			t.Errorf("%q: dark = %v", c.cookie, !c.dark)
		}
		if got := attr(n, "data-signals:theme"); got != "'"+c.theme+"'" {
			t.Errorf("%q: theme signal %q", c.cookie, got)
		}
		if got, want := attr(n, "data-signals:dark"), map[bool]string{true: "true", false: "false"}[c.dark]; got != want {
			t.Errorf("%q: dark signal %q", c.cookie, got)
		}
	}
	if got := attr(htmlOf("fc_sidebar=collapsed"), "data-signals:sidebar-collapsed"); got != "true" {
		t.Errorf("collapsed: %q", got)
	}
	if got := attr(htmlOf("x=y"), "data-signals:sidebar-collapsed"); got != "false" {
		t.Errorf("not collapsed: %q", got)
	}
	// the user menu's theme picker starts from the cookie
	pressed := find(parse(t, g.get("/orders", "Cookie", "fc_theme=dark").body()), func(n *html.Node) bool {
		return hasClass(n, "fc-theme-option") && attr(n, "aria-pressed") == "true"
	})
	if len(pressed) != 1 || !strings.Contains(textOf(pressed[0]), "Dark") {
		t.Errorf("theme picker: %v", pressed)
	}
	// a page without a shell has the theme but not the shell's attributes
	bare := firstTag(t, parse(t, newRig(t, false).get("/orders", "Cookie", "fc_theme=dark").body()), "html")
	if !hasClass(bare, "dark") || attr(bare, "data-signals:sidebar-collapsed") != "" {
		t.Error("bare page html attributes")
	}
}

func TestFlashCookieRendersOnTheNextDocumentAndIsCleared(t *testing.T) {
	g := newRig(t, true)
	r := g.get("/orders", "Cookie", "fw_flash=saved")
	doc := parse(t, r.body())
	stack := byID(t, doc, "flash-stack")
	if got := textOf(stack); !strings.Contains(got, "Saved.") || !strings.Contains(got, "The order was saved.") {
		t.Errorf("flash stack: %q", got)
	}
	cleared := r.Result().Cookies()
	if len(cleared) != 1 || cleared[0].Name != "fw_flash" || cleared[0].MaxAge != -1 || cleared[0].Value != "" ||
		cleared[0].Path != "/" || !cleared[0].HttpOnly || cleared[0].SameSite != http.SameSiteLaxMode {
		t.Errorf("clearing cookie: %v", r.Header()["Set-Cookie"])
	}
	if got := r.Header().Get("Set-Cookie"); got != "fw_flash=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" { // the rig allows plain HTTP cookies
		t.Errorf("Set-Cookie %q", got)
	}
	// an unknown or malformed key renders nothing and is cleared too
	for _, key := range []string{"nope", "Bad_Key", strings.Repeat("a", 65)} {
		r := g.get("/orders", "Cookie", "fw_flash="+key)
		if strings.Contains(textOf(byID(t, parse(t, r.body()), "flash-stack")), "Saved") || len(r.Result().Cookies()) != 1 {
			t.Errorf("key %q: shown or not cleared", key)
		}
	}
	// no cookie: nothing set
	if got := g.get("/orders").Header().Values("Set-Cookie"); len(got) != 0 {
		t.Errorf("Set-Cookie without a flash: %v", got)
	}
	// a Datastar answer ignores the cookie and leaves it for the next document
	d := g.datastar("/orders", "Cookie", "fw_flash=saved")
	if strings.Contains(d.body(), "Saved.") || len(d.Header().Values("Set-Cookie")) != 0 {
		t.Errorf("a Datastar answer must not use the flash cookie: %s", d.body())
	}
}

func TestPageFlashesRenderInTheStack(t *testing.T) {
	g := newRig(t, false)
	rec := httptest.NewRecorder()
	err := g.views.Page(rec, httptest.NewRequest("GET", "/", http.NoBody), "T", text(""),
		views.WithPageFlashes(kit.FlashMessageProps{Kind: kit.FlashWarn, Summary: "Careful"}))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(textOf(byID(t, parse(t, rec.Body.String()), "flash-stack")), "Careful") {
		t.Error("the page's flash is missing")
	}
}

func TestSetFlashCookieAttributes(t *testing.T) {
	v, err := views.New(loadAssets(t), views.WithFlashes(flashes))
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	if err := v.SetFlash(rec, httptest.NewRequest("GET", "/", http.NoBody), "saved"); err != nil {
		t.Fatal(err)
	}
	if got := rec.Header().Get("Set-Cookie"); got != "fw_flash=saved; Path=/; Max-Age=60; HttpOnly; SameSite=Lax; Secure" {
		t.Errorf("Set-Cookie %q", got)
	}
	cs := rec.Result().Cookies()
	if len(cs) != 1 {
		t.Fatalf("cookies: %v", cs)
	}
	c := cs[0]
	if c.Name != "fw_flash" || c.Value != "saved" || c.Path != "/" || c.MaxAge != 60 || !c.HttpOnly ||
		c.SameSite != http.SameSiteLaxMode || !c.Secure {
		t.Errorf("cookie %+v", c)
	}
}

func TestFlashCookieSecureRule(t *testing.T) {
	a := loadAssets(t)
	strict, err := views.New(a, views.WithFlashes(flashes))
	if err != nil {
		t.Fatal(err)
	}
	local, err := views.New(a, views.WithFlashes(flashes), views.WithPlainHTTPCookies())
	if err != nil {
		t.Fatal(err)
	}
	tlsReq := httptest.NewRequest("GET", "https://app.example/", http.NoBody)
	tlsReq.TLS = &tls.ConnectionState{}
	plainReq := httptest.NewRequest("GET", "http://app.example/", http.NoBody)
	for _, c := range []struct {
		name   string
		v      *views.Views
		r      *http.Request
		secure bool
	}{
		{"default, plain HTTP", strict, plainReq, true},
		{"default, TLS", strict, tlsReq, true},
		{"plain-HTTP option, plain HTTP", local, plainReq, false},
		{"plain-HTTP option, TLS", local, tlsReq, true},
	} {
		rec := httptest.NewRecorder()
		if err := c.v.SetFlash(rec, c.r, "saved"); err != nil {
			t.Fatal(err)
		}
		if got := rec.Result().Cookies()[0].Secure; got != c.secure {
			t.Errorf("%s: Secure = %v", c.name, got)
		}
	}
	// the clearing cookie follows the same rule
	rec := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "http://app.example/", http.NoBody)
	req.AddCookie(&http.Cookie{Name: "fw_flash", Value: "saved"})
	if err := strict.Page(rec, req, "T", text("")); err != nil {
		t.Fatal(err)
	}
	if cs := rec.Result().Cookies(); len(cs) != 1 || !cs[0].Secure {
		t.Errorf("clearing cookie must be Secure by default: %v", cs)
	}
}

func TestSetFlashRefusesAKeyThatIsNotLowerCaseAlphanumericOrDash(t *testing.T) {
	v := newRig(t, false).views
	for _, key := range []string{"", "Saved", "a_b", "a b", "a;b", "a\r\nSet-Cookie: x=y", strings.Repeat("a", 65), "é"} {
		rec := httptest.NewRecorder()
		err := v.SetFlash(rec, httptest.NewRequest("GET", "/", http.NoBody), key)
		if !errors.Is(err, views.ErrInvalidFlashKey) || len(rec.Result().Cookies()) != 0 {
			t.Errorf("key %q: err %v, cookies %v", key, err, rec.Header()["Set-Cookie"])
		}
	}
	for _, key := range []string{"saved", "gone"} {
		if err := v.SetFlash(httptest.NewRecorder(), httptest.NewRequest("GET", "/", http.NoBody), key); err != nil {
			t.Errorf("key %q: %v", key, err)
		}
	}
}

// A well-formed key the application never registered would render nothing after the redirect: refused,
// with no cookie, by SetFlash and by SeeOther.
func TestSetFlashRefusesAnUnregisteredKey(t *testing.T) {
	v := newRig(t, false).views
	for _, key := range []string{"a", "saved-2", "0", strings.Repeat("a", 64)} {
		rec := httptest.NewRecorder()
		err := v.SetFlash(rec, httptest.NewRequest("GET", "/", http.NoBody), key)
		if !errors.Is(err, views.ErrUnknownFlashKey) || len(rec.Result().Cookies()) != 0 {
			t.Errorf("key %q: err %v, cookies %v", key, err, rec.Header()["Set-Cookie"])
		}
	}
	rec := httptest.NewRecorder()
	err := v.SeeOther(rec, httptest.NewRequest("POST", "/", http.NoBody), "/x", "savd")
	if !errors.Is(err, views.ErrUnknownFlashKey) || rec.Header().Get("Location") != "" || len(rec.Result().Cookies()) != 0 {
		t.Errorf("SeeOther with an unregistered key: err %v, headers %v", err, rec.Header())
	}
}

func TestNewRefusesABadFlashRegistration(t *testing.T) {
	a := loadAssets(t)
	for name, m := range map[string]map[string]kit.FlashMessageProps{
		"bad key":  {"Saved": {Summary: "x"}},
		"bad kind": {"saved": {Kind: "loud", Summary: "x"}},
	} {
		if _, err := views.New(a, views.WithFlashes(m)); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
}

func TestSeeOtherSetsTheFlashAndRedirectsToALocalPathOnly(t *testing.T) {
	g := newRig(t, false)
	rec := httptest.NewRecorder()
	if err := g.views.SeeOther(rec, httptest.NewRequest("POST", "/orders", http.NoBody), "/orders?saved=1", "saved"); err != nil {
		t.Fatal(err)
	}
	if rec.Code != 303 || rec.Header().Get("Location") != "/orders?saved=1" {
		t.Errorf("%d %q", rec.Code, rec.Header().Get("Location"))
	}
	if cs := rec.Result().Cookies(); len(cs) != 1 || cs[0].Value != "saved" {
		t.Errorf("cookies %v", cs)
	}
	for _, path := range []string{"//evil.example/x", "/\\evil", "https://evil.example", "orders", "/a\r\nb", ""} {
		rec := httptest.NewRecorder()
		if err := g.views.SeeOther(rec, httptest.NewRequest("POST", "/", http.NoBody), path, "saved"); err == nil {
			t.Errorf("%q accepted", path)
		}
		if rec.Code != 200 || rec.Body.Len() != 0 || len(rec.Header()) != 0 {
			t.Errorf("%q: something was written: %d %v", path, rec.Code, rec.Header())
		}
	}
	// no key: no cookie; a bad key: error, nothing written
	rec = httptest.NewRecorder()
	if err := g.views.SeeOther(rec, httptest.NewRequest("POST", "/", http.NoBody), "/x", ""); err != nil || len(rec.Result().Cookies()) != 0 {
		t.Errorf("no key: %v %v", err, rec.Header())
	}
	rec = httptest.NewRecorder()
	if err := g.views.SeeOther(rec, httptest.NewRequest("POST", "/", http.NoBody), "/x", "Bad"); err == nil || rec.Header().Get("Location") != "" {
		t.Errorf("bad key: %v", err)
	}
	// a Datastar request cannot follow a 303: it gets a redirect patch with status 200
	req := httptest.NewRequest("POST", "/orders", http.NoBody)
	req.Header.Set("Datastar-Request", "true")
	rec = httptest.NewRecorder()
	if err := g.views.SeeOther(rec, req, "/orders", "saved"); err != nil {
		t.Fatal(err)
	}
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "window.location.assign(") || rec.Header().Get("Location") != "" {
		t.Errorf("datastar: %d %s", rec.Code, rec.Body.String())
	}
	if len(rec.Result().Cookies()) != 1 {
		t.Error("the flash cookie should still be set")
	}
}

func TestSessionExpiredListenerCarriesReturnToByDefault(t *testing.T) {
	expr := func(opts ...views.Option) string {
		a := loadAssets(t)
		v, err := views.New(a, opts...)
		if err != nil {
			t.Fatal(err)
		}
		rec := httptest.NewRecorder()
		if err := v.Page(rec, httptest.NewRequest("GET", "/", http.NoBody), "T", text("")); err != nil {
			t.Fatal(err)
		}
		return attr(firstTag(t, parse(t, rec.Body.String()), "body"), "data-on:datastar-fetch")
	}
	want := "evt.detail.type === 'error' && evt.detail.argsRaw.status === '401' && " +
		"window.location.assign('/auth/login?returnTo=' + encodeURIComponent(location.pathname + location.search))"
	if got := expr(views.WithLogin("/auth/login", "")); got != want {
		t.Errorf("default parameter:\n%s", got)
	}
	if got := expr(views.WithLogin("/ui/login", "redirect")); !strings.Contains(got, "assign('/ui/login?redirect=' + encodeURIComponent(") {
		t.Errorf("named parameter: %s", got)
	}
	if got := expr(); got != "" {
		t.Errorf("without a login there is no listener: %q", got)
	}
}

func TestNewRefusesAMalformedLoginHashOrNilAssets(t *testing.T) {
	a := loadAssets(t)
	for name, o := range map[string]views.Option{
		"path with a quote":   views.WithLogin("/a'b", ""),
		"path not local":      views.WithLogin("//evil", ""),
		"path with a query":   views.WithLogin("/login?x=1", ""),
		"param with a quote":  views.WithLogin("/login", "a'b"),
		"param with a hyphen": views.WithLogin("/login", "return-to"),
		"param without path":  views.WithLogin("", "returnTo"),
		"hash":                views.WithScriptHash("not a hash"),
		"hash quoted":         views.WithScriptHash("'sha256-abc='"),
		"hash algorithm":      views.WithScriptHash("sha1-abc="),
	} {
		if _, err := views.New(a, o); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
	if _, err := views.New(nil); err == nil {
		t.Error("nil assets accepted")
	}
}

func TestCSPBuilder(t *testing.T) {
	got, err := views.CSP()
	if err != nil || got != wantCSP {
		t.Errorf("CSP() = %q, %v", got, err)
	}
	got, err = views.CSP("sha256-abc=", "sha384-A+b/c==", "sha512-xyz")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, "script-src 'self' 'unsafe-eval' 'sha256-abc=' 'sha384-A+b/c==' 'sha512-xyz'; style-src 'self';") {
		t.Errorf("hashes: %s", got)
	}
	for _, bad := range []string{"", "not a hash", "sha256-", "sha256-a b", "'sha256-abc='", "sha1-abc=", "sha256-abc===", "sha256-ab';"} {
		if _, err := views.CSP(bad); err == nil {
			t.Errorf("hash %q accepted", bad)
		}
	}
	// the policy is what documents send, hashes included
	g := newRig(t, false, views.WithScriptHash("sha256-abc="))
	if csp := g.get("/orders").Header().Get("Content-Security-Policy"); !strings.Contains(csp, "'unsafe-eval' 'sha256-abc=';") {
		t.Errorf("document CSP: %s", csp)
	}
}

// The extra image sources of contract section 6 rule 1 (amended 2026-11-12). The same cases are
// written out in Java's ViewsTest (imageSourcesWidenOnlyImgSrc, imageSourcesOutsideTheRuleAreRefused).

// imageSourcesAccepted: the sources given, and the img-src directive they give.
var imageSourcesAccepted = []struct {
	sources []string
	imgSrc  string
}{
	{nil, "img-src 'self' data:"},
	{[]string{}, "img-src 'self' data:"},
	{[]string{"https://tile.openstreetmap.org"}, "img-src 'self' data: https://tile.openstreetmap.org"},
	{
		[]string{"https://tile.openstreetmap.org", "https://tiles.example.org", "https://cdn.example.net"},
		"img-src 'self' data: https://tile.openstreetmap.org https://tiles.example.org https://cdn.example.net",
	},
	{[]string{"https://*.tile.openstreetmap.org"}, "img-src 'self' data: https://*.tile.openstreetmap.org"},
	{[]string{"https://tiles.example.org:8443"}, "img-src 'self' data: https://tiles.example.org:8443"},
	{[]string{"https://*.example.org:443"}, "img-src 'self' data: https://*.example.org:443"},
	{[]string{"https://localhost:65535", "https://192.168.0.10:1"}, "img-src 'self' data: https://localhost:65535 https://192.168.0.10:1"},
	{[]string{"https://tile-1.example.org"}, "img-src 'self' data: https://tile-1.example.org"},
	{[]string{"HTTPS://Tile.OpenStreetMap.ORG"}, "img-src 'self' data: https://tile.openstreetmap.org"},
	{
		[]string{"https://b.example.org", "https://a.example.org", "https://b.example.org", "HTTPS://A.example.org"},
		"img-src 'self' data: https://b.example.org https://a.example.org",
	},
}

// imageSourcesRefused: each is refused on its own.
var imageSourcesRefused = []string{
	"", "*", "https:", "https://", "https:/tile.example.org", "data:", "blob:", "'self'", "'none'",
	"http://tile.example.org", "wss://tile.example.org", "tile.example.org", "//tile.example.org",
	"https://tile.example.org/", "https://tile.example.org/tiles/{z}/{x}/{y}.png", "https://tile.example.org?x=1",
	"https://tile.example.org#x", "https://user@tile.example.org", "https://user:pw@tile.example.org",
	" https://tile.example.org", "https://tile.example.org ", "https://tile.example .org", "https://tile.example.org\t",
	"https://tile.example.org\n", "'https://tile.example.org'", `"https://tile.example.org"`,
	"https://a.example.org,https://b.example.org", "https://a.example.org;", "https://a.example.org; script-src *",
	"https://*", "https://*.", "https://*.*.example.org", "https://a.*.example.org", "https://*example.org",
	"https://tile.example.org:", "https://tile.example.org:0", "https://tile.example.org:080",
	"https://tile.example.org:65536", "https://tile.example.org:99999", "https://tile.example.org:*",
	"https://tile..example.org", "https://.example.org", "https://example.org.", "https://tile_1.example.org",
	"https://[::1]", "https://tile.\u212Aexample.org", "https://til\u00e9.example.org",
}

func TestImageSourcesWidenOnlyImgSrc(t *testing.T) {
	for _, c := range imageSourcesAccepted {
		got, err := views.CSPWithImageSources(nil, c.sources)
		if err != nil {
			t.Errorf("%q: %v", c.sources, err)
			continue
		}
		if want := strings.Replace(wantCSP, "img-src 'self' data:", c.imgSrc, 1); got != want {
			t.Errorf("%q:\n got %s\nwant %s", c.sources, got, want)
		}
	}
	// with hashes too, and the default is byte for byte CSP()'s
	got, err := views.CSPWithImageSources([]string{"sha256-abc="}, []string{"https://tile.openstreetmap.org"})
	if err != nil || !strings.Contains(got, "'unsafe-eval' 'sha256-abc='; style-src") ||
		!strings.Contains(got, "; img-src 'self' data: https://tile.openstreetmap.org; font-src 'self';") {
		t.Errorf("hashes and sources: %q, %v", got, err)
	}
	if got, err := views.CSPWithImageSources(nil, nil); err != nil || got != wantCSP {
		t.Errorf("no sources: %q, %v", got, err)
	}
	// documents send it; without the option, or with it empty, the header is unchanged
	for _, opts := range [][]views.Option{nil, {views.WithImageSources()}} {
		if csp := newRig(t, false, opts...).get("/orders").Header().Get("Content-Security-Policy"); csp != wantCSP {
			t.Errorf("default document CSP: %s", csp)
		}
	}
	g := newRig(t, false,
		views.WithImageSources("https://tile.openstreetmap.org"), views.WithImageSources("https://*.example.org:8443", "https://tile.openstreetmap.org"))
	want := strings.Replace(wantCSP, "img-src 'self' data:", "img-src 'self' data: https://tile.openstreetmap.org https://*.example.org:8443", 1)
	if csp := g.get("/orders").Header().Get("Content-Security-Policy"); csp != want {
		t.Errorf("document CSP:\n got %s\nwant %s", csp, want)
	}
	// a Datastar answer still carries no policy
	if csp := g.datastar("/orders").Header().Get("Content-Security-Policy"); csp != "" {
		t.Errorf("Datastar answer has a CSP: %s", csp)
	}
}

func TestImageSourcesOutsideTheRuleAreRefused(t *testing.T) {
	a := loadAssets(t)
	for _, bad := range imageSourcesRefused {
		_, err := views.CSPWithImageSources(nil, []string{"https://ok.example.org", bad})
		if err == nil {
			t.Errorf("image source %q accepted", bad)
			continue
		}
		if !strings.Contains(err.Error(), "image source") || !strings.Contains(err.Error(), "https origin") {
			t.Errorf("image source %q: message %q", bad, err)
		}
		if _, err := views.New(a, views.WithImageSources(bad)); err == nil {
			t.Errorf("New accepted image source %q", bad)
		}
	}
}

func TestPageOptionsStatusAndIslandScripts(t *testing.T) {
	g := newRig(t, false)
	page := func(opts ...views.PageOption) (*httptest.ResponseRecorder, error) {
		rec := httptest.NewRecorder()
		return rec, g.views.Page(rec, httptest.NewRequest("GET", "/", http.NoBody), "T", text("<p>x</p>"), opts...)
	}
	rec, err := page(views.WithStatus(http.StatusUnprocessableEntity))
	if err != nil || rec.Code != 422 {
		t.Errorf("status: %d %v", rec.Code, err)
	}
	rec, err = page(views.WithScripts("fc-code-editor.js", "fc-diagram.js", "fc-code-editor.js", "datastar.js"))
	if err != nil {
		t.Fatal(err)
	}
	mods := find(parse(t, rec.Body.String()), func(n *html.Node) bool { return n.Data == "script" && attr(n, "type") == "module" })
	var srcs []string
	for _, m := range mods {
		srcs = append(srcs, attr(m, "src"))
	}
	want := []string{g.assets.URL("datastar.js"), g.assets.URL("fc-code-editor.js"), g.assets.URL("fc-diagram.js")}
	if strings.Join(srcs, " ") != strings.Join(want, " ") {
		t.Errorf("scripts %v, want %v", srcs, want)
	}
	for _, bad := range [][]views.PageOption{
		{views.WithScripts("kit.css")},
		{views.WithScripts("nope.js")},
		{views.WithScripts("../x.js")},
		{views.WithStatus(99)},
		{views.WithStatus(204)},
		{views.WithStatus(304)},
		{views.WithStatus(600)},
	} {
		rec, err := page(bad...)
		if err == nil || rec.Body.Len() != 0 || len(rec.Header()) != 0 {
			t.Errorf("a bad option was accepted or wrote something: %v", err)
		}
	}
	// a Datastar answer validates the options too (a programming error shows in every request)
	req := httptest.NewRequest("GET", "/", http.NoBody)
	req.Header.Set("Datastar-Request", "true")
	if err := g.views.Page(httptest.NewRecorder(), req, "T", text(""), views.WithScripts("nope.js")); err == nil {
		t.Error("a Datastar answer must refuse a bad option")
	}
}

type failing struct{}

func (failing) Render(context.Context, io.Writer) error { return errors.New("boom") }

func TestARenderErrorOrPanicWritesNothing(t *testing.T) {
	g := newRig(t, true)
	req := httptest.NewRequest("GET", "/", http.NoBody)
	rec := httptest.NewRecorder()
	err := g.views.Page(rec, req, "T", templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		_, _ = io.WriteString(w, "<p>partial")
		return failing{}.Render(ctx, w)
	}))
	if err == nil || !strings.Contains(err.Error(), "boom") {
		t.Errorf("err = %v", err)
	}
	if rec.Body.Len() != 0 || len(rec.Header()) != 0 {
		t.Errorf("an error wrote something: %q %v", rec.Body.String(), rec.Header())
	}

	rec = httptest.NewRecorder()
	func() {
		defer func() {
			if recover() == nil {
				t.Error("the panic should propagate to the handler's recovery")
			}
		}()
		_ = g.views.Page(rec, req, "T", kit.Button(kit.ButtonProps{Variant: "huge"}))
	}()
	if rec.Body.Len() != 0 || len(rec.Header()) != 0 {
		t.Errorf("a panic wrote something: %q %v", rec.Body.String(), rec.Header())
	}

	// a Datastar patch that fails to render sends no event
	dreq := httptest.NewRequest("GET", "/", http.NoBody)
	dreq.Header.Set("Datastar-Request", "true")
	rec = httptest.NewRecorder()
	if err := g.views.Page(rec, dreq, "T", failing{}); err == nil {
		t.Error("want the render error")
	}
	if strings.Contains(rec.Body.String(), "event:") {
		t.Errorf("a failing patch was sent: %s", rec.Body.String())
	}
}

func TestIconsUseTheAssetsSpriteURLInDocumentsAndPatches(t *testing.T) {
	a, err := assets.Load(assets.WithPrefix("/ui/assets/"))
	if err != nil {
		t.Fatal(err)
	}
	v, err := views.New(a, views.WithShell(shell))
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	if err := v.Page(rec, httptest.NewRequest("GET", "/", http.NoBody), "T", text("")); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(rec.Body.String(), `href="/ui/assets/lucide-sprite.svg#i-`) || strings.Contains(rec.Body.String(), `"/icons/lucide-sprite.svg`) {
		t.Error("the document's icons do not use the assets' sprite URL")
	}
	if !strings.Contains(rec.Body.String(), "/ui/assets/kit.css?v=") {
		t.Error("prefix not used for the stylesheet")
	}
	srec := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/", http.NoBody)
	req.Header.Set("Datastar-Request", "true")
	if err := v.Stream(srec, req, func(p *views.Patches) error {
		return p.Flash(kit.FlashError, "Bad", "detail")
	}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(srec.Body.String(), "/ui/assets/lucide-sprite.svg#i-") {
		t.Errorf("patch icons: %s", srec.Body.String())
	}
}

func TestAssetsAreServedHashedCachedAndConditional(t *testing.T) {
	g := newRig(t, false)
	css := g.get(g.assets.URL("kit.css"))
	if css.Code != 200 || !strings.HasPrefix(css.Header().Get("Content-Type"), "text/css") ||
		!strings.Contains(css.Header().Get("Cache-Control"), "immutable") || !strings.Contains(css.body(), "--background") {
		t.Fatalf("css: %d %v", css.Code, css.Header())
	}
	if again := g.get(g.assets.URL("kit.css"), "If-None-Match", css.Header().Get("ETag")); again.Code != 304 {
		t.Errorf("conditional: %d", again.Code)
	}
	if g.get("/fw-ui/other.js").Code != 404 {
		t.Error("an unknown asset is 404")
	}
}
