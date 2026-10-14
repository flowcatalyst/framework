package conformance_test

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

const configVector = "config/config.json"

type configMode string

const (
	modeFast      configMode = "FAST"
	modeSlowStart configMode = "SLOW_START"
)

type configDoc struct {
	EnvNames []struct {
		Key string `json:"key"`
		Env string `json:"env"`
	} `json:"envNames"`
	Values []struct {
		Type  string         `json:"type"`
		Text  string         `json:"text"`
		Value jsontext.Value `json:"value"`
		Error *string        `json:"error"`
	} `json:"values"`
	Scenarios []configScenario `json:"scenarios"`
}

type configScenario struct {
	Name         string            `json:"name"`
	Env          map[string]string `json:"env"`
	Files        map[string]string `json:"files"`
	Declarations []struct {
		Module        string         `json:"module"`
		Key           string         `json:"key"`
		Type          string         `json:"type"`
		Default       jsontext.Value `json:"default"`
		DefaultMillis *int64         `json:"defaultMillis"`
		Required      bool           `json:"required"`
	} `json:"declarations"`
	Values   map[string]jsontext.Value `json:"values"`
	Problems *[]string                 `json:"problems"`
}

// typed is a declaration of the vector's type plus a reader that returns the resolved value in
// the vector's normalised form.
type typed struct {
	decl config.Decl
	read func(*config.Values) (any, bool)
}

func makeTyped[T any](s config.Setting[T], norm func(T) any) typed {
	d := &collector{}
	h := config.Declare(d, s)
	return typed{decl: d.decl, read: func(v *config.Values) (any, bool) {
		x, ok := h.Lookup(v)
		if !ok {
			return nil, false
		}
		return norm(x), true
	}}
}

type collector struct{ decl config.Decl }

func (c *collector) DeclareSetting(d config.Decl) { c.decl = d }

func identity[T any](x T) any { return x }

// newTyped builds the declaration of a vector type. defaultJSON is the "default" value,
// defaultMillis the duration default; either may be absent.
func newTyped(t *testing.T, where, typ, key string, defaultJSON jsontext.Value, defaultMillis *int64, required bool) typed {
	t.Helper()
	switch typ {
	case "string":
		s := config.String(key, "")
		if len(defaultJSON) > 0 {
			s = s.WithDefault(decodeAs[string](t, where, defaultJSON))
		}
		return makeTyped(req(s, required), identity[string])
	case "integer":
		s := config.Int(key, "")
		if len(defaultJSON) > 0 {
			s = s.WithDefault(decodeAs[int](t, where, defaultJSON))
		}
		return makeTyped(req(s, required), identity[int])
	case "long":
		s := config.Int64(key, "")
		if len(defaultJSON) > 0 {
			s = s.WithDefault(decodeAs[int64](t, where, defaultJSON))
		}
		return makeTyped(req(s, required), identity[int64])
	case "boolean":
		s := config.Bool(key, "")
		if len(defaultJSON) > 0 {
			s = s.WithDefault(decodeAs[bool](t, where, defaultJSON))
		}
		return makeTyped(req(s, required), identity[bool])
	case "duration":
		s := config.Duration(key, "")
		if defaultMillis != nil {
			s = s.WithDefault(time.Duration(*defaultMillis) * time.Millisecond)
		}
		return makeTyped(req(s, required), func(d time.Duration) any { return strconv.FormatInt(int64(d), 10) })
	case "stringList":
		s := config.StringList(key, "")
		return makeTyped(req(s, required), func(l []string) any {
			if l == nil {
				return []string{}
			}
			return l
		})
	case "secret":
		return makeTyped(req(config.SecretValue(key, ""), required), func(s config.Secret) any { return s.Reveal() })
	case "enum":
		return makeTyped(req(config.Enum(key, "", modeFast, modeSlowStart), required), func(m configMode) any { return string(m) })
	}
	t.Fatalf("%s: unknown type %q", where, typ)
	return typed{}
}

func req[T any](s config.Setting[T], required bool) config.Setting[T] {
	if required {
		return s.Required()
	}
	return s
}

func decodeAs[T any](t *testing.T, where string, raw jsontext.Value) T {
	t.Helper()
	var x T
	if err := json.Unmarshal(raw, &x); err != nil {
		t.Fatalf("%s: decode %s: %v", where, raw, err)
	}
	return x
}

func TestConfigVectorEnvNames(t *testing.T) {
	t.Parallel()
	var doc configDoc
	conformance.ReadJSON(t, configVector, &doc)
	if len(doc.EnvNames) == 0 {
		t.Fatalf("%s: envNames is empty", configVector)
	}
	for _, c := range doc.EnvNames {
		where := conformance.Where(configVector, "envNames "+c.Key, "env")
		if got := config.EnvName(c.Key); got != c.Env {
			t.Errorf("%s: want %q, got %q", where, c.Env, got)
		}
	}
}

