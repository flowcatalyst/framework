package objectstore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"regexp"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the object stores module's id.
const ModuleID = "object-stores"

// StoreFactory makes one store, given the name it has in [WithStore]. Which factory backs a store
// is a fact of the code (config-and-code contract section 2): [Memory], [Folder], s3store.Bucket.
// A store that holds a resource (a file handle, a connection) implements [io.Closer]; [Stores.Close]
// closes it.
type StoreFactory func(ctx context.Context, b *fw.Boot, name string) (Store, error)

// Option configures a driver factory.
type Option func(*options)

type options struct{ maxBytes int }

// MaxBytes sets the largest value a store accepts (at least 1; default [DefaultMaxBytes]).
func MaxBytes(n int) Option { return func(o *options) { o.maxBytes = n } }

func resolve(opts []Option) (options, error) {
	o := options{maxBytes: DefaultMaxBytes}
	for _, f := range opts {
		f(&o)
	}
	if o.maxBytes < 1 {
		return o, fmt.Errorf("MaxBytes must be at least 1, was %d", o.maxBytes)
	}
	return o, nil
}

// Memory is the factory of the in-process store: values live in this process only (tests).
func Memory(opts ...Option) StoreFactory {
	return func(_ context.Context, _ *fw.Boot, name string) (Store, error) {
		o, err := resolve(opts)
		if err != nil {
			return nil, err
		}
		return &memoryStore{name: name, maxBytes: o.maxBytes, values: make(map[string][]byte)}, nil
	}
}

// Folder is the factory of the folder store: a value is a file under the absolute directory
// (created if missing). Allowed only when APP_ENV is "local" (the default) or "test"; elsewhere
// the store fails to build and start-up fails naming it, because a folder on one machine is not
// shared storage.
func Folder(dir string, opts ...Option) StoreFactory {
	return func(_ context.Context, b *fw.Boot, name string) (Store, error) {
		o, err := resolve(opts)
		if err != nil {
			return nil, err
		}
		if !filepath.IsAbs(dir) {
			return nil, fmt.Errorf("directory must be an absolute path, was %s", dir)
		}
		if env := b.Environment(); env != "local" && env != "test" {
			return nil, fmt.Errorf("the folder object store is allowed only when APP_ENV is local or test (it is '%s'): "+
				"a folder on one machine is not shared storage; use s3store.Bucket when deployed", env)
		}
		return openFolder(name, dir, o.maxBytes)
	}
}

// BuildOption names a store or the default one for [Build].
type BuildOption func(*buildConfig)

type buildConfig struct {
	stores   []namedStore
	defaultN string
}

type namedStore struct {
	name    string
	factory StoreFactory
}

// WithStore adds a store named name ([a-z][a-z0-9-]*), made by f. A name given twice is an error
// from [Build]. With exactly one store it is the default; with several, [DefaultStore] must name
// one.
func WithStore(name string, f StoreFactory) BuildOption {
	return func(c *buildConfig) { c.stores = append(c.stores, namedStore{name: name, factory: f}) }
}

// DefaultStore names the default store: required when [Build] is given several stores.
func DefaultStore(name string) BuildOption {
	return func(c *buildConfig) { c.defaultN = name }
}

// Declared is the object stores module's declaration, from [Declare].
type Declared struct{}

// Declare declares the object stores module. It declares no setting: stores are code, given to
// [Build]. It claims the component "object-stores", so a leftover `object-stores:` configuration
// section fails start-up.
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).ClaimComponent("object-stores")
	return &Declared{}
}

// Stores are the application's object stores, by name.
type Stores struct {
	names  []string
	def    string
	byName map[string]Store
}

// Get returns the named store, or an error naming the known stores.
func (s *Stores) Get(name string) (Store, error) {
	if x, ok := s.byName[name]; ok {
		return x, nil
	}
	return nil, fmt.Errorf("no object store named '%s'; known stores: %s", name, listOrNone(s.names))
}

// Default returns the default store, and false when no store is configured.
func (s *Stores) Default() (Store, bool) {
	x, ok := s.byName[s.def]
	return x, ok
}

// Names returns the stores' names in the order they were given.
func (s *Stores) Names() []string { return slices.Clone(s.names) }

// Close closes every store that is an [io.Closer], and returns the failures joined; one failure
// does not stop the rest. [Build] registers it to run at shutdown.
func (s *Stores) Close() error {
	var errs []error
	for _, n := range s.names {
		if c, ok := s.byName[n].(io.Closer); ok {
			if err := c.Close(); err != nil {
				errs = append(errs, fmt.Errorf("closing object store '%s': %w", n, err))
			}
		}
	}
	return errors.Join(errs...)
}

var storeName = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

// Build makes the stores given in code. Names must be unique and match [a-z][a-z0-9-]*; one
// store is the default, several need [DefaultStore]; no store at all is allowed (an empty
// [Stores]). When any store fails to build, the stores built before it are closed and the error
// names every failed store. The stores are closed at shutdown.
func Build(ctx context.Context, b *fw.Boot, _ *Declared, opts ...BuildOption) (*Stores, error) {
	var cfg buildConfig
	for _, o := range opts {
		o(&cfg)
	}
	seen := map[string]bool{}
	out := &Stores{byName: make(map[string]Store)}
	for _, st := range cfg.stores {
		if !storeName.MatchString(st.name) {
			return nil, fmt.Errorf("object store name '%s' must match [a-z][a-z0-9-]*", st.name)
		}
		if seen[st.name] {
			return nil, fmt.Errorf("object store '%s' is given twice", st.name)
		}
		if st.factory == nil {
			return nil, fmt.Errorf("object store '%s' has no factory", st.name)
		}
		seen[st.name] = true
		out.names = append(out.names, st.name)
	}
	switch {
	case cfg.defaultN != "":
		if !seen[cfg.defaultN] {
			return nil, fmt.Errorf("default object store '%s' is not one of the stores; known: %s", cfg.defaultN, listOrNone(out.names))
		}
		out.def = cfg.defaultN
	case len(out.names) == 1:
		out.def = out.names[0]
	case len(out.names) > 1:
		return nil, fmt.Errorf("several object stores are given (%s) so objectstore.DefaultStore must name one", strings.Join(out.names, ", "))
	}
	var problems []error
	for _, st := range cfg.stores {
		store, err := st.factory(ctx, b, st.name)
		if err == nil && store == nil {
			err = errors.New("the factory returned no store")
		}
		if err != nil {
			problems = append(problems, fmt.Errorf("object store '%s' failed: %w", st.name, err))
			continue
		}
		out.byName[st.name] = store
	}
	if len(problems) > 0 {
		_ = out.Close() // start-up is failing anyway; the problems are what matters
		return nil, errors.Join(problems...)
	}
	b.OnShutdown(ModuleID, func(context.Context) error { return out.Close() })
	return out, nil
}

func listOrNone(names []string) string {
	if len(names) == 0 {
		return "(none)"
	}
	return strings.Join(names, ", ")
}
