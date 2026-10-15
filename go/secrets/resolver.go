package secrets

import (
	"context"
	"fmt"
	"log/slog"
	"regexp"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the secrets module's id.
const ModuleID = "secrets"

// blocked is why the platform driver fails start-up.
const blocked = "the platform driver is blocked on the platform: FlowCatalyst has no endpoint that resolves a per-client " +
	"secret for an application (secrets contract, section 4); use Env or File"

// PlatformFactory is the platform store, which is blocked: see [Platform].
type PlatformFactory struct{ cache CachePolicy }

// Platform is the store that would ask the platform. The platform has no endpoint that resolves a
// secret reference or returns a per-client credential, so it still fails start-up with a message
// saying so.
func Platform() PlatformFactory { return PlatformFactory{cache: DefaultCache()} }

// WithCache returns a copy with another cache policy.
func (f PlatformFactory) WithCache(p CachePolicy) PlatformFactory { f.cache = p; return f }

// Create implements [StoreFactory]; it always fails.
func (PlatformFactory) Create(context.Context, string, *fw.Boot) (Store, error) {
	return nil, fmt.Errorf("%s", blocked)
}

// Declared is the secrets module's declaration, from [Declare].
type Declared struct{}

// Declare declares the secrets module. It declares no setting: stores are code (config-and-code
// contract section 3.8). It claims the component "secrets", so a leftover secrets: configuration
// section fails start-up naming where it moved.
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).ClaimComponent("secrets")
	return &Declared{}
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
// from [Build].
func WithStore(name string, f StoreFactory) BuildOption {
	return func(c *buildConfig) { c.stores = append(c.stores, namedStore{name: name, factory: f}) }
}

// DefaultStore names the default store: required when [Build] is given several stores.
func DefaultStore(name string) BuildOption { return func(c *buildConfig) { c.defaultN = name } }

var storeName = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

// Build makes the stores given in code and the resolver over them. Every problem is reported at
// once as a *fw.StartupError. With no store at all the resolver exists and every Resolve returns
// [ErrNoStore].
func Build(ctx context.Context, b *fw.Boot, _ *Declared, opts ...BuildOption) (*Resolver, error) {
	var cfg buildConfig
	for _, o := range opts {
		o(&cfg)
	}
	var problems []fw.Problem
	problem := func(key, msg string) {
		problems = append(problems, fw.Problem{Module: ModuleID, Key: key, Message: msg})
	}
	var names []string
	seen := map[string]bool{}
	for _, st := range cfg.stores {
		switch {
		case !storeName.MatchString(st.name):
			problem("secret store '"+st.name+"'", "secret store name must match [a-z][a-z0-9-]*, was '"+st.name+"'")
		case seen[st.name]:
			problem("secret store '"+st.name+"'", "secret store '"+st.name+"' is already given")
		case st.factory == nil:
			problem("secret store '"+st.name+"'", "has no factory")
		}
		seen[st.name] = true
		names = append(names, st.name)
	}
	def := cfg.defaultN
	switch {
	case def != "" && !seen[def]:
		problem("secret store '"+def+"'", "defaultStore '"+def+"' is not a store; stores: "+listOrNone(names))
	case def == "" && len(names) == 1:
		def = names[0]
	case def == "" && len(names) > 1:
		problem("secrets", "several secret stores ("+strings.Join(names, ", ")+"): name the default with secrets.DefaultStore")
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	built := map[string]Store{}
	for _, st := range cfg.stores {
		s, err := st.factory.Create(ctx, st.name, b)
		if err != nil {
			problem("secret store '"+st.name+"'", "failed: "+err.Error())
			continue
		}
		built[st.name] = s
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	return &Resolver{stores: built, names: names, def: def, log: b.Logger(ModuleID)}, nil
}

func listOrNone(names []string) string {
	if len(names) == 0 {
		return "(none)"
	}
	return strings.Join(names, ", ")
}

// Resolver resolves references for a client against the stores. It is safe for concurrent use.
type Resolver struct {
	stores map[string]Store
	names  []string
	def    string
	log    *slog.Logger
}

// Resolve looks the reference up for the client. The client comes from the caller, never from the
// reference. The error is [ErrNoStore] (nothing to resolve against), [ErrUnknownStore] or a
// [*StoreError] for a store that cannot be read.
func (r *Resolver) Resolve(ctx context.Context, client fw.ClientID, ref Ref) (Result, error) {
	if client.IsZero() {
		return nil, fmt.Errorf("%w: the zero ClientID", ErrInvalidName)
	}
	if ref.IsZero() {
		return nil, fmt.Errorf("%w: the zero Ref", ErrInvalidName)
	}
	name, store, err := r.store(ref)
	if err != nil {
		return nil, err
	}
	res, err := store.Find(ctx, client, ref.name)
	if err != nil {
		return nil, err
	}
	if r.log.Enabled(ctx, slog.LevelDebug) {
		r.log.DebugContext(ctx, fmt.Sprintf("secret %s of client %s from store %s: %s", ref.name, client, name, outcome(res)))
	}
	return res, nil
}

func outcome(res Result) string {
	switch res.(type) {
	case Found:
		return "Found"
	case NotFound:
		return "NotFound"
	case Refused:
		return "Refused"
	}
	return "?"
}

// Require is Resolve that returns the secret, or an error wrapping [ErrNotFound] for NotFound and
// Refused (the message names the store, client and name, never a value).
func (r *Resolver) Require(ctx context.Context, client fw.ClientID, ref Ref) (config.Secret, error) {
	res, err := r.Resolve(ctx, client, ref)
	if err != nil {
		return config.Secret{}, err
	}
	name, _, _ := r.store(ref)
	switch v := res.(type) {
	case Found:
		return v.Secret, nil
	case Refused:
		return config.Secret{}, notFoundf("secret %s for client %s refused: %s", ref.name, client, v.Reason)
	case NotFound:
	}
	return config.Secret{}, notFoundf("no secret %s for client %s in store %s", ref.name, client, name)
}

// Evict drops the cached value of one secret.
func (r *Resolver) Evict(client fw.ClientID, ref Ref) error {
	_, store, err := r.store(ref)
	if err != nil {
		return err
	}
	store.Evict(client, ref.name)
	return nil
}

// EvictAll drops every cached value of every store.
func (r *Resolver) EvictAll() {
	for _, s := range r.stores {
		s.EvictAll()
	}
}

// Names returns the stores' names in the order given.
func (r *Resolver) Names() []string { return slices.Clone(r.names) }

func (r *Resolver) store(ref Ref) (string, Store, error) {
	name := ref.store
	if name == "" {
		name = r.def
	}
	if name == "" {
		if len(r.stores) == 0 {
			return "", nil, ErrNoStore
		}
		return "", nil, fmt.Errorf("%w as the default", ErrNoStore)
	}
	s, ok := r.stores[name]
	if !ok {
		return "", nil, fmt.Errorf("%w '%s'; known: %s", ErrUnknownStore, name, listOrNone(r.names))
	}
	return name, s, nil
}
