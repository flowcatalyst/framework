<!-- types: AppBuilder, Configured, Built, Running, Boot, Module, Declarations, ModuleId, Setting, SettingHandle, Key, Config, Environment, InstanceSection, DriverSpec, KeySpec, ConfiguredInstances, InstanceConfig, ConfigEnum, Context, ClientId, CorrelationId, Ids, Id, IdKind, IdPrefix, Clock, SystemClock, FixedClock, ManualClock, Secret, Problem, StartupError, Health, Criticality, CheckResult, Background, LoopOptions, LoopTick, LoopGate, GateOutcome, Metrics, Command -->
# Rust: the application, modules and config

`fw-core` is the Rust counterpart of `framework-core`: the lifecycle, modules, configuration,
`Context` and ids, health, background loops and metrics. The contract is the same
([core](../../contracts/core.md), [background and metrics](../../contracts/background-and-metrics.md));
the API is shaped for Rust ([design](../../rust/design.md) section 1). The examples below are taken
from the crate's tests.

## No container: declare, configure, build

There is no service container and no `get(Class)`. A **module** only declares: settings, instance
sections (drivers and their keys) and migration sets. It returns **typed handles** for what it
declared. The application then builds its services in its own `build` function, passing each its
dependencies, so a missing dependency is a compile error.

```rust
let mut app = AppBuilder::new(AppName::of("cfg-test"))
    .config_dir(dir.path())
    .environment(Environment::from_pairs(env.iter().map(|(k, v)| (*k, *v))));
let h = app.module(Svc);                       // the module's handles
let c = app.configure().await.unwrap();         // every config problem at once
let cfg = c.config();
assert_eq!(cfg.get(h.port), 3);                 // a setting with a default: cannot fail
assert_eq!(cfg.find(h.note).as_deref(), Some("n")); // an optional setting
```

The phases are separate types: `AppBuilder` (declaring) → `Configured` → `Built` → `Running`.
`Configured::build` runs the build function with a `Boot`; `Built::start` runs the started actions
in order, then starts the background loops; `Running::shutdown` stops the loops first, then runs the
shutdown actions in reverse registration order, each bounded by `app.shutdown.drain`. If the build
function fails, the shutdown actions it registered so far run before the error is returned.

```rust
.build(async |boot: &mut Boot| {
    register(boot, &l, "a");                    // boot.on_started(..) and boot.on_shutdown(..)
    register(boot, &l, "b");
    boot.background().spawn_loop("ticker", LoopOptions::every(Duration::from_secs(60)), move |tick| { .. })?;
    boot.health().add("ok", Criticality::Critical, || async { CheckResult::Up })?;
    Ok(())
})
// start, then shutdown, logs:
// ["started a", "started b", "run 1", "loop saw stop", "shutdown b", "shutdown a"]
```

`launch(app, std::env::args(), build)` does all of it from `main`: configure, build, then the
**command** named by the first argument (a `Command` registered with `boot.command(..)`, run after
build without started actions or loops), or start and run until SIGTERM or SIGINT. A start-up
problem is printed and the exit code is 1.

`Boot` gives the build function the configuration, the clock (`boot.clock()`), the id generator
(`boot.ids()`), `boot.metrics()`, `boot.health()`, `boot.background()`, the migration sets every
module contributed, and `on_started`, `on_shutdown` and `command`.

## Settings

A setting is a `const`, so its key is checked when the crate compiles (`Key::of` panics during
constant evaluation on a bad key, which is a compile error):

```rust
const PORT: Setting<i32, Present> = Setting::new(Key::of("svc.port"), "port").default(8080);
const NAME: Setting<String, Present> = Setting::new(Key::of("svc.name"), "name").required();
const NOTE: Setting<String, Optional> = Setting::new(Key::of("svc.note"), "note");
const TAGS: Setting<Vec<String>, Present> = Setting::new(Key::of("svc.tags"), "tags").default_list(&["a"]);
```

`Present` settings (a default, or required) are read with `Config::get`, which cannot fail;
`Optional` ones with `Config::find`. Types: `String`, `i32` ("an integer"), `i64`, `u16`, `u32`,
`u64`, `bool`, `Duration`, `Vec<String>`, `Secret` (which cannot have a default: its default type has
no values), and any enum implementing `ConfigEnum` (matched ignoring case, `-` read as `_`).

Sources, keys, environment-variable names, value syntax, `secrets.local.yaml` and every error
message are the Java framework's: see [Config](../config.md). The shared vectors in
`conformance/config/` check the environment names, value parsing and whole scenarios (sources in
order, every problem at once, collisions) against the Java code.

## Named instances and drivers

A capability declares its section, its drivers and each driver's keys; `configure` then checks every
configured instance, so an unknown driver, a malformed value, a missing required key and an unknown
key are all in the one start-up report, before anything is built:

```rust
d.instances(
    InstanceSection::new("stores", "Store")
        .driver(DriverSpec::new("memory", []))
        .driver(DriverSpec::new("remote", [
            KeySpec::new("url", KeyType::String).required(),
            KeySpec::new("pool.max", KeyType::Integer),
            KeySpec::new("password", KeyType::Secret),
        ]))
        .not_compiled("redis", "`redis` of fw-store"),
)
```

`Config::instances(handle)` returns the checked `ConfiguredInstances` (the default's name, each
`InstanceConfig` with typed getters). Naming a driver whose Cargo feature was not compiled in says
which feature to enable. The default rules are the Java ones (`default`, or the only instance).

