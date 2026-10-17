# Contract: service settings are configuration; things with many instances are code

Status: owner ruling 2026-10-14, **clarified the same day**. The first reading ("move all behaviour
to code", written here and built in Java in commits `3444145` to `9326692`) over-reached; the
owner's rule is narrower: *"The configs for services and things like that are all fine. It is just
don't use a config to define pollers or things that can have many instances."* This text states
that rule and the final state of every module; section 11 records what was reversed and why.

It generalises MA item 10 (rate limiters built in code, [rate-limiting contract](rate-limiting.md)
section 1) to every capability whose instances are a code fact. It amends the [core
contract](core.md) section 2 (drivers) and the configuration sections of the [locks](locks.md),
[HTTP client](http-client.md) and [secrets](secrets.md) contracts; where they disagree with this
text, this text wins. The [database and Redis](database-and-redis.md), [HTTP](http.md),
[background and metrics](background-and-metrics.md) and [FlowCatalyst](flowcatalyst.md) contracts'
configuration sections stand as written before 2026-10-14 (with section 3's notes on ranges).

Implementations: Java builds this. The Go implementation (G1) followed it on 2026-10-15 (commit
24b2be1): no driver registry, lock stores named in code, removed keys and claimed components
checked at load, ranges checked at start-up. The Rust implementation (R1, parked) still reads driver
names for locks, secrets and HTTP clients from configuration; it follows this contract when it is
resumed (the conformance vectors are unchanged by it, section 6).

## 1. The rule

**Configuration** (files, environment variables, `secrets.local.yaml`, later the platform's store)
holds the settings of a **service** or of a **connection**:

- the settings of a singleton service of the application: the shutdown drain, health-check timeout
  and criticality, the metrics driver, the HTTP server's limits and timeouts, the FlowCatalyst
  module's switches, timeouts and receiver settings;
- connection instances and their settings: database and Redis instances (URL, user, password, pool
  sizing, timeouts, whether migrations run), the platform's address, the application's code on
  the platform, the application's own public URLs;
- secrets: passwords, tokens, client secrets, keys (`Secret`).

**Code** defines the things an application can have **many of**, each with its own policy, and
the policy objects they carry:

- rate limiters (MA item 10), background loops and pollers;
- lock stores, secret stores, named HTTP clients, each with the driver (or strategy) that backs it
  and its policy: retry, authentication strategy, cache policy, timeouts and size limits of one
  client;
- which driver backs such a thing (there is no `driver:` key anywhere).

**The decision test.** *Could an application want several of these, with different values?* Then
it is code: each one is named and built where it is used, with its policy as a typed value.
*Is it one setting of the service, or of a connection?* Then it is configuration: declared once,
with a default and a checked range, varied per deployment. A deployment varies service settings,
connections and secrets; the set of limiters, stores, clients and loops, and their policies, is
the same in every deployment.

## 2. How the code-defined things are given

- **Builders that name instances.** `LocksModule.builder().store(name, LockStoreFactory)`,
  `SecretsModule.builder().store(name, SecretStoreFactory)`,
  `HttpClientModule.builder().client(name, HttpClientOptions)`; rate limiters are built with
  `MemoryRateLimiter.create` / `RedisRateLimiter.create`. Names match `[a-z][a-z0-9-]*`; a name
  given twice is `IllegalArgumentException`. With exactly one, it is the default; with several,
  `defaultStore` must name one (otherwise `build()` throws). The registries (`Locks`,
  `SecretResolver`, `ServiceClients`) keep their API.
- **Policy objects are records** with a `defaults()` factory and `withX(...)` copies whose compact
  constructor validates every value (`IllegalArgumentException` naming the field and the range):
  `HttpClientOptions`, `RetryPolicy`, `ClientAuth`, `CachePolicy`, the store factories
  (`LockStores`, `DatabaseLockStores`, `RedisLockStores`, `SecretStores`).
- **Driver choice is the factory.** `LockStores.memory()`, `DatabaseLockStores.database()`,
  `RedisLockStores.redis(connection, prefix)`, `SecretStores.env(prefix)`, `SecretStores.file(dir)`.
- **No container.** The module and lifecycle mechanism is unchanged (`AppBuilder`, modules,
  `Setting`, providers, `Lookup`). There is no declarative wiring, no scanning and no
  YAML-declared instance of a code-defined thing.
- **Service settings have one way in: configuration.** The modules of section 3 that read
  configuration have no options record and no code override of those settings (an application
  that wants a different value per test or per deployment sets the key). Where a test of the
  framework needs a value, it sets the key.

### 2.1 Named instances without drivers (core contract section 2, amended)

- `Drivers`, `DriverFactory` and `AppBuilder.drivers()` are removed. A capability whose instances
  are connections in configuration (database, Redis) builds every instance under
  `<section>.instances` with **one** factory: `Instances.build(capability, section, lookup,
  InstanceFactory<T>)`, where the factory receives the instance name, its section, the lookup and
  the instances already built. The other rules of core section 2 hold: unread keys in an instance
  section fail start-up as unknown, `<section>.default` names the default instance, an instance
  cannot be created from environment variables alone. A `driver` key in such a section is a
  removed key (section 8).
- A capability whose instances are a code fact (lock stores, secret stores, HTTP clients) names
  them in its module's builder (section 2).

## 3. Module by module (final state)

"Config" lists every key the module reads, with its default and the range checked at start-up
(a value out of range is a start-up problem naming the key). "Code" lists what is given in code.

### 3.1 core (`app.*`, claimed)

| Config | Default | Checked |
|---|---|---|
| `app.shutdown.drain` | `10s` | a duration |
| `app.health.timeout` | `2s` | a duration |
| `app.health.critical`, `app.health.informational` | empty | string lists; a name in both fails (problem on `app.health.informational`); a name matching no check after every `started` hook is a WARN |
| `app.metrics.driver` | `memory` | `memory` or `noop` |
| `app.metrics.max-series` | `1000` | 1 to 100 000 |

Code: nothing. The metrics driver is a singleton service setting, so it is configuration.

### 3.2 database (`database.instances.<n>`, connection instances)

| Config | Default | Checked |
|---|---|---|
| `database.default` | the only instance | names a configured instance |
| `url`, `username`, `password` (secret) | `url` required, `username` empty | |
| `pool.max` | `10` | 1 to 1000 |
| `pool.min-idle` | `1` | 0 to `pool.max` |
| `pool.connect-timeout` | `5s` | 250 ms to 5 min |
| `pool.login-timeout` | `10s` | 1 s to 5 min, whole seconds (the driver's limit for one connection attempt; [database](database-and-redis.md), 1.1) |
| `migrate` | `true` | a boolean; only the default instance's value is used (migrations run there) |

Code: nothing. `driver` is removed (PostgreSQL is the only database). *Change from before
2026-10-14:* the pool keys were not range-checked and a connect timeout under 250 ms was raised
silently; they are now checked at start-up (the ranges the over-reach introduced, kept because a
silent correction is worse than a start-up problem).

### 3.3 Redis (`redis.instances.<n>`, connection instances)

| Config | Default | Checked |
|---|---|---|
| `redis.default` | the only instance | names a configured instance |
| `url`, `password` (secret) | `url` required | `redis://` or `rediss://` |
| `timeout` | `2s` | more than 0, at most 5 min |
| `pool.max` | `16` | 1 to 1000 |

Code: nothing. `driver` is removed. *Change from before 2026-10-14:* `timeout` and `pool.max` are
range-checked (as in 3.2).

### 3.4 HTTP server (`http.*`, claimed)

| Config | Default | Checked |
|---|---|---|
| `http.host` | `0.0.0.0` | |
| `http.port` | `8080` | 0 to 65 535 |
| `http.max-body-bytes` | `1048576` | not negative |
| `http.request-timeout` | `30s` | a duration |
| `http.shutdown-grace` | `app.shutdown.drain` | a duration |
| `http.metrics.enabled` | `false` | a boolean |
| `http.metrics.path` | `/metrics` | literal segments |
| `http.metrics.bearer-token` | none (secret) | |

Code: nothing (`new JettyHttpModule()`).

### 3.5 FlowCatalyst (`flowcatalyst.*`, claimed)

Every key of the [FlowCatalyst contract](flowcatalyst.md) sections 1 and 4 is configuration, with
the defaults and checks it gives: `enabled` (`true`), `base-url`, `application`, `client-id`,
`client-secret` (secret), `timeout` (`30s`, positive), `subscription-target-base-url`,
`sync.on-boot` (`false`), `outbox.stale-after` (`5m`, positive), and under `receivers`: `enabled`
(`true`), `path` (`/fc`, literal segments), `require-signature` (`true`), `signing-secret`,
`bearer-token`, `dispatch.signing-secret`, `dispatch.bearer-token`,
`scheduled-jobs.signing-secret`, `scheduled-jobs.bearer-token` (secrets), `dispatch-timeout`
(`25s`, positive), `job-timeout` (`10m`, positive), `job-lock-lease` (`10m`, 1 s to 24 h),
`completion-retry-delay` (`1s`, 0 to 1 min), `once-retention` (`30d`, positive),
`once-purge-interval` (`1h`, positive).

Code: nothing (`new FlowCatalystModule()`). Read the timeout as any setting,
`config.get(FlowCatalystModule.TIMEOUT)` (the over-reach's `PlatformClient.timeout()` is removed
with it).

### 3.6 Sessions and login (`auth.*`, claimed)

Already code-first (M4c); configuration holds `auth.issuer`, `auth.client-id`,
`auth.client-secret`, `auth.public-base-url`, `auth.cookie-keys`. The module claims its component
(section 5).

### 3.7 locks (code; no `locks` configuration)

| Code | Notes |
|---|---|
| `LocksModule.builder().store(n, LockStores.memory())` | one process only |
| `.store(n, DatabaseLockStores.database())` / `database(instance)` | framework-locks-database; `LocksDatabaseModule` is still listed, for the `fw_lock` migration, and the store checks `fw_lock` exists on its instance at start-up |
| `.store(n, RedisLockStores.redis())` / `redis(connection)` / `redis(connection, prefix)` | prefix default `fw:lock:`, not blank, at most 100 characters |
| `.defaultStore(n)` | required with several stores |

`LocksRedisModule` is removed. A store factory is a `LockStoreFactory` (`create(name, lookup)`).
The database and Redis instances a store uses are configuration (3.2, 3.3).

### 3.8 secrets (code; no `secrets` configuration)

| Code | Notes |
|---|---|
| `SecretsModule.builder().store(n, SecretStores.env(prefix))` | |
| `.store(n, SecretStores.file(Path).withMaxBytes(int))` | absolute path (checked at once), a directory (checked at start-up); 1 B to 1 MiB, default 65 536 |
| `SecretStores.platform()` | still fails start-up: the platform has no endpoint |
| `.withCache(CachePolicy.of(ttl, maxEntries))` | ttl 0 (no cache; also `CachePolicy.none()`) to 24 h, entries 1 to 1 000 000; `CachePolicy.defaults()` is 5 min, 10 000 |
| `.defaultStore(n)` | required with several stores |

A deployment-specific directory or prefix is an application setting read by the store factory
(`SecretStoreFactory` receives the lookup, section 4).

### 3.9 HTTP clients (code; each client's section holds its URL and credentials)

| Code | Notes |
|---|---|
| `HttpClientModule.builder().client(name, HttpClientOptions)` | the client exists because code names it; also `client(name, Function<Config, HttpClientOptions>)` |
| `HttpClientOptions.defaults().withConnectTimeout`, `withRequestTimeout`, `withMaxResponseBytes` | 5 s, 30 s, 10 MiB; more than 0 and at most 1 h; 1 B to 1 GiB |
| `HttpClientOptions.withRetry(RetryPolicy)` | `RetryPolicy.defaults().withMaxAttempts(...)`, ranges of the HTTP client contract |
| `HttpClientOptions.withAuth(ClientAuth)` | `none()`, `bearer()`, `basic()`, `header(name)`, `clientCredentials().withScope(..).withAudience(..).withMethod(..)` |

Config, per client `<n>` named in code, in `http-clients.instances.<n>`: `base-url` (required) and
the credentials its `ClientAuth` reads: `auth.token` (secret, `bearer`), `auth.username` and
`auth.password` (secret, `basic`), `auth.value` (secret, `header`), `auth.token-url`,
`auth.client-id` and `auth.client-secret` (secret, `clientCredentials`). A key the client's
strategy does not read, a section for a client the code does not name and a stray key under
`http-clients` fail start-up. A client's section must be in a file; the environment overrides
single values (`HTTP_CLIENTS_INSTANCES_BILLING_BASE_URL`). The module claims `http-client`.

The engine every named client shares is configuration (owner ruling, 2026-10-14, after the
decision test of section 1): `http-client.event-loops` (default 2, 1 to 64, environment variable
`HTTP_CLIENT_EVENT_LOOPS`) and `http-client.max-connections-per-host` (default 50, 1 to 1000,
`HTTP_CLIENT_MAX_CONNECTIONS_PER_HOST`), declared by `HttpClientModule` as `EVENT_LOOPS` and
`MAX_CONNECTIONS_PER_HOST`. A value out of range fails start-up naming the key and where it came
from. They have no builder methods; the builder holds only the named clients.

### 3.10 Rate limiting, background loops, use cases

Rate limiters are code (MA item 10); the old `rate-limits` section fails start-up (section 8).
Background loops are code (`Background`, `LoopOptions`). Use cases, the outbox writer,
`ContextIdsModule` and the database session store have no settings.

## 4. Making a code-defined thing differ per deployment

An application that wants, for example, a different prefix for its secret store per deployment
declares **its own** setting in its own component and reads it where the thing is built:

- store factories (`LockStoreFactory`, `SecretStoreFactory`) receive the `Lookup` and can read
  `Config`;
- `HttpClientModule.builder().client(name, Function<Config, HttpClientOptions>)` resolves the
  client's options once configuration is loaded;
- a rate limiter or a background loop is built in a module's `boot`, where `app.config()` is
  available.

The application's key must not be in a component the framework claims (section 5); choose your
own component (`orders.*`). Service settings (section 3.1 to 3.6) need none of this: they are
configuration already.

## 5. Removed keys fail start-up; framework components are closed

**Removed keys.** Every key listed in section 8 is a **removed key**. Start-up fails in
configuration loading (core contract step 2), before any service is built, when one is given:

- in a file (`application.yaml`, `application-{env}.yaml`, `secrets.local.yaml` when it is read):
  a key fails when its leading segments match a removed pattern, `*` matching one segment (so the
  pattern `locks` matches every key under `locks`);
- in the environment: the variable derived from a removed pattern (core contract section 4) is
  looked up by its exact name; a `*` is replaced by each instance name found at that position in
  the files. The environment is still never scanned.

The problem names the key, its source and where the thing is defined now:
`locks.default (config/application.yaml): moved to code: LocksModule.builder().defaultStore(name) (see the guide page migrating-config-to-code)`.
One problem per offending key. A key that some module **declares** is never a removed key. The
list is the framework's, applied whatever modules are listed, so a leftover key of a module the
application no longer lists still fails.

**Claimed components.** A module may claim a component in `register`
(`AppBuilder.claimComponent(name)`): every key under it in a file must then be declared (by any
module) or is a removed key; any other key, a misspelt one included, fails start-up as unknown,
listing the declared keys of that component. Two modules claiming one component fail start-up.
The framework claims `app` (core), `http` (`JettyHttpModule`), `http-client` (`HttpClientModule`,
which checks `http-clients` itself, section 3.9), `locks` (`LocksModule`), `secrets`
(`SecretsModule`), `flowcatalyst` (`FlowCatalystModule`) and `auth` (`AuthModule`). `database`
and `redis` are not claimed: their instance sections are checked by `Instances.build` (unread keys
fail as unknown). Environment variables are not checked for unknown keys (they cannot be listed
without scanning).

## 6. What does not change

- Sources, precedence, environment variable names, value formats, secrets handling, the
  two-keys-one-variable check, the platform addressing rules (core contract section 4).
- The `conformance/config` vector. It exercises the loader alone
  (`Config.load(directory, env, declarations)`), which applies neither the removed keys nor the
  claims. The application builder applies both through `Config.load(directory, env, declarations,
  rules)` after the same loading. A vector for the two checks is to be added together with the Go
  port of this contract.
- Health check names, metric names and their help texts, routes.

## 7. Migration

From a configuration written before 2026-10-14:

1. Delete the `locks`, `secrets` and `rate-limits` sections; name the stores in code
   (`LocksModule.builder()`, `SecretsModule.builder()`, section 3.7, 3.8) and drop
   `LocksRedisModule`.
2. For each HTTP client, keep `base-url` and its credentials in its section, delete the other keys
   (`driver`, timeouts, `max-response-bytes`, `retry.*`, `auth.type`, `auth.method`, `auth.scope`,
   `auth.audience`, `auth.name`) and `http-clients.default`, and name the client in code with its
   policy (section 3.9). `http-client.event-loops` and `.max-connections-per-host` stay
   configuration, unchanged.
3. Delete `driver` from database and Redis instances.
4. Everything else stays as it was.

From the over-reach state (an application already migrated to the first reading): replace
`App.builder().shutdownDrain`, `.health(HealthPolicy)`, `.metrics(MetricsOptions)`,
`new JettyHttpModule(HttpOptions)`, `DatabaseModule.builder()`, `RedisModule.builder()` and
`new FlowCatalystModule(FlowCatalystOptions)` by the keys of section 3, and use the no-argument
constructors. Start-up names every removed key that is still given; the compiler names every
removed type.

The guide page `migrating-config-to-code.md` gives the before and after for each moved key.

## 8. The removed keys

Patterns; `*` is one segment (an instance or client name).

| Pattern | Defined in code by |
|---|---|
| `database.instances.*.driver`, `redis.instances.*.driver` | nothing: one driver each |
| `locks` (and `locks.default`, `locks.instances.*.driver`, `.database`, `.connection`, `.prefix`) | `LocksModule.builder()` with `LockStores`, `DatabaseLockStores`, `RedisLockStores` |
| `secrets` (and `secrets.default`, `secrets.instances.*.driver`, `.directory`, `.prefix`, `.max-bytes`, `.cache-ttl`, `.cache-max`) | `SecretsModule.builder()` with `SecretStores`, `CachePolicy` |
| `http-clients.default`, `http-clients.instances.*.driver` | nothing: clients are named in code |
| `http-clients.instances.*.connect-timeout`, `.request-timeout`, `.max-response-bytes` | `HttpClientOptions` |
| `http-clients.instances.*.retry` (and `retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff`, `retry.max-retry-after`) | `HttpClientOptions.withRetry(RetryPolicy)` |
| `http-clients.instances.*.auth.type`, `.auth.method`, `.auth.scope`, `.auth.audience`, `.auth.name` | `HttpClientOptions.withAuth(ClientAuth)` |
| `rate-limits` (and `rate-limits.default`, `rate-limits.instances.*.driver`, `.limit`, `.period`, `.burst`, `.max-keys`, `.connection`, `.prefix`) | `MemoryRateLimiter.create`, `RedisRateLimiter.create` (MA item 10) |

No key under `app`, `http`, `flowcatalyst`, `database.instances.*` (other than `driver`) or
`redis.instances.*` (other than `driver`) is removed.

## 9. Tests

Per configuration module: every default; every range bound (just inside starts, just outside
fails naming the key); the environment variable of each key; a misspelt key under a claimed
component fails; the keys of section 3.1 to 3.5 given in a file and in the environment no longer
fail as removed. Per code-defined thing: the defaults, every range bound of its records, a
non-default value changing behaviour, each removed key of section 8 failing in a file and in the
environment with its message.

## 10. As built (Java)

- Key rules: `Config.load(directory, env, declarations, KeyRules)`; `KeyRules.framework(claims)` is
  the framework's removed keys (`KeyRules.frameworkRemovedKeys()`, one list in `framework-core`)
  plus the modules' `ComponentClaim`s; `RemovedKey(pattern, module, movedTo)`. The three-argument
  loader applies no rules. An empty mapping (`app:` with nothing under it) above a declared key is
  not reported.
- `LockStoreFactory`, `LockStores`, `DatabaseLockStores`, `RedisLockStores`; `LocksModule` and
  `SecretsModule` are builder-only.
- `SecretStores.env`, `file`, `platform` return the records `SecretStores.EnvStore`, `FileStore`,
  `PlatformStore` (each a `SecretStoreFactory` with `withCache`, `FileStore.withMaxBytes`);
  `CachePolicy.defaults()`, `of`, `none()`.
- `HttpClientOptions`, `ClientAuth` (sealed: `None`, `Bearer`, `Basic`, `Header`,
  `ClientCredentialsGrant`), `HttpClientModule.builder()`. The credential keys are not declared as
  settings, because with the `Function<Config, HttpClientOptions>` form the strategy is known only
  once configuration is loaded; the module reads the section like an instance section instead.
- `Instances.build(capability, section, lookup, InstanceFactory)`; `Drivers`, `DriverFactory`,
  `AppBuilder.drivers()` removed.
- The reversal (section 11): `cab9acb` (core), `47e8411` (http), `e57a790` (database, Redis), then
  the FlowCatalyst module with this text's final pass. Each module declares its keys again with
  the old names and defaults; `app`, `http` and `flowcatalyst` stay claimed, now with keys declared,
  so a misspelt key fails start-up listing the module's keys. The database and Redis range checks
  are start-up problems raised by the instance factory, before any connection opens, one problem
  per wrong value.
- Not done: the conformance vector for the key rules (section 6); the Rust port. Go's checks are
  table tests (`config` and `fw`) until the Java side writes the vector.

## 11. The reversal (2026-10-14)

The first reading of the ruling moved **every** behavioural key to code: the core's drain, health
policy and metrics driver (`HealthPolicy`, `MetricsOptions`, `AppBuilder.shutdownDrain`,
`.health`, `.metrics`), the HTTP server's limits (`HttpOptions`), database and Redis pools and
`migrate` (`DatabasePool`, `RedisPool`, `DatabaseModule.builder()`, `RedisModule.builder()`) and
the FlowCatalyst module's switches, timeouts and receiver settings (`FlowCatalystOptions`,
`ReceiverOptions`, `PlatformClient.timeout()`), each with a `Function<Config, T>` form so a deployment could still tune them
through an application setting. That was the tell: a service setting that every deployment may
need to vary had become an application-declared setting passed through code, two ways to do one
thing, while the owner's concern was only things that can have many instances (pollers, limiters,
stores, clients), whose number and policies are a fact of the code.

Reversed: those records, builders and overloads are removed, and the keys are configuration
again, with their pre-2026-10-14 names, defaults and environment variables (section 3.1 to 3.5).
Kept: lock stores, secret stores and HTTP clients named in code with their policies, rate
limiters in code, the driver registry's removal, the removed-key check (with the shorter list of
section 8) and claimed components (now with the restored keys declared, so a misspelt key under
`app`, `http` or `flowcatalyst` fails).

The HTTP client engine settings (`http-client.event-loops`, `http-client.max-connections-per-host`)
were moved to the module's builder in the first reading and, by the owner's ruling the same day,
are configuration again: one setting each of a single engine, not a thing with many instances.
They are not removed keys (section 8). Pool range checks on database and Redis stay as they are.
