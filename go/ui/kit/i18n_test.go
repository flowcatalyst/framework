package kit

import (
	"bytes"
	"context"
	"encoding/json"
	"html"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/i18n"
)

// The kit's user-visible text is the catalog's (docs/contracts/i18n.md sections 4 and 6): every
// fixture renders with no message missing in English and in Afrikaans, every key the sources use
// is in the English catalog, and every English kit key is used by the sources.

// eachFixture renders every fixture of every component with ctx.
func eachFixture(t *testing.T, ctx context.Context, fn func(name, html string)) {
	t.Helper()
	root := fixturesDir(t)
	dirs, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, d := range dirs {
		render, ok := renderers[d.Name()]
		if !d.IsDir() || !ok {
			continue
		}
		cases, _ := filepath.Glob(filepath.Join(root, d.Name(), "*.json"))
		for _, jsonPath := range cases {
			raw, err := os.ReadFile(jsonPath)
			if err != nil {
				t.Fatal(err)
			}
			f := &fixture{used: map[string]bool{}}
			dec := json.NewDecoder(bytes.NewReader(raw))
			dec.DisallowUnknownFields()
			if err := dec.Decode(f); err != nil {
				t.Fatalf("fixture %s: %v", jsonPath, err)
			}
			c, err := render(f)
			if err != nil {
				t.Fatalf("fixture %s: %v", jsonPath, err)
			}
			var out strings.Builder
			name := d.Name() + "/" + strings.TrimSuffix(filepath.Base(jsonPath), ".json")
			if err := c.Render(ctx, &out); err != nil {
				t.Fatalf("render %s: %v", name, err)
			}
			fn(name, out.String())
			n++
		}
	}
	if n == 0 {
		t.Fatal("no fixtures ran")
	}
}

func recordingContext(locale string, missing *[]string) context.Context {
	b := i18n.Kit().OnMissing(func(loc, key string) { *missing = append(*missing, loc+" "+key) })
	return i18n.WithBundle(i18n.WithLocale(context.Background(), locale), b)
}

func TestEveryFixtureRendersWithoutAMissingMessage(t *testing.T) {
	for _, locale := range []string{"en", "af", "af-ZA", "zu", "xx"} {
		var missing []string
		eachFixture(t, recordingContext(locale, &missing), func(string, string) {})
		if len(missing) > 0 {
			t.Errorf("%s: messages missing (a raw key would be rendered): %v", locale, missing)
		}
	}
}

func TestEnglishRenderingNeverShowsAKey(t *testing.T) {
	keyText := regexp.MustCompile(`\bkit\.[a-z0-9_]+\.[a-z0-9_.]+`)
	eachFixture(t, context.Background(), func(name, html string) {
		if m := keyText.FindString(html); m != "" {
			t.Errorf("%s renders the raw key %q", name, m)
		}
	})
}

func TestAfrikaansRendersTheAfrikaansCatalog(t *testing.T) {
	af := i18n.WithLocale(context.Background(), "af")
	en := context.Background()
	differs := 0
	enOut, afOut := map[string]string{}, map[string]string{}
	eachFixture(t, en, func(name, html string) { enOut[name] = html })
	eachFixture(t, af, func(name, html string) { afOut[name] = html })
	for name, h := range enOut {
		if afOut[name] != h {
			differs++
		}
	}
	if differs < 10 {
		t.Errorf("only %d fixtures render differently in Afrikaans; the kit's text is not coming from the catalog", differs)
	}
	var sb strings.Builder
	if err := Paginator(PaginatorProps{Page: 2, Pages: 5, Total: 50, Rows: 10, Noun: "rye"}).Render(af, &sb); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`aria-label="Eerste"`, `aria-label="Vorige"`, `aria-label="Volgende"`, `aria-label="Laaste"`, "Wys 11 tot 20 van 50 rye"} {
		if !strings.Contains(sb.String(), want) {
			t.Errorf("Afrikaans paginator lacks %s:\n%s", want, sb.String())
		}
	}
}

// A label prop wins over the catalog.
func TestAPropThatSetsALabelWinsOverTheCatalog(t *testing.T) {
	af := i18n.WithLocale(context.Background(), "af")
	var sb strings.Builder
	if err := AlertDialog(AlertDialogProps{ID: "d", Title: "T", Message: "M", Action: "/x", CancelLabel: "Nope", ConfirmLabel: "Yes"}).Render(af, &sb); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(sb.String(), "Nope") || strings.Contains(sb.String(), "Kanselleer") {
		t.Errorf("the CancelLabel prop did not win:\n%s", sb.String())
	}
	sb.Reset()
	if err := AlertDialog(AlertDialogProps{ID: "d", Title: "T", Message: "M", Action: "/x"}).Render(af, &sb); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(sb.String(), "Kanselleer") || !strings.Contains(sb.String(), "Bevestig") {
		t.Errorf("the defaults did not come from the Afrikaans catalog:\n%s", sb.String())
	}
}

