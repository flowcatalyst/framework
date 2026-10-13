# Implementation plan (2026-10-08)

Companion to [`framework-plumbing.md`](framework-plumbing.md), which holds the rules and the
owner rulings. Java 25 first (ruled 2026-10-08). Each capability is specified in
[`contracts/`](contracts/) before it is built; the code is a port of that text.

## Milestones

| # | Scope | Depends on | Status |
|---|---|---|---|
| M1 | **Foundation and locks:** modules and the application lifecycle, named instances and the driver registry, `Context`, config (declarations, layered local sources, secrets), database connections and migrations, Redis connections, the lock capability with memory, database and Redis drivers and a shared contract test suite | — | Done (2026-10-08) |
| M2a | **Write path:** use cases (validate, authorize, execute, plan), the runner and unit of work, optimistic locking with a per-use-case conflict policy, client scoping, events and audit rows into the SDK-compatible outbox, TSIDs — see [`contracts/write-path.md`](contracts/write-path.md) | M1 | Done (2026-10-08) |
| M2b | **Checked SQL:** queries in `.sql` files, a generator that checks them against the migrated schema and produces typed Java functions and result records, committed output with a check mode — see [`contracts/checked-sql.md`](contracts/checked-sql.md) | M2a | Done (2026-10-08) |
| M3 | **HTTP:** the server on virtual threads, routing declared by modules, the one middleware pipeline shared with jobs, typed request validation, errors mapped to responses; the Datastar adapter | M2 | Done 2026-10-08 (contracts/http.md) |
| M4a | **FlowCatalyst module, client side:** the platform client as the application's service account (wrapping the Java SDK), definition sync at deploy time (event types, roles and permissions including the framework's own `admin:*`, subscriptions, connections, scheduled jobs), a health check on the outbox backlog (delivery is the platform's outbox processor, owner ruling 2026-10-09) | M3, Java SDK, platform batch-ingest permissions (done 2026-10-09) | Done 2026-10-09 (client, sync, Command, `outbox` backlog health check); the generated outbox docs page is not built |
| M4b | **Receivers:** HTTP endpoints for dispatch jobs and scheduled-job firings: signature checks, ack / defer / reject, a handler failure or timeout is a transient error (owner ruling 2026-10-09), locks | M4a | Built 2026-10-09 (contract 4a, follow-up rulings) |
| M4c | **Sessions and login** (owner rulings 2026-10-13: one language-neutral, JSON-first contract; the application is its own backend-for-frontend; no framework-rendered login pages, so no dependency on M-UI; behaviour in code, only connections and secrets in configuration). Contract `contracts/sessions-and-login.md`: `/auth/login`, `/auth/callback`, `/auth/session`, `/auth/logout`; the OIDC relying party (PKCE S256, `state`, `nonce`, discovery, JWKS, RS256-only id_token checks); platform bearer tokens; `__Host-fw_session` with `database` (default), `redis`, `cookie` stores; the platform's F16 CSRF rules and login-state binding; the real `ContextResolver` with client selection and permission mapping in code; `Context.permissions`. Guide deliverable: the sessions-and-login page and the migration page from the first application's `flowcatalyst-token` library | M3, M4a | **Built** 2026-10-13 (`framework-auth`, `framework-auth-database`, `framework-auth-redis`; nothing published). Rulings taken in the contract's section 12 for the owner to confirm or reverse: no platform tokens in the session and no refresh; the JDK HTTP client, not `framework-httpclient`; `/auth/session` answers 200; http only in local and test; CSRF on logout; no session permissions until roles are mapped; `Context` gains `permissions` |
| M-UI | **`framework-ui`** (guide deliverable: a page on JTE templates, the component set and Datastar interactions, with a runnable example app whose pages ARE the examples): JTE templates (precompiled, no reflection) for the `ui-kit/` components, and a view response helper that returns a full page or a Datastar element patch from one template | M3 | Not started |
| M4d | **Admin skeleton** (guide deliverable: a page on the built-in `/admin` area: what ships, permissions and roles, how to add and replace pages): the `admin` module mounted under `/admin` (shell, navigation, permission gating, `admin:*` permissions and roles declared and synced); routes replaceable by an app; start-up fails if an admin route has no required permission | M4c, M-UI (open owner decision: JTE + Datastar or the shared Nuxt UI SPA package) | Not started |
| M4e | **Admin pages, one slice each:** users and invitations, event types, config and secret status, then scoped events and dispatch jobs (needs a scoped read endpoint on the platform and read methods in the Java SDK) | M4d | Not started |
| M4f | **Trial business service** on these contracts, with a Datastar front end | M4e | Not started |
| MA | **Usability gaps raised by the first application (2026-10-09), in order:** (1) a `framework-bom` and published artifacts (GitHub Packages first; the application had to import `framework-parent` and copy its compiler setup); (2) test overrides: `AppBuilder.override(Class, instance)` allowed only in test mode so a service can be replaced without leaving the App; (3) background work and metrics: a managed `Background` primitive (named loop on a virtual thread, lifecycle-owned, optionally lock-gated, stops within the drain, health-visible) and a `Metrics` capability (counters, gauges, timers with a no-op and an in-memory driver, Prometheus text endpoint); (4) dispatch-job rows in the framework's outbox writer (the platform's outbox processor already delivers `DISPATCH_JOB` rows to `/api/dispatch-jobs/batch`); (5) a `SecretResolver` for per-client credentials an application references (SFTP and similar), with env, file and platform drivers; (6) an HTTP client library and framework adapter (blocking API on virtual threads over Vert.x, a client per consumed service, auth strategies with token caching and refresh, JSON mapping, timeouts, retries, Context propagation; wanted before M4c because login uses it); (7) informational health checks: a per-check flag so a check such as `flowcatalyst` or `outbox` is reported but does not make `/health/ready` answer 503 (today a platform outage that outlasts the cached token marks every replica not ready; raised by the first application's review, 2026-10-09); (8) split `UseCaseError` and `ErrorKind` out of `framework-usecase` (or move `HttpErrors.of(UseCaseError)` to the use case side) so `framework-http` depends on `framework-core` only and an HTTP-only application no longer gets `framework-database`, HikariCP and the PostgreSQL driver; (9) rate limiting for HTTP routes (a middleware, in-memory for one process and Redis-backed across replicas); (10) rate limits per request, changing at run time (each ingestion's limit from a control plane), and, by owner ruling 2026-10-13, limiters built in code rather than declared in configuration | M3 | Built 2026-10-10 (all nine; nothing published): (7) built 2026-10-10 (core contract section 1, criticality); (8) built 2026-10-10 as a core `ServiceError` interface (http contract section 1); (2) built 2026-10-10 (core contract section 1, test overrides; test mode = `APP_ENV=test`); (1) built 2026-10-10 (`contracts/build.md`; publishing ready, nothing published); (4) built 2026-10-10 (write-path contract section 7a); (3) built 2026-10-10 (`contracts/background-and-metrics.md`); (5) built 2026-10-10 for `file` and `env`, `platform` driver blocked on the platform (`contracts/secrets.md`); (6) built 2026-10-10 (`contracts/http-client.md`); (9) built 2026-10-10 (`contracts/rate-limiting.md`); (10) built 2026-10-13 (same contract, sections 1 and 3a; removes the `rate-limits` configuration, breaking) |
| M5 | **Cache and storage;** the platform config driver; metering and messaging | M2; platform config and metering features | Not started |

