package config_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/flowcatalyst/framework/go/config"
)

func TestSourcesInOrder(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{
		"application.yaml":      "svc:\n  a: app\n  b: app\n  c: app\n  d: app\n",
		"application-test.yaml": "svc:\n  b: envfile\n  c: envfile\n  d: envfile\n",
		"secrets.local.yaml":    "svc:\n  d: secrets\n",
	})
	d := &declarer{}
	hs := make([]config.Handle[string], 0, 6)
	for _, k := range []string{"svc.a", "svc.b", "svc.c", "svc.d", "svc.e", "svc.f"} {
		s := config.String(k, "")
		if k == "svc.e" {
			s = s.WithDefault("default")
		}
		hs = append(hs, config.Declare(d, s))
	}
	v, ps := load(t, dir, map[string]string{"APP_ENV": "test", "SVC_C": "env"}, d.decls...)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	want := []string{"app", "envfile", "env", "secrets", "default"}
	for i, w := range want {
		if got := hs[i].Get(v); got != w {
			t.Errorf("%s = %q; want %q", hs[i].Setting().Key(), got, w)
		}
	}
	if _, ok := hs[5].Lookup(v); ok {
		t.Error("svc.f must have no value")
	}
	if v.Environment() != "test" {
		t.Errorf("Environment() = %q", v.Environment())
	}
}

func TestSecretsFileOnlyForLocalAndTest(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"secrets.local.yaml": "svc:\n  password: hunter2\n", "application-prod.yaml": "svc:\n  other: x\n"})
	for env, want := range map[string]bool{"prod": false, "local": true, "test": true, "": true, " \t": true, "staging": false} { //nolint:gocritic // the whitespace is the case under test
		d := &declarer{}
		h := config.Declare(d, config.SecretValue("svc.password", ""))
		e := map[string]string{}
		if env != "" || want {
			e["APP_ENV"] = env
		}
		v, ps := load(t, dir, e, d.decls...)
		if len(ps) != 0 {
			t.Fatal(ps)
		}
		_, got := h.Lookup(v)
		if got != want {
			t.Errorf("APP_ENV=%q: password present = %v; want %v", env, got, want)
		}
	}
}

func TestBlankAppEnvMeansLocal(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application-local.yaml": "svc:\n  a: local-file\n"})
	d := &declarer{}
	h := config.Declare(d, config.String("svc.a", ""))
	v, _ := load(t, dir, map[string]string{"APP_ENV": " "}, d.decls...)
	if h.Get(v) != "local-file" || v.Environment() != "local" {
		t.Errorf("got %q in %q", h.Get(v), v.Environment())
	}
}

func TestEnvironmentVariableBeatsFileEvenWhenEmpty(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "svc:\n  a: file\n"})
	d := &declarer{}
	h := config.Declare(d, config.String("svc.a", ""))
	v, _ := load(t, dir, map[string]string{"SVC_A": ""}, d.decls...)
	if got := h.Get(v); got != "" {
		t.Errorf("got %q", got)
	}
}

