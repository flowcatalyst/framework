package config_test

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/config"
)

// yamlString loads one string setting "k" from application.yaml text and returns its value.
func yamlString(t *testing.T, yamlText string) (string, bool) {
	t.Helper()
	dir := files(t, map[string]string{"application.yaml": yamlText})
	d := &declarer{}
	h := config.Declare(d, config.String("k", ""))
	v, ps := load(t, dir, nil, d.decls...)
	if len(ps) != 0 {
		t.Fatalf("%q: %v", yamlText, ps)
	}
	return h.Lookup(v)
}

func TestYAMLScalarResolution(t *testing.T) {
	t.Parallel()
	cases := map[string]string{
		"k: hello": "hello", "k: 8080": "8080", "k: +5": "5", "k: -0": "0", "k: 007": "7", "k: 0x1F": "31", "k: 0o17": "15",
		"k: 12345678901234567890123": "12345678901234567890123", "k: true": "true", "k: True": "true", "k: TRUE": "true", "k: false": "false",
		"k: yes": "yes", "k: on": "on", "k: No": "No", "k: TrUe": "TrUe",
		"k: 1e3": "1000.0", "k: 1.50": "1.5", "k: 1.": "1.0", "k: .5": "0.5", "k: 0.0001": "1.0E-4", "k: 1E+2": "100.0", "k: -2.5e-3": "-0.0025",
		"k: .inf": "Infinity", "k: .Inf": "Infinity", "k: -.INF": "-Infinity", "k: +.inf": "Infinity", "k: .nan": "NaN", "k: .NaN": "NaN", "k: .NAN": "NaN",
		"k: 1e999": "Infinity",
		"k: 1_000": "1_000", "k: 0b101": "0b101", "k: 0X1F": "0X1F", "k: -0x1F": "-0x1F", "k: 0o8": "0o8", "k: 1.2.3": "1.2.3", "k: e5": "e5", "k: 2001-01-01": "2001-01-01",
		`k: "123"`: "123", `k: '123'`: "123", `k: "true"`: "true", `k: "~"`: "~", "k: !!str 123": "123", "k: !!str true": "true",
		"k: !!int 0x10": "16", "k: !!float 1": "1.0", "k: !!bool true": "true",
		"k: |\n  a\n  b\n": "a\nb\n", "k: >\n  a\n  b\n": "a b\n", "k: a # c": "a", "k: 'it''s'": "it's",
	}
	for in, want := range cases {
		got, ok := yamlString(t, in)
		if !ok || got != want {
			t.Errorf("%q = %q, %v; want %q", in, got, ok, want)
		}
	}
	for _, in := range []string{"k:", "k: ~", "k: null", "k: Null", "k: NULL", "k: !!null x", "k: {}", "k: !!null", "k:    "} {
		if got, ok := yamlString(t, in); ok {
			t.Errorf("%q = %q; want no value", in, got)
		}
	}
	// quoted empty string is a value; the empty plain scalar is not
	if got, ok := yamlString(t, `k: ""`); !ok || got != "" {
		t.Errorf("quoted empty = %q %v", got, ok)
	}
}

func TestYAMLFlattening(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{
		"application.yaml": "a:\n  b:\n    c: 1\n    d: [x, 2, true, 1.5]\n  e: {}\n  n:\n",
	})
	d := &declarer{}
	c := config.Declare(d, config.String("a.b.c", ""))
	l := config.Declare(d, config.StringList("a.b.d", ""))
	e := config.Declare(d, config.String("a.e", "").WithDefault("kept"))
	n := config.Declare(d, config.String("a.n", "").WithDefault("kept"))
	v, ps := load(t, dir, nil, d.decls...)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if got := c.Get(v); got != "1" {
		t.Errorf("a.b.c = %q", got)
	}
	if got := l.Get(v); !equalStrings(got, []string{"x", "2", "true", "1.5"}) {
		t.Errorf("a.b.d = %q", got)
	}
	if e.Get(v) != "kept" || n.Get(v) != "kept" {
		t.Error("an empty map or a null is not a value")
	}
}