**Rust implementation (design only, 2026-10-11):** [`rust/design.md`](rust/design.md) with
[`rust/capabilities.md`](rust/capabilities.md), [`rust/orders-showcase.md`](rust/orders-showcase.md)
and [`rust/milestones.md`](rust/milestones.md). A `rust/` workspace in this repository honouring the
same wire and storage contracts, milestones R0 to R8 mirroring the order above, conformance vectors
shared with the Java tests. Nothing is built; it waits on the owner decisions listed in
`rust/design.md` section 6 (the first: whether to start before a second Java service has used the
contracts, plumbing section 10 step 6).

**TypeScript implementation (design only, 2026-10-13):** [`typescript/design.md`](typescript/design.md)
with [`typescript/capabilities.md`](typescript/capabilities.md),
[`typescript/orders-showcase.md`](typescript/orders-showcase.md),
[`typescript/spike.md`](typescript/spike.md) and [`typescript/milestones.md`](typescript/milestones.md).
The owner intends TypeScript as the default for new business applications; a `ts/` workspace in this
repository honouring the same wire and storage contracts, milestones T0 to T9, conformance vectors
shared with the Java tests. Nothing is built; the T0 spike (approved) informs decision 0, TypeScript or
Go, and the other decisions in `typescript/design.md` section 8.

**Parked (owner, 2026-10-13):** the Rust and TypeScript implementations; nothing more is built or
designed for them until the owner resumes them.

**Go implementation (design only, 2026-10-13):** owner decision 2026-10-13 to build a Go framework
alongside the Java one; later a part of a business application is built on both and compared.
[`go/gap-analysis.md`](go/gap-analysis.md) (every Java capability against the Go SDK `pkg/fcsdk`
and the Go platform), [`go/design.md`](go/design.md), [`go/capabilities.md`](go/capabilities.md),
[`go/orders-showcase.md`](go/orders-showcase.md) and [`go/milestones.md`](go/milestones.md). A `go/`
directory in this repository with three Go modules (core, the FlowCatalyst module, the UI kit shared
with the platform), milestones G0 to G7 in the Java order, conformance vectors shared with the Java
tests. Nothing is built; it waits on the owner decisions in `go/design.md` section 9.

