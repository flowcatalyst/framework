package fw

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/metrics"
)

// Boot is what the application's build function receives: the configured values, the clock,
// ids, logger and metrics, and the lifecycle registrations (health checks, loops, started and
// shutdown actions, commands). Nothing registered runs until build has returned.
type Boot struct {
	app     *App
	values  *config.Values
	ids     *Ids
	metrics *metrics.Registry
	loops   *background.Loops
	health  *health
	ready   atomic.Bool

	mu       sync.Mutex
	problems []Problem
	started  []namedAction
	shutdown []namedAction
	commands map[string]CommandFunc
	cmdOrder []string
}

type namedAction struct {
	name string
	run  func(ctx context.Context) error
}

// CommandFunc runs a command claimed with [Boot.Command]: args are the arguments after the
// command's name, out is where it prints; it returns the process exit code.
type CommandFunc func(ctx context.Context, args []string, out io.Writer) int

// BuildFunc is the application's build function: it constructs the services in order, passing
// each its dependencies, registers lifecycle work on b, and returns what to serve (nil for an
// application that only runs loops until it is stopped).
type BuildFunc func(ctx context.Context, b *Boot) (Serve, error)

// Serve is what a started application runs, usually the HTTP server. Serve blocks until ctx is
// cancelled (shutdown has begun; it then drains and returns) or until its work is done. When it
// returns before shutdown began, the application shuts down: with exit code 0 for a nil error,
// 1 otherwise.
type Serve interface {
	Serve(ctx context.Context) error
}

// ServeFunc adapts a function to [Serve].
type ServeFunc func(ctx context.Context) error

// Serve calls f(ctx).
func (f ServeFunc) Serve(ctx context.Context) error { return f(ctx) }

// AppName returns the application's name.
func (b *Boot) AppName() string { return b.app.name }

// Environment returns APP_ENV ("local" when absent or blank).
func (b *Boot) Environment() string { return b.values.Environment() }

// ConfigValue implements config.Source, so config handles read through the Boot:
// handle.Get(b).
func (b *Boot) ConfigValue(key string) (any, bool) { return b.values.ConfigValue(key) }

// Instances returns the checked instances of a declared instance section.
func (b *Boot) Instances(h InstancesHandle) config.Instances { return b.values.Instances(h.path) }

// MigrationSets returns the migration sets declared by the application's modules, in module
// declaration order.
func (b *Boot) MigrationSets() []MigrationSet {
	b.app.mu.Lock()
	defer b.app.mu.Unlock()
	order := b.app.modules
	sets := slices.Clone(b.app.migrations)
	slices.SortStableFunc(sets, func(x, y MigrationSet) int {
		return slices.Index(order, x.Module) - slices.Index(order, y.Module)
	})
	return sets
}

// HealthReport runs every health check, each bounded by app.health.timeout, and reports.
func (b *Boot) HealthReport(ctx context.Context) HealthReport { return b.health.run(ctx) }

// Ready reports whether the application is ready: started, and not shutting down. The HTTP
// layer answers /health/ready from it and the critical checks.
func (b *Boot) Ready() bool { return b.ready.Load() }

// ShutdownDrain returns app.shutdown.drain, the time each shutdown step may take.
func (b *Boot) ShutdownDrain() time.Duration { return b.app.core.drain.Get(b) }

// Clock returns the application's wall clock.
func (b *Boot) Clock() Clock { return b.app.opts.clock }

// Ids returns the application's TSID generator.
func (b *Boot) Ids() *Ids { return b.ids }

// Metrics returns the application's metrics registry.
func (b *Boot) Metrics() *metrics.Registry { return b.metrics }

// Logger returns the application's logger for a module: every line carries module=<module>.
func (b *Boot) Logger(module string) *slog.Logger {
	return b.app.opts.logger.With(slog.String("module", module))
}

// Health adds a health check. A name used twice is a start-up problem.
func (b *Boot) Health(name string, c Criticality, check HealthCheck) {
	if err := b.health.add(name, c, check); err != nil {
		b.problem(Problem{Message: err.Error()})
	}
}