func TestYAMLNestedListIsNotAListOfStrings(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "nest: [[a], {k: v}]\n"})
	d := &declarer{}
	config.Declare(d, config.StringList("nest", ""))
	_, ps := load(t, dir, nil, d.decls...)
	want := "module 'svc': key 'nest' (from " + dir + "/application.yaml): expected a list of strings (YAML list, or comma-separated text), found 'a list'"
	if len(ps) != 1 || ps[0] != want {
		t.Errorf("problems = %q; want %q", ps, want)
	}
}

func TestYAMLNullNeverHidesEarlierValue(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{
		"application.yaml":       "svc:\n  a: one\n  b: one\n  c: {}\n",
		"application-local.yaml": "svc:\n  a:\n  b: ~\n  c: two\n  d: {}\n",
	})
	d := &declarer{}
	a := config.Declare(d, config.String("svc.a", ""))
	b := config.Declare(d, config.String("svc.b", ""))
	c := config.Declare(d, config.String("svc.c", ""))
	v, ps := load(t, dir, nil, d.decls...)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if a.Get(v) != "one" || b.Get(v) != "one" || c.Get(v) != "two" {
		t.Errorf("a=%q b=%q c=%q", a.Get(v), b.Get(v), c.Get(v))
	}
}

func TestYAMLAnchorsAndMerge(t *testing.T) {
	t.Parallel()
	got, ok := yamlString(t, "base: &b\n  x: 1\nk: *b\n")
	if ok {
		t.Errorf("k is a mapping, got %q", got)
	}
	dir := files(t, map[string]string{"application.yaml": "base: &b\n  x: 1\n  y: 2\nsvc:\n  <<: *b\n  y: 3\nname: &n bob\nother: *n\n"})
	d := &declarer{}
	x := config.Declare(d, config.String("svc.x", ""))
	y := config.Declare(d, config.String("svc.y", ""))
	o := config.Declare(d, config.String("other", ""))
	v, ps := load(t, dir, nil, d.decls...)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if x.Get(v) != "1" || y.Get(v) != "3" || o.Get(v) != "bob" {
		t.Errorf("x=%q y=%q other=%q", x.Get(v), y.Get(v), o.Get(v))
	}
}

func TestYAMLFileProblems(t *testing.T) {
	t.Parallel()
	cases := map[string]string{
		"- a\n- b\n":           "the top level must be a mapping",
		"just text\n":          "the top level must be a mapping",
		"a: [1\n":              "invalid YAML: ",
		"a: b: c\n":            "invalid YAML: ",
		"a: 1\na: 2\n":         "invalid YAML: ",
		"a:\n  b: 1\n  b: 2\n": "invalid YAML: ",
	}
	for text, want := range cases {
		dir := files(t, map[string]string{"application.yaml": text})
		_, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, nil, config.KeyRules{})
		if len(ps) != 1 {
			t.Errorf("%q: %+v; want one problem", text, ps)
			continue
		}
		if ps[0].Source != dir+"/application.yaml" || ps[0].Module != "" || ps[0].Key != "" || !strings.HasPrefix(ps[0].Message, want) {
			t.Errorf("%q: %+v; want message starting %q", text, ps, want)
		}
		if strings.Contains(ps[0].Message, "\n") {
			t.Errorf("%q: message has several lines: %q", text, ps[0].Message)
		}
	}
	// empty and comment-only files are fine
	for _, text := range []string{"", "# nothing\n", "---\n", "~\n"} {
		dir := files(t, map[string]string{"application.yaml": text})
		if _, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, nil, config.KeyRules{}); len(ps) != 0 {
			t.Errorf("%q: %v", text, ps)
		}
	}
}

// A duplicate mapping key is refused. This is an allowed difference from the Java reader, which
// keeps the last value (go.yaml.in/yaml/v3 refuses it).
func TestYAMLDuplicateKeyIsRefused(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "svc:\n  a: 1\n  a: 2\n"})
	_, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, nil, config.KeyRules{})
	if len(ps) != 1 || !strings.HasPrefix(ps[0].String(), dir+"/application.yaml: invalid YAML: ") || !strings.Contains(ps[0].Message, "already defined") {
		t.Errorf("problems = %+v", ps)
	}
}

func TestMissingFilesAreFineAndDirectoriesIgnored(t *testing.T) {
	t.Parallel()
	dir := files(t, nil)
	if _, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, nil, config.KeyRules{}); len(ps) != 0 {
		t.Error(ps)
	}
}
