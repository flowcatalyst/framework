package views_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/look"
	"github.com/flowcatalyst/framework/go/ui/views"
)

type ctxKey struct{}

// testLook is a look with markup of its own, to tell what the views took from it. It records what it
// was asked.
type testLook struct {
	sheet     string
	shellSeen []bool
}

func (l *testLook) Stylesheet() string { return l.sheet }

func (*testLook) Context(ctx context.Context, a *assets.Assets) context.Context {
	return context.WithValue(ctx, ctxKey{}, "ctx:"+a.SpriteURL())
}

func (l *testLook) HTMLAttrs(_ *http.Request, shell bool) templ.Attributes {
	l.shellSeen = append(l.shellSeen, shell)
	return templ.Attributes{"data-test-look": "html"}
}

func (*testLook) Main() templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		if _, err := io.WriteString(w, `<main class="test-main">`); err != nil {
			return err
		}
		if err := templ.GetChildren(ctx).Render(ctx, w); err != nil {
			return err
		}
		_, err := io.WriteString(w, `</main>`)
		return err
	})
}

func (*testLook) FlashStack(id string) templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		if _, err := io.WriteString(w, `<section id="`+id+`" class="test-stack">`); err != nil {
			return err
		}
		if err := templ.GetChildren(ctx).Render(ctx, w); err != nil {
			return err
		}
		_, err := io.WriteString(w, `</section>`)
		return err
	})
}

func (*testLook) FlashMessage(m look.Flash) templ.Component {
	return templ.ComponentFunc(func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, `<p class="test-flash test-flash-`+string(m.Kind)+`">`+templ.EscapeString(m.Summary)+`</p>`)
		return err
	})
}

// ownAssets is the module's assets plus a.css, b.css and own.css and a script that is no stylesheet.
func ownAssets(t *testing.T) *assets.Assets {
	t.Helper()
	fsys := fstest.MapFS{
		"own.css": {Data: []byte("/* own */")}, "a.css": {Data: []byte("/* a */")},
		"b.css": {Data: []byte("/* b */")}, "x.js": {Data: []byte("// x")},
	}
	a, err := assets.Load(assets.WithFiles(fsys, "own.css", "a.css", "b.css", "x.js"))
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func newLookViews(t *testing.T, l look.Look, opts ...views.Option) *views.Views {
	t.Helper()
	base := []views.Option{
		views.WithLook(l), views.WithFlashes(flashes), views.WithPlainHTTPCookies(),
	}
	v, err := views.New(ownAssets(t), append(base, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func document(t *testing.T, v *views.Views, cookie string) string {
	t.Helper()
	req := httptest.NewRequest("GET", "/orders", http.NoBody)
	if cookie != "" {
		req.AddCookie(&http.Cookie{Name: views.FlashCookie, Value: cookie})
	}
	rec := httptest.NewRecorder()
	err := v.Page(rec, req, "Orders", text("<i>page</i>"),
		views.WithPageFlashes(look.Flash{Kind: look.FlashWarn, Summary: "From the page."}))
	if err != nil {
		t.Fatal(err)
	}
	return rec.Body.String()
}

func TestADocumentIsRenderedThroughTheLook(t *testing.T) {
	l := &testLook{sheet: "own.css"}
	body := document(t, newLookViews(t, l), "saved")
	for _, want := range []string{
		`<link rel="stylesheet" href="/fw-ui/own.css?v=`,
		`<html lang="en" data-test-look="html">`,
		`<main class="test-main"><section id="flash-stack" class="test-stack">` +
			`<p class="test-flash test-flash-success">Saved.</p>` +
			`<p class="test-flash test-flash-warn">From the page.</p></section>` +
			`<div id="fw-page"><i>page</i></div></main>`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("the document does not contain %q:\n%s", want, body)
		}
	}
	for _, not := range []string{"kit.css", "fc-flash", "mx-auto max-w-xl"} {
		if strings.Contains(body, not) {
			t.Errorf("the document contains the module kit's %q", not)
		}
	}
}

func TestPatchesFlashRendersTheLooksFlashMessage(t *testing.T) {
	v := newLookViews(t, &testLook{sheet: "own.css"})
	req := httptest.NewRequest("GET", "/orders", http.NoBody)
	req.Header.Set(ds.RequestHeader, "true")
	rec := httptest.NewRecorder()
	if err := v.Stream(rec, req, func(p *views.Patches) error {
		return p.Flash(look.FlashError, "Failed.", "detail")
	}); err != nil {
		t.Fatal(err)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `<p class="test-flash test-flash-error">Failed.</p>`) || strings.Contains(body, "fc-flash") {
		t.Errorf("the patch is not the look's flash message:\n%s", body)
	}
}

func TestViewsContextReturnsWhatTheLookAdds(t *testing.T) {
	v := newLookViews(t, &testLook{sheet: "own.css"})
	ctx := v.Context(httptest.NewRequest("GET", "/", http.NoBody))
	if got := ctx.Value(ctxKey{}); got != "ctx:/fw-ui/lucide-sprite.svg" {
		t.Errorf("the context value = %v", got)
	}
}

// ownShell is an application's own shell: a wrapper with the flash stack and the page as children.
func ownShell(*http.Request) templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		if _, err := io.WriteString(w, `<div class="own-shell">`); err != nil {
			return err
		}
		if err := templ.GetChildren(ctx).Render(ctx, w); err != nil {
			return err
		}
		_, err := io.WriteString(w, `</div>`)
		return err
	})
}

