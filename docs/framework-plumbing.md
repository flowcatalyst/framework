# Application framework: plumbing and contracts (draft 1, 2026-10-08)

Status: a thinking document. Nothing is built. Names are placeholders.

Companion: [`laravel-capability-inventory.md`](laravel-capability-inventory.md), the factual
survey of Laravel 13.17 this draft draws on.

## 1. What this is

An application framework for business services with strong domain models, for teams that know
Laravel. It keeps the parts of Laravel that are structural and drops the parts that depend on
PHP being dynamic. It is designed once as language-neutral contracts and implemented in Java 25
first, then Rust, possibly TypeScript.

FlowCatalyst is the default back end for everything it already does: identity and users,
events, queues, scheduling, and (new) metering of outbound messages.

## 2. What is kept and what is dropped

Kept from Laravel:

| Idea | Form here |
|---|---|
| Service providers: register, then boot | Modules, wired in code, checked at start-up |
| Named instances with drivers (stores, disks, mailers) | The same, with a typed driver registry |
| Composition by name (failover, round-robin, scoped) | Wrapper drivers: failover, prefixed, metered, cached |
| A fake for every service | Every contract ships a recording fake that implements the contract |
| Store versus user-facing API | Small driver contract; richer API built once on top of it |
| One pipeline for HTTP, jobs and commands | One middleware abstraction |
| Context that follows the request into logs and jobs | Explicit `Context` value: tenant, principal, application, correlation id |
| Conventions, project layout, a command-line tool | Yes |

Dropped, and why:

| Laravel feature | Reason |
|---|---|
| Eloquent and active record | A model that saves itself ties the domain to the table; lazy loading hides queries |
| Facades and global helpers | Hidden dependencies; nothing in a signature says what a function uses |
| Container auto-wiring by reflection | Wiring errors appear at first use, not at start-up |
| Drivers found by method-name convention | An unknown driver should fail at boot |
| Jobs serialised as language objects, closures on queues | Cannot cross languages or versions; use named, versioned messages |
| Validation and rules as string mini-languages | Typed schemas |

## 3. Design rules

1. **Contracts first.** Each capability is specified once: operations, error cases, what a
   driver must guarantee, and how the fake behaves. Implementations are ports of that text.
2. **No reflection, no global state.** Everything a piece of code uses arrives through its
   constructor or its arguments.
3. **Fail at start-up.** Every named instance, driver and required setting is resolved and
   validated before the service accepts work.
4. **Explicit scopes.** Three lifetimes only: application, request (or job), and transaction.
   Nothing is "flushed" between requests because nothing request-scoped is stored globally.
5. **Errors are values in the contract.** Each operation lists its failure kinds.
6. **A capability lives in the application process unless it needs a shared view across
   services.** Counting, quotas, identity and delivery tracking need a shared view, so they live
   in the platform and are reached through a driver.
7. **Use the library where it earns its place; plain code elsewhere.** The framework must not
   make simple things pass through its own vocabulary.
8. **The tenant is always present** (owner ruling, 2026-10-08). Every `Context` carries a
   client, and repositories scope every query by it. There is no single-tenant mode: a service
   built for one customer sets up one client.

## 3a. Per-language build rules

**Go, if a Go implementation is made** (owner, 2026-10-08). Go has no sum types and allows nil,
so the framework enforces in the linter what Java's compiler checks. Every generated project
ships a golangci-lint profile with:

- `exhaustive` with `default-signifies-exhaustive: false`: a `default` branch does not count as
  covering the remaining members, so a member added later fails the lint at every switch;
- `gochecksumtype` for sealed variants, written as an interface with an unexported marker
  method;
- `nilaway` on domain packages.

## 4. Modules (the service-provider idea)

A module declares what it provides and what it requires. Start-up has two phases, as in
Laravel, for the same reason: a module may need another module's service while starting.

1. **Register.** Each module adds constructors for its services to the application builder.
   Nothing runs yet.
2. **Boot.** Services are built in dependency order. Each module may then register routes,
   handlers, schedules, migrations and health checks, and start background work.
3. **Shutdown.** Reverse order, with a drain period.

Java 25 shape (illustrative):

```java
public final class CacheModule implements Module {
  @Override public void register(AppBuilder app) {
    app.provide(Caches.class, ctx -> Caches.fromConfig(
        ctx.get(Config.class).section("cache"),
        ctx.get(DriverRegistry.class)));
  }
  @Override public void boot(App app) {
    app.health().add("cache", app.get(Caches.class)::ping);
  }
}

App app = App.builder()
    .module(new ConfigModule())
    .module(new FlowCatalystModule())
    .module(new CacheModule())
    .module(new OrdersModule())      // the application's own
    .start();                        // validates everything, then serves
```

`ctx.get(X.class)` is a typed lookup in a map built at start-up, not reflection. A missing
provider is an error from `start()`, naming the module that asked.

