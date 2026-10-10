<!-- types: Module, AppBuilder, App, Provider, Lookup, Drivers, DriverFactory, Instances, InstanceConfig, Command, Launch, Health, HealthCheck, HealthReport, HealthResult, Criticality, StartupException, Ids, MigrationSet, Databases, Database, Migrations -->
# Modules and the application lifecycle

An application is a list of **modules** given to a builder, in code. Everything the framework
provides (database, locks, HTTP, the platform client) is a module, and so is your own code.

## The `Module` interface

```java
public interface Module {
  String id();
  default void register(AppBuilder app) throws Exception {}
  default void boot(App app) throws Exception {}
  default void started(App app) throws Exception {}
  default void shutdown(App app) throws Exception {}
}
```

The id matches `[a-z][a-z0-9-]*` and must be unique in the application. `core` is taken by the
framework's own module, which is added for you.

| Hook | Use it to | Do not |
|---|---|---|
| `register(AppBuilder)` | declare settings, provide services, register drivers, contribute migrations and definitions, claim commands | build services, read config values, start threads |
| `boot(App)` | read config, look up services, add health checks, register routes, register background loops | provide new services |
| `started(App)` | work that needs *every* module booted (the Jetty module compiles the route table and starts listening here) | provide new services |
| `shutdown(App)` | stop what you started | |

`App.builder().module(a).module(b)...start()` does this, in order:

1. Calls every module's `register`, in the order listed.
2. Loads and validates configuration. Any problem fails here.
3. Builds every provided service, eagerly. A provider asks for what it needs and that is built
   first; the build order is recorded.
4. Calls every module's `boot`, in the order listed.
5. Calls every module's `started`, in the order listed, after **every** `boot` finished. If any
   `boot` failed, no `started` runs.
6. Returns the running `App`.

Failures are thrown from `start()` as `StartupException`, collected where possible so one start
reports every problem, each naming its module and key: a duplicate module id, two providers for
one type, a lookup of a type nobody provides, a dependency cycle, any config error, an unknown
driver, an exception from a provider or a hook. Services already built are closed before it is
thrown. `StartupException.problems()` lists them.

### Shutdown

`App.close()` is idempotent (it is `AutoCloseable`, so use try-with-resources in tests).
`builder.shutdownHook(true)` also closes the app from a JVM shutdown hook; the default is off so
tests leave no hooks behind. Closing first stops the [background loops](background-and-metrics.md),
then runs each booted module's `shutdown` in reverse module order,
then closes every built service that is `AutoCloseable`, in reverse build order. A failure in one
step is logged and does not stop the rest. Each step runs on its own virtual thread and gets
`app.shutdown.drain` (default `10s`). A step that is still running after that is logged as an
overrun, **interrupted, and left behind**: shutdown moves on to the next step without waiting for
it, so a `shutdown` hook that ignores interruption can still be running while later steps (and
the closing of services it uses) proceed. Write hooks that stop promptly and notice
interruption.

## Services

A module provides a service by type during `register`:

```java
app.provide(
    OrderUseCases.class,
    lookup ->
        new OrderUseCases(
            new OrderRepository(),
            lookup.get(Databases.class).defaultInstance(),
            lookup.get(Events.class),
            lookup.get(Ids.class),
            OrderUseCases.Policies.defaults()));
```

(from `examples/orders-demo`, `OrdersModule`). The lambda is a `Provider<T>`:
`T create(Lookup lookup) throws Exception`. `Lookup` offers `get(Class)`, `contributions(Class)`
(see below) and `moduleId()`.

