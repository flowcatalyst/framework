<!-- types: Config, Setting, SettingType, Secret, InstanceConfig, ConfigProblem, ConfigException, StartupException, AppBuilder -->
# Config

Every setting is declared once, in code, by the module that reads it. Values come from files and
the environment; there is no object binding and no reflection.

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
`http.max-body-bytes` is `HTTP_MAX_BODY_BYTES`, `flowcatalyst.client-secret` is
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

Capabilities with drivers (databases, Redis, locks) are configured as named instances under a
section; the key path decides the environment variable.

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app
      username: app
      password: ""          # a secret: give it in secrets.local.yaml or DATABASE_INSTANCES_MAIN_PASSWORD
      pool: { max: 10, min-idle: 1, connect-timeout: 5s }
      migrate: true

redis:
  default: default
  instances:
    default: { url: "redis://localhost:6379/0", timeout: 2s }

locks:
  default: main
  instances:
    main: { driver: database }
    shared: { driver: redis, connection: default, prefix: "fw:lock:" }
    local: { driver: memory }
```

(`examples/locks-demo/config/application.yaml`.) The instance must be named in a YAML file;
`DATABASE_INSTANCES_MAIN_URL`, `DATABASE_INSTANCES_MAIN_PASSWORD` or
`DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT` then override single values. An instance cannot be
created from environment variables alone.

Keys each framework instance reads (a key left unread, other than `driver`, fails start-up as
unknown):

| Section | Keys |
|---|---|
| `database.instances.<n>` | `url` (required), `username`, `password` (secret), `pool.max` (10), `pool.min-idle` (1), `pool.connect-timeout` (5s), `migrate` (true). No `driver` needed. |
| `redis.instances.<n>` | `url` (required, `redis://` or `rediss://`, may carry user, password and database number), `timeout` (2s), `pool.max` (16), `password` (secret, wins over the URL's). No `driver` needed. |
| `locks.instances.<n>` | `driver` (required: `memory`, `database` or `redis`); `database` (instance name, for `database`); `connection` (Redis instance name) and `prefix` (default `fw:lock:`), for `redis` |
| `secrets.instances.<n>` | `driver` (required: `file`, `env`; `platform` fails start-up); `directory` and `max-bytes` (65536) for `file`; `prefix` for `env`; `cache-ttl` (5m), `cache-max` (10000) for both. See [Per-client secrets](secrets.md) |
| `rate-limits.instances.<n>` | `driver` (`memory`, or `redis` with `RateLimitRedisModule`); `limit` and `period` (required), `burst` (= limit); `max-keys` (100000) for `memory`; `connection`, `prefix` (`fw:rl:`) for `redis`. See [Rate limiting](rate-limiting.md) |
| `http-clients.instances.<n>` | see [HTTP client](http-client.md#configured-clients) |

`<section>.default` names the default instance; with exactly one instance it may be omitted.

### Settings the framework declares

| Key | Type | Default | Module |
|---|---|---|---|
| `app.shutdown.drain` | duration | `10s` | core |
| `app.health.timeout` | duration | `2s` | core |
| `app.health.critical`, `app.health.informational` | string list | empty | core; check names whose criticality configuration decides ([health checks](modules-and-lifecycle.md#critical-and-informational-checks)) |
| `app.metrics.driver` | enum `memory`, `noop` | `memory` | core ([metrics](background-and-metrics.md#drivers)) |
| `app.metrics.max-series` | integer | `1000` | core |
| `http.host`, `http.port`, `http.max-body-bytes`, `http.request-timeout`, `http.shutdown-grace` | see [HTTP](http.md#the-jetty-module-and-its-settings) | | `http` |
| `http.metrics.enabled`, `http.metrics.path`, `http.metrics.bearer-token` | see [the Prometheus endpoint](background-and-metrics.md#the-prometheus-endpoint) | `false`, `/metrics`, none | `http` |
| `flowcatalyst.*` | see [The FlowCatalyst module](flowcatalyst-module.md#settings) | | `flowcatalyst` |

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
driver, a duplicate declaration, two keys that read one environment variable, an invalid YAML file. Each names the key and the source, for
example the file or `environment variable MAIL_HOST`.

The specification is [contracts/core.md](../contracts/core.md) section 4 and
[contracts/database-and-redis.md](../contracts/database-and-redis.md).
