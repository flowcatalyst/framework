package htmlnorm

import (
	"strings"
	"testing"
)

func TestEquivalentFormsNormalizeEqual(t *testing.T) {
	cases := []struct{ name, a, b string }{
		{"attribute order", `<a href="/x" class="c" title="t">x</a>`, `<a title="t" class="c" href="/x">x</a>`},
		{"quoting and entities", `<p data-x='a "b"' title=plain>&lt;&amp;&#39;</p>`, `<p title="plain" data-x="a &quot;b&quot;">&lt;&amp;'</p>`},
		{"whitespace between elements", "<div>\n  <span>a</span>\n  <span>b</span>\n</div>", `<div><span>a</span><span>b</span></div>`},
		{"whitespace inside text and attribute values", "<p class=\"a\n   b\">one   two\n three</p>", `<p class="a b">one two three</p>`},
		{"comments", `<div><!-- slot: body --><b>x</b></div>`, `<div><b>x</b></div>`},
		{"void and self-closing", `<svg><use href="#i"/></svg><input disabled>`, `<svg><use href="#i"></use></svg><input disabled="">`},
		{"table row context", `<tr class="r"><td>1</td></tr>`, "<tr class=\"r\">\n<td>1</td>\n</tr>"},
		{"cell context", `<td class="c">x</td>`, `<td class="c">x</td>`},
		{"option context", `<option value="a" selected>A</option>`, `<option selected value="a">A</option>`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			a, b := MustNormalize(tc.a), MustNormalize(tc.b)
			if a != b {
				t.Fatalf("not equal:\n%s", Diff(a, b))
			}
			if strings.TrimSpace(a) == "" {
				t.Fatal("normalized to nothing (wrong parsing context?)")
			}
		})
	}
}

// Differences the conformance tests must catch.
func TestMeaningfulDifferencesSurvive(t *testing.T) {
	cases := []struct{ name, a, b string }{
		{"class token order", `<b class="x y">t</b>`, `<b class="y x">t</b>`},
		{"missing class token", `<b class="x y">t</b>`, `<b class="x">t</b>`},
		{"attribute value", `<a href="/a">t</a>`, `<a href="/b">t</a>`},
		{"missing attribute", `<button type="button" disabled>t</button>`, `<button type="button">t</button>`},
		{"text", `<p>Saved</p>`, `<p>Saved.</p>`},
		{"element name", `<span>t</span>`, `<div>t</div>`},
		{"nesting", `<div><span>a</span><span>b</span></div>`, `<div><span>a<span>b</span></span></div>`},
		{"datastar expression", `<button data-on:click="$a = 1">t</button>`, `<button data-on:click="$a = 2">t</button>`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if MustNormalize(tc.a) == MustNormalize(tc.b) {
				t.Fatalf("%q and %q normalized equal", tc.a, tc.b)
			}
		})
	}
}

func TestCanonicalShape(t *testing.T) {
	got := MustNormalize(`<div title="t" class="a  b"><span>x</span> y </div>`)
	want := "<div class=\"a b\" title=\"t\">\n  <span>\n    x\n  </span>\n  y\n</div>\n"
	if got != want {
		t.Fatalf("got %q\nwant %q", got, want)
	}
}

func TestDocument(t *testing.T) {
	got := MustNormalize("<!doctype html><html lang=\"en\"><head><title>T</title></head><body><p>x</p></body></html>")
	if !strings.Contains(got, "<html lang=\"en\">") || !strings.Contains(got, "<title>") {
		t.Fatalf("document not normalized: %s", got)
	}
}

func TestDiffShowsOnlyChangedLines(t *testing.T) {
	a := MustNormalize(`<ul><li>1</li><li>2</li><li>3</li></ul>`)
	b := MustNormalize(`<ul><li>1</li><li>two</li><li>3</li></ul>`)
	d := Diff(a, b)
	if !strings.Contains(d, "-     2") || !strings.Contains(d, "+     two") || strings.Contains(d, "1") {
		t.Fatalf("unexpected diff:\n%s", d)
	}
}
