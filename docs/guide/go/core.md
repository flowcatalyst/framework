<!-- types: App, Declarer, Boot, BuildFunc, Serve, ServeFunc, Running, StartupError, InstancesHandle, MigrationSet, CommandFunc, Criticality, HealthCheck, HealthReport, HealthResult, Setting, Handle, Secret, Values, Instances, Instance, Section, Problem, Context, ClientResult, PanicError, Options, Tick, Gate, Ran, Skipped, Lost, Loops, Registry, Counter, Gauge, Timer -->
# Go: the application and config

Packages `fw`, `config`, `background` and `metrics` are the Go counterpart of `framework-core`: the
lifecycle, configuration, `Context` and ids, health, background loops and metrics. The contracts
are the same ([core](../../contracts/core.md),
[background and metrics](../../contracts/background-and-metrics.md)); the API is shaped for Go
([design](../../go/design.md) section 1). Snippets come from `go/examples/locks-demo` and the
packages' tests.

## No container: declare, then build

There is no service container and no lookup by type. Each capability package has a `Declare`
function that registers its settings, instance sections and migration sets on the `fw.App` and
returns a typed handle. The application then builds its services in one function, passing each
its dependencies, so a missing dependency is a compile error (from `examples/locks-demo/demo.go`):

```go
func declare(app *fw.App) declared {
	d := declared{
		db:    database.Declare(app),
		redis: redisfw.Declare(app),
		locks: locks.Declare(app),
	}
	dblock.Declare(app) // the fw_lock migration
	return d
}
```

```go
dbs, err := database.Connect(ctx, b, d.db)
if err != nil {
	return nil, err
}
if err := database.Migrate(ctx, b, dbs); err != nil {
	return nil, err
}
clients, err := redisfw.Connect(ctx, b, d.redis)
if err != nil {
	return nil, err
}
all, err := locks.Build(ctx, b, d.locks,
	locks.WithStore("main", dblock.Store(dbs)),
	locks.WithStore("shared", redislock.Store(clients)),
	locks.DefaultStore("main"))
```

`App.Launch(ctx, args, build)` runs, and returns the exit code:

1. **configure**: every declared setting and every instance section (its keys, declared
   with `fw.Declarer.Instances`) is checked, as are the removed keys and claimed components below; every problem is reported at once as a
   `*fw.StartupError` (exit 1);
2. **build**: the `BuildFunc` constructs the services and registers lifecycle work on the `*fw.Boot`
   (`Health`, `Loop`, `OnStarted`, `OnShutdown`, `Command`); it returns what to serve (`fw.Serve`,
   usually the HTTP server, or a `ServeFunc`);
3. a first argument claimed with `Boot.Command` runs that command after build, without started
   actions, loops or serving, then shuts down;
4. **start**: the started actions in registration order, then the loops, then serve;
5. it runs until SIGTERM or SIGINT, or until serve returns;
6. **shutdown**: not ready from then on; loops stopped **first**; serve cancelled and waited for;
   then the shutdown actions in **reverse** registration order, each bounded by
   `app.shutdown.drain` (an overrun is logged and abandoned, a failure logged, the rest still run).

Tests use `App.Start(ctx, build)`, which returns a `*fw.Running` (`Health`, `Ready`, `Done`,
`Shutdown`). A build that fails runs the shutdown actions registered so far. A panic in build, a
started action, a command or serve is recovered and treated as a failure. Declaring after `Launch`
or `Start` began panics.

What is lost against Java, as in Rust: problems found while building (a database that does not
answer) stop at the first failing component; configuration problems are still reported together.
There is no `override`: a `build` takes the collaborators a test replaces as parameters (the demo's
`deps`).

## Config

Settings are immutable descriptors with a type; `config.Declare` returns the handle that reads the
value after configuration:

```go
drain: config.Declare(d, config.Duration("app.shutdown.drain",
	"Time each shutdown step may take before it is logged as overrun.").WithDefault(10*time.Second)),
```

`handle.Get(b)` cannot fail for a required setting or one with a default; `Lookup` returns
`(value, false)` for an optional one. Types: `String`, `Int` (32 bits), `Int64`, `Bool`, `Duration`
(`500ms`, `30s`, `5m`, `2h`, `1d`, ISO-8601), `URL`, `StringList`, `SecretValue` and `Enum`. The
rules are the Java framework's, checked against `conformance/config/config.json` (every entry
passes): sources from the default, `config/application.yaml`, `config/application-{env}.yaml`,
`config/secrets.local.yaml` (only for `APP_ENV` `local` or `test`) to environment variables named
after the key; every problem at once with the Java texts; two keys reading one environment variable
refused.

`config.Secret` prints `Secret[****]` through every `fmt` verb and `slog`, and refuses to be
encoded as JSON; `Reveal` gives the value, `Matches` compares SHA-256 digests in constant time.

