package views_test

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/flowcatalyst/framework/go/ui/i18n"
	"github.com/flowcatalyst/framework/go/ui/views"
)

func appBundle(t *testing.T) *i18n.Bundle {
	t.Helper()
	layer, err := i18n.LoadFS(fstest.MapFS{
		"i18n/af.json": {Data: []byte(`{"flash.saved": "Gestoor.", "flash.saved.detail": "Die bestelling is gestoor."}`)},
		"i18n/en.json": {Data: []byte(`{"flash.gone": "Removed."}`)},
	}, "i18n")
	if err != nil {
		t.Fatal(err)
	}
	return i18n.Kit().With(layer)
}

func localeRig(t *testing.T) *rig {
	return newRig(t, true, views.WithLocales(i18n.Config{Supported: []string{"en", "af"}, Bundle: appBundle(t)}))
}

func TestTheDocumentLangIsTheResolvedLocale(t *testing.T) {
	g := localeRig(t)
	for _, c := range []struct{ accept, lang string }{{"", "en"}, {"af-ZA,af;q=0.9,en;q=0.5", "af"}, {"fr", "en"}} {
		var headers []string
		if c.accept != "" {
			headers = []string{"Accept-Language", c.accept}
		}
		body := g.get("/orders", headers...).body()
		if !strings.Contains(body, `<html lang="`+c.lang+`"`) {
			t.Errorf("Accept-Language %q: want lang %q in %.120s", c.accept, c.lang, body)
		}
	}
}

func TestAPageWithoutLocalesIsEnglish(t *testing.T) {
	g := newRig(t, true)
	body := g.get("/orders", "Accept-Language", "af").body()
	if !strings.Contains(body, `<html lang="en"`) || !strings.Contains(body, "Sign Out") {
		t.Errorf("a page without WithLocales is not English: %.200s", body)
	}
}

func TestTheShellSpeaksTheResolvedLanguage(t *testing.T) {
	g := localeRig(t)
	af := g.get("/orders", "Accept-Language", "af").body()
	for _, want := range []string{"Teken uit", "Profiel", "Weergawe", `aria-label="Maak navigasie oop"`} {
		if !strings.Contains(af, want) {
			t.Errorf("Afrikaans shell lacks %q", want)
		}
	}
	if strings.Contains(af, "Sign Out") {
		t.Error("English text left in the Afrikaans shell")
	}
}

func TestAMiddlewareLocaleIsKeptByTheViews(t *testing.T) {
	g := localeRig(t)
	// the views resolve themselves unless a middleware already stored a locale
	rec := g.get("/orders", "Accept-Language", "af")
	if !strings.Contains(rec.body(), `lang="af"`) {
		t.Fatal("not af")
	}
	h := i18n.Middleware(i18n.Config{Supported: []string{"en", "af"}, User: func(*http.Request) string { return "en" }})(g.handler)
	req := httptest.NewRequest(http.MethodGet, "/orders", nil)
	req.Header.Set("Accept-Language", "af")
	rr := newRecorder()
	h.ServeHTTP(rr, req)
	if !strings.Contains(rr.Body.String(), `lang="en"`) {
		t.Errorf("the middleware's locale was replaced: %.200s", rr.Body.String())
	}
}

func TestAFlashComesFromTheCatalogWhenItHasTheKey(t *testing.T) {
	g := localeRig(t)
	af := g.get("/orders", "Accept-Language", "af", "Cookie", "fw_flash=saved").body()
	if !strings.Contains(af, "Gestoor.") || !strings.Contains(af, "Die bestelling is gestoor.") || strings.Contains(af, "The order was saved.") {
		t.Errorf("flash.saved was not used in Afrikaans")
	}
	en := g.get("/orders", "Cookie", "fw_flash=saved").body()
	if !strings.Contains(en, "Saved.") || !strings.Contains(en, "The order was saved.") {
		t.Errorf("English keeps the registered message")
	}
	// the registered message when the catalogs have no flash.<key>
	gone := g.get("/orders", "Accept-Language", "af", "Cookie", "fw_flash=gone").body()
	if !strings.Contains(gone, "Removed.") { // flash.gone exists in en: the fallback chain reaches it
		t.Errorf("flash.gone: %.0s", gone)
	}
}

func TestADatastarPatchIsRenderedInTheResolvedLanguage(t *testing.T) {
	g := localeRig(t)
	body := g.datastar("/orders/stream", "Accept-Language", "af").body()
	if !strings.Contains(body, "Maak kennisgewing toe") { // the flash message's dismiss button
		t.Errorf("patch not in Afrikaans: %.300s", body)
	}
}

func newRecorder() *httptest.ResponseRecorder { return httptest.NewRecorder() }