- **One provider per type.** A second `provide` for a type fails start-up, naming both modules.
  In tests only (`APP_ENV=test`), `AppBuilder.override(type, instance)` replaces what a module
  provides: see [Testing](testing.md#replacing-a-service).
- Providers run once, at start-up, in dependency order. A provider that asks for a type nobody
  provides fails start-up and names the asking module.
- These types are provided by the framework and cannot be provided by a module: `Config`,
  `Drivers`, `InstantSource`, `Ids`, `MonotonicClock`, `Health`. The core module also provides
  `Metrics` and `Background` ([Background and metrics](background-and-metrics.md)). `Ids` generates TSIDs (`ids.generate("ord")`
  gives `ord_` plus 13 characters); `InstantSource` is the clock to use instead of
  `Instant.now()`.
- A service that is `AutoCloseable` is closed at shutdown.

### Looking services up

After start-up, `App` is a typed map. No reflection.

```java
Locks locks = app.get(Locks.class);                       // throws IllegalArgumentException if absent
Optional<ContextResolver> r = app.find(ContextResolver.class);  // for optional collaborators
```

Other things on `App`: `config()`, `health()`, `moduleIds()`, `buildOrder()`, `shutdownDrain()`.

### Contributions

A module can hand a value to another module that collects them. This is how migrations work:

```java
MigrationSet.contribute(app, OrdersModule.class, "db/migration/orders");
```

stores a `MigrationSet`, a record of three things: the contributing module's id (taken from the
module whose `register` is running, so it must be called there), a class loader and the classpath
directory. The class you pass (`OrdersModule.class`) is used **only for its class loader**: it
says which jar to look in for `db/migration/orders`, so pass any class from the jar that holds
the SQL files (your module class is the natural choice). The database module collects every set with
`lookup.contributions(MigrationSet.class)` and applies them. The raw form is
`app.contribute(Class<T>, T)`. `Definitions.in(app)` in the
[flowcatalyst module](flowcatalyst-module.md) uses the same mechanism.

## The database module and migrations

