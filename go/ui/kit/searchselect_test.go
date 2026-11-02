package kit

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/a-h/templ"
)

func renderSearchSelect(t *testing.T, p SearchSelectProps) string {
	t.Helper()
	var buf bytes.Buffer
	if err := SearchSelect(p).Render(context.Background(), &buf); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func TestSearchSelect(t *testing.T) {
	p := SearchSelectProps{
		ID: "app", Label: "Application", Selected: "b",
		Options:     []Option{{Value: "a", Label: "Alpha"}, {Value: "b", Label: "Beta"}},
		Href:        func(v string) string { return "/x?app=" + v },
		Placeholder: "Choose",
		Signal:      "appSearch",
		OptionAttrs: templ.Attributes{"data-on:click__prevent": "@get(el.href)"},
	}
	out := renderSearchSelect(t, p)
	for _, want := range []string{
		`<span id="app-summary" class="fc-multiselect-summary">Beta</span>`,
		`role="listbox"`,
		`<a class="fc-multiselect-option" role="option" href="/x?app=a" aria-selected="false"`,
		`href="/x?app=b" aria-selected="true"`,
		`data-filter-text="alpha a"`,
		`type="search"`,
		`@get(el.href)`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	if strings.Index(out, "Alpha") > strings.Index(out, "Beta</a>") {
		t.Error("options are not in the order given")
	}

	p.Selected = ""
	if got := renderSearchSelect(t, p); !strings.Contains(got, ">Choose</span>") {
		t.Errorf("nothing chosen should show the placeholder:\n%s", got)
	}
}