func TestProblemsAllAtOnceAndSecretNeverQuoted(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "svc:\n  port: eighty\n  timeout: 5 minutes\n  tags: { a: 1 }\n  mode: medium\n  list: [a]\n  token: [x]\n"})
	d := &declarer{}
	config.Declare(d, config.Int("svc.port", "").WithDefault(8080))
	config.Declare(d, config.Duration("svc.timeout", "").WithDefault(time1s))
	config.Declare(d, config.String("svc.name", "").Required())
	config.Declare(d, config.SecretValue("svc.secret", "").Required())
	config.Declare(d, config.Int64("svc.retries", ""))
	config.Declare(d, config.StringList("svc.tags", ""))
	config.Declare(d, config.Enum("svc.mode", "", fast, slowStart))
	config.Declare(d, config.String("svc.list", ""))
	config.Declare(d, config.SecretValue("svc.token", ""))
	_, ps := load(t, dir, map[string]string{"SVC_RETRIES": "many"}, d.decls...)
	src := " (from " + dir + "/application.yaml)"
	want := []string{
		"module 'svc': key 'svc.port'" + src + ": expected an integer, found 'eighty'",
		"module 'svc': key 'svc.timeout'" + src + ": expected " + durationExpected + ", found '5 minutes'",
		"module 'svc': key 'svc.name': missing required setting; set it in config/application.yaml or as environment variable SVC_NAME",
		"module 'svc': key 'svc.secret': missing required setting; set it in config/application.yaml, config/secrets.local.yaml (local and test only) or as environment variable SVC_SECRET",
		"module 'svc': key 'svc.retries' (from environment variable SVC_RETRIES): expected a long integer, found 'many'",
		"module 'svc': key 'svc.mode'" + src + ": expected one of FAST, SLOW_START, found 'medium'",
		"module 'svc': key 'svc.list'" + src + ": expected a string, found 'a list'",
		"module 'svc': key 'svc.token'" + src + ": expected a string",
	}
	if !equalStrings(ps, want) {
		t.Errorf("problems:\n got %q\nwant %q", ps, want)
	}
}

const time1s = 1_000_000_000

func TestDuplicateDeclarationAndEnvironmentNameCollisions(t *testing.T) {
	t.Parallel()
	dir := files(t, map[string]string{"application.yaml": "locks:\n  instances:\n    a-b: { driver: memory }\n    a_b: { driver: memory }\nother:\n  instances:\n    x: { url: u }\n"})
	decls := []config.Declared{
		{Module: "mail", Setting: config.String("mail.host-name", "")},
		{Module: "mail", Setting: config.String("mail.host_name", "")},
		{Module: "other", Setting: config.String("mail.host-name", "")},
		{Module: "other", Setting: config.String("other.instances.x.url", "")},
		{Module: "web", Setting: config.String("mail.host.name", "")},
	}
	_, ps := config.Load(config.Sources{Dir: dir, Env: map[string]string{}}, decls, nil)
	want := []string{
		"module 'other': key 'mail.host-name': declared twice (first by module 'mail')",
		"module 'mail': key 'mail.host_name': reads environment variable MAIL_HOST_NAME, which key 'mail.host-name' (declared by module 'mail') reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
		"module 'web': key 'mail.host.name': reads environment variable MAIL_HOST_NAME, which key 'mail.host-name' (declared by module 'mail') reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
		"key 'locks.instances.a_b.driver': reads environment variable LOCKS_INSTANCES_A_B_DRIVER, which key 'locks.instances.a-b.driver' (an instance setting in " + dir + "/application.yaml) reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
	}
	if got := problemTexts(ps); !equalStrings(got, want) {
		t.Errorf("problems:\n got %q\nwant %q", got, want)
	}
}

func TestProblemString(t *testing.T) {
	t.Parallel()
	cases := []struct {
		p    config.Problem
		want string
	}{
		{config.Problem{Module: "m", Key: "k", Source: "s", Message: "x"}, "module 'm': key 'k' (from s): x"},
		{config.Problem{Module: "m", Key: "k", Message: "x"}, "module 'm': key 'k': x"},
		{config.Problem{Key: "k", Source: "s", Message: "x"}, "key 'k' (from s): x"},
		{config.Problem{Source: "config/a.yaml", Message: "x"}, "config/a.yaml: x"},
		{config.Problem{Module: "m", Message: "x"}, "module 'm': x"},
		{config.Problem{Message: "x"}, "x"},
	}
	for _, c := range cases {
		if got := c.p.String(); got != c.want {
			t.Errorf("%+v = %q; want %q", c.p, got, c.want)
		}
	}
}

