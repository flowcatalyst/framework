<!-- types: AppBuilder, KeyRules, DatabaseModule, RedisModule, JettyHttpModule, HttpClientModule, HttpClientOptions, ClientAuth, SecretsModule, SecretStores, CachePolicy, FlowCatalystModule, LocksModule, LockStores, DatabaseLockStores, RedisLockStores -->
# Migrating configuration to code

**Breaking (2026-10-14)** for lock stores, secret stores, HTTP clients and driver keys. The rule
([config-and-code contract](../contracts/config-and-code.md)): the settings of **services and
connections** are configuration; the things an application can have **many of** (lock stores,
secret stores, named HTTP clients, rate limiters, background loops), with their policies and the
driver behind each, are **code**. Ask: *could an application want several of these, with
different values?* Then it is code. *Is it one setting of the service, or of a connection?* Then it
is configuration. This page lists each key that moved and what to write instead.

**Most applications change little.** `app.*`, `http.*`, `flowcatalyst.*` (receivers included),
`auth.*` and the database and Redis instances (pools, timeouts, `migrate`) are configuration, as
before 2026-10-14. Only these sections or keys moved: `locks`, `secrets`, `rate-limits`, the
behaviour of each HTTP client, and `driver` in instance sections.

*History.* For a few hours on 2026-10-14 the first reading of the ruling moved every behavioural
key to code (`HealthPolicy`, `MetricsOptions`, `HttpOptions`, `DatabasePool`, `RedisPool`,
`FlowCatalystOptions`, `ReceiverOptions`, `AppBuilder.shutdownDrain`, `DatabaseModule.builder()`,
`RedisModule.builder()`). That over-reached and was reversed the same day: those types are gone
and their keys are configuration again, with the old names, defaults and environment variables. An
application that had already moved to them goes back to the keys and to `new JettyHttpModule()`,
`new DatabaseModule()`, `new RedisModule()`, `new FlowCatalystModule()`.

## How to migrate

1. Start the application. Every moved key still in a file (`application.yaml`,
   `application-{env}.yaml`, `secrets.local.yaml`) or in the environment fails start-up with a
   problem naming the key, where it came from and the code that replaces it. Nothing is ignored
   silently.
2. Delete each key and give the thing in code with the API in the tables below.
3. If a value of a code-defined thing must differ per deployment, declare a setting of your own (in
   your own component, such as `orders.*`) and read it where the thing is built
   ([Config](config.md#making-a-code-defined-thing-differ-per-deployment)).

## Code that no longer compiles

| Gone | Instead |
|---|---|
| `new LocksModule()`, `new SecretsModule()` | `LocksModule.builder()...build()`, `SecretsModule.builder()...build()` |
| `LocksRedisModule` | `RedisLockStores.redis(..)` in `LocksModule.builder().store(..)` |
| `LocksModule.MEMORY`, `LocksDatabaseModule.DRIVER`, `LocksRedisModule.DRIVER`, `DEFAULT_PREFIX` | `LockStores.memory()`, `DatabaseLockStores.database()`, `RedisLockStores.redis()`, `RedisLockStores.DEFAULT_PREFIX` |
| `SecretsModule.ENV`, `FILE`, `PLATFORM` | `SecretStores.env(..)`, `file(..)`, `platform()` |
| `DatabaseModule.DRIVER`, `RedisModule.DRIVER`, `HttpClientModule.DRIVER` | nothing (one driver each; clients are named in code) |
| `HttpClientModule.EVENT_LOOPS`, `MAX_CONNECTIONS` | `HttpClientModule.EVENT_LOOPS`, `MAX_CONNECTIONS_PER_HOST` (still configuration) |
| `Drivers`, `DriverFactory`, `AppBuilder.drivers()`, `Instances.build(capability, section, lookup[, defaultDriver])` | choose a driver in code; for instances that are connections, `Instances.build(capability, section, lookup, InstanceFactory)` |

## Database and Redis instances

Nothing moves but `driver`: delete `driver: postgresql` and `driver: redis` from instance
sections. The pool keys are now checked at start-up (`pool.max` 1 to 1000, `pool.min-idle` 0 to
`pool.max`, `pool.connect-timeout` 250 ms to 5 min; Redis `timeout` more than 0 and at most 5 min,
`pool.max` 1 to 1000); a connect timeout under 250 ms used to be raised silently and is now refused.

## HTTP client (`http-client.*`, `http-clients.*`)

An application can call many services, each with its own retry and authentication policy, so
each client is now named in code; its section keeps `base-url` and the credentials of its strategy.

| Key | Default | Now |
|---|---|---|
| a client under `http-clients.instances.<n>` | | `HttpClientModule.builder().client("<n>", HttpClientOptions.defaults()...)` |
| `connect-timeout`, `request-timeout`, `max-response-bytes` | `5s`, `30s`, 10 MiB | `HttpClientOptions.withConnectTimeout`, `withRequestTimeout`, `withMaxResponseBytes` |
| `retry.*` | `RetryPolicy.defaults()` | `HttpClientOptions.withRetry(RetryPolicy.defaults().withMaxAttempts(..).withBackoff(..).withMaxRetryAfter(..))` |
| `auth.type: bearer`, `basic`, `header` + `auth.name`, `client-credentials` | `none` | `withAuth(ClientAuth.bearer())`, `basic()`, `header("X-Api-Key")`, `clientCredentials()` |
| `auth.scope`, `auth.audience`, `auth.method` | | `ClientAuth.clientCredentials().withScope(..).withAudience(..).withMethod(..)` |
| `http-client.event-loops`, `http-client.max-connections-per-host` | `2`, `50` | unchanged: still configuration (one engine for all clients), now range-checked at start-up (1 to 64, 1 to 1000) |
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