**Instance sections are checked at configure time**, which Java does when building: a capability
declares the keys of its instances (`fw.Declarer.Instances`; there is no driver registry, one
schema for every instance), so an unknown or malformed key, a missing default and so on are reported with the other configuration problems. An
instance exists only when a file names it; its values may then come from the environment
(`DATABASE_INSTANCES_MAIN_URL`).

**Removed keys and claimed components** (config-and-code contract section 5). A key whose
behaviour moved to code (`locks.*`, `database.instances.*.driver`, `http-clients.instances.*.retry`,
and the rest of `config.FrameworkRemovedKeys()`) fails start-up with `moved to code: ...`, in a file
or as the environment variable derived from it. A module claims a component with
`Declarer.ClaimComponent` (the core claims `app`): any key under it that no module declares fails
start-up as unknown, listing the declared ones. Range checks are part of a setting
(`Setting.Checked(config.Between(lo, hi))`): a value out of range is a problem naming the key, never
corrected silently.

**Allowed difference:** a YAML file with a duplicate key is refused (`invalid YAML: ... mapping key
"k" already defined ...`), where the Java reader keeps the last value. No config vector case has a
duplicate key, so the difference is recorded here and in STATUS, not in the vector.

## Context and ids

`fw.Context` holds the client and correlation id (always), the principal, application, causation
and execution ids and the permissions (`HasPermission` uses the platform's wildcard rule). It is
passed explicitly beside `context.Context`, never stored in one. `fw.Ids` generates TSIDs
([Go overview](index.md)).

### Work for one client after another

A scheduled job's context is the platform's (`_platform`) and a background loop has none, but the work
goes through use cases, which are scoped to one client ([core contract](../../contracts/core.md) section 3a).
`cx.ForClient(client)` copies the context for another client and keeps the principal, its kind, the
application, the correlation, causation and execution ids and the permissions; it panics on the zero
client or the zero `Context`. `fw.EachClient(ctx, log, base, clients, task)` is Java's `PerClient.each`:
it runs `task(ctx, cx)` for each client in order with `base.ForClient(client)`, each client once, and
returns a `[]fw.ClientResult[R]` (client, value, error) in the order the clients ran:

```go
res := fw.EachClient(ctx, log, base, clients, func(ctx context.Context, cx fw.Context) (string, error) {
	done, err := d.Run(ctx, cx, cmd{Name: "swept-" + cx.Client().String()})
	return done.Event().Name, err
})
```

(from `usecase/runner_test.go`, `TestEachClientRunsUseCasesPerClientAndAuthorizeDecidesPerClient`). A task that
returns an error, or panics (`*fw.PanicError`), is logged to `log` (nothing when `log` is nil) and recorded
as failed for that client; the others still run. It stops starting clients once `ctx` is done; clients not
started are absent. Where the clients come from is the application's: one deliberate, unscoped query that
returns client codes, then every read and write is scoped to the client in hand. `ForClient` does not
check that the principal may act for the client; the use case's `Authorize` does, as for any caller.
Differences from Java: a slice instead of an ordered map; `ctx` done instead of thread interruption; a
panic is recorded as failed (Java records only exceptions).

## Health

`b.Health(name, fw.Critical or fw.Informational, check)`; each check runs with
`app.health.timeout` and reports `Up`, `Down` (the error's text) or `TimedOut`. `IsReady` looks at
the critical checks only; `app.health.critical` and `app.health.informational` override by name
(a name in both fails start-up, an unmatched name is a WARN). `database:<name>` and `redis:<name>`
are critical; `background:<name>` informational unless the loop's `Options.Critical`.

## Background loops

```go
b.Loop("sweeper", background.Options{Interval: 50 * time.Millisecond, Gate: gate},
	func(ctx context.Context, _ background.Tick) error {
		<-ctx.Done()
		x.trace("loop stopped")
		return nil
	})
```

A loop waits `InitialDelay`, runs, then waits `Interval` after the run ended (never overlapping).
An error is logged and the loop continues; a **panic** is recovered, logged, and ends the loop with
its health check down (Java's `Error`). A `Gate` decides each run: `Ran`, `Skipped` or `Lost`;
`locks.Gate` admits only the holder of a lock. Metrics `fw_background_runs_total{loop,outcome}` and
`fw_background_run_duration_seconds{loop}`.

## Metrics

`b.Metrics()` is the application's `*metrics.Registry` (never global): `Counter`, `Gauge`,
`GaugeFunc`, `Timer`, families with fixed label names and `With(values...)` per series; the naming,
label and cardinality rules of the contract; `metrics.WritePrometheus` writes the text exposition
byte for byte as the Java writer (numbers in Java's `Double.toString` form); `metrics.Handler`
serves it, with an optional bearer token compared as digests. `app.metrics.driver` is `memory` or
`noop`; `app.metrics.max-series` bounds each family.
