package fw

import (
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

// App is an application being declared. Capability packages declare what they need through it
// (their Declare functions take the App), then [App.Launch] (or [App.Start] in tests) configures,
// builds, starts and runs it. Declaring after Launch or Start began panics: it is a programming
// error that the first test starting the application finds.
type App struct {
	name string
	opts appOptions

	mu         sync.Mutex
	sealed     bool
	problems   []Problem
	modules    []string
	settings   []config.Declared
	sections   []config.Section
	migrations []MigrationSet

	core coreSettings
}

// Option configures [New].
type Option func(*appOptions)

type appOptions struct {
	env       map[string]string
	clock     Clock
	configDir string
	logger    *slog.Logger
	stdout    io.Writer
	stderr    io.Writer
}

// WithEnvironment makes the application read env instead of the process environment (tests).
func WithEnvironment(env map[string]string) Option {
	return func(o *appOptions) { o.env = env }
}

// WithClock sets the application's wall clock (tests; default [SystemClock]).
func WithClock(c Clock) Option { return func(o *appOptions) { o.clock = c } }

// WithConfigDir sets the configuration directory (default "config").
func WithConfigDir(dir string) Option { return func(o *appOptions) { o.configDir = dir } }

// WithLogger sets the application's logger (default: JSON lines on standard error).
func WithLogger(l *slog.Logger) Option { return func(o *appOptions) { o.logger = l } }

// WithOutput sets where commands print (stdout) and where start-up problems are reported
// (stderr). The defaults are the process's standard output and error.
func WithOutput(stdout, stderr io.Writer) Option {
	return func(o *appOptions) { o.stdout, o.stderr = stdout, stderr }
}

// New returns an application named name ([a-z][a-z0-9-]*; a bad name is a start-up problem).
func New(name string, opts ...Option) *App {
	a := &App{name: name}
	for _, o := range opts {
		o(&a.opts)
	}
	if a.opts.clock == nil {
		a.opts.clock = SystemClock()
	}
	if a.opts.configDir == "" {
		a.opts.configDir = "config"
	}
	if a.opts.stdout == nil {
		a.opts.stdout = os.Stdout
	}
	if a.opts.stderr == nil {
		a.opts.stderr = os.Stderr
	}
	if a.opts.logger == nil {
		a.opts.logger = slog.New(slog.NewJSONHandler(a.opts.stderr, nil))
	}
	if !isSlug(name) {
		a.problems = append(a.problems, Problem{Message: fmt.Sprintf("application name %s must match [a-z][a-z0-9-]*", quote(name))})
	}
	a.core = declareCore(a.Module(coreModule))
	return a
}

// Name returns the application's name.
func (a *App) Name() string { return a.name }

// Declarer declares the settings, instance sections and migrations of one module. Every
// capability's Declare function asks the App for one, so problems name their module.
type Declarer struct {
	app    *App
	module string
}

// Module returns the declarer of a module. Module ids match [a-z][a-z0-9-]* and are unique in
// the application: a second module with one id is a start-up problem.
func (a *App) Module(id string) *Declarer {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.mustNotBeSealed("Module")
	switch {
	case !isSlug(id):
		a.problems = append(a.problems, Problem{Module: id, Message: "module id must match [a-z][a-z0-9-]*"})
	case slices.Contains(a.modules, id):
		a.problems = append(a.problems, Problem{Module: id, Message: "two modules with one id"})
	default:
		a.modules = append(a.modules, id)
	}
	return &Declarer{app: a, module: id}
}

// Module returns the declarer's module id.
func (d *Declarer) Module() string { return d.module }

// App returns the application being declared.
func (d *Declarer) App() *App { return d.app }

// DeclareSetting implements config.Declarer: config.Declare(d, setting) calls it.
func (d *Declarer) DeclareSetting(s config.Decl) {
	d.app.mu.Lock()
	defer d.app.mu.Unlock()
	d.app.mustNotBeSealed("DeclareSetting")
	d.app.settings = append(d.app.settings, config.Declared{Module: d.module, Setting: s})
}

// InstancesHandle is the proof that an instance section was declared; [Boot.Instances] reads
// the checked instances with it.
type InstancesHandle struct{ path string }

// Instances declares an instance section (core contract section 2): the instances at
// path.instances.<name>, each using one of drivers, whose keys are checked when the application
// is configured. capability names the capability in problems; defaultDriver is the driver of an
// instance that names none ("" when every instance must name one).
func (d *Declarer) Instances(path, capability, defaultDriver string, drivers ...config.Driver) InstancesHandle {
	d.app.mu.Lock()
	defer d.app.mu.Unlock()
	d.app.mustNotBeSealed("Instances")
	for _, s := range d.app.sections {
		if s.Path == path {
			d.app.problems = append(d.app.problems, Problem{
				Module: d.module, Key: path,
				Message: fmt.Sprintf("instance section declared twice (first by module '%s')", s.Module),
			})
			return InstancesHandle{path: path}
		}
	}
	d.app.sections = append(d.app.sections, config.Section{
		Module: d.module, Path: path, Capability: capability, DefaultDriver: defaultDriver, Drivers: drivers,
	})
	return InstancesHandle{path: path}
}

// MigrationSet is the migrations one module contributes: files named V<version>__<description>.sql
// at the root of Files (usually a //go:embed directory, made a root with fs.Sub). Sets are
// applied in module declaration order, to the default database instance (database contract
// section 2).
type MigrationSet struct {
	Module string
	Files  fs.FS
}

// Migrations contributes the module's migration set. A module contributes at most one.
func (d *Declarer) Migrations(files fs.FS) {
	d.app.mu.Lock()
	defer d.app.mu.Unlock()
	d.app.mustNotBeSealed("Migrations")
	for _, s := range d.app.migrations {
		if s.Module == d.module {
			d.app.problems = append(d.app.problems, Problem{Module: d.module, Message: "contributes a second migration set"})
			return
		}
	}
	d.app.migrations = append(d.app.migrations, MigrationSet{Module: d.module, Files: files})
}

func (a *App) mustNotBeSealed(what string) {
	if a.sealed {
		panic("fw: " + what + " called after Launch or Start began; declare everything before")
	}
}

func (a *App) seal() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sealed = true
}

