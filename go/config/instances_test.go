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

func lockSection(defaultDriver string) config.Section {
	return config.Section{
		Module: "locks", Path: "locks", Capability: "lock store", DefaultDriver: defaultDriver,
		Drivers: []config.Driver{
			{Name: "memory"},
			{Name: "redis", Keys: []config.Decl{keyURL, keyPrefix, keyPool, keyPass}},
		},
	}
}

func loadSection(t *testing.T, sec config.Section, env map[string]string, yaml string) (*config.Values, []string) {
	t.Helper()
	dir := files(t, map[string]string{"application.yaml": yaml})
	if env == nil {
		env = map[string]string{}
	}
	v, ps := config.Load(config.Sources{Dir: dir, Env: env}, nil, []config.Section{sec})
	return v, problemTexts(ps)
}

func TestInstancesHappyPath(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), map[string]string{"LOCKS_INSTANCES_SHARED_URL": "from-env"}, `
locks:
  default: shared
  instances:
    local: { driver: memory }
    shared:
      driver: redis
      url: from-file
      pool: { max: 7 }
      password: hunter2
`)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	in := v.Instances("locks")
	if in.Path != "locks" || in.Default != "shared" || !equalStrings(in.Names, []string{"local", "shared"}) {
		t.Fatalf("%+v", in)
	}
	sh := in.ByName["shared"]
	if sh.Name != "shared" || sh.Driver != "redis" || in.ByName["local"].Driver != "memory" {
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
		t.Error("memory driver does not declare pool.max: Lookup must not find it")
	}
}

func TestInstanceGetPanics(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), nil, "locks:\n  default: a\n  instances:\n    a: { driver: redis, url: u }\n    m: { driver: memory }\n")
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	a := v.Instances("locks").ByName["a"]
	mustPanic(t, "no value", func() { keyPool.Get(a) }, "pool.max", "Lookup")
	mustPanic(t, "undeclared", func() { keyURL.Get(v.Instances("locks").ByName["m"]) }, "url", "memory")
	if _, ok := keyURL.Lookup(v.Instances("locks").ByName["m"]); ok {
		t.Error("Lookup of a key the driver does not declare must not find a value")
	}
	if got := v.Instances("nothing"); got.Names != nil || got.Default != "" {
		t.Errorf("undeclared section: %+v", got)
	}
}

func TestInstancesNoneConfiguredIsValid(t *testing.T) {
	t.Parallel()
	for _, y := range []string{"", "locks:\n  instances: {}\n", "other: 1\n"} {
		v, ps := loadSection(t, lockSection(""), nil, y)
		if len(ps) != 0 {
			t.Fatalf("%q: %v", y, ps)
		}
		if in := v.Instances("locks"); len(in.Names) != 0 || in.Default != "" || len(in.ByName) != 0 {
			t.Errorf("%q: %+v", y, in)
		}
	}
}

func TestEnvAloneNeverCreatesAnInstance(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), map[string]string{"LOCKS_INSTANCES_X_DRIVER": "memory"}, "")
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if len(v.Instances("locks").Names) != 0 {
		t.Error("instance created from the environment")
	}
}

func TestSingleInstanceIsTheDefault(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), nil, "locks:\n  instances:\n    only: { driver: memory }\n")
	if len(ps) != 0 || v.Instances("locks").Default != "only" {
		t.Errorf("%v %+v", ps, v)
	}
}

func TestDefaultFromEnvironment(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), map[string]string{"LOCKS_DEFAULT": "b"}, "locks:\n  instances:\n    a: { driver: memory }\n    b: { driver: memory }\n")
	if len(ps) != 0 || v.Instances("locks").Default != "b" {
		t.Errorf("%v", ps)
	}
}

