package objectstore_test

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/objectstore"
	"github.com/flowcatalyst/framework/go/objectstore/objectstoretest"
)

const limit = 4096

func TestMemoryContract(t *testing.T) {
	t.Parallel()
	objectstoretest.Run(t, objectstoretest.Harness{Limit: limit, New: func(t *testing.T) objectstore.Store {
		return startOne(t, "test", objectstore.Memory(objectstore.MaxBytes(limit)))
	}})
}

func TestFolderContract(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	s := startOne(t, "test", objectstore.Folder(dir, objectstore.MaxBytes(limit)))
	objectstoretest.Run(t, objectstoretest.Harness{Limit: limit, New: func(*testing.T) objectstore.Store { return s }})
}

// startOne builds one store named "t" in an application running with APP_ENV=env and returns it.
func startOne(t *testing.T, env string, f objectstore.StoreFactory) objectstore.Store {
	t.Helper()
	all, err := build(t, env, objectstore.WithStore("t", f))
	if err != nil {
		t.Fatal(err)
	}
	s, err := all.Get("t")
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// build runs Build inside a started application; the application is shut down when the test ends.
func build(t *testing.T, env string, opts ...objectstore.BuildOption) (*objectstore.Stores, error) {
	t.Helper()
	environ := map[string]string{}
	if env != "" {
		environ["APP_ENV"] = env
	}
	app := fw.New("objectstore-test", fw.WithEnvironment(environ), fw.WithConfigDir(t.TempDir()),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := objectstore.Declare(app)
	var out *objectstore.Stores
	r, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		out, err = objectstore.Build(ctx, b, d, opts...)
		return nil, err
	})
	if err == nil {
		t.Cleanup(func() { _ = r.Shutdown() })
	}
	return out, err
}

func TestFolderSpecifics(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	t.Run("a put leaves no temporary file behind", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		s := startOne(t, "test", objectstore.Folder(dir))
		if err := s.Put(ctx, "a/b.json", []byte{1}); err != nil {
			t.Fatal(err)
		}
		var files []string
		_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, _ error) error {
			if !d.IsDir() {
				rel, _ := filepath.Rel(dir, p)
				files = append(files, rel)
			}
			return nil
		})
		if len(files) != 1 || files[0] != filepath.Join("a", "b.json") {
			t.Errorf("files: %v", files)
		}
	})
	t.Run("a symbolic link out of the folder is not followed", func(t *testing.T) {
		t.Parallel()
		dir, outside := t.TempDir(), t.TempDir()
		if err := os.WriteFile(filepath.Join(outside, "secret"), []byte("nope"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(outside, filepath.Join(dir, "link")); err != nil {
			t.Fatal(err)
		}
		s := startOne(t, "test", objectstore.Folder(dir))
		if err := s.Put(ctx, "link/secret", []byte{1}); !errors.Is(err, objectstore.ErrStore) {
			t.Errorf("put: want ErrStore, got %v", err)
		}
		if _, _, err := s.Get(ctx, "link/secret"); !errors.Is(err, objectstore.ErrStore) {
			t.Errorf("get: want ErrStore, got %v", err)
		}
		if err := s.Delete(ctx, "link/secret"); !errors.Is(err, objectstore.ErrStore) {
			t.Errorf("delete: want ErrStore, got %v", err)
		}
		if b, _ := os.ReadFile(filepath.Join(outside, "secret")); string(b) != "nope" {
			t.Errorf("the file outside changed: %q", b)
		}
	})
	t.Run("a value replaced by a file too large on disk is refused on read", func(t *testing.T) {
		t.Parallel()
		dir := t.TempDir()
		s := startOne(t, "test", objectstore.Folder(dir, objectstore.MaxBytes(limit)))
		if err := s.Put(ctx, "big", []byte{1}); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "big"), make([]byte, 5000), 0o600); err != nil {
			t.Fatal(err)
		}
		_, _, err := s.Get(ctx, "big")
		if !errors.Is(err, objectstore.ErrStore) || !strings.Contains(err.Error(), "over the limit") {
			t.Errorf("%v", err)
		}
	})
	t.Run("a key used as a value cannot also be a folder", func(t *testing.T) {
		t.Parallel()
		s := startOne(t, "test", objectstore.Folder(t.TempDir()))
		if err := s.Put(ctx, "x", []byte{1}); err != nil {
			t.Fatal(err)
		}
		if err := s.Put(ctx, "x/y", []byte{1}); !errors.Is(err, objectstore.ErrStore) {
			t.Errorf("want ErrStore, got %v", err)
		}
		if _, ok, err := s.Get(ctx, "x/y"); ok || err != nil {
			t.Errorf("get x/y: %v, %v", ok, err)
		}
	})
	t.Run("a relative directory is refused", func(t *testing.T) {
		t.Parallel()
		_, err := build(t, "test", objectstore.WithStore("f", objectstore.Folder("relative/dir")))
		if err == nil || !strings.Contains(err.Error(), "object store 'f'") || !strings.Contains(err.Error(), "absolute") {
			t.Errorf("%v", err)
		}
	})
}

