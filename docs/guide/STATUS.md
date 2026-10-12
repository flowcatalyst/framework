<!-- types: AppBuilder, ContextResolver, OutboxWriter, FlowCatalystModule, JettyHttpModule, Secret, LockStore, Routes -->
# Status

Last verified against commit 0598015 (2026-10-12). `verify.sh` fails if that commit is not an
ancestor of `HEAD`; update this line, and the pages it affects, in the same commit as any change to
public API or behaviour.

The framework is **pre-release**. The rows marked **Built** are covered by tests in this
repository; nothing here has been used in production. The plan, with owner rulings, is in
[`implementation-plan.md`](../implementation-plan.md); the rules in
[`framework-plumbing.md`](../framework-plumbing.md).

## Milestones

| # | Scope | State | Guide page |
|---|---|---|---|
| M1 | Foundation and locks: modules and lifecycle, named instances and drivers, `Context`, config and secrets, database and Redis connections, migrations, locks (memory, database, Redis) | **Built** | [lifecycle](modules-and-lifecycle.md), [config](config.md), [context](context.md), [locks](locks.md) |
| M2a | Write path: use cases, runner, optimistic locking, client scoping, events and audit rows into the SDK-compatible outbox, TSIDs | **Built** | [write path](write-path.md) |
| M2b | Checked SQL: queries in `.sql` files, generator, Maven plugin with `generate`, `check`, `add-source` | **Built** | [checked SQL](checked-sql.md) |
| M3 | HTTP: Jetty 12 on virtual threads, routing, middleware, typed validation, error envelope, correlation id, health endpoints, graceful shutdown, Datastar adapter | **Built** | [http](http.md) |
| M4a | FlowCatalyst module, client side | **Built**, except the generated docs page for the platform's outbox processor. `PlatformClient`, the `flowcatalyst` and `outbox` health checks, definitions, validation, `DefinitionSync` and the `sync` command, `Command` and `launch` in core | [flowcatalyst module](flowcatalyst-module.md) |
| M4b | Receivers: dispatch-job and scheduled-job endpoints, signature checks, ack / defer / reject, the overlap lock | **Built.** `Receivers`, `DispatchHandler` and `DispatchResult`, `DispatchDelivery.once` with its retention purge, `ScheduledJobHandler`, `JobOptions`, `JobResult` and `ScheduledJobFiring`, per-receiver signing secrets; a receiver without a handler answers 404, and `flowcatalyst.receivers.enabled: false` removes both routes | [receivers](receivers.md) |
| M4c | Sessions and login: session capability, OIDC relying-party flow, the real `ContextResolver`, login pages | Not started (depends on M-UI) | |
| M-UI | `framework-ui`: JTE templates for the `ui-kit/` components, view response helper | Not started | |
| M4d | Admin skeleton under `/admin` with permission gating | Not started | |
| M4e | Admin pages: users, invitations, event types, config, scoped events and dispatch jobs | Not started | |
| M4f | Trial business service on these contracts | Not started | |
| MA | Usability gaps raised by the first application | **Built** (2026-10-10), nothing published. Item 9 (rate limiting: `RateLimit` middleware, `memory` and `redis` limiters, key resolvers; [guide](rate-limiting.md)); item 6 (`framework-httpclient` on Vert.x and `HttpClientModule`: a client per service, auth with token caching, idempotent-only retries, `Context` propagation, metrics; [guide](http-client.md)); item 5 (`SecretResolver` with `file` and `env` drivers, cache, threat model; `platform` driver blocked on the platform; [guide](secrets.md)); item 3 (`Background` loops with health and an optional `LockGate`; `Metrics` with memory and noop drivers, built-in HTTP, use-case and loop metrics, a Prometheus endpoint off by default; [guide](background-and-metrics.md)); item 4 (`DispatchJob`, `Plan.andDispatch`: `DISPATCH_JOB` outbox rows in the platform's batch-item shape; [write path](write-path.md#dispatch-jobs)); item 1 (`framework-bom`, publishing configuration and workflow, [contract](../contracts/build.md); nothing published); item 2 (`AppBuilder.override(Class, instance)`, only when `APP_ENV` is `test`; [testing](testing.md#replacing-a-service)); item 8 (`framework-http` depends on `framework-core` only, enforced by a Maven Enforcer rule; `HttpErrors.of(ServiceError)`, which `UseCaseError` implements; [migration](http.md#what-framework-http-pulls-in)); item 7 (informational health checks: `Criticality`, `app.health.critical` / `app.health.informational`; `flowcatalyst` and `outbox` informational by default; `/health/ready` answers `DEGRADED` with 200) | [lifecycle](modules-and-lifecycle.md#critical-and-informational-checks) |
| M5 | Cache and storage, platform config driver, metering, messaging | Not started | |

## The Rust implementation

A second implementation of the contracts, in `rust/` ([overview](rust/index.md), design in
[`docs/rust/`](../rust/design.md)). It shares the contracts, `ui-kit/` and the conformance vectors
in `conformance/`, which the Java tests write (`-Dconformance.update=true`) and the Rust tests
compare. Nothing is published.

| # | Scope | State | Guide page |
|---|---|---|---|
| R0 | Workspace: toolchain pin, lints, `deny.toml`, CI workflow, `fw-test` (PostgreSQL 18 and Redis, a database per test), the conformance runner, the Java vector helper (`Conformance` in the `framework-core` test-jar), the TSID vector | **Built** | [Rust overview](rust/index.md) |
| R1 | Foundation and locks: `fw-core` (lifecycle, modules and handles, config with instance sections checked at configure, `Context` and ids, TSIDs, health with criticality, background loops, metrics and the Prometheus writer, commands), `fw-db` (pools, the migration runner on `fw_schema_history`), `fw-redis`, `fw-locks` (memory, database, redis; the contract suite; the lock gate), `examples/locks-demo`; vectors for config, the lock SQL and Lua, and the `locks-database` migration history | **Built** | [application and config](rust/core.md), [database and Redis](rust/database.md), [locks](rust/locks.md) |
| R2 | Write path and checked SQL | Not started | |

## Known gaps

These were raised by the first application built on the framework. Each has a plan item (the `MA`
row of `implementation-plan.md`) or a milestone.

| Gap | What it means today | Plan |
|---|---|---|
| **Nothing published yet** | `framework-bom` exists and is all an application imports (no framework parent, no Error Prone setup); the GitHub Packages configuration and the tag-triggered publish workflow are ready, but no version has been tagged. Until then build the repo with `./mvnw install`. See [Getting started](getting-started.md#3-getting-the-artifacts-from-github-packages) | first `v*` tag (owner) |
| **No authentication, login or sessions** | `Request.context()` needs a `ContextResolver` you write. No login, no token validation, no permission model for `authorize` steps | M4c (needs M-UI); admin and permissions M4d |
| **No UI module** | HTML is hand-built strings; Datastar works at the wire level only | M-UI |
| **Dispatch jobs cannot name a client by identifier** | `plan.andDispatch(DispatchJob)` writes `DISPATCH_JOB` outbox rows, but the platform's dispatch-jobs batch stores `clientId` unresolved, so a job is platform-scoped unless you pass the platform's own client id (`platformClientId`). See [Dispatch jobs](write-path.md#dispatch-jobs) | platform work: resolve `clientCode` in `/api/dispatch-jobs/batch` |
| **Per-client secrets from files and environment only** | `SecretResolver` resolves a client's secret reference through `file` and `env` stores; the `platform` driver fails start-up because the platform has no endpoint for it. The platform-config read endpoint also returns SECRET values unmasked to any anchor-tier caller, which includes every application's service account (a platform gap, reported). See [Per-client secrets](secrets.md) | platform work ([contract](../contracts/secrets.md), section 4); M5 (platform config and secret store) |

## Other things to know

- **Receivers are at-least-once**: a dispatch handler can run twice for one job. Make it idempotent
  (`DispatchDelivery.once` helps); see [Receivers](receivers.md#at-least-once-the-rule).
- **The framework does not deliver the outbox.** The platform's outbox processor does. Without a
  platform, rows accumulate and nothing consumes them; the `outbox` health check goes down when the
  oldest undelivered row is older than `flowcatalyst.outbox.stale-after`.
- **PostgreSQL only**, but several named database instances may be configured. Only use cases, the
  outbox and migrations are limited to the **default** instance; other instances can be queried
  with `Jdbc`. Redis is standalone only (no Cluster or Sentinel); the Redis lock driver is not
  Redlock.
- **No external config stores**, no hot reload: config is read once at start-up.
- **Docs sync is skipped**: `Definitions.docs(...)` is accepted but the Java SDK cannot sync docs.
- **The FlowCatalyst Java SDK dependency** (`io.flowcatalyst:flowcatalyst-sdk:0.0.11`) is **not
  published to any remote repository** that was found; it must be built from the tag `java-sdk/v0.0.11`
  of `github.com/flowcatalyst/flowcatalyst` into the local repository first. Exact commands in
  [Getting started](getting-started.md#1-install-the-framework-into-your-local-repository).
- **No TLS, HTTP/2, CORS, WebSockets, multipart or compression** in the HTTP layer.
- **Checked SQL needs Docker** (or an empty database) to generate and, as the example binds it, to
  check; `-Dfw.sql.skip=true` also disables `add-source`.
- **Definition validation is stricter than the platform's** (lowercase segments, six-field cron);
  the owner may relax it.
- `docs/handover.md` is the short session hand-off note (what is done, what is next, rulings). It
  is rewritten at hand-off, so it can lag the code between hand-offs; this page and the plan are the
  record.

## Where the guide and the contracts can differ

The [contracts](reference/contracts.md) are the specification and are written before the code, but
they can lag. When a page here describes behaviour different from a contract, the page was checked
against the code. Known differences at the last verification:

- `contracts/http.md` section 10 shows `Datastar.stream(request)` returning a generator; the code is
  `Datastar.stream(Request, Datastar.Events)` (a callback). The contract also omits
  `Response.html(...)`, which exists.
- `contracts/checked-sql.md` section 4 says `check` is bound to `verify` "only when the project
  enables it"; the plugin has no such switch, and the example binds it unconditionally.