func TestAShellComponentWrapsTheFlashStackAndThePage(t *testing.T) {
	l := &testLook{sheet: "own.css"}
	body := document(t, newLookViews(t, l, views.WithShellComponent(ownShell)), "")
	want := `<div class="own-shell"><section id="flash-stack" class="test-stack">` +
		`<p class="test-flash test-flash-warn">From the page.</p></section> <div id="fw-page"><i>page</i></div></div>`
	if !strings.Contains(body, want) {
		t.Errorf("the page is not inside the shell:\n%s", body)
	}
	if strings.Contains(body, "test-main") {
		t.Error("a document with a shell has the bare main")
	}
	if len(l.shellSeen) != 1 || !l.shellSeen[0] {
		t.Errorf("HTMLAttrs saw shell = %v, want [true]", l.shellSeen)
	}

	l = &testLook{sheet: "own.css"}
	document(t, newLookViews(t, l), "")
	if len(l.shellSeen) != 1 || l.shellSeen[0] {
		t.Errorf("without a shell HTMLAttrs saw shell = %v, want [false]", l.shellSeen)
	}

	l = &testLook{sheet: "own.css"}
	document(t, newLookViews(t, l, views.WithShell(shell)), "")
	if len(l.shellSeen) != 1 || !l.shellSeen[0] {
		t.Errorf("with the kit's shell props HTMLAttrs saw shell = %v, want [true]", l.shellSeen)
	}
}

func TestBothShellOptionsAreRefused(t *testing.T) {
	_, err := views.New(ownAssets(t), views.WithLook(&testLook{sheet: "own.css"}),
		views.WithShell(shell), views.WithShellComponent(ownShell))
	if err == nil || !strings.Contains(err.Error(), "WithShell") {
		t.Fatalf("New accepted both shell options: %v", err)
	}
}

func TestStylesheetsAreLinkedAfterTheLooksOwnOnceEach(t *testing.T) {
	v := newLookViews(t, &testLook{sheet: "own.css"}, views.WithStylesheets("a.css", "b.css", "a.css"))
	body := document(t, v, "")
	var got []string
	for _, part := range strings.Split(body, `<link rel="stylesheet" href="/fw-ui/`)[1:] {
		name, _, _ := strings.Cut(part, "?")
		got = append(got, name)
	}
	if strings.Join(got, ",") != "own.css,a.css,b.css" {
		t.Errorf("stylesheets = %v, want own.css, a.css, b.css", got)
	}
}

func TestNewRefusesABadStylesheetOrLook(t *testing.T) {
	a := ownAssets(t)
	good := &testLook{sheet: "own.css"}
	cases := map[string][]views.Option{
		"an extra stylesheet that is no asset": {views.WithLook(good), views.WithStylesheets("missing.css")},
		"an extra stylesheet that is no .css":  {views.WithLook(good), views.WithStylesheets("x.js")},
		"a look stylesheet that is no asset":   {views.WithLook(&testLook{sheet: "nope.css"})},
		"a look stylesheet that is no .css":    {views.WithLook(&testLook{sheet: "x.js"})},
		"a nil look":                           {views.WithLook(nil)},
		"the default look with a stray sheet":  {views.WithStylesheets("kit.css", "gone.css")},
	}
	for name, opts := range cases {
		if _, err := views.New(a, opts...); err == nil {
			t.Errorf("New accepted %s", name)
		}
	}
	_, err := views.New(a, views.WithLook(&testLook{sheet: "nope.css"}))
	if err == nil || !strings.Contains(err.Error(), `"nope.css"`) || !strings.Contains(err.Error(), "own.css") {
		t.Errorf("the error should name the file and list the assets: %v", err)
	}
}

// A shell component that gives no shell for a request (a sign-in page, say) gets the bare document, and
// the look is told so.
func TestAShellComponentThatReturnsNilRendersWithoutAShell(t *testing.T) {
	l := &testLook{sheet: "own.css"}
	v := newLookViews(t, l, views.WithShellComponent(func(*http.Request) templ.Component { return nil }))
	body := document(t, v, "")
	if !strings.Contains(body, `<main class="test-main">`) {
		t.Errorf("the document is not the bare one:\n%s", body)
	}
	if len(l.shellSeen) != 1 || l.shellSeen[0] {
		t.Errorf("HTMLAttrs saw shell = %v, want one false", l.shellSeen)
	}
}