Rust shape: the same two phases, with modules contributing to a builder that produces a typed
application struct; a missing dependency is a compile error where that is practical and a
start-up error otherwise.

## 5. Named instances and drivers

Every driver-based capability follows one pattern:

- **Instance:** a name the application uses (`cache "sessions"`, `disk "invoices"`,
  `mailer "transactional"`).
- **Driver:** the kind of back end, chosen in that instance's configuration (`redis`, `s3`,
  `ses`, `flowcatalyst`, `memory`).
- **Registry:** drivers are registered by name with a typed factory. An unknown driver name
  fails at start-up.
- **Wrappers:** drivers that wrap other instances: `failover`, `prefixed`, `metered`,
  `rate-limited`, `logged`. This is how cross-cutting behaviour is added without each driver
  reimplementing it.

```yaml
mail:
  default: transactional
  instances:
    transactional: { driver: metered, inner: ses-eu, meter: mail }
    ses-eu:        { driver: ses, region: eu-west-1 }
```

## 6. Capabilities, first cut

"Process" means a library with local drivers. "Platform" means the default driver calls
FlowCatalyst.

| Capability | Core operations | Drivers | Lives in | Notes |
|---|---|---|---|---|
| **Config** | typed get, required/optional, sections, watch for change | env, file, database, AWS Parameter Store, AWS Secrets Manager, FlowCatalyst | Platform when present, otherwise process | Layered sources with a stated order. A `Secret` type that cannot be printed or logged. Encrypted values decrypted by a key driver. |
| **Cache** | get, put with required TTL, forget, remember, increment | memory, Redis, Postgres | Process | TTL is always required. Tags only if a driver can do them correctly. |
| **Locks** | acquire with lease, release by owner token, refresh, fencing token | database, Redis, memory (owner, 2026-10-08) | Process | Laravel's contract has no refresh and no fencing token; both are needed for correctness. The memory driver locks within one process only: for a single instance, local development and tests. |
| **Storage** | put, get, stream, delete, list, temporary URL, visibility | local, S3, memory | Process | The fake is in memory, not a temp directory. |
| **Mail** | send a typed message, attachments, templates | SES, SMTP, SendGrid, log | Process, metered by platform | Sending always goes through the meter (see below). |
| **Messaging** | send to a recipient on a channel: SMS, WhatsApp, push | Twilio/SendGrid, platform | Process, metered and tracked by platform | One contract for all channels; delivery status callbacks recorded centrally. |
| **Metering and quotas** | reserve, commit, release; query usage | FlowCatalyst, memory | Platform | Limits per application or group by hour, day and month. Must be a shared count. |
| **Rate limiting** | attempt within a named limit | memory, Redis, FlowCatalyst | Process or platform | Named limiters, as in Laravel, with typed keys. |
| **Identity and users** | authenticate a request, current principal, roles and permissions, create/invite/disable users | FlowCatalyst, local, fake | Platform | The platform is the system of record. No local `users` table is required. |
| **Events** | emit a domain event inside a use case | outbox to FlowCatalyst, memory | Platform | Already exists in the SDKs. |
| **Jobs** | dispatch a named, versioned message; handle it | FlowCatalyst dispatch, in-process | Platform | No object serialisation. A type name plus a schema. |
| **Schedule** | declare a job with a cron; run its handler | FlowCatalyst scheduled jobs | Platform | Already exists. |
| **HTTP client** | typed request and response, retries, timeouts | — | Process | Ships with a fake that records and scripts responses. |
| **Observability** | logs, metrics, traces, health checks | OpenTelemetry | Process | On by default. Context fields attached automatically. |

### Metering, in more detail

Outbound mail and messages share one flow, so limits and counts are uniform:

1. The application calls `mail.send(...)` or `messages.send(...)`.
2. The `metered` wrapper asks the meter to **reserve** one unit for (application, group,
   channel). The meter answers allowed, or refused with the limit that was hit and when it
   resets.
3. The inner driver sends.
4. The wrapper **commits** the reservation on success or **releases** it on failure.
5. Provider delivery callbacks (delivered, bounced, failed) update the tracked message.

The meter is a platform service because several instances of several services share each
limit. This is new platform work: a metering API, the counters, and a delivery-tracking record.

### Config and secrets, in more detail

Owner direction (2026-10-06): ordinary config as in Laravel, overridden by an external store
when the store has a value; any single item can opt out of the default store; secrets are
declared as secrets; local development reads secrets from a file that is never committed;
config is synced to the store the way migrations are applied. The existing tenant config in
`integral-service-v1` (plain and encrypted tables keyed by domain and key) is the kind of thing
this must absorb.

**Every setting is declared once, in code.** The declaration is the catalogue the rest works
from:

