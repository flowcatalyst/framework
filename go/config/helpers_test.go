package config_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/flowcatalyst/framework/go/config"
)

// files writes name->content into a fresh "config" directory and returns its path.
func files(t *testing.T, content map[string]string) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	for name, text := range content {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

// load runs Load for one module's declarations.
func load(t *testing.T, dir string, env map[string]string, decls ...config.Decl) (*config.Values, []string) {
	t.Helper()
	var ds []config.Declared
	for _, d := range decls {
		ds = append(ds, config.Declared{Module: "svc", Setting: d})
	}
	if env == nil {
		env = map[string]string{}
	}
	v, ps := config.Load(config.Sources{Dir: dir, Env: env}, ds, nil, config.KeyRules{})
	return v, problemTexts(ps)
}

func problemTexts(ps []config.Problem) []string {
	var out []string
	for _, p := range ps {
		out = append(out, p.String())
	}
	return out
}

// declarer collects declarations for config.Declare.
type declarer struct{ decls []config.Decl }

func (d *declarer) DeclareSetting(s config.Decl) { d.decls = append(d.decls, s) }

// parseEnv resolves s from an environment variable holding text.
func parseEnv[T any](t *testing.T, s config.Setting[T], text string) (T, string) {
	t.Helper()
	d := &declarer{}
	h := config.Declare(d, s)
	v, ps := load(t, filepath.Join(t.TempDir(), "none"), map[string]string{s.EnvName(): text}, d.decls...)
	if len(ps) > 0 {
		var zero T
		return zero, ps[0]
	}
	return h.Get(v), ""
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
