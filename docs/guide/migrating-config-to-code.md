<!-- types: AppBuilder, HealthPolicy, MetricsOptions, KeyRules, DatabaseModule, DatabasePool, RedisModule, RedisPool -->
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

## Rate limits (`rate-limits.*`)

Moved to code in MA item 10 ([Rate limiting](rate-limiting.md)) and silently ignored since; a
leftover `rate-limits` section now fails start-up. Build the limiter with
`MemoryRateLimiter.create` or `RedisRateLimiter.create`.