func TestRegistry(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	t.Run("stores are named in code and keep separate values", func(t *testing.T) {
		t.Parallel()
		all, err := build(t, "", objectstore.WithStore("one", objectstore.Memory()), objectstore.WithStore("two", objectstore.Memory()),
			objectstore.DefaultStore("two"))
		if err != nil {
			t.Fatal(err)
		}
		if got := all.Names(); len(got) != 2 || got[0] != "one" || got[1] != "two" {
			t.Errorf("names %v", got)
		}
		one, _ := all.Get("one")
		two, _ := all.Get("two")
		if err := one.Put(ctx, "k", []byte{1}); err != nil {
			t.Fatal(err)
		}
		if _, ok, _ := two.Get(ctx, "k"); ok {
			t.Error("two sees one's value")
		}
		if def, ok := all.Default(); !ok || def != two {
			t.Error("default is not two")
		}
		if one.Name() != "one" {
			t.Errorf("name %q", one.Name())
		}
	})
	t.Run("one store is the default and several need one", func(t *testing.T) {
		t.Parallel()
		all, err := build(t, "", objectstore.WithStore("only", objectstore.Memory()))
		if err != nil {
			t.Fatal(err)
		}
		if def, ok := all.Default(); !ok || def.Name() != "only" {
			t.Error("no default")
		}
		_, err = build(t, "", objectstore.WithStore("a", objectstore.Memory()), objectstore.WithStore("b", objectstore.Memory()))
		if err == nil || !strings.Contains(err.Error(), "DefaultStore must name one") {
			t.Errorf("%v", err)
		}
		_, err = build(t, "", objectstore.WithStore("a", objectstore.Memory()), objectstore.DefaultStore("zz"))
		if err == nil || !strings.Contains(err.Error(), "default object store 'zz'") {
			t.Errorf("%v", err)
		}
	})
	t.Run("a name twice or malformed", func(t *testing.T) {
		t.Parallel()
		_, err := build(t, "", objectstore.WithStore("a", objectstore.Memory()), objectstore.WithStore("a", objectstore.Memory()))
		if err == nil || !strings.Contains(err.Error(), "'a' is given twice") {
			t.Errorf("%v", err)
		}
		for _, n := range []string{"", "A", "1a", "a_b", "Bad Name"} {
			if _, err := build(t, "", objectstore.WithStore(n, objectstore.Memory())); err == nil || !strings.Contains(err.Error(), "must match") {
				t.Errorf("%q: %v", n, err)
			}
		}
	})
	t.Run("none is allowed", func(t *testing.T) {
		t.Parallel()
		all, err := build(t, "")
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := all.Default(); ok || len(all.Names()) != 0 {
			t.Error("expected an empty Stores")
		}
		if _, err := all.Get("x"); err == nil || !strings.Contains(err.Error(), "(none)") {
			t.Errorf("%v", err)
		}
	})
	t.Run("a bad limit is refused", func(t *testing.T) {
		t.Parallel()
		_, err := build(t, "", objectstore.WithStore("m", objectstore.Memory(objectstore.MaxBytes(0))))
		if err == nil || !strings.Contains(err.Error(), "object store 'm'") {
			t.Errorf("%v", err)
		}
	})
}

