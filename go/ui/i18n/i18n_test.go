package i18n_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"slices"
	"sort"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/flowcatalyst/framework/go/ui/i18n"
)

func TestParseCatalogRefusesWhatTheContractRefuses(t *testing.T) {
	for name, src := range map[string]string{
		"not an object":           `["a"]`,
		"key without a namespace": `{"hello": "x"}`,
		"key with a space":        `{"kit.a b": "x"}`,
		"upper-case namespace":    `{"Kit.a": "x"}`,
		"number message":          `{"kit.a": 1}`,
		"plural without other":    `{"kit.a": {"one": "x"}}`,
		"unknown category":        `{"kit.a": {"few2": "x", "other": "y"}}`,
		"plural of numbers":       `{"kit.a": {"other": 1}}`,
	} {
		if _, err := i18n.ParseCatalog([]byte(src)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	c, err := i18n.ParseCatalog([]byte(`{"kit.a": "A", "validation.TOO_SHORT": "x", "app.x-y_z.k": {"one": "1", "other": "n"}}`))
	if err != nil || len(c) != 3 {
		t.Fatalf("valid catalog: %v %v", c, err)
	}
}

func TestLookupFallsBackFromTagToLanguageToEnglish(t *testing.T) {
	parse := func(s string) i18n.Catalog {
		c, err := i18n.ParseCatalog([]byte(s))
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	b := i18n.NewBundle(i18n.Layer{
		"en":    parse(`{"t.a": "en a", "t.b": "en b", "t.c": "en c"}`),
		"af":    parse(`{"t.a": "af a", "t.b": "af b"}`),
		"af-ZA": parse(`{"t.a": "af-ZA a"}`),
	})
	for _, c := range []struct{ locale, key, want string }{
		{"af-ZA", "t.a", "af-ZA a"},
		{"af-ZA", "t.b", "af b"}, // the language step
		{"af-ZA", "t.c", "en c"}, // English
		{"af-za", "t.a", "af-ZA a"},
		{"af-NA", "t.a", "af a"},
		{"zu", "t.a", "en a"},
		{"", "t.a", "en a"},
		{"af", "t.missing", "t.missing"}, // the key itself
	} {
		if got := b.Message(c.locale, c.key); got != c.want {
			t.Errorf("Message(%q, %q) = %q, want %q", c.locale, c.key, got, c.want)
		}
	}
}

func TestAnApplicationLayerOverridesTheKit(t *testing.T) {
	app := fstest.MapFS{"i18n/en.json": {Data: []byte(`{"kit.dialog.cancel": "Never mind"}`)}, "i18n/readme.txt": {Data: []byte("x")}}
	layer, err := i18n.LoadFS(app, "i18n")
	if err != nil {
		t.Fatal(err)
	}
	b := i18n.Kit().With(layer)
	if got := b.Message("en", "kit.dialog.cancel"); got != "Never mind" {
		t.Errorf("override: %q", got)
	}
	if got := b.Message("af", "kit.dialog.cancel"); got != "Kanselleer" { // the kit's own Afrikaans still beats an English override
		t.Errorf("af: %q", got)
	}
	if got := i18n.Kit().Message("en", "kit.dialog.cancel"); got != "Cancel" {
		t.Errorf("the kit bundle was changed by With: %q", got)
	}
}

func TestMissingKeyIsReported(t *testing.T) {
	var got []string
	b := i18n.Kit().OnMissing(func(locale, key string) { got = append(got, locale+":"+key) })
	b.Message("af", "kit.nope.nothing")
	b.Message("af", "kit.dialog.cancel")
	if !slices.Equal(got, []string{"af:kit.nope.nothing"}) {
		t.Errorf("missing = %v", got)
	}
}

func TestContextDefaultsToTheKitInEnglish(t *testing.T) {
	ctx := context.Background()
	if got := i18n.FromContext(ctx); got != "en" {
		t.Errorf("FromContext = %q", got)
	}
	if got := i18n.T(ctx, "kit.dialog.close"); got != "Close" {
		t.Errorf("T = %q", got)
	}
	if got := i18n.T(i18n.WithLocale(ctx, "af-za"), "kit.dialog.close"); got != "Maak toe" {
		t.Errorf("T(af-ZA) = %q", got)
	}
	if got := i18n.T(i18n.WithLocale(ctx, "af"), "kit.paginator.showing", i18n.Args{"from": 1, "to": 10, "total": 55, "noun": "rye"}); got != "Wys 1 tot 10 van 55 rye" {
		t.Errorf("T with args = %q", got)
	}
}

func TestMiddlewareResolvesOnceAndStoresTheLocale(t *testing.T) {
	var seen, text string
	h := i18n.Middleware(i18n.Config{
		Supported: []string{"af"},
		User:      func(r *http.Request) string { return r.Header.Get("X-User-Locale") },
		Tenant:    func(r *http.Request) string { return r.Header.Get("X-Tenant-Locale") },
	})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = i18n.FromContext(r.Context())
		text = i18n.T(r.Context(), "kit.dialog.cancel")
	}))
	run := func(headers map[string]string) string {
		seen, text = "", ""
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		h.ServeHTTP(httptest.NewRecorder(), r)
		return seen
	}
	for _, c := range []struct {
		headers map[string]string
		want    string
	}{
		{nil, "en"},
		{map[string]string{"Accept-Language": "af-ZA, en;q=0.5"}, "af"},
		{map[string]string{"X-Tenant-Locale": "af", "Accept-Language": "en"}, "af"},
		{map[string]string{"X-User-Locale": "en", "X-Tenant-Locale": "af", "Accept-Language": "af"}, "en"},
		{map[string]string{"X-User-Locale": "fr", "X-Tenant-Locale": "xx", "Accept-Language": "af;q=0.2, fr"}, "af"},
	} {
		if got := run(c.headers); got != c.want {
			t.Errorf("%v: locale %q, want %q", c.headers, got, c.want)
		}
	}
	run(map[string]string{"X-User-Locale": "af"})
	if text != "Kanselleer" {
		t.Errorf("T after the middleware = %q", text)
	}
}

