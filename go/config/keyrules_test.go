package config_test

import (
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

func TestBetween(t *testing.T) {
	t.Parallel()
	b := config.Between(1, 10)
	for _, v := range []int{1, 10, 5} {
		if m := b(v); m != "" {
			t.Errorf("%d: %q", v, m)
		}
	}
	for v, want := range map[int]string{0: "must be 1 to 10, was 0", 11: "must be 1 to 10, was 11"} {
		if m := b(v); m != want {
			t.Errorf("%d: %q", v, m)
		}
	}
	d := config.Between(250*time.Millisecond, 5*time.Minute)
	if m := d(100 * time.Millisecond); m != "must be 250ms to 5m0s, was 100ms" {
		t.Errorf("%q", m)
	}
}

func TestCheckedAppliesToSourcesAndDefault(t *testing.T) {
	t.Parallel()
	good := config.Int("svc.n", "").WithDefault(5).Checked(config.Between(1, 9))
	if v, ps := load(t, filepath.Join(t.TempDir(), "none"), nil, good); len(ps) != 0 || v == nil {
		t.Fatalf("%v", ps)
	}
	bad := config.Int("svc.n", "").WithDefault(50).Checked(config.Between(1, 9))
	_, ps := load(t, filepath.Join(t.TempDir(), "none"), nil, bad)
	if len(ps) != 1 || ps[0] != "module 'svc': key 'svc.n': must be 1 to 9, was 50" {
		t.Errorf("default: %q", ps)
	}
	_, ps = load(t, filepath.Join(t.TempDir(), "none"), map[string]string{"SVC_N": "0"}, good)
	if len(ps) != 1 || ps[0] != "module 'svc': key 'svc.n' (from environment variable SVC_N): must be 1 to 9, was 0" {
		t.Errorf("env: %q", ps)
	}
}

func rules() config.KeyRules {
	return config.KeyRules{
		Removed: config.FrameworkRemovedKeys(),
		Claims:  []config.ComponentClaim{{Module: "http", Component: "http"}},
	}
}

func loadRules(t *testing.T, r config.KeyRules, env map[string]string, yaml string, decls ...config.Declared) []string {
	t.Helper()
	dir := files(t, map[string]string{"application.yaml": yaml})
	src := filepath.Join(dir, "application.yaml")
	if env == nil {
		env = map[string]string{}
	}
	_, ps := config.Load(config.Sources{Dir: dir, Env: env}, decls, nil, r)
	out := problemTexts(ps)
	for i := range out {
		out[i] = strings.ReplaceAll(out[i], src, "FILE")
	}
	return out
}

func httpDecls() []config.Declared {
	return []config.Declared{
		{Module: "http", Setting: config.String("http.host", "").WithDefault("0.0.0.0")},
		{Module: "http", Setting: config.Int("http.port", "").WithDefault(8080)},
	}
}

func TestRemovedKeysInFiles(t *testing.T) {
	t.Parallel()
	got := loadRules(t, rules(), nil, "locks:\n  default: main\n  instances:\n    main: { driver: database }\ndatabase:\n  instances:\n    m: { driver: x, url: u }\n")
	want := []string{
		"module 'locks': key 'locks.default' (from FILE): moved to code: locks.DefaultStore(name) (see the guide page migrating-config-to-code)",
		"module 'locks': key 'locks.instances.main.driver' (from FILE): moved to code: locks.Build(ctx, b, d, locks.WithStore(name, locks.Memory() | dblock.Store(dbs) | redislock.Store(clients))) (see the guide page migrating-config-to-code)",
		"module 'database': key 'database.instances.m.driver' (from FILE): moved to code: nothing: PostgreSQL is the only database driver (see the guide page migrating-config-to-code)",
	}
	if !equalStrings(got, want) {
		t.Errorf("got\n%q\nwant\n%q", got, want)
	}
}

func TestRemovedKeysInTheEnvironment(t *testing.T) {
	t.Parallel()
	got := loadRules(t, rules(), map[string]string{
		"LOCKS_DEFAULT":                  "main",
		"REDIS_INSTANCES_CACHE_DRIVER":   "redis",
		"REDIS_INSTANCES_UNKNOWN_DRIVER": "redis", // no such instance in the files: never scanned for
	}, "redis:\n  instances:\n    cache: { url: u }\n")
	want := []string{
		"module 'redis': key 'redis.instances.cache.driver' (from environment variable REDIS_INSTANCES_CACHE_DRIVER): moved to code: nothing: one Redis driver (see the guide page migrating-config-to-code)",
		"module 'locks': key 'locks.default' (from environment variable LOCKS_DEFAULT): moved to code: locks.DefaultStore(name) (see the guide page migrating-config-to-code)",
	}
	if !equalStrings(got, want) {
		t.Errorf("got\n%q\nwant\n%q", got, want)
	}
}

func TestDeclaredKeyIsNeverRemoved(t *testing.T) {
	t.Parallel()
	r := config.KeyRules{Removed: []config.RemovedKey{{Pattern: "svc.old", Module: "svc", MovedTo: "code"}}}
	d := []config.Declared{{Module: "svc", Setting: config.String("svc.old.kept", "")}}
	if got := loadRules(t, r, nil, "svc:\n  old:\n    kept: x\n", d...); len(got) != 0 {
		t.Errorf("%q", got)
	}
	if got := loadRules(t, r, nil, "svc:\n  old:\n    other: x\n", d...); len(got) != 1 {
		t.Errorf("%q", got)
	}
}

func TestClaimedComponent(t *testing.T) {
	t.Parallel()
	got := loadRules(t, rules(), nil, "http:\n  port: 9\n  prot: 8\n  metrics:\n    x: 1\napp: {}\n", httpDecls()...)
	want := []string{
		"module 'http': key 'http.prot' (from FILE): unknown key: component 'http' belongs to module 'http', which reads only http.host, http.port",
		"module 'http': key 'http.metrics.x' (from FILE): unknown key: component 'http' belongs to module 'http', which reads only http.host, http.port",
	}
	if !equalStrings(got, want) {
		t.Errorf("got\n%q\nwant\n%q", got, want)
	}
	// a component with nothing declared
	r := config.KeyRules{Claims: []config.ComponentClaim{{Module: "locks", Component: "locks"}}}
	got = loadRules(t, r, nil, "locks:\n  x: 1\n")
	want = []string{"module 'locks': key 'locks.x' (from FILE): unknown key: component 'locks' belongs to module 'locks', which reads no key under it (its behaviour is set in code)"}
	if !equalStrings(got, want) {
		t.Errorf("got\n%q\nwant\n%q", got, want)
	}
	// the environment is never checked for unknown keys; an empty mapping above a declared key is fine
	if got := loadRules(t, rules(), map[string]string{"HTTP_PROT": "1"}, "http:\n", httpDecls()...); len(got) != 0 {
		t.Errorf("%q", got)
	}
}

func TestClaimProblems(t *testing.T) {
	t.Parallel()
	r := config.KeyRules{Claims: []config.ComponentClaim{{Module: "a", Component: "x"}, {Module: "b", Component: "x"}, {Module: "c", Component: "Bad!"}}}
	got := loadRules(t, r, nil, "")
	want := []string{
		"module 'b': key 'x': component 'x' is already claimed by module 'a'",
		"module 'c': key 'Bad!': a claimed component must match [a-z][a-z0-9_-]*",
	}
	if !equalStrings(got, want) {
		t.Errorf("got\n%q\nwant\n%q", got, want)
	}
}

func TestRemovedKeyPatternIsChecked(t *testing.T) {
	t.Parallel()
	r := config.KeyRules{Removed: []config.RemovedKey{{Pattern: "a..b", Module: "m", MovedTo: "x"}}}
	if got := loadRules(t, r, nil, ""); len(got) != 1 {
		t.Errorf("%q", got)
	}
}

func TestRemovedKeyInAnInstanceSectionIsReportedOnce(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "redis:\n  instances:\n    a: { url: u, driver: redis }\n"})
	sec := config.Section{Module: "redis", Path: "redis", Capability: "Redis", Keys: []config.Decl{keyURL}}
	_, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, nil, []config.Section{sec}, rules())
	if got := problemTexts(ps); len(got) != 1 || !strings.Contains(got[0], "moved to code: nothing: one Redis driver") {
		t.Errorf("%q", got)
	}
}

func TestDeclaredKeyIsNeverRemovedInTheEnvironment(t *testing.T) {
	t.Parallel()
	r := config.KeyRules{Removed: []config.RemovedKey{{Pattern: "svc.old", Module: "svc", MovedTo: "code"}}}
	d := []config.Declared{{Module: "svc", Setting: config.String("svc.old", "")}}
	if got := loadRules(t, r, map[string]string{"SVC_OLD": "x"}, "", d...); len(got) != 0 {
		t.Errorf("%q", got)
	}
}