// Loop registers a background loop, started after every started action, stopped first at
// shutdown, with the health check background:<name> (informational unless opts.Critical).
func (b *Boot) Loop(name string, opts background.Options, run background.Func) {
	if err := b.loops.Register(name, opts, run); err != nil {
		b.problem(Problem{Key: "loop " + name, Message: err.Error()})
		return
	}
	crit := Informational
	if opts.Critical {
		crit = Critical
	}
	b.Health("background:"+name, crit, HealthCheck(b.loops.Check(name)))
}

// OnStarted registers work that runs after build returned, in registration order, before loops
// start and the application serves. A failure stops the start.
func (b *Boot) OnStarted(name string, f func(ctx context.Context) error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.started = append(b.started, namedAction{name: name, run: f})
}

// OnShutdown registers work that runs at shutdown, after loops stopped and the server drained,
// in reverse registration order, each bounded by app.shutdown.drain. Register the closing of a
// resource right after opening it; a failure is logged and the rest still run.
func (b *Boot) OnShutdown(name string, f func(ctx context.Context) error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.shutdown = append(b.shutdown, namedAction{name: name, run: f})
}

// Command claims a command name: when the application is launched with it as the first
// argument, the command runs after build (without started actions, loops or serving) and the
// application then shuts down. Two claims of one name are a start-up problem.
func (b *Boot) Command(name string, run CommandFunc) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.commands == nil {
		b.commands = make(map[string]CommandFunc)
	}
	if _, dup := b.commands[name]; dup {
		b.problems = append(b.problems, Problem{Key: "command " + name, Message: "claimed twice"})
		return
	}
	b.commands[name] = run
	b.cmdOrder = append(b.cmdOrder, name)
}

func (b *Boot) problem(p Problem) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.problems = append(b.problems, p)
}

func (b *Boot) takeProblems() []Problem {
	b.mu.Lock()
	defer b.mu.Unlock()
	p := b.problems
	b.problems = nil
	return p
}

func (b *Boot) command(name string) (CommandFunc, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	c, ok := b.commands[name]
	return c, ok
}

// newBoot configures the application: it reads every source, resolves every declared setting
// and instance section, and builds the core services. Every configuration problem is returned
// at once.
func (a *App) newBoot() (*Boot, error) {
	a.seal()
	env := a.opts.env
	if env == nil {
		env = config.Environment()
	}
	a.mu.Lock()
	problems := slices.Clone(a.problems)
	settings := slices.Clone(a.settings)
	sections := slices.Clone(a.sections)
	rules := config.KeyRules{Removed: config.FrameworkRemovedKeys(), Claims: slices.Clone(a.claims)}
	a.mu.Unlock()

	values, cfgProblems := config.Load(config.Sources{Dir: a.opts.configDir, Env: env}, settings, sections, rules)
	problems = append(problems, cfgProblems...)
	if values == nil || len(problems) > 0 {
		return nil, &StartupError{Problems: problems}
	}

	b := &Boot{app: a, values: values, ids: NewIds(a.opts.clock)}
	critical := a.core.critical.Get(b)
	informational := a.core.informational.Get(b)
	for _, n := range critical {
		if slices.Contains(informational, n) {
			problems = append(problems, Problem{
				Module: coreModule, Key: "app.health.informational",
				Message: fmt.Sprintf("names check '%s', which app.health.critical names too", n),
			})
		}
	}
	maxSeries := a.core.maxSeries.Get(b)
	if len(problems) > 0 {
		return nil, &StartupError{Problems: problems}
	}
	mopts := []metrics.Option{metrics.WithMaxSeries(maxSeries), metrics.WithLogger(b.Logger("metrics"))}
	if a.core.metricsDriver.Get(b) == MetricsNoop {
		mopts = append(mopts, metrics.Noop())
	}
	reg, err := metrics.NewRegistry(mopts...)
	if err != nil {
		return nil, &StartupError{Problems: []Problem{{Module: coreModule, Key: "app.metrics.driver", Message: err.Error()}}}
	}
	b.metrics = reg
	loops, err := background.NewLoops(b.Logger("background"), reg, a.opts.clock)
	if err != nil {
		return nil, &StartupError{Problems: []Problem{{Module: coreModule, Message: err.Error()}}}
	}
	b.loops = loops
	b.health = &health{
		timeout:       a.core.healthTimeout.Get(b),
		critical:      critical,
		informational: informational,
	}
	return b, nil
}