func TestConfigVectorValues(t *testing.T) {
	t.Parallel()
	var doc configDoc
	conformance.ReadJSON(t, configVector, &doc)
	if len(doc.Values) == 0 {
		t.Fatalf("%s: values is empty", configVector)
	}
	for _, c := range doc.Values {
		cas := c.Type + " " + strconv.Quote(c.Text)
		tp := newTyped(t, conformance.Where(configVector, cas, "declaration"), c.Type, "x", nil, nil, false)
		v, ps := config.Load(config.Sources{Dir: filepath.Join(t.TempDir(), "none"), Env: map[string]string{"X": c.Text}},
			[]config.Declared{{Module: "m", Setting: tp.decl}}, nil, config.KeyRules{})

		if c.Error != nil {
			where := conformance.Where(configVector, cas, "error")
			if len(ps) != 1 {
				t.Errorf("%s: want one problem expecting %q, got %d (%v)", where, *c.Error, len(ps), ps)
				continue
			}
			want := "expected " + *c.Error
			if c.Type != "secret" {
				want += ", found '" + c.Text + "'"
			}
			if ps[0].Message != want {
				t.Errorf("%s: want %q, got %q", where, want, ps[0].Message)
			}
			continue
		}
		where := conformance.Where(configVector, cas, "value")
		if len(ps) != 0 {
			t.Errorf("%s: want %s, got problems %v", where, c.Value, ps)
			continue
		}
		got, ok := tp.read(v)
		if !ok {
			t.Errorf("%s: want %s, got no value", where, c.Value)
			continue
		}
		gotJSON, err := json.Marshal(got)
		if err != nil {
			t.Errorf("%s: marshal %v: %v", where, got, err)
			continue
		}
		conformance.EqualJSON(t, where, c.Value, gotJSON)
	}
}

func TestConfigVectorScenarios(t *testing.T) {
	var doc configDoc
	conformance.ReadJSON(t, configVector, &doc)
	if len(doc.Scenarios) == 0 {
		t.Fatalf("%s: scenarios is empty", configVector)
	}
	for _, sc := range doc.Scenarios {
		// not parallel: each scenario changes the working directory
		t.Run(sc.Name, func(t *testing.T) { runConfigScenario(t, sc) })
	}
}

func runConfigScenario(t *testing.T, sc configScenario) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "config"), 0o750); err != nil {
		t.Fatal(err)
	}
	for name, text := range sc.Files {
		if err := os.WriteFile(filepath.Join(root, "config", name), []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	t.Chdir(root)

	var declared []config.Declared
	readers := make([]typed, 0, len(sc.Declarations))
	for _, d := range sc.Declarations {
		where := conformance.Where(configVector, sc.Name, "declaration "+d.Key)
		tp := newTyped(t, where, d.Type, d.Key, d.Default, d.DefaultMillis, d.Required)
		declared = append(declared, config.Declared{Module: d.Module, Setting: tp.decl})
		readers = append(readers, tp)
	}
	env := sc.Env
	if env == nil {
		env = map[string]string{}
	}
	values, problems := config.Load(config.Sources{Dir: "config", Env: env}, declared, nil, config.KeyRules{})

	if sc.Problems != nil {
		where := conformance.Where(configVector, sc.Name, "problems")
		got := make([]string, 0, len(problems))
		for _, p := range problems {
			got = append(got, p.String())
		}
		if !slices.Equal(got, *sc.Problems) {
			t.Errorf("%s: problems differ\n want: %q\n  got: %q", where, *sc.Problems, got)
		}
		return
	}
	if len(problems) != 0 {
		t.Fatalf("%s: want values, got problems %v", conformance.Where(configVector, sc.Name, "problems"), problems)
	}
	for i, d := range sc.Declarations {
		where := conformance.Where(configVector, sc.Name, "value "+d.Key)
		want, ok := sc.Values[d.Key]
		if !ok {
			t.Errorf("%s: vector has no expected value for the declared key", where)
			continue
		}
		got, has := readers[i].read(values)
		gotJSON := []byte("null")
		if has {
			var err error
			if gotJSON, err = json.Marshal(got); err != nil {
				t.Errorf("%s: marshal %v: %v", where, got, err)
				continue
			}
		}
		conformance.EqualJSON(t, where, want, gotJSON)
	}
	if len(sc.Values) != len(sc.Declarations) {
		t.Errorf("%s: %d expected values for %d declarations", conformance.Where(configVector, sc.Name, "values"), len(sc.Values), len(sc.Declarations))
	}
}
