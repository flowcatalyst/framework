package config_test

import (
	"path/filepath"
	"testing"

	"github.com/flowcatalyst/framework/go/config"
)

var (
	keyURL    = config.String("url", "").Required()
	keyPrefix = config.String("prefix", "").WithDefault("fw:")
	keyPool   = config.Int("pool.max", "")
	keyPass   = config.SecretValue("password", "")
)

func dbSection() config.Section {
	return config.Section{
		Module: "db", Path: "db", Capability: "database",
		Keys: []config.Decl{keyURL, keyPrefix, keyPool, keyPass},
	}
}

func loadSection(t *testing.T, sec config.Section, env map[string]string, yaml string) (*config.Values, []string) {
	t.Helper()
	dir := files(t, map[string]string{"application.yaml": yaml})
	if env == nil {
		env = map[string]string{}
	}
	v, ps := config.Load(config.Sources{Dir: dir, Env: env}, nil, []config.Section{sec}, config.KeyRules{})
	return v, problemTexts(ps)
}

func TestInstancesHappyPath(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, dbSection(), map[string]string{"DB_INSTANCES_SHARED_URL": "from-env"}, `
db:
  default: shared
  instances:
    local: { url: l }
    shared:
      url: from-file
      pool: { max: 7 }
      password: hunter2
`)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	in := v.Instances("db")
	if in.Path != "db" || in.Default != "shared" || !equalStrings(in.Names, []string{"local", "shared"}) {
		t.Fatalf("%+v", in)
	}
	sh := in.ByName["shared"]
	if sh.Name != "shared" {
		t.Errorf("%+v", sh)
	}
	if got := keyURL.Get(sh); got != "from-env" {
		t.Errorf("url = %q (the environment wins)", got)
	}
	if got := keyPrefix.Get(sh); got != "fw:" {
		t.Errorf("prefix = %q", got)
	}
	if got := keyPool.Get(sh); got != 7 {
		t.Errorf("pool.max = %d", got)
	}
	if got, ok := keyPass.Lookup(sh); !ok || got.Reveal() != "hunter2" {
		t.Errorf("password = %v %v", got, ok)
	}
	if _, ok := keyPool.Lookup(in.ByName["local"]); ok {
		t.Error("pool.max is unset in local: Lookup must not find it")
	}
}

func TestInstanceGetPanics(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, dbSection(), nil, "db:\n  instances:\n    a: { url: u }\n")
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	a := v.Instances("db").ByName["a"]
	mustPanic(t, "no value", func() { keyPool.Get(a) }, "pool.max", "Lookup")
	other := config.String("other", "")
	mustPanic(t, "undeclared", func() { other.Get(a) }, "other", "section")
	if _, ok := other.Lookup(a); ok {
		t.Error("Lookup of a key the section does not declare must not find a value")
	}
	if got := v.Instances("nothing"); got.Names != nil || got.Default != "" {
		t.Errorf("undeclared section: %+v", got)
	}
}

func TestInstancesNoneConfiguredIsValid(t *testing.T) {
	t.Parallel()
	for _, y := range []string{"", "db:\n  instances: {}\n", "other: 1\n"} {
		v, ps := loadSection(t, dbSection(), nil, y)
		if len(ps) != 0 {
			t.Fatalf("%q: %v", y, ps)
		}
		if in := v.Instances("db"); len(in.Names) != 0 || in.Default != "" || len(in.ByName) != 0 {
			t.Errorf("%q: %+v", y, in)
		}
	}
}

func TestEnvAloneNeverCreatesAnInstance(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, dbSection(), map[string]string{"DB_INSTANCES_X_URL": "u"}, "")
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if len(v.Instances("db").Names) != 0 {
		t.Error("instance created from the environment")
	}
}

func TestSingleInstanceIsTheDefault(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, dbSection(), nil, "db:\n  instances:\n    only: { url: u }\n")
	if len(ps) != 0 || v.Instances("db").Default != "only" {
		t.Errorf("%v %+v", ps, v)
	}
}

func TestDefaultFromEnvironment(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, dbSection(), map[string]string{"DB_DEFAULT": "b"}, "db:\n  instances:\n    a: { url: u }\n    b: { url: u }\n")
	if len(ps) != 0 || v.Instances("db").Default != "b" {
		t.Errorf("%v", ps)
	}
}

