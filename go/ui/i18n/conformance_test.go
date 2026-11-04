package i18n_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/flowcatalyst/framework/go/ui/i18n"
)

// TestConformance compares the package with conformance/i18n/cases.json, which the
// Java kit's I18nConformanceTest writes: lookup and fallback over layered catalogs,
// placeholders, plural categories, locale resolution and validation rendering.

type vector struct {
	Layers []map[string]map[string]json.RawMessage `json:"layers"`
	Lookup []struct {
		Locale string         `json:"locale"`
		Key    string         `json:"key"`
		Args   map[string]any `json:"args"`
		Expect string         `json:"expect"`
	} `json:"lookup"`
	Format []struct {
		Text   string         `json:"text"`
		Args   map[string]any `json:"args"`
		Expect string         `json:"expect"`
	} `json:"format"`
	Placeholders []struct {
		Text   string   `json:"text"`
		Expect []string `json:"expect"`
	} `json:"placeholders"`
	Plurals []struct {
		Lang   string `json:"lang"`
		Count  string `json:"count"`
		Expect string `json:"expect"`
	} `json:"plurals"`
	Resolve []struct {
		Supported      []string `json:"supported"`
		User           *string  `json:"user"`
		Tenant         *string  `json:"tenant"`
		AcceptLanguage *string  `json:"acceptLanguage"`
		Expect         string   `json:"expect"`
	} `json:"resolve"`
	Validation []struct {
		Locale  string         `json:"locale"`
		Code    string         `json:"code"`
		Params  map[string]any `json:"params"`
		Message string         `json:"message"`
		Expect  string         `json:"expect"`
	} `json:"validation"`
}

func readVector(t *testing.T) vector {
	t.Helper()
	path := filepath.Join("..", "..", "..", "conformance", "i18n", "cases.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("conformance vector: %v", err)
	}
	var v vector
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber() // a number argument is the text it was written with
	if err := dec.Decode(&v); err != nil {
		t.Fatalf("conformance vector %s: %v", path, err)
	}
	return v
}

func bundleOf(t *testing.T, v vector) *i18n.Bundle {
	t.Helper()
	var layers []i18n.Layer
	for _, l := range v.Layers {
		layer := i18n.Layer{}
		for tag, raw := range l {
			data, err := json.Marshal(raw)
			if err != nil {
				t.Fatal(err)
			}
			c, err := i18n.ParseCatalog(data)
			if err != nil {
				t.Fatalf("catalog %s: %v", tag, err)
			}
			layer[tag] = c
		}
		layers = append(layers, layer)
	}
	return i18n.NewBundle(layers...)
}

func TestConformanceLookup(t *testing.T) {
	v := readVector(t)
	b := bundleOf(t, v)
	if len(v.Lookup) == 0 {
		t.Fatal("no lookup cases")
	}
	for _, c := range v.Lookup {
		if got := b.Message(c.Locale, c.Key, i18n.Args(c.Args)); got != c.Expect {
			t.Errorf("lookup(%q, %q, %v) = %q, want %q", c.Locale, c.Key, c.Args, got, c.Expect)
		}
	}
}

func TestConformanceFormat(t *testing.T) {
	v := readVector(t)
	if len(v.Format) == 0 {
		t.Fatal("no format cases")
	}
	for _, c := range v.Format {
		if got := i18n.Format(c.Text, i18n.Args(c.Args)); got != c.Expect {
			t.Errorf("format(%q) = %q, want %q", c.Text, got, c.Expect)
		}
	}
	for _, c := range v.Placeholders {
		got := i18n.Placeholders(c.Text)
		if got == nil {
			got = []string{}
		}
		if !slices.Equal(got, c.Expect) {
			t.Errorf("placeholders(%q) = %v, want %v", c.Text, got, c.Expect)
		}
	}
}

func TestConformancePlurals(t *testing.T) {
	v := readVector(t)
	if len(v.Plurals) == 0 {
		t.Fatal("no plural cases")
	}
	for _, c := range v.Plurals {
		if got := i18n.PluralCategory(c.Lang, c.Count); got != c.Expect {
			t.Errorf("plural(%q, %q) = %q, want %q", c.Lang, c.Count, got, c.Expect)
		}
	}
}

func TestConformanceResolve(t *testing.T) {
	v := readVector(t)
	if len(v.Resolve) == 0 {
		t.Fatal("no resolve cases")
	}
	for _, c := range v.Resolve {
		cfg := i18n.Config{Supported: c.Supported}
		if c.User != nil {
			u := *c.User
			cfg.User = func(*http.Request) string { return u }
		}
		if c.Tenant != nil {
			tn := *c.Tenant
			cfg.Tenant = func(*http.Request) string { return tn }
		}
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		if c.AcceptLanguage != nil {
			r.Header.Set("Accept-Language", *c.AcceptLanguage)
		}
		if got := i18n.NewResolver(cfg).Resolve(r); got != c.Expect {
			t.Errorf("resolve(%v user=%v tenant=%v accept=%v) = %q, want %q", c.Supported, deref(c.User), deref(c.Tenant), deref(c.AcceptLanguage), got, c.Expect)
		}
	}
}

func deref(s *string) string {
	if s == nil {
		return "<nil>"
	}
	return *s
}

func TestConformanceValidation(t *testing.T) {
	v := readVector(t)
	b := bundleOf(t, v)
	if len(v.Validation) == 0 {
		t.Fatal("no validation cases")
	}
	for _, c := range v.Validation {
		ctx := i18n.WithBundle(i18n.WithLocale(t.Context(), c.Locale), b)
		if got := i18n.Validation(ctx, c.Code, i18n.Args(c.Params), c.Message); got != c.Expect {
			t.Errorf("validation(%q, %q, %v) = %q, want %q", c.Locale, c.Code, c.Params, got, c.Expect)
		}
	}
}