func TestFolderRefusedOutsideLocalAndTest(t *testing.T) {
	t.Parallel()
	dir := filepath.Join(t.TempDir(), "objects")
	for _, env := range []string{"", "local", "test"} {
		all, err := build(t, env, objectstore.WithStore("f", objectstore.Folder(dir)))
		if err != nil {
			t.Fatalf("APP_ENV=%q: %v", env, err)
		}
		s, err := all.Get("f")
		if err != nil {
			t.Fatal(err)
		}
		if err := s.Put(t.Context(), "a/b", []byte{7}); err != nil {
			t.Fatal(err)
		}
		if b, _ := os.ReadFile(filepath.Join(dir, "a", "b")); !bytes.Equal(b, []byte{7}) {
			t.Errorf("APP_ENV=%q: file holds %v", env, b)
		}
	}
	for _, env := range []string{"prod", "staging", "np", "production"} {
		_, err := build(t, env, objectstore.WithStore("f", objectstore.Folder(dir)))
		if err == nil || !strings.Contains(err.Error(), "object store 'f'") || !strings.Contains(err.Error(), "local or test") ||
			!strings.Contains(err.Error(), env) {
			t.Errorf("APP_ENV=%q: %v", env, err)
		}
	}
}

// closing is a store that records being closed.
type closing struct {
	objectstore.Store
	closed *atomic.Int32
}

func (c closing) Close() error { c.closed.Add(1); return nil }

func closingFactory(closed *atomic.Int32) objectstore.StoreFactory {
	return func(ctx context.Context, b *fw.Boot, name string) (objectstore.Store, error) {
		inner, err := objectstore.Memory()(ctx, b, name)
		return closing{Store: inner, closed: closed}, err
	}
}

func TestClose(t *testing.T) {
	t.Parallel()
	t.Run("a closable store is closed by Close", func(t *testing.T) {
		t.Parallel()
		var closed atomic.Int32
		all, err := build(t, "", objectstore.WithStore("c", closingFactory(&closed)), objectstore.WithStore("m", objectstore.Memory()),
			objectstore.DefaultStore("c"))
		if err != nil {
			t.Fatal(err)
		}
		if closed.Load() != 0 {
			t.Fatal("closed too early")
		}
		if err := all.Close(); err != nil {
			t.Fatal(err)
		}
		if closed.Load() != 1 {
			t.Errorf("closed %d times", closed.Load())
		}
	})
	t.Run("shutdown closes the stores", func(t *testing.T) {
		t.Parallel()
		var closed atomic.Int32
		app := fw.New("objectstore-test", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()),
			fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
		d := objectstore.Declare(app)
		r, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
			_, err := objectstore.Build(ctx, b, d, objectstore.WithStore("c", closingFactory(&closed)))
			return nil, err
		})
		if err != nil {
			t.Fatal(err)
		}
		if closed.Load() != 0 {
			t.Fatal("closed too early")
		}
		if err := r.Shutdown(); err != nil {
			t.Fatal(err)
		}
		if closed.Load() != 1 {
			t.Errorf("closed %d times", closed.Load())
		}
	})
	t.Run("a failing store builds nothing and closes the earlier ones", func(t *testing.T) {
		t.Parallel()
		var closed atomic.Int32
		bad := func(context.Context, *fw.Boot, string) (objectstore.Store, error) {
			return nil, errors.New("no bucket")
		}
		all, err := build(t, "", objectstore.WithStore("good", closingFactory(&closed)), objectstore.WithStore("bad", bad),
			objectstore.DefaultStore("good"))
		if err == nil || !strings.Contains(err.Error(), "object store 'bad'") || !strings.Contains(err.Error(), "no bucket") {
			t.Errorf("%v", err)
		}
		if all != nil {
			t.Error("a Stores was returned")
		}
		if closed.Load() != 1 {
			t.Errorf("the earlier store was closed %d times", closed.Load())
		}
	})
	t.Run("a leftover object-stores section fails start-up", func(t *testing.T) {
		t.Parallel()
		cfg := t.TempDir()
		if err := os.WriteFile(filepath.Join(cfg, "application.yaml"), []byte("object-stores:\n  x: 1\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		app := fw.New("objectstore-test", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(cfg), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
		d := objectstore.Declare(app)
		_, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
			_, err := objectstore.Build(ctx, b, d, objectstore.WithStore("m", objectstore.Memory()))
			return nil, err
		})
		if err == nil || !strings.Contains(err.Error(), "object-stores") {
			t.Errorf("%v", err)
		}
	})
}
