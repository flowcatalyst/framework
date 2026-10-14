<!-- types: AppBuilder, HealthPolicy, MetricsOptions, KeyRules, DatabaseModule, DatabasePool, RedisModule, RedisPool, JettyHttpModule, HttpOptions, HttpClientModule, HttpClientOptions, ClientAuth, LocksModule, LockStores, DatabaseLockStores, RedisLockStores -->
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

## Rate limits (`rate-limits.*`)

Moved to code in MA item 10 ([Rate limiting](rate-limiting.md)) and silently ignored since; a
leftover `rate-limits` section now fails start-up. Build the limiter with
`MemoryRateLimiter.create` or `RedisRateLimiter.create`.