`DatabaseModule` provides `Databases` (the registry) and a `Database` per configured instance, each
a HikariCP pool over the PostgreSQL JDBC driver, with a `database:<name>` health check.
Configuration is in [Config](config.md#named-instances). `Database` has `dataSource()` and `name()`.

Migrations are plain SQL files, forward only, named `V<version>__<description>.sql` with a
positive integer version (for example `V0001__create_orders.sql`). Each module contributes at most
one set (its module id plus a classpath location, found through the class loader of the class the
module passes to `MigrationSet.contribute`); your application's module contributes its own the
same way. Sets are applied to the **default** database instance, in module registration order,
and by version within a set, only when that instance's `migrate` is true (the default). Each file
runs in its own transaction together with its row in `fw_schema_history`, under a PostgreSQL
advisory lock so instances starting together apply each file once. A changed checksum of an
applied file, a missing applied file, or a skipped lower version fails start-up.

List `OutboxModule` (or any module whose tables you need) *before* your own module if your
migrations depend on its tables; registration order is migration order.

## Drivers and named instances

A capability that has more than one implementation (locks now; cache and storage later) works the
same way: modules register **drivers** by name, and configuration declares **named instances**
that choose a driver. From `LocksDatabaseModule`:

```java
app.drivers()
    .register(
        LockStore.class,
        DRIVER,
        (name, config, lookup, built) -> {
          Databases databases = lookup.get(Databases.class);
          Database database =
              config
                  .findString("database")
                  .map(databases::get)
                  .orElseGet(databases::defaultInstance);
          return new LockStore(
              new DatabaseLockDriver(database.dataSource()), lookup.get(MonotonicClock.class));
        });
```

A `DriverFactory<T>` receives the instance name, its `InstanceConfig` section, a `Lookup`, and the
instances of the same capability already built (for wrapper drivers). It reads its keys through the
typed getters (`string`, `integer`, `bool`, `duration`, `secret`, `findString`, `section`...). After
it returns, any key in the section that it did not read, other than `driver`, fails start-up as
unknown, so a typo cannot pass silently.

The capability's registry is a provided service built from `Instances`:

```java
app.provide(Locks.class, lookup -> new Locks(Instances.build(LockStore.class, "locks", lookup)));
```

`Instances.build(capability, section, lookup)` reads `<section>.default` and
`<section>.instances.<name>.driver`, builds each instance, and reports every problem at once. A
second form takes a default driver name, for a capability with one obvious driver (databases
default to `postgresql`). The result has `get(name)`, `defaultInstance()`, `names()`, `all()`. If
there is exactly one instance, `default` may be omitted; with several, it must name one. A
capability with no configured instances is valid and its registry is empty.

The registry classes the framework ships (`Locks`, `Databases`, `RedisConnections`) have package-
private constructors; to add a capability of your own, write a registry class around `Instances`.
**Limitation:** a driver name is unique per capability, so you cannot replace a framework driver
with a test double of the same name; see [Testing](testing.md).

## Health checks

Add a named check in `boot`:

```java
app.health().add("mail", () -> {
  if (app.get(Mailer.class).host.isEmpty()) {
    throw new IllegalStateException("no host");
  }
});
```

`HealthCheck.check()` returns normally for up and throws for down; the message becomes the reason.
`app.health().check()` runs all checks in parallel on virtual threads, each limited by
`app.health.timeout` (default `2s`), and returns a `HealthReport`: a map of name to `HealthResult`
(`Up`, `Down(reason)`, `TimedOut`). With the Jetty module, `/health/ready` serves this
([HTTP](http.md#health-endpoints)).

### Critical and informational checks

A check is `Criticality.CRITICAL` (the default) or `Criticality.INFORMATIONAL`. A critical check
that is not up makes the application **not ready**; an informational one is run and reported the
same way but never does. Use informational for a dependency you can serve requests without:

```java
app.health().add("flowcatalyst", client::verifyCredentials, Criticality.INFORMATIONAL);
```

(from `FlowCatalystModule`, whose `flowcatalyst` and `outbox` checks are informational.) On the
report, `isUp()` is true when every check is up, `isReady()` when every critical one is, and
`criticality(name)` says which a check ran with. `app.health().criticality(name)` returns the same
for a registered check (null for an unknown name).

Configuration changes a check's criticality by name and wins over the module:

```yaml
app:
  health:
    critical: [flowcatalyst]          # the platform decides readiness again
    informational: ["database:reporting"]
```

A name in both lists fails start-up. A name that matches no check is logged at WARN once start-up
finishes, not refused, because some checks exist only in some deployments (the `outbox` check
needs the table).

## Commands and `launch`

A module may claim a command name in `register`. This is how `java -jar app.jar sync` works.

```java
app.command(
    new Command() {
      @Override
      public String name() { return "mail-test"; }

      @Override
      public int run(App app, List<String> args, PrintStream out) {
        out.println("host " + app.get(Mailer.class).host);
        return 0;
      }
    });
```

`AppBuilder.launch(String... args)` looks at the first argument. If a module claimed it, the
command runs after start-up step 3 (config loaded, services built, **no `boot`**), the application
is closed, and `launch` returns `Launch.Ran(exitCode)`. Anything else starts the application
normally and returns `Launch.Started(app)`; an unknown argument is not an error. Command names
match `[a-z][a-z0-9-]*`, and two modules claiming one name fail start-up.

```java
switch (App.builder().module(/* ... */).launch(args)) {
  case Launch.Ran ran -> System.exit(ran.exitCode());
  case Launch.Started started -> { }   // running; the server threads keep the JVM alive
}
```

Because a command skips `boot`, it has no routes registered, no health checks, and (with the Jetty
module) no server. `start()` is unchanged for callers that do not use commands.

## Builder options

| Method | Meaning |
|---|---|
| `module(Module)` | add a module; order matters (register, boot, started, migrations) |
| `configDirectory(Path)` | where `application.yaml` lives; default `config` under the working directory |
| `environment(Map<String,String>)` | the environment to read instead of `System.getenv()`; tests use this |
| `instantSource(InstantSource)` | the wall clock; tests pass a fixed one |
| `monotonicClock(MonotonicClock)` | the monotonic clock used by locks and timeouts |
| `shutdownHook(boolean)` | close from a JVM shutdown hook; default off |
| `override(Class<T>, T)` | replace a provided service; **only when `APP_ENV` is `test`**, otherwise start-up fails ([Testing](testing.md#replacing-a-service)) |

A builder starts once; reuse throws `IllegalStateException`.

## A complete small module

```java
static final class MailModule implements Module {
  static final Setting<String> HOST =
      Setting.string("mail.host").asRequired().describedAs("SMTP host.");
  static final Setting<Secret> PASSWORD = Setting.secret("mail.password");

  @Override
  public String id() {
    return "mail";
  }

  @Override
  public void register(AppBuilder app) {
    app.declare(HOST);
    app.declare(PASSWORD);
    app.provide(
        Mailer.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          return new Mailer(config.get(HOST), config.find(PASSWORD).orElse(Secret.of("")));
        });
  }

  @Override
  public void boot(App app) {
    app.health().add("mail", () -> {
      if (app.get(Mailer.class).host.isEmpty()) {
        throw new IllegalStateException("no host");
      }
    });
  }
}
```

Settings are covered in [Config](config.md); the specification is
[contracts/core.md](../contracts/core.md).