| Part of a declaration | Meaning |
|---|---|
| Key | `mail.from_address`, or for tenant settings a domain and key |
| Type | string, integer, duration, URL, a typed record (decoded from JSON) |
| Scope | **service** (one value per deployment) or **tenant** (one value per tenant) |
| Kind | **value** or **secret** |
| Default | a real default for a value; for a secret, none, or a marked dummy for local use only |
| Required | whether start-up fails without it |
| Store | which store holds it, when not the default store (the per-item opt-out) |
| Description | shown in tooling and in the store |

**Resolution order for a service-scoped value,** lowest to highest:

1. The default in the declaration.
2. The committed config file for the environment.
3. Environment variables.
4. The external store, if it has a value for the key.

An item pinned to a different store, or to `env` only, resolves from that source at step 4.

**Secrets:**

- A secret is its own type. It cannot be printed, logged or serialised by accident; reading the
  raw value is an explicit call.
- A secret never has a real default. In a deployed environment a missing or still-dummy
  required secret fails start-up, naming the key.
- Local development reads secrets from `secrets.local.yaml`, which the project template lists
  in `.gitignore` and the tooling refuses to commit.
- Deployed services read secrets from the secret store driver: AWS Secrets Manager, AWS
  Parameter Store (secure strings), a database table with envelope encryption, or FlowCatalyst
  when it offers one.
- Encrypted-at-rest values in a database use envelope encryption through a key driver (a cloud
  key service, or a local key for development).

**Tenant-scoped settings** replace the two `integral-service-v1` tables with one model:

- Keyed by (tenant, domain, key), each declared with a type so the value is decoded into a
  typed record, as `findTypedEncryptedConfig` does today.
- Kind value or secret; a secret is stored encrypted.
- Optional expiry, as today (`expireAt`).
- A missing required setting raises a typed error and fires a throttled alert hook, which is
  what the existing support e-mail does.
- An operator screen lists settings per tenant and marks those still unset or unconfirmed.

**Config sync, modelled on migrations.** `config sync` compares the declared catalogue with the
store and records what it has done in a ledger:

- A declared key absent from the store is created there: a value with its default, a secret
  with a placeholder marked **unset**.
- A key already in the store is never overwritten. The store is the authority once a value
  exists.
- A key in the store that is no longer declared is reported, not deleted.
- The ledger records which declarations have been pushed, so sync is repeatable and a new
  setting in a release shows up as one new entry.
- `config status` lists unset secrets and unconfirmed defaults, so a deployment can be checked
  before it starts. This is the role "confirm" plays in the existing package.

**Runtime behaviour:** values are loaded and validated at start-up, cached, and refreshed on an
interval or on a change notice where the store supports one. A setting declares whether it may
change while running.

**Config in the platform** (owner ruling, 2026-10-08). Config and secrets are a FlowCatalyst
feature, and the framework defers to it when the platform is present:

- Every setting the platform holds is keyed by client and by `{application_code}:{sub_domain}`,
  then the key. This is the same application and sub-domain prefix event types use.
- Access is controlled on both: a caller reads or writes only settings for application codes and
  clients it has access to. An application's service account reaches its own application's
  settings; a user reaches them through their roles and client access.
- Without the platform, the same drivers (env, file, database, AWS Parameter Store, AWS Secrets
  Manager) cover the same declarations, so the choice is configuration, not code.

Open point: whether environment variables should rank above the store as an emergency override.

## 7. The database

The framework does not put a layer between the domain and the tables. It provides two paths.

**Write path.** A use case runs inside a unit of work:

1. Validate the command and authorise the caller.
2. Load an aggregate through its repository.
3. Call a method on the aggregate, which returns the new state and its events.
4. The unit of work persists the aggregate and the events in one transaction, with the outbox.

Repositories contain explicit SQL. Aggregates do not know they are stored. This is the use-case
envelope the FlowCatalyst SDKs already have; the framework adopts it as its write path.

Rules for aggregates (owner direction, 2026-10-06):

- **Load once.** A use case finds its aggregate once and only once. It is never reloaded or
  rehydrated inside the same use case. If the use case needs fresh state, it has failed and is
  run again from the start as a new attempt.
- **Optimistic locking is the standard.** Every aggregate row carries a version. The save is
  `UPDATE ... WHERE id = ? AND version = ?`; zero rows changed is a typed conflict error. No
  pessimistic row locks in application code.
- **The conflict policy belongs to the use case,** declared once: fail to the caller, or retry
  the whole use case a bounded number of times.
- **An aggregate is small.** It holds what its rules need to decide. High-volume facts
  attached to it, such as telemetry, readings and history, are flat rows written and read
  directly. They are not loaded with the aggregate and are not part of its version.

**Read path.** Screens, lists and reports call typed queries that return plain result types
shaped for the screen. They do not load aggregates.