// Problem is one start-up problem: the module, key and source where known, and the message.
type Problem = config.Problem

// StartupError is every problem that stopped the application from starting. Configuration
// problems are all reported together; a component that fails while being built stops the build.
type StartupError struct {
	Problems []Problem
}

func (e *StartupError) Error() string {
	var b strings.Builder
	n := len(e.Problems)
	fmt.Fprintf(&b, "Application failed to start: %d problem", n)
	if n != 1 {
		b.WriteString("s")
	}
	for _, p := range e.Problems {
		b.WriteString("\n  - ")
		b.WriteString(p.String())
	}
	return b.String()
}

// The core module's settings (Java CoreModule).
const coreModule = "core"

// MetricsDriver is the value of app.metrics.driver.
type MetricsDriver string

// The metrics drivers.
const (
	MetricsMemory MetricsDriver = "MEMORY"
	MetricsNoop   MetricsDriver = "NOOP"
)

type coreSettings struct {
	drain         config.Handle[time.Duration]
	healthTimeout config.Handle[time.Duration]
	critical      config.Handle[[]string]
	informational config.Handle[[]string]
	metricsDriver config.Handle[MetricsDriver]
	maxSeries     config.Handle[int]
}

func declareCore(d *Declarer) coreSettings {
	return coreSettings{
		drain: config.Declare(d, config.Duration("app.shutdown.drain",
			"Time each shutdown step may take before it is logged as overrun.").WithDefault(10*time.Second)),
		healthTimeout: config.Declare(d, config.Duration("app.health.timeout",
			"Time each health check may take before it reports TimedOut.").WithDefault(2*time.Second)),
		critical: config.Declare(d, config.StringList("app.health.critical",
			"Names of health checks that decide readiness, whatever their module asked for.").WithDefault([]string{})),
		informational: config.Declare(d, config.StringList("app.health.informational",
			"Names of health checks that are reported but never make the application not ready.").WithDefault([]string{})),
		metricsDriver: config.Declare(d, config.Enum("app.metrics.driver",
			"Metrics driver: memory (readable, Prometheus endpoint) or noop.", MetricsMemory, MetricsNoop).WithDefault(MetricsMemory)),
		maxSeries: config.Declare(d, config.Int("app.metrics.max-series",
			"Most series one metric may hold (1 to 100000); further label combinations are dropped.").WithDefault(1000)),
	}
}
