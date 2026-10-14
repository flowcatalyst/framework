package locks

import (
	"context"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the locks module's id.
const ModuleID = "locks"

// StoreFactory makes one lock store. Which factory backs a store is a fact of the code (config-
// and-code contract section 2): [Memory], dblock.Store, redislock.Store.
type StoreFactory func(ctx context.Context, b *fw.Boot) (Store, error)

// Memory is the factory of the in-process store: for one process only (tests, a single replica).
func Memory() StoreFactory {
	return func(_ context.Context, b *fw.Boot) (Store, error) { return NewMemoryStore(b.Clock()), nil }
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

// WithStore adds a lock store named name ([a-z][a-z0-9-]*), made by f. A name given twice is an
// error from [Build]. With exactly one store it is the default; with several, [DefaultStore]
// must name one.
func WithStore(name string, f StoreFactory) BuildOption {
	return func(c *buildConfig) { c.stores = append(c.stores, namedStore{name: name, factory: f}) }
}

// DefaultStore names the default store: required when [Build] is given several stores.
func DefaultStore(name string) BuildOption {
	return func(c *buildConfig) { c.defaultN = name }
}

// Declared is the locks module's declaration, from [Declare].
type Declared struct{}

// Declare declares the locks module. It declares no setting: lock stores are code (config-and-
// code contract section 3.7), given to [Build]. It claims the component "locks", so a leftover
// `locks:` configuration section fails start-up naming where its content moved.
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).ClaimComponent("locks")
	return &Declared{}
}

// Locks are the configured lock instances.
type Locks struct {
	names  []string
	def    string
	byName map[string]*Lock
}

// Get returns the named instance, or an error naming the known instances.
func (l *Locks) Get(name string) (*Lock, error) {
	if x, ok := l.byName[name]; ok {
		return x, nil
	}
	known := "(none)"
	if len(l.names) > 0 {
		known = strings.Join(l.names, ", ")
	}
	return nil, fmt.Errorf("no lock instance named '%s'; known instances: %s", name, known)
}

// Default returns the default instance, and false when no instance is configured.
func (l *Locks) Default() (*Lock, bool) {
	x, ok := l.byName[l.def]
	if !ok || x == nil {
		return nil, false
	}
	return x, true
}

// Names returns the instances' names in configuration order.
func (l *Locks) Names() []string { return slices.Clone(l.names) }

// Build makes the lock stores given in code. Names must be unique and match [a-z][a-z0-9-]*;
// exactly one store is the default, several need [DefaultStore]; no store at all is allowed (an
// empty [Locks]).
func Build(ctx context.Context, b *fw.Boot, _ *Declared, opts ...BuildOption) (*Locks, error) {
	var cfg buildConfig
	for _, o := range opts {
		o(&cfg)
	}
	seen := map[string]bool{}
	out := &Locks{byName: make(map[string]*Lock)}
	for _, st := range cfg.stores {
		if !storeName.MatchString(st.name) {
			return nil, fmt.Errorf("lock store name '%s' must match [a-z][a-z0-9-]*", st.name)
		}
		if seen[st.name] {
			return nil, fmt.Errorf("lock store '%s' is given twice", st.name)
		}
		if st.factory == nil {
			return nil, fmt.Errorf("lock store '%s' has no factory", st.name)
		}
		seen[st.name] = true
		out.names = append(out.names, st.name)
	}
	switch {
	case cfg.defaultN != "":
		if !seen[cfg.defaultN] {
			return nil, fmt.Errorf("default lock store '%s' is not one of the stores; known: %s", cfg.defaultN, listOrNone(out.names))
		}
		out.def = cfg.defaultN
	case len(out.names) == 1:
		out.def = out.names[0]
	case len(out.names) > 1:
		return nil, fmt.Errorf("several lock stores are given (%s) so locks.DefaultStore must name one", strings.Join(out.names, ", "))
	}
	for _, st := range cfg.stores {
		store, err := st.factory(ctx, b)
		if err != nil {
			return nil, fmt.Errorf("lock store '%s' failed: %w", st.name, err)
		}
		out.byName[st.name] = New(store, WithClock(b.Clock()), WithLogger(b.Logger(ModuleID)))
	}
	return out, nil
}

var storeName = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

func listOrNone(names []string) string {
	if len(names) == 0 {
		return "(none)"
	}
	return strings.Join(names, ", ")
}

// Gate is a background loop gate that admits a run only to the holder of a lock: each run takes
// the key with With and no wait, so a held lock skips the run instead of waiting, and the lease is
// refreshed while the task runs (background-and-metrics contract section 1).
type Gate struct {
	lock  *Lock
	key   Key
	lease time.Duration
}

// NewGate returns a gate on key with the given lease (100ms to 24h).
func NewGate(lock *Lock, key Key, lease time.Duration) (*Gate, error) {
	if _, err := leaseMillis(lease); err != nil {
		return nil, err
	}
	if key.IsZero() {
		return nil, fmt.Errorf("%w: the zero Key", ErrInvalidArgument)
	}
	return &Gate{lock: lock, key: key, lease: lease}, nil
}

// ServiceGate is a gate on the default instance with the service key name.
func ServiceGate(all *Locks, name string, lease time.Duration) (*Gate, error) {
	lock, ok := all.Default()
	if !ok {
		return nil, fmt.Errorf("no lock instance is configured")
	}
	key, err := ServiceKey(name)
	if err != nil {
		return nil, err
	}
	return NewGate(lock, key, lease)
}

// Enter implements background.Gate.
func (g *Gate) Enter(ctx context.Context, _ string, run func(ctx context.Context, fence int64, hasFence bool) error) (background.Outcome, error) {
	res, err := With(ctx, g.lock, g.key, g.lease, 0, func(ctx context.Context, lease Lease) (struct{}, error) {
		return struct{}{}, run(ctx, lease.Fence(), true)
	})
	switch res.Outcome {
	case Completed:
		return background.Ran{}, err
	case NotAcquired:
		return background.Skipped{Reason: "lock " + g.key.Stored() + " is held"}, err
	case Lost:
		return background.Lost{}, err
	}
	return nil, err
}
