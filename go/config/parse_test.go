package config_test

import (
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

const durationExpected = "a duration such as 500ms, 30s, 5m, 2h, 1d or PT30S"

func failure(key, source, expected, found string) string {
	return "module 'svc': key '" + key + "' (from environment variable " + source + "): expected " + expected + ", found '" + found + "'"
}

func TestIntParsing(t *testing.T) {
	t.Parallel()
	s := config.Int("n", "")
	ok := map[string]int{"42": 42, "-7": -7, "+7": 7, " 8 ": 8, "007": 7, "-0": 0, "2147483647": 2147483647, "-2147483648": -2147483648, "  5\t": 5}
	for in, want := range ok {
		if got, p := parseEnv(t, s, in); p != "" || got != want {
			t.Errorf("Int(%q) = %d, %q; want %d", in, got, p, want)
		}
	}
	for _, in := range []string{"", " ", "+", "-", "2147483648", "-2147483649", "4.0", "x", "1 2", "0x10", "1_000", " 5"} {
		_, p := parseEnv(t, s, in)
		if want := failure("n", "N", "an integer", in); p != want {
			t.Errorf("Int(%q) problem = %q; want %q", in, p, want)
		}
	}
}

func TestInt64Parsing(t *testing.T) {
	t.Parallel()
	s := config.Int64("n", "")
	if got, p := parseEnv(t, s, "9223372036854775807"); p != "" || got != 9223372036854775807 {
		t.Errorf("got %d, %q", got, p)
	}
	if got, p := parseEnv(t, s, "-9223372036854775808"); p != "" || got != -9223372036854775808 {
		t.Errorf("got %d, %q", got, p)
	}
	_, p := parseEnv(t, s, "9223372036854775808")
	if want := failure("n", "N", "a long integer", "9223372036854775808"); p != want {
		t.Errorf("problem = %q; want %q", p, want)
	}
}

func TestBoolParsing(t *testing.T) {
	t.Parallel()
	s := config.Bool("b", "")
	for in, want := range map[string]bool{"true": true, "false": false, " true\n": true} { //nolint:gocritic // the whitespace is the case under test
		if got, p := parseEnv(t, s, in); p != "" || got != want {
			t.Errorf("Bool(%q) = %v, %q", in, got, p)
		}
	}
	for _, in := range []string{"TRUE", "True", "yes", "1", "", "on"} {
		if _, p := parseEnv(t, s, in); p != failure("b", "B", "true or false", in) {
			t.Errorf("Bool(%q) problem = %q", in, p)
		}
	}
}

func TestStringIsNotStripped(t *testing.T) {
	t.Parallel()
	if got, p := parseEnv(t, config.String("s", ""), "  as written  "); p != "" || got != "  as written  " {
		t.Errorf("got %q, %q", got, p)
	}
}

func TestDurationParsing(t *testing.T) {
	t.Parallel()
	s := config.Duration("d", "")
	ok := map[string]time.Duration{
		"500ms": 500 * time.Millisecond, "30s": 30 * time.Second, "5m": 5 * time.Minute, "2h": 2 * time.Hour,
		"1d": 24 * time.Hour, " 10s ": 10 * time.Second, "0s": 0, "000000000000001ms": time.Millisecond, //nolint:gocritic // the whitespace is the case under test
		"PT30S": 30 * time.Second, "pt0.5s": 500 * time.Millisecond, "P1DT2H3M4.000000005S": 93784*time.Second + 5,
		"PT1H-30M": 30 * time.Minute, "-PT-1S": time.Second, "+PT1S": time.Second, "P1D": 24 * time.Hour,
		"PT0S": 0, "-PT0S": 0, "PT1,5S": 1500 * time.Millisecond, "PT1.S": time.Second, "PT.5S": -1,
		"p1dt": 24 * time.Hour, "PT36H": 36 * time.Hour, "P+1DT-1H": 23 * time.Hour, "PT1.123456789S": time.Second + 123456789,
		"PT1M1S": 61 * time.Second, "PT-0.5S": -1, "PT2562047H47M16.854775807S": 9223372036854775807,
	}
	for in, want := range ok {
		got, p := parseEnv(t, s, in)
		if want == -1 { // expected refusal
			if p == "" {
				t.Errorf("Duration(%q) = %v; want refusal", in, got)
			}
			continue
		}
		if p != "" || got != want {
			t.Errorf("Duration(%q) = %v, %q; want %v", in, got, p, want)
		}
	}
	bad := []string{
		"-PT1S", "PT", "1.5s", "10", "1w", "", "P", "PT-1S", "P-1D", "PTS", "P1DT", "1000000000000000s", "PT1.1234567891234S",
		"PT2562047H47M16.854775808S", "P106751991167301D", "PT1H 30M", "P1H", "PT1D", "pt1h30m1d", "1 s", "1S", "ſ", "PT1ſ",
	}
	for _, in := range bad {
		if _, p := parseEnv(t, s, in); p != failure("d", "D", durationExpected, in) {
			t.Errorf("Duration(%q) problem = %q", in, p)
		}
	}
}

func TestURLParsing(t *testing.T) {
	t.Parallel()
	s := config.URL("u", "")
	got, p := parseEnv(t, s, " https://example.com:8443/a?b=c#d ")
	if p != "" || got.Host != "example.com:8443" || got.Path != "/a" || got.Fragment != "d" {
		t.Errorf("got %v, %q", got, p)
	}
	if got, p := parseEnv(t, s, ""); p != "" || got != (url.URL{}) {
		t.Errorf("empty URI: got %v, %q", got, p)
	}
	for _, in := range []string{"http://exa mple.com", "http://[::1", "a b", "%zz", "http://x/<>", ":foo"} {
		if _, p := parseEnv(t, s, in); p != failure("u", "U", "a URI", in) {
			t.Errorf("URL(%q) problem = %q", in, p)
		}
	}
}

func TestStringListParsing(t *testing.T) {
	t.Parallel()
	s := config.StringList("l", "")
	cases := map[string][]string{"a, b ,c": {"a", "b", "c"}, "": {}, "  ": {}, "a,,b": {"a", "", "b"}, "a,": {"a", ""}, ",": {"", ""}, "solo": {"solo"}}
	for in, want := range cases {
		if got, p := parseEnv(t, s, in); p != "" || !equalStrings(got, want) || got == nil {
			t.Errorf("StringList(%q) = %#v, %q; want %#v", in, got, p, want)
		}
	}
}

type mode string

const (
	fast      mode = "FAST"
	slowStart mode = "SLOW_START"
)

func TestEnumParsing(t *testing.T) {
	t.Parallel()
	s := config.Enum("m", "", fast, slowStart)
	for in, want := range map[string]mode{"fast": fast, "slow-start": slowStart, "SLOW_START": slowStart, " Fast ": fast, "slow_Start": slowStart} { //nolint:gocritic // the whitespace is the case under test
		if got, p := parseEnv(t, s, in); p != "" || got != want {
			t.Errorf("Enum(%q) = %q, %q", in, got, p)
		}
	}
	for _, in := range []string{"medium", "", "slow start", "fast,"} {
		if _, p := parseEnv(t, s, in); p != failure("m", "M", "one of FAST, SLOW_START", in) {
			t.Errorf("Enum(%q) problem = %q", in, p)
		}
	}
}

func TestSecretParsingNeverQuotesText(t *testing.T) {
	t.Parallel()
	s := config.SecretValue("pw", "")
	got, p := parseEnv(t, s, "  hunter2 ")
	if p != "" || got.Reveal() != "  hunter2 " {
		t.Errorf("got %q, %q", got.Reveal(), p)
	}
	// a list is not a scalar: the problem has no found part
	dir := files(t, map[string]string{"application.yaml": "pw: [a, b]\n"})
	d := &declarer{}
	config.Declare(d, s)
	_, ps := load(t, dir, nil, d.decls...)
	want := "module 'svc': key 'pw' (from " + dir + "/application.yaml): expected a string"
	if len(ps) != 1 || ps[0] != want {
		t.Errorf("problems = %q; want %q", ps, want)
	}
}

func TestInvalidKeyAndSecretDefaultAreProblems(t *testing.T) {
	t.Parallel()
	for _, key := range []string{"", "Upper", "1a", "a..b", "a.", "a b", "-a", "a.B"} {
		d := &declarer{}
		config.Declare(d, config.String(key, ""))
		_, ps := load(t, filepath.Join(t.TempDir(), "x"), nil, d.decls...)
		want := "module 'svc': key '" + key + "': setting key '" + key + "' must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"
		if key == "" {
			want = "module 'svc': setting key '' must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"
		}
		if len(ps) != 1 || ps[0] != want {
			t.Errorf("key %q: problems = %q; want %q", key, ps, want)
		}
	}
	d := &declarer{}
	config.Declare(d, config.SecretValue("pw", "").WithDefault(config.NewSecret("x")))
	_, ps := load(t, filepath.Join(t.TempDir(), "x"), nil, d.decls...)
	if want := "module 'svc': key 'pw': secret setting 'pw' cannot have a default"; len(ps) != 1 || ps[0] != want {
		t.Errorf("problems = %q; want %q", ps, want)
	}
	for _, key := range []string{"a", "a-b", "a_b.c9", "pool.max"} {
		d := &declarer{}
		config.Declare(d, config.String(key, ""))
		if _, ps := load(t, filepath.Join(t.TempDir(), "x"), nil, d.decls...); len(ps) != 0 {
			t.Errorf("key %q refused: %q", key, ps)
		}
	}
	if !strings.Contains(config.EnvName("a.b-c"), "A_B_C") {
		t.Error("EnvName")
	}
}