func TestValidationFallsBackToTheErrorsOwnMessage(t *testing.T) {
	af := i18n.WithLocale(context.Background(), "af")
	if got := i18n.Validation(af, "TOO_SHORT", i18n.Args{"min": 8}, "must be at least 8 characters"); got != "moet ten minste 8 karakters lank wees" {
		t.Errorf("translated: %q", got)
	}
	if got := i18n.Validation(af, "TOO_SHORT", nil, "must be at least 8 characters"); got != "must be at least 8 characters" {
		t.Errorf("no params must fall back, got %q", got)
	}
	if got := i18n.Validation(af, "MY_RULE", nil, "own text"); got != "own text" {
		t.Errorf("unknown code: %q", got)
	}
	if got := i18n.Validation(context.Background(), "NOT_NULL", nil, "x"); got != "is required" {
		t.Errorf("english: %q", got)
	}
}

// ---- the checks of contract section 6, on the kit's catalogs

func TestKitCatalogsAreConsistent(t *testing.T) {
	cats, err := i18n.KitCatalogs()
	if err != nil {
		t.Fatal(err)
	}
	en, ok := cats["en"]
	if !ok || len(en) == 0 {
		t.Fatal("no English catalog")
	}
	for tag, c := range cats {
		for key, m := range c {
			e, ok := en[key]
			if !ok {
				t.Errorf("%s: key %q is not in en", tag, key)
				continue
			}
			if tag == "en" {
				continue
			}
			if (m.Plural != nil) != (e.Plural != nil) {
				t.Errorf("%s: %q is plural in one of en and %s only", tag, key, tag)
				continue
			}
			enPH, trPH := placeholders(e), placeholders(m)
			for _, name := range trPH {
				if !slices.Contains(enPH, name) {
					t.Errorf("%s: %q uses {%s}, which the English message does not have", tag, key, name)
				}
			}
			if !slices.Equal(enPH, trPH) {
				t.Errorf("%s: %q has the placeholders %v, English has %v", tag, key, trPH, enPH)
			}
			if m.Plural != nil {
				if _, ok := m.Plural["other"]; !ok {
					t.Errorf("%s: plural %q has no other", tag, key)
				}
			}
		}
	}
}

// placeholders are the placeholder names of every text of a message.
func placeholders(m i18n.Message) []string {
	var texts []string
	if m.Plural != nil {
		for _, t := range m.Plural {
			texts = append(texts, t)
		}
	} else {
		texts = []string{m.Text}
	}
	set := map[string]bool{}
	for _, t := range texts {
		for _, n := range i18n.Placeholders(t) {
			set[n] = true
		}
	}
	out := make([]string, 0, len(set))
	for n := range set {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

func TestEveryValidationCodeHasAMessage(t *testing.T) {
	cats, err := i18n.KitCatalogs()
	if err != nil {
		t.Fatal(err)
	}
	for _, code := range []string{"NOT_NULL", "NOT_BLANK", "TOO_SHORT", "TOO_LONG", "PATTERN", "OUT_OF_RANGE", "SIZE", "NOT_ONE_OF"} {
		for tag, c := range cats {
			if _, ok := c["validation."+code]; !ok {
				t.Errorf("%s lacks validation.%s", tag, code)
			}
		}
	}
}

// TestUntranslatedReport lists, without failing, the English keys each other catalog lacks.
func TestUntranslatedReport(t *testing.T) {
	cats, err := i18n.KitCatalogs()
	if err != nil {
		t.Fatal(err)
	}
	for tag, c := range cats {
		if tag == "en" {
			continue
		}
		var missing []string
		for key := range cats["en"] {
			if _, ok := c[key]; !ok {
				missing = append(missing, key)
			}
		}
		sort.Strings(missing)
		t.Logf("%s: %d of %d keys translated; untranslated: [%s]", tag, len(cats["en"])-len(missing), len(cats["en"]), strings.Join(missing, " "))
	}
}

func TestResolverMatchesThefullTagThenTheLanguage(t *testing.T) {
	r := i18n.NewResolver(i18n.Config{Supported: []string{"af-ZA", "zu"}})
	for in, want := range map[string]string{"af-ZA": "af-ZA", "af": "af-ZA", "af-NA": "af-ZA", "zu-ZA": "zu", "en-GB": "en", "xx": "", "": ""} {
		if got := r.Match(in); got != want {
			t.Errorf("Match(%q) = %q, want %q", in, got, want)
		}
	}
	if !slices.Equal(r.Supported(), []string{"af-ZA", "zu", "en"}) {
		t.Errorf("Supported = %v (en is always added)", r.Supported())
	}
}
