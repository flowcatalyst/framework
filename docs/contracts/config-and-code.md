# Contract: configuration holds connections and secrets; behaviour is code

Status: owner ruling 2026-10-14 ("move all behaviour to code"), written before the code. It
generalises MA item 10 (rate limiters built in code, [rate-limiting contract](rate-limiting.md)
section 1) to every module. It amends the [core contract](core.md) sections 1, 2 and 4 and the
configuration sections of the [database and Redis](database-and-redis.md), [locks](locks.md),
[HTTP](http.md), [HTTP client](http-client.md), [secrets](secrets.md),
[background and metrics](background-and-metrics.md) and [FlowCatalyst](flowcatalyst.md) contracts;
where they disagree with this text, this text wins. **Breaking** for every application: the
migration is section 7 and [the guide page](../guide/migrating-config-to-code.md).

Implementations: Java builds this. The Go (G1) and Rust (R1) implementations still read driver
names and behavioural keys from configuration; they follow this contract when they next change
their configuration code (the conformance vectors are unchanged by it, section 6).

## 1. The rule

Configuration (files, environment variables, `secrets.local.yaml`, later the platform's store)
holds **only**:

- **connection settings**: where something is and who the application is to it: URLs, hosts,
  ports, user names, client ids, the application's code on the platform, the application's own
  public URLs, which configured connection is the default;
- **secret settings**: passwords, tokens, client secrets, keys (`Secret`).

Everything else is **behaviour** and is written in code: timeouts, sizes, pool sizing, retries,
cache policies, health criticality, switches that turn a feature on or off, route paths, intervals
and retentions, and **which driver** backs a capability. Behaviour is given to the framework as
typed values when the application is assembled (section 2), reviewed and tested with the code,
the same in every deployment. A deployment varies connections and secrets, nothing else.

An application that wants a behavioural number to differ per deployment declares **its own**
setting, in its own component, and passes the value in code (section 4). It then owns the name,
the range and the default; the framework's components never carry behavioural keys.

## 2. How behaviour is given in code

- **Options records.** Each module's behaviour is a record with a `defaults()` factory and
  `withX(...)` methods that return a copy (house rule: values are records). The compact constructor
  **validates every value** and throws `IllegalArgumentException` naming the field and the allowed
  range, so a wrong value fails where it is written, at the latest when the application is
  assembled, before any connection is opened.
- **Modules take their options when constructed.** A no-argument constructor (or `builder()` with
  nothing set) means the documented defaults. Modules that hold named things in code (lock stores,
  secret stores, HTTP clients) have a builder.
- **Driver choice is code.** A capability with more than one driver is wired with a factory that
  names the driver: `LockStores.memory()`, `DatabaseLockStores.database()`,
  `RedisLockStores.redis(connection, prefix)`, `SecretStores.env(prefix)`,
  `SecretStores.file(directory)`. There is no `driver:` key anywhere.
- **No container.** The module and lifecycle mechanism is unchanged (`AppBuilder`, modules,
  `Setting` for connection and secret values, providers, `Lookup`). There is no declarative
  wiring, no scanning and no YAML-declared instance of anything behavioural.
- **Connections stay named instances in configuration** (`database.instances.<n>`,
  `redis.instances.<n>`, `http-clients.instances.<n>`), holding connection and secret keys only.
  Options for them are given in code by instance name (section 3).

### 2.1 Named instances without drivers (core contract section 2, amended)

- `Drivers`, `DriverFactory` and `AppBuilder.drivers()` are removed. A capability whose instances
  come from configuration (database, Redis) builds every instance under `<section>.instances` with
  **one** factory: `Instances.build(capability, section, lookup, InstanceFactory<T>)`, where the
  factory receives the instance name, its section, the lookup and the instances already built.
  The rules of section 2 otherwise hold: unread keys in an instance section fail start-up as
  unknown, `<section>.default` names the default instance, an instance cannot be created from
  environment variables alone.
- A capability whose instances are a code fact (lock stores, secret stores, HTTP clients) names
  them in the module's builder. Names match `[a-z][a-z0-9-]*`; a name given twice is
  `IllegalArgumentException`. With exactly one, it is the default; with several, `defaultStore`
  must name one (otherwise `build()` throws). The registries (`Locks`, `SecretResolver`,
  `ServiceClients`) keep their API.

## 3. Module by module

Defaults are those the configuration had; ranges are checked by the record's constructor.
"Kept" lists every key the module still reads.

### 3.1 core (`app.*`)

| Before (config) | After (code) |
|---|---|
| `app.shutdown.drain` (10s) | `App.builder().shutdownDrain(Duration)`; more than 0, at most 1 h |
| `app.health.timeout` (2s) | `App.builder().health(HealthPolicy)`: `HealthPolicy.defaults().withTimeout(Duration)`; more than 0, at most 5 min |
| `app.health.critical`, `app.health.informational` | `HealthPolicy.withCritical(String...)`, `withInformational(String...)`; a name in both, or a blank name, is refused by the constructor. A name that matches no check after every `started` hook is still a WARN |
| `app.metrics.driver` (memory) | `App.builder().metrics(MetricsOptions)`: `MetricsOptions.defaults()` (memory, 1000), `MetricsOptions.memory(maxSeries)`, `MetricsOptions.noop()` |
| `app.metrics.max-series` (1000) | `MetricsOptions.memory(int)`; 1 to 100 000 |

Kept: nothing. `health(...)`, `metrics(...)` and `shutdownDrain(...)` also accept a
`Function<Config, T>`, resolved after configuration is loaded (section 4).

### 3.2 database (`database.instances.<n>`)

| Before | After |
|---|---|
| `pool.max` (10) | `DatabaseModule.builder().pool(DatabasePool)` for every instance, `.pool(name, DatabasePool)` for one; `DatabasePool.defaults().withMaxSize(int)`, 1 to 1000 |
| `pool.min-idle` (1) | `DatabasePool.withMinIdle(int)`, 0 to `maxSize` |
| `pool.connect-timeout` (5s) | `DatabasePool.withConnectTimeout(Duration)`, 250 ms to 5 min (before: values under 250 ms were silently raised) |
| `migrate` (true) | `DatabaseModule.builder().migrate(boolean)`; migrations run on the default instance only, as before |
| `driver` (optional, `postgresql`) | removed; PostgreSQL is the only database |

Kept: `database.default`, `database.instances.<n>.url`, `.username`, `.password` (secret).
`.pool(name, ...)` naming an instance that is not configured fails start-up. `pool(...)` and
`migrate(...)` also accept a `Function<Config, T>` / `Predicate<Config>`.

### 3.3 Redis (`redis.instances.<n>`)

| Before | After |
|---|---|
| `timeout` (2s) | `RedisModule.builder().pool(RedisPool)` / `.pool(name, RedisPool)`; `RedisPool.defaults().withTimeout(Duration)`, more than 0, at most 5 min |
| `pool.max` (16) | `RedisPool.withMaxSize(int)`, 1 to 1000 |
| `driver` (optional, `redis`) | removed |

Kept: `redis.default`, `redis.instances.<n>.url`, `.password` (secret). An unknown instance name in
`.pool(name, ...)` fails start-up.

### 3.4 locks (`locks.*`, the whole component)

| Before | After |
|---|---|
| `locks.instances.<n>.driver: memory` | `LocksModule.builder().store(n, LockStores.memory())` |
| `driver: database` (+ `database: <instance>`) | `.store(n, DatabaseLockStores.database())` / `database(instance)` (framework-locks-database; `LocksDatabaseModule` is still listed, for the `fw_lock` migration) |
| `driver: redis` (+ `connection`, `prefix`) | `.store(n, RedisLockStores.redis())` / `redis(connection)` / `redis(connection, prefix)`; prefix default `fw:lock:`, not blank, at most 100 characters |
| `locks.default` | `.defaultStore(n)` |

Kept: nothing. `LocksRedisModule` is removed (it only registered the driver). A store factory is a
`LockStoreFactory` (`create(name, lookup)`), so an application can write its own. The database
store now checks that `fw_lock` exists on every instance it uses, the default one included, and
fails start-up saying to list `LocksDatabaseModule`.

### 3.5 HTTP (`http.*`)

| Before | After |
|---|---|
| `http.max-body-bytes` (1 MiB) | `new JettyHttpModule(HttpOptions.defaults().withMaxBodyBytes(long))`; 0 to 1 GiB |
| `http.request-timeout` (30s) | `HttpOptions.withRequestTimeout(Duration)`; more than 0, at most 1 h |
| `http.shutdown-grace` (the drain) | `HttpOptions.withShutdownGrace(Duration)`; 0 to 1 h; unset falls back to the shutdown drain |
| `http.metrics.enabled` (false), `http.metrics.path` (`/metrics`) | `HttpOptions.withMetricsEndpoint(String path)`; literal segments; absent means no endpoint |

Kept: `http.host`, `http.port`, `http.metrics.bearer-token` (secret). The module also accepts a
`Function<Config, HttpOptions>`.

### 3.6 HTTP client (`http-client.*`, `http-clients.*`)

| Before | After |
|---|---|
| `http-client.event-loops` (2), `http-client.max-connections-per-host` (50) | `HttpClientModule.builder().eventLoops(int)` (1 to 64), `.maxConnectionsPerHost(int)` (1 to 1000) |
| an instance under `http-clients.instances` | `.client(name, HttpClientOptions)`: the client exists because code names it |
| `connect-timeout` (5s), `request-timeout` (30s), `max-response-bytes` (10 MiB) | `HttpClientOptions.defaults().withConnectTimeout`, `withRequestTimeout` (more than 0, at most 1 h), `withMaxResponseBytes` (1 B to 1 GiB) |
| `retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff`, `retry.max-retry-after` | `HttpClientOptions.withRetry(RetryPolicy)` (`RetryPolicy.defaults().withMaxAttempts(...)`, ranges unchanged) |
| `auth.type` and `auth.method`, `auth.scope`, `auth.audience`, `auth.name` | `HttpClientOptions.withAuth(ClientAuth)`: `ClientAuth.none()`, `bearer()`, `basic()`, `header(name)`, `clientCredentials().withScope(..).withAudience(..).withMethod(..)` |
| `http-clients.default`, `driver` | removed |

Kept, per client `<n>` named in code (declared as settings by the module, so each can also come
from the environment alone, for example `HTTP_CLIENTS_INSTANCES_BILLING_BASE_URL`):
`http-clients.instances.<n>.base-url` (required) and the credentials its `ClientAuth` needs:
`auth.token` (secret, `bearer`), `auth.username` and `auth.password` (secret, `basic`),
`auth.value` (secret, `header`), `auth.token-url`, `auth.client-id` and `auth.client-secret`
(secret, `clientCredentials`). All of them are required for that client. `client(name, ...)` also
accepts a `Function<Config, HttpClientOptions>`. `new HttpClientModule()` is the defaults with no
named client.

### 3.7 secrets (`secrets.*`, the whole component)

| Before | After |
|---|---|
| `secrets.instances.<n>.driver: env` + `prefix` | `SecretsModule.builder().store(n, SecretStores.env(prefix))` |
| `driver: file` + `directory`, `max-bytes` (65536) | `.store(n, SecretStores.file(Path).withMaxBytes(int))`; the path must be absolute (checked at once) and a directory (checked at start-up); 1 to 1 MiB |
| `driver: platform` | `SecretStores.platform()`: still fails start-up, the platform has no endpoint |
| `cache-ttl` (5m), `cache-max` (10000) | `.withCache(CachePolicy.of(ttl, maxEntries))`; ttl 0 (no cache, also `CachePolicy.none()`) to 24 h, entries 1 to 1 000 000; `CachePolicy.defaults()` |
| `secrets.default` | `.defaultStore(n)` |

Kept: nothing. A deployment-specific directory or prefix is an application setting passed in code
(a `SecretStoreFactory` receives the lookup, section 4).

### 3.8 FlowCatalyst (`flowcatalyst.*`)

| Before | After |
|---|---|
| `flowcatalyst.enabled` (true) | `new FlowCatalystModule(FlowCatalystOptions.disabled())`, or `.withEnabled(boolean)` |
| `flowcatalyst.timeout` (30s) | `FlowCatalystOptions.defaults().withTimeout(Duration)`; more than 0, at most 1 h; readable as `PlatformClient.timeout()` |
| `flowcatalyst.sync.on-boot` (false) | `.withSyncOnBoot(boolean)` |
| `flowcatalyst.outbox.stale-after` (5m) | `.withOutboxStaleAfter(Duration)`; more than 0 |
| `flowcatalyst.receivers.enabled` (true), `.path` (`/fc`), `.require-signature` (true) | `.withReceivers(ReceiverOptions)`: `ReceiverOptions.defaults()`, `ReceiverOptions.disabled()`, `withPath(String)` (literal segments), `withRequireSignature(boolean)` |
| `.dispatch-timeout` (25s), `.job-timeout` (10m), `.job-lock-lease` (10m), `.completion-retry-delay` (1s), `.once-retention` (30d), `.once-purge-interval` (1h) | `ReceiverOptions.withDispatchTimeout`, `withJobTimeout` (both more than 0), `withJobLockLease` (1 s to 24 h), `withCompletionRetryDelay` (0 to 1 min), `withOnceRetention`, `withOncePurgeInterval` (both more than 0) |

Kept: `flowcatalyst.base-url`, `flowcatalyst.application`, `flowcatalyst.client-id`,
`flowcatalyst.client-secret` (secret), `flowcatalyst.subscription-target-base-url` (the URL the
platform reaches this deployment at; a connection setting, like `auth.public-base-url`), and the
receiver secrets `flowcatalyst.receivers.signing-secret`, `.bearer-token`,
`.dispatch.signing-secret`, `.dispatch.bearer-token`, `.scheduled-jobs.signing-secret`,
`.scheduled-jobs.bearer-token`. The module also accepts a `Function<Config, FlowCatalystOptions>`.

### 3.9 Already code, or nothing to move

- Sessions and login (`AuthModule`): already code-first (M4c); config holds `auth.issuer`,
  `auth.client-id`, `auth.client-secret`, `auth.public-base-url`, `auth.cookie-keys`. The module now
  claims its component (section 5).
- Rate limiting: built in code since MA item 10. The old `rate-limits` section, which was silently
  ignored since then, now fails start-up (section 5).
- Use cases, the outbox writer, `ContextIdsModule`, the database session store: no settings.

## 4. Making a number tunable

The application declares its own setting in one of its modules and passes the value in code. Every
module option that is a single value also accepts a function of `Config`, resolved once, after
configuration is loaded and before the module's services are built:

```java
// in OrdersModule.register: app.declare(DB_POOL);
static final Setting<Integer> DB_POOL = Setting.integer("orders.db-pool").withDefault(10);

App.builder()
    .module(DatabaseModule.builder()
        .pool(config -> DatabasePool.defaults().withMaxSize(config.get(DB_POOL)))
        .build())
    .module(new FlowCatalystModule(config ->
        config.get(PLATFORM_ON) ? FlowCatalystOptions.defaults() : FlowCatalystOptions.disabled()))
    .module(new OrdersModule())
```

- The function's result is validated like a value; an exception from it (a range, an undeclared
  setting) fails start-up as a problem of the module that resolved it.
