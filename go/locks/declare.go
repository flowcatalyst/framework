package locks

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the locks module's id.
const ModuleID = "locks"

// MemoryDriverName is the memory driver's name in configuration.
const MemoryDriverName = "memory"

// DriverSpec declares a driver of the locks section: its name and the keys an instance using it
// may set, checked when the application is configured. Driver packages provide theirs
// (dblock.Declare, redislock.Driver); [MemoryDriver] is the memory driver's.
type DriverSpec struct {
	Name string
	Keys []config.Decl
}

// MemoryDriver is the memory driver's spec (no keys).
func MemoryDriver() DriverSpec { return DriverSpec{Name: MemoryDriverName} }

// Factory makes the store of one configured instance.
type Factory func(ctx context.Context, b *fw.Boot, in config.Instance) (Store, error)

// BuildOption gives [Build] the factory of a driver. Driver packages provide theirs
// (dblock.With(dbs), redislock.With(clients)); the memory driver needs none.
type BuildOption struct {
	driver  string
	factory Factory
}

// WithFactory returns the option that builds the instances of driver with f.
func WithFactory(driver string, f Factory) BuildOption {
	return BuildOption{driver: driver, factory: f}
}

// Declared is the locks module's declaration, from [Declare].
type Declared struct {
	instances fw.InstancesHandle
}

// Declare declares the locks module: the instance section "locks", whose instances each name
// one of drivers (there is no default driver).
func Declare(app *fw.App, drivers ...DriverSpec) *Declared {
	d := app.Module(ModuleID)
	specs := make([]config.Driver, 0, len(drivers))
	for _, s := range drivers {
		specs = append(specs, config.Driver{Name: s.Name, Keys: s.Keys})
	}
	return &Declared{instances: d.Instances("locks", "lock store", "", specs...)}
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

// Build makes every configured lock instance with its driver's factory.
func Build(ctx context.Context, b *fw.Boot, d *Declared, opts ...BuildOption) (*Locks, error) {
	factories := map[string]Factory{
		MemoryDriverName: func(_ context.Context, b *fw.Boot, _ config.Instance) (Store, error) {
			return NewMemoryStore(b.Clock()), nil
		},
	}
	for _, o := range opts {
		factories[o.driver] = o.factory
	}
	insts := b.Instances(d.instances)
	out := &Locks{names: slices.Clone(insts.Names), def: insts.Default, byName: make(map[string]*Lock)}
	for _, name := range insts.Names {
		in := insts.ByName[name]
		f, ok := factories[in.Driver]
		if !ok {
			return nil, fmt.Errorf("lock instance '%s': driver '%s' has no factory; pass its option to locks.Build (dblock.With, redislock.With)", name, in.Driver)
		}
		store, err := f(ctx, b, in)
		if err != nil {
			return nil, fmt.Errorf("lock instance '%s': driver '%s' failed: %w", name, in.Driver, err)
		}
		out.byName[name] = New(store, WithClock(b.Clock()), WithLogger(b.Logger(ModuleID)))
	}
	return out, nil
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