func TestInstanceProblems(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name string
		env  map[string]string
		yaml string
		want func(src string) []string
	}{
		{"several without default", nil, "db:\n  instances:\n    b: { url: u }\n    a: { url: u }\n", func(string) []string {
			return []string{"module 'db': key 'db.default': missing; several instances are configured (b, a) so one must be named as the default"}
		}},
		{"default names a missing instance", nil, "db:\n  default: zz\n  instances:\n    a: { url: u }\n    b: { url: u }\n", func(string) []string {
			return []string{"module 'db': key 'db.default': names instance 'zz', which is not configured; known: a, b"}
		}},
		{"default names a missing instance, none configured", nil, "db:\n  default: zz\n", func(string) []string {
			return []string{"module 'db': key 'db.default': names instance 'zz', which is not configured; known: (none)"}
		}},
		{"unknown top-level key", nil, "db:\n  stray: 1\n  nested:\n    deep: 2\n  instances:\n    a: { url: u }\n", func(src string) []string {
			return []string{
				"module 'db': key 'db.stray' (from " + src + "): unknown key in section 'db'",
				"module 'db': key 'db.nested.deep' (from " + src + "): unknown key in section 'db'",
			}
		}},
		{"missing required key", nil, "db:\n  instances:\n    a: { prefix: p }\n", func(string) []string {
			return []string{"module 'db': key 'db.instances.a.url': missing required key; set it in a config file or as environment variable DB_INSTANCES_A_URL"}
		}},
		{"unknown key", nil, "db:\n  instances:\n    a: { url: u, driver: x, deep: { x: 1 } }\n", func(src string) []string {
			return []string{
				"module 'db': key 'db.instances.a.driver' (from " + src + "): unknown key for database instance 'a'",
				"module 'db': key 'db.instances.a.deep.x' (from " + src + "): unknown key for database instance 'a'",
			}
		}},
		{"malformed key", nil, "db:\n  instances:\n    a: { url: u, pool: { max: lots } }\n", func(src string) []string {
			return []string{"module 'db': key 'db.instances.a.pool.max' (from " + src + "): expected an integer, found 'lots'"}
		}},
		{"malformed secret is not quoted", map[string]string{}, "db:\n  instances:\n    a: { url: u, password: [x] }\n", func(src string) []string {
			return []string{"module 'db': key 'db.instances.a.password' (from " + src + "): expected a string"}
		}},
		{"malformed from environment", map[string]string{"DB_INSTANCES_A_POOL_MAX": "x y"}, "db:\n  instances:\n    a: { url: u }\n", func(string) []string {
			return []string{"module 'db': key 'db.instances.a.pool.max' (from environment variable DB_INSTANCES_A_POOL_MAX): expected an integer, found 'x y'"}
		}},
		{"default is a list", nil, "db:\n  default: [a]\n  instances:\n    a: { url: u }\n    b: { url: u }\n", func(src string) []string {
			return []string{
				"module 'db': key 'db.default' (from " + src + "): expected a string, found 'a list'",
				"module 'db': key 'db.default': missing; several instances are configured (a, b) so one must be named as the default",
			}
		}},
	}
	for _, c := range cases {
		dir := files(t, map[string]string{"application.yaml": c.yaml})
		src := filepath.Join(dir, "application.yaml")
		env := c.env
		if env == nil {
			env = map[string]string{}
		}
		_, ps := config.Load(config.Sources{Dir: dir, Env: env}, nil, []config.Section{dbSection()}, config.KeyRules{})
		if got, want := problemTexts(ps), c.want(src); !equalStrings(got, want) {
			t.Errorf("%s:\n got %q\nwant %q", c.name, got, want)
		}
	}
}

func TestInstanceNamesFollowFileOrderAcrossFiles(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{
		"application.yaml":       "db:\n  default: c\n  instances:\n    c: { url: u }\n    a: { url: u }\n",
		"application-local.yaml": "db:\n  instances:\n    b: { url: u }\n    c: { url: u }\n",
	})
	v, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, []config.Section{dbSection()}, config.KeyRules{})
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if got := v.Instances("db").Names; !equalStrings(got, []string{"c", "a", "b"}) {
		t.Errorf("names = %q", got)
	}
}

func TestInstanceRangeCheckNamesKeyAndSource(t *testing.T) {
	t.Parallel()
	pool := config.Int("pool.max", "").WithDefault(10).Checked(config.Between(1, 1000))
	sec := config.Section{Module: "db", Path: "db", Capability: "database", Keys: []config.Decl{keyURL, pool}}
	dir := files(t, map[string]string{"application.yaml": "db:\n  instances:\n    a: { url: u, pool: { max: 5000 } }\n"})
	_, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, []config.Section{sec}, config.KeyRules{})
	want := "module 'db': key 'db.instances.a.pool.max' (from " + filepath.Join(dir, "application.yaml") + "): must be 1 to 1000, was 5000"
	if got := problemTexts(ps); !equalStrings(got, []string{want}) {
		t.Errorf("got %q want %q", got, want)
	}
}
