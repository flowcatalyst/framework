package views_test

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"testing"

	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
)

// goldenDocument renders the default document (no look option) for one request.
func goldenDocument(t *testing.T, withShell bool) string {
	t.Helper()
	opts := []views.Option{
		views.WithTitleSuffix(" - Demo"), views.WithLogin("/auth/login", ""), views.WithFlashes(flashes),
		views.WithPlainHTTPCookies(),
	}
	if withShell {
		opts = append(opts, views.WithShell(shell))
	}
	v, err := views.New(loadAssets(t), opts...)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("GET", "/orders?q=x", http.NoBody)
	req.AddCookie(&http.Cookie{Name: "fc_theme", Value: "dark"})
	req.AddCookie(&http.Cookie{Name: "fc_sidebar", Value: "collapsed"})
	req.AddCookie(&http.Cookie{Name: views.FlashCookie, Value: "saved"})
	rec := httptest.NewRecorder()
	err = v.Page(rec, req, "Orders", searched(req),
		views.WithPageFlashes(kit.FlashMessageProps{Kind: kit.FlashWarn, Summary: "Careful.", Detail: "Check it."}))
	if err != nil {
		t.Fatal(err)
	}
	return rec.Body.String()
}

// goldenPatch renders a Datastar answer with a flash patch.
func goldenPatch(t *testing.T) string {
	t.Helper()
	v, err := views.New(loadAssets(t))
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("GET", "/orders/stream", http.NoBody)
	req.Header.Set("Datastar-Request", "true")
	rec := httptest.NewRecorder()
	err = v.Stream(rec, req, func(p *views.Patches) error {
		return errors.Join(p.Flash(kit.FlashError, "Failed.", "Try again."), p.Flash(kit.FlashSuccess, "Saved.", ""))
	})
	if err != nil {
		t.Fatal(err)
	}
	return rec.Body.String()
}

// assetVersion is the content hash in an asset URL. It changes whenever a served file is rebuilt
// (kit.css after any class is added), which is not what this test is about, so both sides are compared
// with it masked. A deliberate change to the kit's shell or flash markup does change these documents:
// rewrite the files with GOLDEN_WRITE=1 in the same commit.
var assetVersion = regexp.MustCompile(`\?v=[0-9a-f]{12}`)

// TestDefaultOutputIsByteIdenticalToTheCapturedGoldens pins that without a look option every byte the
// views write is what they wrote before the look seam existed (captured from that tree into testdata/).
func TestDefaultOutputIsByteIdenticalToTheCapturedGoldens(t *testing.T) {
	cases := map[string]string{
		"document-bare.html":  goldenDocument(t, false),
		"document-shell.html": goldenDocument(t, true),
		"patch-flash.sse":     goldenPatch(t),
	}
	for name, got := range cases {
		path := filepath.Join("testdata", name)
		if os.Getenv("GOLDEN_WRITE") == "1" {
			if err := os.MkdirAll("testdata", 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(got), 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		want, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if assetVersion.ReplaceAllString(got, "?v=X") != assetVersion.ReplaceAllString(string(want), "?v=X") {
			t.Errorf("%s differs from the golden output", name)
		}
	}
}
