<!-- types: Config, Setting, SettingType, Secret, InstanceConfig, ConfigProblem, ConfigException, StartupException, AppBuilder, KeyRules, RemovedKey, ComponentClaim -->
# Config

Every setting is declared once, in code, by the module that reads it. Values come from files and
the environment; there is no object binding and no reflection.

## What configuration holds

Configuration holds **connections and secrets only**: URLs, hosts, ports, user names, client ids,
your application's code on the platform, its public URLs, passwords, tokens and keys. Everything
else the framework does (timeouts, sizes, pools, retries, cache policies, health criticality,
switches, route paths, which driver backs a capability) is **code**: typed options records with
validated ranges, given to modules when you assemble the application (owner ruling 2026-10-14,
[config-and-code contract](../contracts/config-and-code.md)). A deployment varies connections and
secrets, nothing else. If you want a number to differ per deployment, declare a setting of your own
and pass its value in code ([below](#making-a-number-tunable)).

A key whose behaviour moved to code **fails start-up** when it is still in a file or in the
environment, naming where it went ([removed keys](#removed-keys-and-claimed-components)). The
migration, key by key, is in [Migrating configuration to code](migrating-config-to-code.md).

## Declaring and reading a setting

Declare in `register`, keep the `Setting` in a constant, read it after start-up through `Config`:

```java
static final Setting<String> HOST =
    Setting.string("mail.host").asRequired().describedAs("SMTP host.");
static final Setting<Duration> TIMEOUT =
    Setting.duration("mail.timeout").withDefault(Duration.ofSeconds(10));
static final Setting<Secret> PASSWORD = Setting.secret("mail.password");

@Override
public void register(AppBuilder app) {
  app.declare(HOST);
  app.declare(TIMEOUT);
  app.declare(PASSWORD);
  app.provide(
      Mailer.class,
      lookup -> {
        Config config = lookup.get(Config.class);
        return new Mailer(config.get(HOST), config.find(PASSWORD).orElse(Secret.of("")));
      });
}
```

Settings are values, not annotations: `Setting.string`, `integer`, `longValue`, `bool`, `duration`,
`uri`, `stringList`, `enumOf(key, values)` and `secret`. Each starts optional with no default;
`asRequired()`, `withDefault(value)` and `describedAs(text)` return a modified copy.

- The key is dotted lowercase, `[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*`. A key declared twice fails
  start-up.
- **Required** means start-up fails when no source gives a value. A default counts as a value.
- `config.get(setting)` returns the value; it throws `IllegalStateException` for an optional
  setting nobody set, so use `config.find(setting)` (an `Optional`) for those. Passing a setting
  that was not declared (or declared differently) is `IllegalArgumentException`.
- Values do not change while the application runs.
- Settings are **not readable in `register`**; read them in a provider, `boot` or later.
- The framework's own settings are declared the same way. See `JettyHttpModule.PORT` for a
  public-constant example.

### Value formats

| Type | Accepts |
|---|---|
| string, integer, long | as written |
| boolean | `true` or `false` only |
| duration | `500ms`, `30s`, `5m`, `2h`, `1d`, or ISO-8601 such as `PT30S` |
| URI | any URI |
| string list | a YAML list, or comma-separated text |
| enum | the constant names |
| secret | text, kept in a `Secret` |

A value that does not parse fails start-up with the key, the source it came from and the expected
type. The raw text of a secret is never included in an error.

## Sources, lowest to highest

1. The declaration's default.
2. `config/application.yaml`, then `config/application-{env}.yaml`. Missing files are fine.
3. `config/secrets.local.yaml`, **only when `env` is `local` or `test`**. In any other
   environment this file is ignored.
4. Environment variables.

`env` is the environment variable `APP_ENV`, default `local`. The config directory is `config/`
under the working directory unless you call `builder.configDirectory(path)`. A later source wins
over an earlier one, key by key.

There are no external stores (AWS, the platform) yet; see [STATUS](STATUS.md).

### Environment variable names

The key uppercased, with `.` and `-` replaced by `_`: `mail.host` is `MAIL_HOST`,
`http.port` is `HTTP_PORT`, `flowcatalyst.client-secret` is
`FLOWCATALYST_CLIENT_SECRET`. `Setting.envName()` returns it. The environment is **never
scanned**: only names derived from declared keys and from instance sections are looked up.
Tests pass their own map with `builder.environment(Map)`, so they never touch the real one.

**Two keys, one variable.** A key may contain `_`, and both `.` and `-` become `_`, so `a.b-c`,
`a.b_c` and `a.b.c` all read `A_B_C`. Setting that variable would configure all of them, so
start-up **fails** when it finds two such keys: two declared keys (the message names both keys and
both modules), or a key under an `instances` section in your files
(`database.instances.main.url`) and a declared key or another instance key. Rename one. The check
looks only at keys the framework knows about (declared keys and the instance keys present in the
files); a typo in some other YAML key is not its business.

## Names, components and the platform

Keys are one global namespace shared by every module: pick a key that cannot collide, usually
starting with your component (`mail.host`). The first segment is the **component** and the rest is
the **path**: in `mail.smtp.host` the component is `mail` and the path is `smtp.host`. Locally the
name is the key itself (environment variable `MAIL_SMTP_HOST`). When settings are held by the
FlowCatalyst platform (planned, M5), the same setting is addressed as
`{application}:{component}` plus the path, scoped to your application
(`amazing-app:mail` / `smtp.host`); you never write the application code in a key. A module id
does not have to match its keys. The framework's own settings (`app`, `http`, `database`, `redis`,
`locks`, `flowcatalyst`) stay in environment variables and files. See
[the core contract](../contracts/core.md) for the full rules.

## Named instances

Connections are configured as named instances under a section; the key path decides the
environment variable. An instance section holds the connection and its secrets only; how the
framework uses it (pool sizes, timeouts, migrations) is code.

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app
      username: app
      password: ""          # a secret: give it in secrets.local.yaml or DATABASE_INSTANCES_MAIN_PASSWORD

redis:
  default: default
  instances:
    default: { url: "redis://localhost:6379/0" }
```

(`examples/locks-demo/config/application.yaml`.) The instance must be named in a YAML file;
`DATABASE_INSTANCES_MAIN_URL` or `DATABASE_INSTANCES_MAIN_PASSWORD` then override single values. An instance cannot be
created from environment variables alone.

Keys each framework instance reads (a key left unread fails start-up as unknown; a key that moved
to code fails naming its replacement):

| Section | Keys |
|---|---|
| `database.instances.<n>` | `url` (required), `username`, `password` (secret). The pool and migrations are code: `DatabaseModule.builder().pool(DatabasePool)`, `.pool(name, DatabasePool)`, `.migrate(boolean)` ([lifecycle](modules-and-lifecycle.md#the-database-module-and-migrations)) |
| `redis.instances.<n>` | `url` (required, `redis://` or `rediss://`, may carry user, password and database number), `password` (secret, wins over the URL's). The timeout and pool are code: `RedisModule.builder().pool(RedisPool)`, `.pool(name, RedisPool)` |
| `http-clients.instances.<n>` | `base-url` and the credentials of the client's `ClientAuth`; the client is named in code ([HTTP client](http-client.md#configured-clients)) |

`<section>.default` names the default instance; with exactly one instance it may be omitted.

### Settings the framework declares

| Key | Type | Default | Module |
|---|---|---|---|
| `http.host`, `http.port` | string, integer | `0.0.0.0`, `8080` | `http` ([HTTP](http.md#the-jetty-module-and-its-settings); the rest is `HttpOptions` in code) |
| `http.metrics.bearer-token` | secret | none | `http` ([the Prometheus endpoint](background-and-metrics.md#the-prometheus-endpoint)) |
| `flowcatalyst.*` | see [The FlowCatalyst module](flowcatalyst-module.md#settings) | | `flowcatalyst` |

The core declares no setting. The shutdown drain, the health policy and the metrics driver are
`App.builder().shutdownDrain(Duration)`, `.health(HealthPolicy)` and `.metrics(MetricsOptions)`
([lifecycle](modules-and-lifecycle.md#critical-and-informational-checks),
[metrics](background-and-metrics.md#drivers)).

## Making a number tunable

Every option the framework takes as a single value also accepts a function of `Config`, resolved
once configuration is loaded. Declare your own setting, in your own component, and read it there.
From `BehaviourInCodeTest`:

```java
static final Setting<Duration> APP_HEALTH = Setting.duration("demo.health-timeout").withDefault(Duration.ofSeconds(1));

App app =
    builder(Map.of("DEMO_HEALTH_TIMEOUT", "250ms"))
        .health(config -> HealthPolicy.defaults().withTimeout(config.get(APP_HEALTH)))
        .shutdownDrain(config -> config.get(APP_HEALTH).multipliedBy(4))
        .metrics(config -> MetricsOptions.noop())
        .module(Mod.of("demo").register(b -> b.declare(APP_HEALTH)))
        .start();
```

A value out of range, or a setting nobody declared, fails start-up as a problem of the module
that resolved it.

## Removed keys and claimed components

**Removed keys.** The framework keeps one list of the keys whose behaviour moved to code
(`KeyRules.frameworkRemovedKeys()`; the table is in the
[contract](../contracts/config-and-code.md), section 8). Start-up fails when one of them is

- in a file: the key or anything under it (`locks` covers every `locks.*` key; a `*` in a pattern
  stands for an instance name), or
- in the environment: the variable named after the key; a `*` is replaced by each instance name
  your files have there. Patterns of one segment (`locks`) are not looked up in the environment.

The problem says where the behaviour went, for example
`key 'app.shutdown.drain' (from environment variable APP_SHUTDOWN_DRAIN): moved to code: AppBuilder.shutdownDrain(Duration)`.
The list applies whatever modules you list, so a leftover section of a module you dropped still
fails. A key that some module **declares** is never treated as removed.

**Claimed components.** A module may claim a component (the first segment of keys) in `register`
with `app.claimComponent("mail")`. Every key under it in your files must then be declared by some
module, or start-up fails naming the keys the component has; a typo such as `mail.hots` no longer
passes silently. The framework claims `app` (core) and, as each module moves to this rule, its own
component. Two modules claiming one component fail start-up. Environment variables are not checked
for unknown keys (they are never listed). From `BehaviourInCodeTest`:

```java
failure(builder().module(Mod.of("mail").register(b -> {
  b.declare(host);
  b.claimComponent("mail");
})));
```

## Secrets

`Secret` is its own type: `toString()` prints `Secret[****]`, it is not serialisable, and the raw
value is read with `reveal()`. `matches(candidate)` compares a presented value with it in constant
time. Credentials that belong to a client rather than the application are
[per-client secrets](secrets.md). A secret declaration never has a default (declaring one throws
`IllegalArgumentException`).

- **Local and tests:** put it in `config/secrets.local.yaml` (add the file to your `.gitignore`;
  the framework's own `.gitignore` lists it) or set the environment variable.
- **Anywhere else:** the environment variable. `secrets.local.yaml` is ignored when `APP_ENV` is
  not `local` or `test`.
- Do not commit a real secret in `application.yaml`.

**There is no secret store.** Only the environment and that local file exist. A secret manager (AWS,
the platform) and a resolver for per-client credentials are planned (see [STATUS](STATUS.md),
plan item MA-5 and M5).

## Errors

Config problems are collected and thrown together from `start()` as one `StartupException`: an
unparseable value, a missing required setting, an unknown key in an instance section, an unknown
driver, a duplicate declaration, two keys that read one environment variable, an invalid YAML file,
a removed key, an unknown key under a claimed component. Each names the key and the source, for
example the file or `environment variable MAIL_HOST`.

The specification is [contracts/core.md](../contracts/core.md) section 4,
[contracts/config-and-code.md](../contracts/config-and-code.md) and
[contracts/database-and-redis.md](../contracts/database-and-redis.md).