// The Datastar expressions that carry messages quote them for JavaScript.
func TestMessagesInExpressionsAreQuotedForJavaScript(t *testing.T) {
	if got := jsq(`it's a \ "test"`); got != `'it\'s a \\ "test"'` {
		t.Errorf("jsq = %s", got)
	}
	en := context.Background()
	if got, want := jsMessage(en, "kit.multiselect.remove", "label", "t.textContent"), `'Remove ' + t.textContent`; got != want {
		t.Errorf("jsMessage = %s, want %s", got, want)
	}
	var sb strings.Builder
	af := i18n.WithLocale(en, "af")
	if err := PasswordInput(PasswordInputProps{ID: "p", Signal: "pw", Meter: true}).Render(af, &sb); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"'Swak'", "'Sterk'", "'Versteek wagwoord'", "'Wys wagwoord'"} {
		if !strings.Contains(html.UnescapeString(sb.String()), want) {
			t.Errorf("password input lacks %s in Afrikaans", want)
		}
	}
}

// literalKeys are the catalog keys the kit's sources name.
var literalKeys = regexp.MustCompile(`"((?:kit|validation|flash)\.[A-Za-z0-9_.-]+)"`)

// sourceKeys reads the non-test sources of this package (the templ files and kit.go).
func sourceKeys(t *testing.T) map[string]bool {
	t.Helper()
	files, _ := filepath.Glob("*.templ")
	files = append(files, "kit.go", "navgroup.go", "surface.go")
	keys := map[string]bool{}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range literalKeys.FindAllStringSubmatch(string(raw), -1) {
			keys[m[1]] = true
		}
	}
	return keys
}

func TestEveryKeyTheSourcesUseIsInEnglish(t *testing.T) {
	kc, err := i18n.KitCatalogs()
	if err != nil {
		t.Fatal(err)
	}
	for key := range sourceKeys(t) {
		if _, ok := kc["en"][key]; !ok && !strings.HasSuffix(key, ".") {
			t.Errorf("the sources use %q, which kit/en.json lacks", key)
		}
	}
}

func TestEveryEnglishKitKeyIsUsedBySources(t *testing.T) {
	kc, err := i18n.KitCatalogs()
	if err != nil {
		t.Fatal(err)
	}
	used := sourceKeys(t)
	var unused []string
	for key := range kc["en"] {
		// keys built from parts: "kit.flash_fallback." + name + ".summary"
		if !strings.HasPrefix(key, "kit.") || used[key] || strings.HasPrefix(key, "kit.flash_fallback.") {
			continue
		}
		unused = append(unused, key)
	}
	sort.Strings(unused)
	if len(unused) > 0 {
		t.Errorf("English kit keys no source uses (dead text, or a literal left in a template): %v", unused)
	}
}

// LocalTime formats with the document's language.
func TestLocalTimeFormatsWithTheDocumentLanguage(t *testing.T) {
	out := render(t, LocalTime(LocalTimeProps{At: mustTime("2026-10-06T08:30:00Z")}), nil)
	if !strings.Contains(out, "toLocaleString(document.documentElement.lang || undefined)") {
		t.Errorf("local time does not use the document language:\n%s", out)
	}
}

func mustTime(s string) (tm time.Time) {
	if err := tm.UnmarshalText([]byte(s)); err != nil {
		panic(err)
	}
	return tm
}

func TestLocalNumberMoneyAndQuantityRefuseWhatIsNotAPlainValue(t *testing.T) {
	for name, c := range map[string]templ.Component{
		"number text":     LocalNumber(LocalNumberProps{Value: "12,5"}),
		"number exponent": LocalNumber(LocalNumberProps{Value: "1e3"}),
		"number markup":   LocalNumber(LocalNumberProps{Value: `1"><script>`}),
		"money amount":    LocalMoney(LocalMoneyProps{Amount: "x", Currency: "ZAR"}),
		"money currency":  LocalMoney(LocalMoneyProps{Amount: "1", Currency: "zar"}),
		"money digits":    LocalMoney(LocalMoneyProps{Amount: "1", Currency: "ZAR", Digits: new(99)}),
		"quantity":        LocalQuantity(LocalQuantityProps{Value: "-", Unit: "kg"}),
	} {
		func() {
			defer func() {
				if r := recover(); r == nil {
					t.Errorf("%s: no refusal", name)
				} else if s, _ := r.(string); !strings.HasPrefix(s, "kit: ") {
					t.Errorf("%s: panic %v does not start with \"kit: \"", name, r)
				}
			}()
			render(t, c, nil)
		}()
	}
}

func TestLocalNumberKeepsThePlainValueAsText(t *testing.T) {
	out := render(t, LocalNumber(LocalNumberProps{Value: "1234.50"}), nil)
	if !strings.Contains(out, "<span data-ignore-morph>1234.50</span></data>") || !strings.Contains(out, `value="1234.50"`) {
		t.Errorf("the plain value is not the element text:\n%s", out)
	}
	if !strings.Contains(out, "minimumFractionDigits: 2, maximumFractionDigits: 2") {
		t.Errorf("the value's two fraction digits are not kept:\n%s", out)
	}
}