**Flat rows.** Telemetry, logs, audit entries and similar append-mostly data are plain typed
inserts and queries. They may be written inside a use case's transaction when they must commit
with it, and directly otherwise.

**Shared by both:**

- **SQL you can read, checked at build time.** Queries are written as SQL; a generator checks
  them against the schema and produces typed functions and result types (sqlc in Go, sqlx
  macros in Rust, a small generator for Java, Kysely-style typing in TypeScript).
- **No lazy loading.** A query returns what it selected and nothing else.
- **Migrations are plain SQL files**, applied forward only by the framework, with a recorded
  history. No schema DSL.
- **Transactions belong to the use case.** Application code does not begin or commit.
- **Tests get a real database**, one per test class, created from the migrations.

Deliberately absent: models that save themselves, relationship navigation, model events and
observers, query scopes, soft-delete magic, factories that bypass the domain. Test data is
created by running use cases or by small typed builders.

## 8. HTTP and UI

- **Routing and middleware:** routes declared in code by each module; one pipeline abstraction
  shared with jobs and commands.
- **Validation:** typed request schemas; a failed validation is a typed error mapped to a
  response.
- **UI adapters**, each a thin response-side module:
  - **Datastar:** helpers to send element patches and signal updates over a streamed response.
  - **Inertia:** the page object, the `X-Inertia` headers, partial reloads, asset versioning and
    shared props, as recorded in the inventory.
  - **Topcoat:** component rendering helpers.
  The core knows nothing about any of them.

## 9. What FlowCatalyst would need to add

| Need | Exists today? |
|---|---|
| Identity, roles, invitations, portal users | Yes |
| Events through the outbox | Yes |
| Dispatch jobs and scheduled jobs | Yes |
| Metering and quota API | No |
| Outbound message tracking (mail, SMS, WhatsApp) with provider callbacks | No |
| Config and secret store as a driver target, keyed and access-controlled by client and `{application_code}:{sub_domain}` | No; new platform work (ruled in, 2026-10-08) |
| User-management API suitable for an application to drive | Partly; app-managed invitations exist |

## 9a. Relationship to FlowCatalyst (owner ruling, 2026-10-06)

The framework **does not depend on FlowCatalyst**. It is **designed to delegate to it where
that is appropriate**. In practice:

- Every capability has a local driver and a fake, so a service builds, runs and is tested with
  no platform present.
- Where the platform is the better home (identity and users, events, jobs, scheduling,
  metering, and config), a FlowCatalyst driver exists and
  is the recommended choice. Choosing it is configuration, not a code change.
- No core contract mentions the platform. Platform-specific types stay inside the FlowCatalyst
  module.

### Pre-scaffolded application admin

A new project can be generated with an application admin area already in place: screens built
from the UI kit, served by the application, that manage the things the application owns.
When the FlowCatalyst module is enabled, those screens read and write through the platform's
API, scoped to this application:

| Screen | Source when delegated to the platform | Local fallback |
|---|---|---|
| Users, roles and invitations for this application | Platform identity API | Local identity driver |
| Event types this application declares, with sync status | Platform event-type API | Declarations in code only |
| Subscriptions and connections it owns | Platform API | None |
| Scheduled jobs and their recent runs | Platform API | Local schedule driver |
| Config and secrets status (declared, unset, unconfirmed) | Platform config API | The configured store driver |
| Outbound message usage against its limits | Platform metering API | Local meter |

The admin is ordinary application code in the generated project, so a team can change or
remove it. It is the reference example of how a service uses the framework.

This is also what the platform must support: an application's service account needs to read
its own application and the resources attached to it (see the `application-service` role's
missing application read permission, noted 2026-10-06).

## 10. Order of work

1. Agree this document: the rules, the module lifecycle, and the capability list.
2. Write contracts for the smallest useful set: **Config, Context, Modules, Database (both
   paths), Identity, Events**. That is enough to build a real service.
3. Build the trial business service in Java on those contracts, with a Datastar front end.
4. Add **Cache, Locks, Storage** as the trial needs them.
5. Design **metering and messaging** together with the platform work they need.
6. Port to Rust from the contracts once two Java services have used them.

## 11. Open questions

1. ~~Is the framework open source alongside FlowCatalyst, or internal?~~ Settled 2026-10-08:
   open source.
2. ~~Does it require FlowCatalyst?~~ Settled: no. See section 9a.
3. ~~Should config and secrets be a platform feature, or only drivers to existing stores?~~
   Settled 2026-10-08: a platform feature, deferred to when present, with the same drivers
   locally. See "Config in the platform" in section 6.
4. ~~Multi-tenancy: is the tenant always part of `Context`, and do repositories enforce it?~~
   Settled 2026-10-08: yes to both. See design rule 8.
5. ~~Which language is first if Rust becomes the platform language?~~ Settled 2026-10-08: Java.
6. Naming.
