<!-- types: AppBuilder, HealthPolicy, MetricsOptions, KeyRules, DatabaseModule, DatabasePool, RedisModule, RedisPool, JettyHttpModule, HttpOptions, HttpClientModule, HttpClientOptions, ClientAuth, SecretsModule, SecretStores, CachePolicy, FlowCatalystModule, FlowCatalystOptions, ReceiverOptions, PlatformClient, LocksModule, LockStores, DatabaseLockStores, RedisLockStores -->
# Migrating configuration to code

**Breaking (2026-10-14).** Configuration now holds connections and secrets only; every behavioural
setting moved to code ([config-and-code contract](../contracts/config-and-code.md), owner ruling
"move all behaviour to code"). This page lists, module by module, each key that moved and what to
write instead.

## How to migrate

1. Start the application. Every moved key still in a file (`application.yaml`,
   `application-{env}.yaml`, `secrets.local.yaml`) or in the environment fails start-up with a
   problem naming the key, where it came from and the code that replaces it. Nothing is ignored
   silently.
2. Delete each key. If its value was the default, you are done with it: the defaults did not
   change.
3. Otherwise give the value in code with the API in the tables below.
4. If the value must differ per deployment, declare a setting of your own (in your own component,
   such as `orders.*`) and pass it in with the `Function<Config, T>` form
   ([Config](config.md#making-a-number-tunable)).

Connections and secrets stay where they are: database and Redis URLs, user names and passwords,
`http.host` and `http.port`, the platform's `flowcatalyst.base-url`, `application`, `client-id`,
`client-secret` and the receiver secrets, `auth.*`.

## Code that no longer compiles

| Gone | Instead |
|---|---|
| `new LocksModule()`, `new SecretsModule()` | `LocksModule.builder()...build()`, `SecretsModule.builder()...build()` |
| `LocksRedisModule` | `RedisLockStores.redis(..)` in `LocksModule.builder().store(..)` |
| `LocksModule.MEMORY`, `LocksDatabaseModule.DRIVER`, `LocksRedisModule.DRIVER`, `DEFAULT_PREFIX` | `LockStores.memory()`, `DatabaseLockStores.database()`, `RedisLockStores.redis()`, `RedisLockStores.DEFAULT_PREFIX` |
| `SecretsModule.ENV`, `FILE`, `PLATFORM` | `SecretStores.env(..)`, `file(..)`, `platform()` |
| `DatabaseModule.DRIVER`, `RedisModule.DRIVER`, `HttpClientModule.DRIVER` | nothing (one driver each; clients are named in code) |
| `JettyHttpModule.MAX_BODY_BYTES`, `REQUEST_TIMEOUT`, `SHUTDOWN_GRACE`, `METRICS_ENABLED`, `METRICS_PATH` | `HttpOptions` (`HOST`, `PORT`, `METRICS_BEARER_TOKEN` stay) |
| `HttpClientModule.EVENT_LOOPS`, `MAX_CONNECTIONS` | `HttpClientModule.builder().eventLoops(..)`, `.maxConnectionsPerHost(..)` |
| `FlowCatalystModule.ENABLED`, `TIMEOUT`, `SYNC_ON_BOOT`, `OUTBOX_STALE_AFTER`, `RECEIVERS_ENABLED`, `RECEIVERS_PATH`, `RECEIVERS_REQUIRE_SIGNATURE`, the receivers' timeout, lease, delay, retention and interval constants | `FlowCatalystOptions`, `ReceiverOptions`; read the timeout with `PlatformClient.timeout()` |
| `Drivers`, `DriverFactory`, `AppBuilder.drivers()`, `Instances.build(capability, section, lookup[, defaultDriver])` | choose a driver in code; for instances that are connections, `Instances.build(capability, section, lookup, InstanceFactory)` |

## Core (`app.*`)

| Key | Default | Now |
|---|---|---|
| `app.shutdown.drain` | `10s` | `App.builder().shutdownDrain(Duration)` (more than 0, at most 1 h) |
| `app.health.timeout` | `2s` | `App.builder().health(HealthPolicy.defaults().withTimeout(Duration))` (more than 0, at most 5 min) |
| `app.health.critical: [a]` | empty | `HealthPolicy.withCritical("a")` |
| `app.health.informational: [b]` | empty | `HealthPolicy.withInformational("b")` |
| `app.metrics.driver: noop` | `memory` | `App.builder().metrics(MetricsOptions.noop())` |
| `app.metrics.max-series` | `1000` | `App.builder().metrics(MetricsOptions.memory(int))` (1 to 100 000) |

```java
// before: app: { shutdown: { drain: 30s }, health: { critical: [flowcatalyst] } }
App.builder()
    .shutdownDrain(Duration.ofSeconds(30))
    .health(HealthPolicy.defaults().withCritical("flowcatalyst"))
```

## Database (`database.instances.<n>`)

`url`, `username`, `password` and `database.default` stay.

| Key | Default | Now |
|---|---|---|
| `pool.max` | `10` | `DatabaseModule.builder().pool(DatabasePool.defaults().withMaxSize(int))` (1 to 1000) |
| `pool.min-idle` | `1` | `DatabasePool.withMinIdle(int)` (0 to `maxSize`) |
| `pool.connect-timeout` | `5s` | `DatabasePool.withConnectTimeout(Duration)` (250 ms to 5 min; values under 250 ms used to be raised silently, now they are refused) |
| `migrate: false` | `true` | `DatabaseModule.builder().migrate(false)` |
| `driver: postgresql` | | delete it |

A pool for one instance: `.pool("reporting", DatabasePool...)`. Several instances with different
`migrate` values are no longer expressible: migrations only ever ran on the default instance, so
only its value mattered.

```java
// before: database.instances.main: { url: ..., pool: { max: 20 }, migrate: false }
DatabaseModule.builder().pool(DatabasePool.defaults().withMaxSize(20)).migrate(false).build()
```

## Redis (`redis.instances.<n>`)

`url`, `password` and `redis.default` stay.

| Key | Default | Now |
|---|---|---|
| `timeout` | `2s` | `RedisModule.builder().pool(RedisPool.defaults().withTimeout(Duration))` (more than 0, at most 5 min) |
| `pool.max` | `16` | `RedisPool.withMaxSize(int)` (1 to 1000) |
| `driver: redis` | | delete it |

## HTTP (`http.*`)

`http.host`, `http.port` and `http.metrics.bearer-token` stay.

| Key | Default | Now |
|---|---|---|
| `http.max-body-bytes` | `1048576` | `new JettyHttpModule(HttpOptions.defaults().withMaxBodyBytes(long))` (0 to 1 GiB) |
| `http.request-timeout` | `30s` | `HttpOptions.withRequestTimeout(Duration)` (more than 0, at most 1 h) |
| `http.shutdown-grace` | the drain | `HttpOptions.withShutdownGrace(Duration)` (0 to 1 h) |
| `http.metrics.enabled: true` + `http.metrics.path` | off, `/metrics` | `HttpOptions.withMetricsEndpoint("/metrics")` |

The module now claims `http`: a misspelt key there (`http.prot`) fails start-up.

## HTTP client (`http-client.*`, `http-clients.*`)

Each client is now named in code; its section keeps `base-url` and the credentials of its strategy.

| Key | Default | Now |
|---|---|---|
| a client under `http-clients.instances.<n>` | | `HttpClientModule.builder().client("<n>", HttpClientOptions.defaults()...)` |
| `connect-timeout`, `request-timeout`, `max-response-bytes` | `5s`, `30s`, 10 MiB | `HttpClientOptions.withConnectTimeout`, `withRequestTimeout`, `withMaxResponseBytes` |
| `retry.*` | `RetryPolicy.defaults()` | `HttpClientOptions.withRetry(RetryPolicy.defaults().withMaxAttempts(..).withBackoff(..).withMaxRetryAfter(..))` |
| `auth.type: bearer`, `basic`, `header` + `auth.name`, `client-credentials` | `none` | `withAuth(ClientAuth.bearer())`, `basic()`, `header("X-Api-Key")`, `clientCredentials()` |
| `auth.scope`, `auth.audience`, `auth.method` | | `ClientAuth.clientCredentials().withScope(..).withAudience(..).withMethod(..)` |
| `http-client.event-loops`, `http-client.max-connections-per-host` | `2`, `50` | `HttpClientModule.builder().eventLoops(int)`, `.maxConnectionsPerHost(int)` |
| `http-clients.default`, `driver` | | delete them |

Kept under `auth`: `token`, `username`, `password`, `value`, `token-url`, `client-id`,
`client-secret`. A section for a client the code does not name now fails start-up.

```java
// before: http-clients.instances.billing: { base-url: ..., request-timeout: 10s,
//           auth: { type: client-credentials, token-url: ..., client-id: orders, scope: billing } }
HttpClientModule.builder()
    .client("billing", HttpClientOptions.defaults()
        .withRequestTimeout(Duration.ofSeconds(10))
        .withAuth(ClientAuth.clientCredentials().withScope("billing")))
    .build()
// http-clients.instances.billing: { base-url: ..., auth: { token-url: ..., client-id: orders } }
```

## FlowCatalyst (`flowcatalyst.*`)

`base-url`, `application`, `client-id`, `client-secret`, `subscription-target-base-url` and the
receiver secrets (`receivers.signing-secret`, `receivers.bearer-token` and their `dispatch.` and
`scheduled-jobs.` variants) stay.

| Key | Default | Now |
|---|---|---|
| `flowcatalyst.enabled: false` | `true` | `new FlowCatalystModule(FlowCatalystOptions.disabled())` |
| `flowcatalyst.timeout` | `30s` | `FlowCatalystOptions.defaults().withTimeout(Duration)`; read it back with `PlatformClient.timeout()` or `app.get(FlowCatalystOptions.class).timeout()` |
| `flowcatalyst.sync.on-boot: true` | `false` | `.withSyncOnBoot(true)` |
| `flowcatalyst.outbox.stale-after` | `5m` | `.withOutboxStaleAfter(Duration)` |
| `flowcatalyst.receivers.enabled: false` | `true` | `.withReceivers(ReceiverOptions.disabled())` |
| `flowcatalyst.receivers.path` | `/fc` | `ReceiverOptions.defaults().withPath(String)` |
| `flowcatalyst.receivers.require-signature: false` | `true` | `ReceiverOptions.withRequireSignature(false)` |
| `receivers.dispatch-timeout`, `job-timeout`, `job-lock-lease`, `completion-retry-delay`, `once-retention`, `once-purge-interval` | `25s`, `10m`, `10m`, `1s`, `30d`, `1h` | `ReceiverOptions.withDispatchTimeout`, `withJobTimeout`, `withJobLockLease`, `withCompletionRetryDelay`, `withOnceRetention`, `withOncePurgeInterval` |

`FlowCatalystModule.TIMEOUT`, `ENABLED`, `SYNC_ON_BOOT`, `OUTBOX_STALE_AFTER` and the behavioural
`RECEIVERS_*` constants are gone; the connection and secret constants (`BASE_URL`, `APPLICATION`,
`CLIENT_ID`, `CLIENT_SECRET`, `SUBSCRIPTION_TARGET_BASE_URL`, `RECEIVERS_*_SECRET`,
`RECEIVERS_*_TOKEN`) stay.

```java
// before: flowcatalyst: { receivers: { enabled: false } }
new FlowCatalystModule(FlowCatalystOptions.defaults().withReceivers(ReceiverOptions.disabled()))
```

## Locks (`locks.*`, the whole section)

Delete the section and `LocksRedisModule` from your module list; name the stores in code.

| Before | Now |
|---|---|
| `main: { driver: memory }` | `LocksModule.builder().store("main", LockStores.memory())` |
| `main: { driver: database }` | `.store("main", DatabaseLockStores.database())`; keep `DatabaseModule` and `LocksDatabaseModule` |
| `main: { driver: database, database: reporting }` | `.store("main", DatabaseLockStores.database("reporting"))` |
| `shared: { driver: redis }` | `.store("shared", RedisLockStores.redis())`; keep `RedisModule` |
| `shared: { driver: redis, connection: cache, prefix: "x:" }` | `.store("shared", RedisLockStores.redis("cache", "x:"))` |
| `locks.default: main` | `.defaultStore("main")` (needed with several stores) |
| `.module(new LocksModule())` | `.module(LocksModule.builder()...build())` (the no-argument constructor is gone) |
| `.module(new LocksRedisModule())` | delete it |

The database store now checks `fw_lock` on the default instance too: without `LocksDatabaseModule`
start-up fails saying so (before, the first lock failed).

## Secrets (`secrets.*`, the whole section)

Delete the section; name the stores in code. `new SecretsModule()` is gone: use the builder.

| Before | Now |
|---|---|
| `env: { driver: env, prefix: CLIENT_SECRET_ }` | `SecretsModule.builder().store("env", SecretStores.env("CLIENT_SECRET_"))` |
| `files: { driver: file, directory: /run/secrets/clients, max-bytes: 4096 }` | `.store("files", SecretStores.file(Path.of("/run/secrets/clients")).withMaxBytes(4096))` |
| `cache-ttl: 1m, cache-max: 500` | `.withCache(CachePolicy.of(Duration.ofMinutes(1), 500))`; `cache-ttl: 0s` is `CachePolicy.none()` |
| `driver: platform` | `SecretStores.platform()` (still fails start-up: blocked on the platform) |
| `secrets.default: files` | `.defaultStore("files")` |

A directory or prefix that differs per deployment: declare a setting of your own and read it in a
`SecretStoreFactory` lambda ([Per-client secrets](secrets.md#setting-it-up)). A relative directory
and a forbidden prefix are now refused when the store is built, not at start-up.

## Rate limits (`rate-limits.*`)

Moved to code in MA item 10 ([Rate limiting](rate-limiting.md)) and silently ignored since; a
leftover `rate-limits` section now fails start-up. Build the limiter with
`MemoryRateLimiter.create` or `RedisRateLimiter.create`.