func TestInstanceProblems(t *testing.T) {
	t.Parallel()
	dirOf := func() string { return "" }
	_ = dirOf
	cases := []struct {
		name string
		sec  config.Section
		env  map[string]string
		yaml string
		want func(src string) []string
	}{
		{"several without default", lockSection(""), nil, "locks:\n  instances:\n    b: { driver: memory }\n    a: { driver: memory }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.default': missing; several instances are configured (b, a) so one must be named as the default"}
		}},
		{"default names a missing instance", lockSection(""), nil, "locks:\n  default: zz\n  instances:\n    a: { driver: memory }\n    b: { driver: memory }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.default': names instance 'zz', which is not configured; known: a, b"}
		}},
		{"default names a missing instance, none configured", lockSection(""), nil, "locks:\n  default: zz\n", func(string) []string {
			return []string{"module 'locks': key 'locks.default': names instance 'zz', which is not configured; known: (none)"}
		}},
		{"unknown top-level key", lockSection(""), nil, "locks:\n  stray: 1\n  nested:\n    deep: 2\n  instances:\n    a: { driver: memory }\n", func(src string) []string {
			return []string{
				"module 'locks': key 'locks.stray' (from " + src + "): unknown key in section 'locks'",
				"module 'locks': key 'locks.nested.deep' (from " + src + "): unknown key in section 'locks'",
			}
		}},
		{"unknown driver", lockSection(""), nil, "locks:\n  instances:\n    a: { driver: zookeeper }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.instances.a.driver': unknown driver 'zookeeper' for lock store; registered: memory, redis"}
		}},
		{"no drivers registered", config.Section{Module: "m", Path: "locks", Capability: "lock store"}, nil, "locks:\n  instances:\n    a: { driver: x }\n", func(string) []string {
			return []string{"module 'm': key 'locks.instances.a.driver': unknown driver 'x' for lock store; registered: (none)"}
		}},
		{"missing driver", lockSection(""), nil, "locks:\n  instances:\n    a: { url: u }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.instances.a.driver': missing required key; set it in a config file or as environment variable LOCKS_INSTANCES_A_DRIVER"}
		}},
		{"missing required driver key", lockSection(""), nil, "locks:\n  instances:\n    a: { driver: redis }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.instances.a.url': missing required key; set it in a config file or as environment variable LOCKS_INSTANCES_A_URL"}
		}},
		{"unknown key for driver", lockSection(""), nil, "locks:\n  instances:\n    a: { driver: memory, url: u, deep: { x: 1 } }\n", func(src string) []string {
			return []string{
				"module 'locks': key 'locks.instances.a.url' (from " + src + "): unknown key for driver 'memory'",
				"module 'locks': key 'locks.instances.a.deep.x' (from " + src + "): unknown key for driver 'memory'",
			}
		}},
		{"malformed driver key", lockSection(""), nil, "locks:\n  instances:\n    a: { driver: redis, url: u, pool: { max: lots } }\n", func(src string) []string {
			return []string{"module 'locks': key 'locks.instances.a.pool.max' (from " + src + "): expected an integer, found 'lots'"}
		}},
		{"malformed secret is not quoted", lockSection(""), map[string]string{}, "locks:\n  instances:\n    a: { driver: redis, url: u, password: [x] }\n", func(src string) []string {
			return []string{"module 'locks': key 'locks.instances.a.password' (from " + src + "): expected a string"}
		}},
		{"malformed from environment", lockSection(""), map[string]string{"LOCKS_INSTANCES_A_POOL_MAX": "x y"}, "locks:\n  instances:\n    a: { driver: redis, url: u }\n", func(string) []string {
			return []string{"module 'locks': key 'locks.instances.a.pool.max' (from environment variable LOCKS_INSTANCES_A_POOL_MAX): expected an integer, found 'x y'"}
		}},
		{"default is a list", lockSection(""), nil, "locks:\n  default: [a]\n  instances:\n    a: { driver: memory }\n    b: { driver: memory }\n", func(src string) []string {
			return []string{
				"module 'locks': key 'locks.default' (from " + src + "): expected a string, found 'a list'",
				"module 'locks': key 'locks.default': missing; several instances are configured (a, b) so one must be named as the default",
			}
		}},
		{"driver is a list", lockSection(""), nil, "locks:\n  instances:\n    a: { driver: [x] }\n", func(src string) []string {
			return []string{"module 'locks': key 'locks.instances.a.driver' (from " + src + "): expected a string, found 'a list'"}
		}},
	}
	for _, c := range cases {
		dir := files(t, map[string]string{"application.yaml": c.yaml})
		src := filepath.Join(dir, "application.yaml")
		env := c.env
		if env == nil {
			env = map[string]string{}
		}
		_, ps := config.Load(config.Sources{Dir: dir, Env: env}, nil, []config.Section{c.sec})
		if got, want := problemTexts(ps), c.want(src); !equalStrings(got, want) {
			t.Errorf("%s:\n got %q\nwant %q", c.name, got, want)
		}
	}
}

func TestInstanceDefaultDriver(t *testing.T) {
	t.Parallel()
	sec := config.Section{
		Module: "db", Path: "database", Capability: "database", DefaultDriver: "postgresql",
		Drivers: []config.Driver{{Name: "postgresql", Keys: []config.Decl{keyURL}}},
	}
	v, ps := loadSection(t, sec, nil, "database:\n  instances:\n    main: { url: postgres://x }\n")
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	in := v.Instances("database")
	if in.ByName["main"].Driver != "postgresql" || in.Default != "main" {
		t.Errorf("%+v", in)
	}
}

func TestInstanceKeyWithEnvironmentOverridesFileDriver(t *testing.T) {
	t.Parallel()
	v, ps := loadSection(t, lockSection(""), map[string]string{"LOCKS_INSTANCES_A_DRIVER": "memory"}, "locks:\n  instances:\n    a: { driver: redis, url: u }\n")
	// the environment says memory, so url is unknown for that driver
	if len(ps) != 1 || v != nil {
		t.Fatalf("%v", ps)
	}
	if want := "unknown key for driver 'memory'"; !containsStr(ps[0], want) {
		t.Errorf("%q", ps[0])
	}
}

func TestInstanceNamesFollowFileOrderAcrossFiles(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{
		"application.yaml":       "locks:\n  default: c\n  instances:\n    c: { driver: memory }\n    a: { driver: memory }\n",
		"application-local.yaml": "locks:\n  instances:\n    b: { driver: memory }\n    c: { driver: memory }\n",
	})
	v, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, []config.Section{lockSection("")})
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if got := v.Instances("locks").Names; !equalStrings(got, []string{"c", "a", "b"}) {
		t.Errorf("names = %q", got)
	}
}
