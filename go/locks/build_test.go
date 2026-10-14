package locks_test

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
)

// build runs Build inside a started application and returns its result.
func build(t *testing.T, opts ...locks.BuildOption) (*locks.Locks, error) {
	t.Helper()
	app := fw.New("locks-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(t.TempDir()),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := locks.Declare(app)
	var out *locks.Locks
	r, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		out, err = locks.Build(ctx, b, d, opts...)
		return nil, err
	})
	if err == nil {
		_ = r.Shutdown()
	}
	return out, err
}

func TestBuildStores(t *testing.T) {
	t.Parallel()
	t.Run("one store is the default", func(t *testing.T) {
		all, err := build(t, locks.WithStore("main", locks.Memory()))
		if err != nil {
			t.Fatal(err)
		}
		if l, ok := all.Default(); !ok || l == nil {
			t.Error("no default")
		}
		if got := all.Names(); len(got) != 1 || got[0] != "main" {
			t.Errorf("%v", got)
		}
	})
	t.Run("several need a default, which must exist", func(t *testing.T) {
		_, err := build(t, locks.WithStore("a", locks.Memory()), locks.WithStore("b", locks.Memory()))
		if err == nil || !strings.Contains(err.Error(), "locks.DefaultStore must name one") {
			t.Errorf("%v", err)
		}
		_, err = build(t, locks.WithStore("a", locks.Memory()), locks.DefaultStore("zz"))
		if err == nil || !strings.Contains(err.Error(), "default lock store 'zz'") {
			t.Errorf("%v", err)
		}
		all, err := build(t, locks.WithStore("a", locks.Memory()), locks.WithStore("b", locks.Memory()), locks.DefaultStore("b"))
		if err != nil {
			t.Fatal(err)
		}
		want, _ := all.Get("b")
		if got, _ := all.Default(); got != want {
			t.Error("default is not b")
		}
	})
	t.Run("a name twice or malformed", func(t *testing.T) {
		if _, err := build(t, locks.WithStore("a", locks.Memory()), locks.WithStore("a", locks.Memory())); err == nil ||
			!strings.Contains(err.Error(), "'a' is given twice") {
			t.Errorf("%v", err)
		}
		for _, n := range []string{"", "A", "1a", "a_b"} {
			if _, err := build(t, locks.WithStore(n, locks.Memory())); err == nil || !strings.Contains(err.Error(), "must match") {
				t.Errorf("%q: %v", n, err)
			}
		}
	})
	t.Run("none is allowed", func(t *testing.T) {
		all, err := build(t)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := all.Default(); ok || len(all.Names()) != 0 {
			t.Error("expected an empty Locks")
		}
	})
}

func TestLeftoverLocksConfigurationFailsStartup(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	writeFile(t, dir+"/application.yaml", "locks:\n  default: main\n  instances:\n    main: { driver: memory }\n")
	app := fw.New("locks-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(dir),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	locks.Declare(app)
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err == nil || !strings.Contains(err.Error(), "key 'locks.default'") || !strings.Contains(err.Error(), "moved to code") {
		t.Errorf("%v", err)
	}
}