## Context and ids

`Context` carries the client (always), principal, application, correlation id (generated when
absent), causation id and execution id; it is passed explicitly, never stored in a task-local.
`ClientId` (`[A-Za-z0-9._-]{1,100}`), `ApplicationCode`, `PrincipalId`, `CorrelationId`,
`CausationId` and `ExecutionId` are validated newtypes (with serde and, with the `sqlx` feature,
sqlx impls).

`Ids` generates TSIDs with the Java layout (shared vectors in `conformance/tsid/`). Typed ids are one
generic type:

```rust
struct Order;
impl IdKind for Order { const PREFIX: IdPrefix = IdPrefix::of("ord"); }
type OrderId = Id<Order>;                       // "ord_" + 13 Crockford characters
let id: Id<Order> = ids.next();
```

`Id<Order>` and `Id<Line>` are different types. `Ids::seeded(clock, seed)` (or
`AppBuilder::seeded_ids`) with a `FixedClock` gives the same ids every run.

## Time and the clock

Wall time is a `jiff::Timestamp` from the injected `Clock` (Java's `InstantSource`, Go's
`fw.Clock`): event times, audit times and the outbox rows' `created_at` all read it, so a test that
gives the builder a `FixedClock` or a `ManualClock` fixes them. `SystemClock` is the only reader of
the system clock. `Clock::millis` rounds down before 1970, as Java and Go do. A `ManualClock` moves by
a `jiff::SignedDuration`, forwards or back (from `fw-core`'s tests):

```rust
let clock = ManualClock::starting_at(at(1_000, 0));
clock.advance(SignedDuration::from_millis(1_500));
assert_eq!(clock.now(), at(1_001, 500_000_000));
clock.advance(SignedDuration::from_secs(-2));
assert_eq!(clock.now(), at(999, 500_000_000));
```

Elapsed time (leases, timeouts, loop intervals) stays `std::time::Duration` and
`tokio::time::Instant`, which tests pause and advance.

The workspace's `clippy.toml` refuses `jiff::Timestamp::now`, `jiff::ZonedDateTime::now`,
`std::time::SystemTime::now` and the host's time zone (`TimeZone::system`, `try_system`): read the
clock, and name the zone. A server's own zone says nothing about a client or a site.

**Text.** Where an instant's bytes are a contract (outbox payloads, event data, JSON on the wire),
write it as Java does: `instant_text(t)` is Java's `Instant.toString()` (`2026-10-08T10:15:31.500Z`:
seconds always, a fraction of 3, 6 or 9 digits when there is one). A `Timestamp` field of a
serialised struct takes `instant_serde` (from the same tests):

```rust
#[derive(Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Data {
    #[serde(with = "instant_serde")]
    placed_at: Timestamp,
    #[serde(with = "instant_serde::option", default)]
    paid_at: Option<Timestamp>,
}
// {"placedAt":"2026-10-08T10:15:31.500Z","paidAt":null}
```

jiff's own `Display` and serde forms trim the fraction (`10:15:31.5Z`), so they are not used where the
bytes count. `parse_instant` (and `instant_serde` reading) accepts RFC 3339 with any offset, `Z` or
`z`, `T`, `t` or a space, and zero to nine fraction digits.

**Time zones.** jiff is built with its bundled IANA database and without `tz-system`
(`rust/Cargo.toml`): the framework runs in small images with no `/usr/share/zoneinfo`, and every
environment then has the same rules, pinned by `Cargo.lock`. A zone the database does not have is an
error that names it, never UTC:

```rust
let joburg = TimeZone::get("Africa/Johannesburg").unwrap();
let local = at(1_760_000_000, 0).to_zoned_datetime(joburg);
assert_eq!(
    local.to_string(),
    "2025-10-09T10:53:20+02:00[Africa/Johannesburg]"
);
let missing = TimeZone::get("Nowhere/Land").unwrap_err().to_string();
```

Moving from the previous API (`chrono`): [from chrono to jiff](from-chrono-to-jiff.md).

## Health, background loops, metrics

- `Health::add(name, Criticality, check)`; `app.health.critical` and `app.health.informational`
  override by name; `check()` runs every check under `app.health.timeout`.
- `Background::spawn_loop(name, LoopOptions::every(..), task)`: fixed delay, optional `LoopGate`
  (the lock gate is in `fw-locks`), health check `background:<name>` (informational by default), an
  `Err` is logged and the loop continues, a panic ends the loop.
- `Metrics::counter`, `gauge`, `gauge_fn`, `timer` with the contract's naming, label and cardinality
  rules; `prometheus_text(&metrics.snapshot())` writes the Java writer's exact bytes. The registry is
  explicit, never global.

## Where Rust differs from Java

- No `AppBuilder.override`: a test builds the application with fakes passed to its own build
  function.
- Configuration problems are collected in one report, as in Java. Problems found while building (a
  database that does not answer) stop at the failing step.
- A loop's health reason does not name the error type (`the last 3 runs failed`).
- An instant (`jiff::Timestamp`) holds years -9999 to 9999 and nanoseconds, Java's `Instant` years
  ±1 000 000 000; both write the same text in that range (`instant_text`). Go's `time.Time` carries a
  location, which the framework ignores for an instant.
- `Secret` is zeroised on drop; it has no `Display` and no `Serialize`, so it cannot be formatted or
  serialised by mistake.
- YAML is read with `serde_norway`: a mapping with a duplicate key is an error, where the Java
  reader keeps the last value.
