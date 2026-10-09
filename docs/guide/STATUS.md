<!-- types: AppBuilder, ContextResolver, OutboxWriter, FlowCatalystModule, JettyHttpModule, Secret, LockStore, Routes -->
# Status

Last verified against commit 0bd84d8 (2026-10-09). `verify.sh` fails if that commit is not an
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
| MA | Usability gaps raised by the first application (below) | Not started | |
| M5 | Cache and storage, platform config driver, metering, messaging | Not started | |

## Known gaps

These were raised by the first application built on the framework. Each has a plan item (the `MA`
row of `implementation-plan.md`) or a milestone.

| Gap | What it means today | Plan |
|---|---|---|
| **No published artifacts, no BOM** | Build the repo with `./mvnw install`; import `framework-parent` as a BOM; copy the Error Prone / NullAway setup and `.mvn/jvm.config`. See [Getting started](getting-started.md) | MA-1: a `framework-bom` and published artifacts (GitHub Packages first) |
| **No authentication, login or sessions** | `Request.context()` needs a `ContextResolver` you write. No login, no token validation, no permission model for `authorize` steps | M4c (needs M-UI); admin and permissions M4d |
| **No UI module** | HTML is hand-built strings; Datastar works at the wire level only | M-UI |
| **Outbox writer: events and audit rows only** | No dispatch-job rows, and no direct framework call that creates a dispatch job either. The platform's outbox processor already delivers `DISPATCH_JOB` rows, but the framework cannot write them; the platform's `POST /api/dispatch-jobs/batch` (permission `platform:messaging:batch:dispatch-jobs-write`) can be called by hand, see [Creating a dispatch job](flowcatalyst-module.md#creating-a-dispatch-job) | MA-4 |
| **No HTTP client library** | Calling a platform API (or any service) means taking `PlatformClient.accessToken()` and using `java.net.http` yourself; see [Calling a platform API yourself](flowcatalyst-module.md#calling-a-platform-api-yourself) | MA item 6 (blocking API on virtual threads, auth strategies, retries, Context propagation) |
| **No rate limiting** | Nothing limits request rates on the HTTP layer; put a limiter in front or write a middleware | MA item 9 |
| **Health checks cannot be excluded from readiness** | A platform outage that outlasts the cached token marks every replica not ready (the `flowcatalyst` check is `Down`); only `/health/live` is unaffected. See [the module page](flowcatalyst-module.md) | MA item 7 |
| **`framework-http` depends on `framework-usecase`** | An HTTP-only application also gets `framework-database`, HikariCP and the PostgreSQL driver on its classpath, because `HttpErrors.of(UseCaseError)` needs the use case error types | MA item 8 |
| **No secret store** | Secrets come from the environment, or `config/secrets.local.yaml` in `local` and `test` only. No secret manager, no per-client credentials | MA-5 (`SecretResolver` with env, file and platform drivers); M5 (platform config and secret store) |
| **No background-work or metrics primitive** | Start your own threads in `boot` and stop them in `shutdown`; no managed loop, no counters or timers, no Prometheus endpoint | MA-3: `Background` and `Metrics` |
| **One provider per service type; services cannot be replaced in tests** | A test cannot swap a service without changing the module. See [Testing](testing.md#the-known-limitation-services-cannot-be-replaced) | MA-2: `AppBuilder.override(Class, instance)`, test mode only |

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
- **No TLS, HTTP/2, CORS, WebSockets, multipart, rate limiting or compression** in the HTTP layer
  (rate limiting has a row in the known gaps above).
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
