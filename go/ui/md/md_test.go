package md

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func render(t *testing.T, src string) string {
	t.Helper()
	out, err := HTML(src)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestHTMLSanitises(t *testing.T) {
	cases := map[string]struct {
		src  string
		gone []string
	}{
		"script tag":        {"<script>alert(1)</script>hello", []string{"<script", "alert(1)"}},
		"javascript url":    {"[x](javascript:alert(1))", []string{"javascript:"}},
		"javascript upper":  {"[x](JaVaScRiPt:alert(1))", []string{"avascript"}},
		"data url link":     {"[x](data:text/html;base64,PHNjcmlwdD4=)", []string{"data:"}},
		"on attribute":      {`<a href="/x" onclick="alert(1)">x</a>`, []string{"onclick"}},
		"img onerror":       {`<img src=x onerror=alert(1)>`, []string{"onerror"}},
		"raw html iframe":   {`<iframe src="https://evil.example"></iframe>`, []string{"<iframe"}},
		"style tag":         {`<style>body{display:none}</style>`, []string{"<style"}},
		"svg onload":        {`<svg onload=alert(1)></svg>`, []string{"onload", "<svg"}},
		"form":              {`<form action="/x"><input name=a></form>`, []string{"<form"}},
		"id attr on para":   {`<p id="x" onmouseover="a()">t</p>`, []string{"onmouseover"}},
		"autolink js":       {"<javascript:alert(1)>", []string{"href=\"javascript"}},
		"code class inject": {"<code class=\"x\" onmouseover=\"a()\">c</code>", []string{"onmouseover"}},
		// Datastar evaluates these on any node added to a page (data-init runs
		// on insert), so a doc page must never carry one through.
		"datastar data-init":    {`<div data-init="alert(1)">x</div>`, []string{"data-init", "alert(1)"}},
		"datastar data-on":      {`<span data-on:click="alert(1)">x</span>`, []string{"data-on", "alert(1)"}},
		"datastar data-signals": {`<p data-signals='{"x":1}' data-effect="alert(1)">x</p>`, []string{"data-signals", "data-effect", "alert(1)"}},
		"datastar in a link":    {`<a href="/x" data-init="alert(1)">x</a>`, []string{"data-init"}},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			out := strings.ToLower(render(t, c.src))
			for _, g := range c.gone {
				if strings.Contains(out, strings.ToLower(g)) {
					t.Errorf("output still contains %q:\n%s", g, out)
				}
			}
		})
	}
}

func TestHTMLKeepsStructure(t *testing.T) {
	out := render(t, "# Title\n\n| a | b |\n|---|:-:|\n| 1 | 2 |\n\n```go\nfmt.Println(\"x\")\n```\n\n[ok](https://example.com) and `inline`\n\n- [x] done\n")
	for _, want := range []string{
		`<h1 id="title">Title</h1>`,
		"<table>",
		`<th style="text-align:center">b</th>`,
		`<code class="language-go">`,
		`href="https://example.com"`,
		"<code>inline</code>",
		`type="checkbox"`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
}

func TestLinksKeepOnlyTheAllowedSchemes(t *testing.T) {
	out := render(t, "[a](https://example.com) [b](http://example.com) [c](mailto:a@b.c) [d](/relative) [e](ftp://example.com) [f](tel:123)")
	for _, want := range []string{`href="https://example.com"`, `href="http://example.com"`, `href="mailto:a@b.c"`, `href="/relative"`} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	for _, gone := range []string{"ftp:", "tel:"} {
		if strings.Contains(out, gone) {
			t.Errorf("output keeps %q:\n%s", gone, out)
		}
	}
}

var headingLine = regexp.MustCompile(`(?m)^#{1,6} `)

// Real documents render without losing tables, code fences or headings with ids.
func TestDocuments(t *testing.T) {
	files, err := filepath.Glob("testdata/*.md")
	if err != nil || len(files) < 2 {
		t.Fatalf("testdata documents: %v, %d found", err, len(files))
	}
	for _, f := range files {
		t.Run(filepath.Base(f), func(t *testing.T) {
			raw, err := os.ReadFile(f)
			if err != nil {
				t.Fatal(err)
			}
			src := string(raw)
			out := render(t, src)

			check := func(what string, got, want int) {
				t.Helper()
				if got != want {
					t.Errorf("%s: got %d, want %d\n%s", what, got, want, out)
				}
			}
			check("heading count", strings.Count(out, "<h")-strings.Count(out, "<hr"), strings.Count(inFences(src, headingLine), "\n#"))
			check("every heading has an id", len(regexp.MustCompile(`<h[1-6] id="`).FindAllString(out, -1)), strings.Count(out, "<h")-strings.Count(out, "<hr"))
			check("tables", strings.Count(out, "<table>"), countTables(src))
			check("code fences", strings.Count(out, "<pre>"), countFences(src))
			if strings.Contains(out, "<script") {
				t.Error("script element in the output")
			}
		})
	}
}

// inFences returns the lines of src outside fenced code that match re, each
// prefixed with "\n#" so the caller can count them.
func inFences(src string, re *regexp.Regexp) string {
	var b strings.Builder
	in := false
	for line := range strings.SplitSeq(src, "\n") {
		if strings.HasPrefix(line, "```") {
			in = !in
			continue
		}
		if !in && re.MatchString(line) {
			b.WriteString("\n#")
		}
	}
	return b.String()
}

var tableSep = regexp.MustCompile(`^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$`)

func countTables(src string) int {
	n, in := 0, false
	for line := range strings.SplitSeq(src, "\n") {
		if strings.HasPrefix(line, "```") {
			in = !in
		}
		if !in && strings.Contains(line, "|") && tableSep.MatchString(strings.TrimSpace(line)) {
			n++
		}
	}
	return n
}

// countFences counts opening fence lines (a fence may be indented).
func countFences(src string) int {
	n, in := 0, false
	for line := range strings.SplitSeq(src, "\n") {
		if strings.HasPrefix(strings.TrimLeft(line, " "), "```") {
			if !in {
				n++
			}
			in = !in
		}
	}
	return n
}