Order changed 2026-10-08: HTTP comes before the FlowCatalyst module, because login and both job
receivers are HTTP endpoints. The survey behind M4 (what the SDKs and the platform provide today)
found the Java SDK has no login, token validation or job receivers; those are new framework code.

Platform work the framework needs, to be built in the platform (Go first):

- ~~the application's service account cannot deliver its own outbox~~ Done 2026-10-09: the
  `application-service` role carries the three batch-ingest permissions and the batch endpoints
  confine an application-scoped caller to its own applications (Go `9c9986a`, Java `14a67de2`,
  Rust `14a1ec9f`; audit-batch permission enforcement in progress);
- a scoped read endpoint for an application's own events and dispatch jobs, and read methods for
  them in the Java SDK (M4e);
- optimistic locking on the records administrators edit;
- the config and secret store, keyed and access-controlled by client and
  `{application_code}:{sub_domain}` (framework-plumbing.md section 6); including an endpoint that
  returns a per-client secret to the application's own service account (MA-5's `platform` driver is
  blocked on it), and closing the gap that the platform-config read returns SECRET values unmasked
  to every anchor-tier caller, application service accounts included (`contracts/secrets.md`
  section 5);
- metering and outbound message tracking (M5).

## M1 in detail

### Repository layout

Maven multi-module build at the repository root, beside `docs/` and `ui-kit/`:

| Module | Contents |
|---|---|
| `framework-core` | `Module`, `AppBuilder`, `App`, typed service lookup, `Drivers` and named instances, `Context` and its id types, config (declarations, sources, `Secret`), health checks, shutdown |
| `framework-database` | named database instances (HikariCP, PostgreSQL JDBC), migrations contributed per module |
| `framework-redis` | named Redis connections, shared later by cache and rate limiting |
| `framework-locks` | the lock contract, the memory driver, `withLock`, and the contract test suite published as a test-jar |
| `framework-locks-database` | the database (PostgreSQL) lock driver and its migration |
| `framework-locks-redis` | the Redis lock driver |

Group id `io.flowcatalyst.framework`, package root `io.flowcatalyst.framework`. Both are
placeholders until naming is settled (open question 6). Licence Apache-2.0, as the platform.

### Java house rules

These hold in every module. They exist to give one way of doing each thing.

- **Values are records.** No Lombok, no mutable beans.
- **Expected outcomes are sealed result types** handled with an exhaustive `switch`. Exceptions
  are for programming errors and infrastructure failure, and each contract names the ones it
  throws.
- **Nullness is checked at build time.** Every package is `@NullMarked` (JSpecify) and the build
  runs Error Prone with NullAway as an error. `Optional` is for return values only, never fields
  or parameters.
- **No reflection, no annotation scanning, no classpath scanning** for wiring. Modules are listed
  in code.
- **Blocking code on virtual threads.** No reactive APIs in public contracts.
- **Time comes from an injected `InstantSource`** (and a monotonic nanosecond source where
  elapsed time matters), never from `Instant.now()` in library code, so the fakes can control it.
- **Logging through the SLF4J API only.** Library modules bring no logging backend.

### Tests

- JUnit 5 and AssertJ.
- Real PostgreSQL 18 and Redis through Testcontainers, one container per test class.
- Every driver of a capability runs the same contract suite from the capability's test-jar. A
  driver is done when it passes it.

### Order of work

1. Build skeleton: parent POM, Error Prone and NullAway, the modules empty, `mvn verify` green.
2. `framework-core`: modules and lifecycle, service lookup, drivers and named instances,
   `Context`, config, health, shutdown.
3. `framework-database`: instances and migrations.
4. `framework-redis`: connections.
5. `framework-locks`: contract types, memory driver, `withLock`, contract suite.
6. `framework-locks-database`, then `framework-locks-redis`.
7. An example application under `examples/locks-demo` that starts with all three lock instances
   configured, so the start-up path is exercised end to end.

One commit per step, each with `mvn verify` passing.

## Documentation site (ruling 2026-10-09)

The guide's Pages site is styled with the UI kit's theme (the same tokens, light and dark, app
shell and sidebar as the admin pages) so documentation and product look alike. From M-UI on,
the guide's examples are the example application's real pages: each page of the guide for
login, admin and UI shows the markup and the Datastar interaction that produce a screen in that
application, and `docs/guide/verify.sh` keeps the types it names real. Later, the site itself may
be served by a framework application using the admin shell.