- Factories (`LockStoreFactory`, `SecretStoreFactory`) receive the `Lookup`, so they can read
  `Config` themselves.
- The application's key must not be in a component the framework claims (section 5). Choose your
  own component (`orders.*`).

## 5. Removed keys fail start-up; framework components are closed

**Removed keys.** Every key listed in section 3 as moved (the full list is in the table of section
8) is a **removed key**. Start-up fails in configuration loading (core contract step 2), before any
service is built, when one is given:

- in a file (`application.yaml`, `application-{env}.yaml`, `secrets.local.yaml` when it is read):
  a key fails when its leading segments match a removed pattern, `*` matching one segment (so the
  pattern `locks` matches every key under `locks`);
- in the environment: the variable derived from a removed pattern (core contract section 4) is
  looked up by its exact name; a `*` is replaced by each instance name found at that position in
  the files. The environment is still never scanned.

The problem names the key, its source and where the behaviour went:
`http.request-timeout (config/application.yaml): moved to code: HttpOptions.withRequestTimeout(Duration) (see migrating-config-to-code)`.
One problem per offending key. A key that some module **declares** is never a removed key (an
application may declare its own setting with such a name, outside the framework's components).
The list is the framework's, applied whatever modules are listed, so a leftover key of a module the
application no longer lists still fails.

**Claimed components.** A module may claim a component in `register`
(`AppBuilder.claimComponent(name)`): every key under it in a file must then be declared (by any
module) or is a removed key; any other key fails start-up as unknown, listing the declared keys of
that component. Two modules claiming one component fail start-up. The framework claims `app`
(core), `http` (`JettyHttpModule`), `http-client` and `http-clients` (`HttpClientModule`), `locks`
(`LocksModule`), `secrets` (`SecretsModule`), `flowcatalyst` (`FlowCatalystModule`) and `auth`
(`AuthModule`). `database` and `redis` are not claimed: their instance sections are checked by
`Instances.build` (unread keys fail as unknown). Environment variables are not checked for unknown
keys (they cannot be listed without scanning).

## 6. What does not change

- Sources, precedence, environment variable names, value formats, secrets handling, the
  two-keys-one-variable check, the platform addressing rules (core contract section 4).
- The `conformance/config` vector. It exercises the loader alone
  (`Config.load(directory, env, declarations)`), which applies neither the removed keys nor the
  claims: one of its scenarios has a `locks` section in a file, to test environment-name
  collisions of instance keys. The application builder applies both checks, through
  `Config.load(directory, env, declarations, rules)`, after the same loading. A vector for the two
  checks is to be added together with the Go port of this contract (adding it now would fail the
  Go comparison).
- Health check names, metric names, routes, log messages other than those naming a moved key.

## 7. Migration

For each module listed, in the application's wiring:

1. Delete the moved keys from every file and deployment environment (section 8). Start-up names
   every one that is left.
2. Give the behaviour in code with the API of section 3, only where the default is not what you
   want. Most applications need nothing beyond deleting the keys.
3. Driver choice: replace `locks` and `secrets` sections with builder calls; drop
   `LocksRedisModule`.
4. Numbers that must differ per deployment: declare an application setting and pass it (section 4).

The guide page `migrating-config-to-code.md` gives the before and after for each key.

## 8. The removed keys

Patterns; `*` is one segment (an instance or client name).

| Pattern | Moved to |
|---|---|
| `app.shutdown.drain` | `AppBuilder.shutdownDrain(Duration)` |
| `app.health.timeout`, `app.health.critical`, `app.health.informational` | `AppBuilder.health(HealthPolicy)` |
| `app.metrics.driver`, `app.metrics.max-series` | `AppBuilder.metrics(MetricsOptions)` |
| `http.max-body-bytes`, `http.request-timeout`, `http.shutdown-grace` | `JettyHttpModule(HttpOptions)` |
| `http.metrics.enabled`, `http.metrics.path` | `HttpOptions.withMetricsEndpoint(path)` |
| `http-client.event-loops`, `http-client.max-connections-per-host` | `HttpClientModule.builder().eventLoops(..)`, `.maxConnectionsPerHost(..)` |
| `http-clients.default`, `http-clients.instances.*.driver` | removed (clients are named in code) |
| `http-clients.instances.*.connect-timeout`, `.request-timeout`, `.max-response-bytes` | `HttpClientOptions` |
| `http-clients.instances.*.retry` (and `retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff`, `retry.max-retry-after`) | `HttpClientOptions.withRetry(RetryPolicy)` |
| `http-clients.instances.*.auth.type`, `.auth.method`, `.auth.scope`, `.auth.audience`, `.auth.name` | `HttpClientOptions.withAuth(ClientAuth)` |
| `database.instances.*.pool` (and `pool.max`, `pool.min-idle`, `pool.connect-timeout`) | `DatabaseModule.builder().pool(..)` |
| `database.instances.*.migrate` | `DatabaseModule.builder().migrate(..)` |
| `database.instances.*.driver`, `redis.instances.*.driver` | removed |
| `redis.instances.*.timeout`, `redis.instances.*.pool` (and `pool.max`) | `RedisModule.builder().pool(..)` |
| `locks` (and `locks.default`, `locks.instances.*.driver`, `.database`, `.connection`, `.prefix`) | `LocksModule.builder()` with `LockStores`, `DatabaseLockStores`, `RedisLockStores` |
| `secrets` (and `secrets.default`, `secrets.instances.*.driver`, `.directory`, `.prefix`, `.max-bytes`, `.cache-ttl`, `.cache-max`) | `SecretsModule.builder()` with `SecretStores`, `CachePolicy` |
| `rate-limits` (and `rate-limits.default`, `rate-limits.instances.*.driver`, `.limit`, `.period`, `.burst`, `.max-keys`, `.connection`, `.prefix`) | `MemoryRateLimiter.create`, `RedisRateLimiter.create` (MA item 10) |
| `flowcatalyst.enabled`, `flowcatalyst.timeout`, `flowcatalyst.sync.on-boot`, `flowcatalyst.outbox.stale-after` | `FlowCatalystModule(FlowCatalystOptions)` |
| `flowcatalyst.receivers.enabled`, `.path`, `.require-signature`, `.dispatch-timeout`, `.job-timeout`, `.job-lock-lease`, `.completion-retry-delay`, `.once-retention`, `.once-purge-interval` | `FlowCatalystOptions.withReceivers(ReceiverOptions)` |

## 9. Tests

Per module: the defaults equal the old configuration defaults; every range bound (just inside
passes, just outside throws, naming the field); behaviour with a non-default option (a shorter
timeout, a smaller pool, a disabled endpoint, a different driver); the `Function<Config, T>` form
reading an application setting; every removed key in a file and in the environment fails start-up
with the moved-to message; an unknown key under a claimed component fails; a declared key under a
removed pattern is allowed; the existing behavioural tests pass through the code API.