func TestEnvNames(t *testing.T) {
	t.Parallel()
	for in, want := range map[string]string{"http.port": "HTTP_PORT", "mail.host-name": "MAIL_HOST_NAME", "mail.host_name": "MAIL_HOST_NAME", "a": "A", "app.metrics.max-series": "APP_METRICS_MAX_SERIES"} {
		if got := config.EnvName(in); got != want {
			t.Errorf("EnvName(%q) = %q", in, got)
		}
	}
	if config.String("x.y-z", "").EnvName() != "X_Y_Z" {
		t.Error("Setting.EnvName")
	}
}

func TestEnvironmentReadsProcessEnvironment(t *testing.T) {
	t.Setenv("FW_CONFIG_TEST_VAR", "yes")
	if config.Environment()["FW_CONFIG_TEST_VAR"] != "yes" {
		t.Error("process environment not visible")
	}
	// A nil Env in Sources means the process environment.
	t.Setenv("SVC_FROM_PROCESS", "p")
	d := &declarer{}
	h := config.Declare(d, config.String("svc.from-process", ""))
	var ds []config.Declared
	ds = append(ds, config.Declared{Module: "svc", Setting: d.decls[0]})
	v, ps := config.Load(config.Sources{Dir: filepath.Join(t.TempDir(), "none")}, ds, nil)
	if len(ps) != 0 || h.Get(v) != "p" {
		t.Errorf("nil Env: %v %v", ps, v)
	}
}

func TestDefaultDirIsConfig(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "config"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "config", "application.yaml"), []byte("svc:\n  a: x\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Chdir(root)
	d := &declarer{}
	h := config.Declare(d, config.String("svc.a", ""))
	v, ps := config.Load(config.Sources{Env: map[string]string{}}, []config.Declared{{Module: "svc", Setting: d.decls[0]}}, nil)
	if len(ps) != 0 || h.Get(v) != "x" {
		t.Errorf("got %v %v", ps, v)
	}
	// and the problem source is config/application.yaml
	if err := os.WriteFile(filepath.Join(root, "config", "application.yaml"), []byte("svc: [\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, ps = config.Load(config.Sources{Env: map[string]string{}}, nil, nil)
	if len(ps) != 1 || ps[0].Source != "config/application.yaml" {
		t.Errorf("source = %+v", ps)
	}
}

func TestHandles(t *testing.T) {
	t.Parallel()
	d := &declarer{}
	opt := config.Declare(d, config.String("svc.opt", ""))
	def := config.Declare(d, config.Int("svc.def", "").WithDefault(5))
	v, ps := load(t, filepath.Join(t.TempDir(), "none"), nil, d.decls...)
	if len(ps) != 0 {
		t.Fatal(ps)
	}
	if got := def.Get(v); got != 5 {
		t.Errorf("Get = %d", got)
	}
	if got, ok := def.Lookup(v); !ok || got != 5 {
		t.Errorf("Lookup = %d %v", got, ok)
	}
	if got, ok := opt.Lookup(v); ok || got != "" {
		t.Errorf("Lookup of unset = %q %v", got, ok)
	}
	mustPanic(t, "no value", func() { opt.Get(v) }, "svc.opt", "Lookup")
	var zero config.Handle[string]
	mustPanic(t, "zero Get", func() { zero.Get(v) }, "zero")
	mustPanic(t, "zero Lookup", func() { zero.Lookup(v) }, "zero")
	if def.Setting().Key() != "svc.def" {
		t.Error("Setting()")
	}
}

func mustPanic(t *testing.T, what string, f func(), contains ...string) {
	t.Helper()
	defer func() {
		t.Helper()
		r := recover()
		if r == nil {
			t.Errorf("%s: no panic", what)
			return
		}
		msg, _ := r.(string)
		if msg == "" {
			if e, ok := r.(error); ok {
				msg = e.Error()
			}
		}
		for _, c := range contains {
			if !containsStr(msg, c) {
				t.Errorf("%s: panic %q does not mention %q", what, msg, c)
			}
		}
	}()
	f()
}

func containsStr(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
