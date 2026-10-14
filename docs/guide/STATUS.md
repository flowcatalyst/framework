<!-- types: AppBuilder, HttpOptions, ContextResolver, OutboxWriter, FlowCatalystModule, JettyHttpModule, Secret, LockStore, Routes, Spa, Csp, SpaTesting, Operation, OpenApi, OpenApiTesting, DeclarationCommand -->
# Status

Last verified against commit 6ff956a (2026-10-14). `verify.sh` fails if that commit is not an
ancestor of `HEAD`; update this line, and the pages it affects, in the same commit as any change to
public API or behaviour.

The framework is **pre-release**. The rows marked **Built** are covered by tests in this
repository; nothing here has been u in production. The plan, with owner rulings, is in
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
| M4b | Receivers: dispatch-job and scheduled-job endpoints, signature checks, ack / defer / reject, the overlap lock | **Built.** `Receivers`, `DispatchHandler` and `DispatchResult`, `DispatchDelivery.once` with its retention purge, `ScheduledJobHandler`, `JobOptions`, `JobResult` and `ScheduledJobFiring`, per-receiver signing secrets; a receiver without a handler answers 404, and `flowcatalyst.receivers.enabled: false` (since 2026-10-14 `ReceiverOptions.disabled()`) removes both routes. 2026-10-14 (owner ruling 2026-10-12): every handler runs as the application's service principal (`PrincipalKind.SERVICE`, the `sub` of the client-credentials token, `PlatformClient.servicePrincipal()`) with the delivered job or instance as the causation id; without that principal nothing runs (`503`, and the `flowcatalyst` check is down); vector `conformance/receivers/context.json` | [receivers](receivers.md) |
| M4c | Sessions and login, written once for every language ([contract](../contracts/sessions-and-login.md), JSON-first): the application as its own backend-for-frontend (`/auth/login`, `/auth/callback`, `/auth/session`, `/auth/logout`), an OIDC relying party of the platform (PKCE S256, `state`, `nonce`, RS256-only id_token checks, JWKS cache), platform bearer tokens (`PlatformTokens`, usable on its own), `__Host-fw_session` with `database` (default), `redis`, `cookie` and `memory` stores, idle and absolute expiry, rotation, revocation, the platform's F16 CSRF rules and login-state binding, `401` versus `302`, client selection and permission mapping in code, `Context.permissions`, `AuthTesting` and `TestOidcProvider` | **Built** (2026-10-13), nothing published. 2026-10-14: `Context` carries the principal kind (`PrincipalKind`, `USER` or `SERVICE`, [context](context.md#the-principal-kind)), set by the resolver from `type`; `PrincipalKind` moved from `io.flowcatalyst.framework.auth` to `io.flowcatalyst.framework.context` (**breaking** for that import). Not built, by design: login pages (the platform hosts login; no dependency on M-UI), refresh (sessions keep no platform token), portal logins | [sessions and login](sessions-and-login.md), [migrating from flowcatalyst-token](migrating-from-flowcatalyst-token.md) |
| MS | Static assets and the SPA fallback ([contract](../contracts/static-assets-and-spa.md)): one built SPA served by `framework-http` as the router's fallback, API prefixes answering the JSON 404, missing assets 404, cache headers and ETags, security headers | **Built** (2026-10-14), nothing published. `Spa` (classpath or directory, index built at start-up, limits, not-built rule by `APP_ENV`), `Csp` (baseline default today; the contract now makes strict the default after U0, and the `Spa` default is still to flip), `Routes.spa` and `new Routes(environment)`, `SpaTesting`; API prefixes derived from the routes plus `api`, `auth` and code; `nosniff` on every framework response; `HEAD` keeps the `GET` body's `Content-Length` on every route; the `conformance/http/spa.json` vector. Not verified in a browser; Jetty refuses most unclean paths with `400` before the framework's `404` | [static assets and the SPA](static-assets-and-spa.md) |
| MO | Typed operations and the OpenAPI 3.1 document ([contract](../contracts/openapi.md)): operations declared in code, schemas from records, the `openapi` command without a database, the generated TypeScript client and its drift checks | **Built** (2026-10-14), nothing published. `Operation` values (typed `PathParam`/`QueryParam`, the body's `Validator`, permission, declared errors) declared with `OpenApi.declare` in `register` and bound with `Routes.bind` in `boot`; handlers return the sealed `Outcome`; `Json.operationMapper()` (nulls left out, `BigDecimal` as a string); the emitter (records, enums, sealed interfaces with `@JsonTypeInfo`, generics, `@Nullable` as "may be absent", closed schemas, constraints from `Rule.describe()`, unknown Jackson annotations refused, the shared `Error` envelope, implied statuses, security schemes from the resolving module, deterministic bytes); the `openapi` declaration-phase command (claimed by `JettyHttpModule`; core's `DeclarationCommand`); `OpenApiTesting.assertCommitted`; `getAuthSession` and `logout` as operations (`endSessionUrl` omitted when unknown); `examples/orders-demo` serves its orders API as operations and commits `web/openapi.json`; vectors `conformance/openapi/` (`fixture.json`, `auth.json`, `orders-demo.json`). Documents validated against the OpenAPI 3.1 schema in the build. Not built: the TypeScript side (generation, drift check, Playwright run), which is MU's U3 | [typed operations and OpenAPI](openapi.md) |
| MU | The shared UI package: Nuxt UI v4 on Vue and Vite in strict TypeScript, the primary UI of the Java and Go frameworks (owner ruling 2026-10-14; design in [`go/ui.md`](../go/ui.md)); steps U0 to U4 | **U0, U1, U2 built** (2026-10-14; results in [`ui/u0.md`](../ui/u0.md), [`ui/u1.md`](../ui/u1.md), [`ui/u2.md`](../ui/u2.md); commits 48a545b, bbed2bb, 9b712b7). U0: the pnpm workspace rooted at `ui/`, strict TypeScript and lint, Nuxt UI without Nuxt, the kit theme, bundled icons, a gallery under the strict CSP with axe. U1: every ui-kit spec has a gallery entry (37 covered, 11 composed, 11 built, 1 not ported; checked against `ui-kit/components`): `FwAlertDialog`, `FwCodeBlock`, `FwCursorPager`, `FwDetailList`/`FwDetailField`, `FwFormSection`, `FwJsonBlock`, `FwLocalTime`, `FwPageHeader`, `FwSpinner`, `FwTableToolbar`, `FwToggleGroup`, `FwTag`/`FwStatusBadge`, `FwCodeChips`, `FwRowActions`, `FwPaginator`, `FwPickList`, `FwEmptyState`, `useConfirm`, `useDirtyForm`, `useFlash` and `FwAnnouncer`. U2: `FwAppShell`, `FwListPage`, `FwRecordDrawer` + `useDrawerRoute`, `FwFormDialog`, `useListState`, `useCursorPagination`, `useLocalState`, `useSession`/`usePermissions`; `@flowcatalyst/api-runtime` (configured client: same origin, cookie only, no CSRF header, 401 to sign-in, failure toasts; session read at start and on focus; route guard; `ServiceError` and field errors; Zod `jitless`; generated client of `conformance/openapi/auth.json`; `fwOpenApiConfig` for any application, with `baseUrl: false` and int64 kept a number). All under the strict CSP with zero violations; axe clean except exact kit-token colour pairs (light 600 shades, dark destructive). Not verified: no backend was called (the gallery uses a generated orders client against an in-page fake server; U3 is next, on the Java orders demo), Firefox/WebKit, screenshots not approved. Open: owner decisions on the kit tokens, the 12.6 px text scale and the screenshots (`ui/u1.md`); contract section 11 needs the two generation settings (`ui/u2.md`); licences and TypeScript 7 as in `handover.md` | |
| MC | Migration guide for the first application's control plane onto typed operations and a `web/` SPA | Not started | |
| M-UI | `framework-ui`: JTE templates for the `ui-kit/` components, view response helper | **Optional, deferred** (owner ruling 2026-10-14: an optional module for server-rendered pages without a JavaScript build; the framework's UI is the shared Nuxt UI package, MU) | |
| M4d | Admin skeleton: typed admin operations with permission gating, and the admin shell as shared `@flowcatalyst/ui` pages | Not started | |
| M4e | Admin pages as shared `@flowcatalyst/ui` pages: users, invitations, event types, config, scoped events and dispatch jobs | Not started | |
| M4f | Trial business service on these contracts | Not started | |
| MA | Usability gaps rai by the first application | **Built** (2026-10-10; item 10 2026-10-13), nothing published. Item 10 (rate limiters built in code, `MemoryRateLimiter.create` and `RedisRateLimiter.create`, replacing the `rate-limits` configuration, `RateLimiters`, `RateLimitModule` and `RateLimitRedisModule` (**breaking**); a `PolicyResolver` on the middleware for a policy per request that may change at run time; [guide](rate-limiting.md#a-policy-per-request)); item 9 (rate limiting: `RateLimit` middleware, `memory` and `redis` limiters, key resolvers; [guide](rate-limiting.md)); item 6 (`framework-httpclient` on Vert.x and `HttpClientModule`: a client per service, auth with token caching, idempotent-only retries, `Context` propagation, metrics; [guide](http-client.md)); item 5 (`SecretResolver` with `file` and `env` drivers, cache, threat model; `platform` driver blocked on the platform; [guide](secrets.md)); item 3 (`Background` loops with health and an optional `LockGate`; `Metrics` with memory and noop drivers, built-in HTTP, use-case and loop metrics, a Prometheus endpoint off by default; [guide](background-and-metrics.md)); item 4 (`DispatchJob`, `Plan.andDispatch`: `DISPATCH_JOB` outbox rows in the platform's batch-item shape; [write path](write-path.md#dispatch-jobs)); item 1 (`framework-bom`, publishing configuration and workflow, [contract](../contracts/build.md); nothing published); item 2 (`AppBuilder.override(Class, instance)`, only when `APP_ENV` is `test`; [testing](testing.md#replacing-a-service)); item 8 (`framework-http` depends on `framework-core` only, enforced by a Maven Enforcer rule; `HttpErrors.of(ServiceError)`, which `UseCaseError` implements; [migration](http.md#what-framework-http-pulls-in)); item 7 (informational health checks: `Criticality`, overrides by name, since 2026-10-14 `HealthPolicy` in code (they were `app.health.critical` / `app.health.informational`); `flowcatalyst` and `outbox` informational by default; `/health/ready` answers `DEGRADED` with 200) | [lifecycle](modules-and-lifecycle.md#critical-and-informational-checks) |
| CC | Configuration holds connections and secrets; behaviour is code (owner ruling 2026-10-14, [contract](../contracts/config-and-code.md)); **breaking** | **In progress.** Built: removed keys fail start-up naming where they went (`KeyRules`, in files and the environment), claimed components (`AppBuilder.claimComponent`), the core's `shutdownDrain(Duration)`, `health(HealthPolicy)` and `metrics(MetricsOptions)` replacing `app.*`, each also as a `Function<Config, T>`; the leftover `rate-limits` section now fails; `DatabaseModule.builder()` with `DatabasePool` and `migrate`, `RedisModule.builder()` with `RedisPool` (instance sections hold connections only; `Instances.build` with one `InstanceFactory`); `LocksModule.builder()` with `LockStores.memory()`, `DatabaseLockStores`, `RedisLockStores` (no `locks` configuration; `LocksRedisModule` removed); `new JettyHttpModule(HttpOptions)` (body limit, request timeout, shutdown grace, the metrics endpoint; `http.host`, `http.port` and the bearer token stay); `HttpClientModule.builder()` with clients named in code (`HttpClientOptions`, `ClientAuth`; sections keep `base-url` and credentials); `SecretsModule.builder()` with `SecretStores.env`, `file`, `platform` and `CachePolicy` (no `secrets` configuration); `new FlowCatalystModule(FlowCatalystOptions)` with `ReceiverOptions` (the platform connection, the application code and the receiver secrets stay; the options are a service; `PlatformClient.timeout()`). Every module is moved; the driver registry (`Drivers`, `AppBuilder.drivers()`) is still in core with no framework user | [config](config.md#what-configuration-holds), [migrating](migrating-config-to-code.md) |
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
| R2 | Write path and checked SQL: `fw-usecase` (use cases, the runner with the contract's failure table, conflict retry with jitter, `Fresh`/`Loaded`, repositories, events, the outbox writer and its migrations, audit rows, dispatch jobs), sqlx macros with committed `.sqlx/` and `cargo fw sql prepare|check`, `examples/orders-demo`; vectors for the outbox rows (byte for byte) and the outbox and orders migration history | **Built** | [write path](rust/write-path.md), [checked SQL](rust/checked-sql.md) |

## The Go implementation

A further implementation of the contracts, in `go/` ([overview](go/index.md), design in
[`docs/go/`](../go/design.md): gap analysis against the Go SDK and platform, design, capabilities,
the orders example, the UI, milestones G0 to G7). It shares the contracts and the conformance
vectors in `conformance/`, which the Java tests write and the Go tests compare. Nothing is
published. The Rust and TypeScript implementations are parked (owner, 2026-10-13).

| # | Scope | State | Guide page |
|---|---|---|---|
| G0 | Skeleton: the core module, `go/flowcatalyst`, `go/tools` (pinned golangci-lint, NilAway, gofumpt), the lint profile and `make -C go go-check`, `cmd/fwvet` (`mustliteral`), `fwtest` (embedded PostgreSQL 18, a database per test, Redis from `PATH`, a fake clock), the conformance runner and the TSID vector, the CI workflow (not run), the [Go build contract](../contracts/go-build.md) | **Built** | [Go overview](go/index.md) |
| G1 | Foundation and locks: `fw` (App, `Declarer` and typed handles, `Boot`, `Launch`/`Start`, commands, health with criticality and overrides, shutdown order), `config` (every rule, instance sections with driver keys checked at configure, `Secret`), `background` (loops, gates), `metrics` (registry, Prometheus writer, endpoint handler), `database` (pgxpool instances, the migration runner on `fw_schema_history`, `Tx`, `Filter`), `redisfw`, `locks` with memory, `dblock` and `redislock` (shared SQL and Lua embedded as checked copies) and `lockstest`; `examples/locks-demo`; vectors for config, the lock SQL and Lua and the `locks-database` history pass. Allowed difference: a duplicate YAML key is refused (Java keeps the last; no vector case has one) | **Built** | [application and config](go/core.md), [database and Redis](go/database.md), [locks](go/locks.md) |
| G2 | Write path and checked SQL: `usecase` (use cases as a four-method interface, so a missing `Authorize` does not compile; the runner with the failure table, conflict retry, `Fresh`/`Loaded`, client scoping, events, the outbox writer and migrations, audit rows, dispatch jobs, metrics; `usecasetest.Repository`), `event`, `id`, `sqlfw` (the `fw sql` tool: schema, sqlc generate/vet/diff against the framework-migrated PostgreSQL, go-jet table code; `Query`), `examples/orders-demo`; vectors: the outbox rows byte for byte, the outbox `INSERT`, the outbox and orders migration history; `fw.Context` gained `PrincipalKind` (core contract section 3, 2026-10-14). Known: sqlc keeps an overridden or cast column non-null in an outer join (write it with `coalesce`) | **Built** | [write path](go/write-path.md), [checked SQL](go/checked-sql.md) |

**UI (owner ruling, 2026-10-13):** the Go framework's UI is **Nuxt UI v4 on plain Vue and Vite,
without Nuxt or Nitro, in strict TypeScript**, replacing the Datastar + templ kit for Go
([`go/ui.md`](../go/ui.md)). Consequences recorded in the design: Go does not implement the
Datastar adapter (http contract section 10) or the `ui-kit/fixtures` goldens (the kit's specs and
tokens stay the reference; conformance is a gallery with Playwright, axe, approved screenshots and
a CSP check); the Go HTTP layer adds typed operations that emit OpenAPI 3.1, from which a
TypeScript client and Zod schemas are generated; the built SPA is embedded and served by the
application, which needs an http contract amendment for static assets; sessions and login become a
backend-for-frontend surface, and the M4c contract is recommended to be written once and JSON-first
for all implementations. **Decided 2026-10-14 (owner):** the Java framework shares the same UI
package, which is the primary UI of both (design decision 31); static assets and the OpenAPI document
are shared contracts ([static assets and the SPA](../contracts/static-assets-and-spa.md),
[OpenAPI](../contracts/openapi.md)), built first in Java (MS, MO, MU above); JTE + Datastar (M-UI) is
an optional, deferred Java module. **Owner answers 2026-10-14:** the platform's templ `/ui` port
continues and the Go framework offers both options as Java does (Nuxt UI primary, an optional templ +
Datastar module later); the UI packages are `@flowcatalyst/ui` and `@flowcatalyst/api-runtime`,
linked by path from a checkout of this repository until they are published to GitHub Packages; the
other answers are in [`implementation-plan.md`](../implementation-plan.md) ("Owner answers").

## Known gaps

These were rai by the first application built on the framework. Each has a plan item (the `MA`
row of `implementation-plan.md`) or a milestone.

| Gap | What it means today | Plan |
|---|---|---|
| **Nothing published yet** | `framework-bom` exists and is all an application imports (no framework parent, no Error Prone setup); the GitHub Packages configuration and the tag-triggered publish workflow are ready, but no version has been tagged. Until then build the repo with `./mvnw install`. See [Getting started](getting-started.md#3-getting-the-artifacts-from-github-packages) | first `v*` tag (owner) |
| **Sessions keep no platform token** | A signed-in person's roles and permissions are those of the sign-in: a role change or a deactivation at the platform takes effect at the next sign-in, at most the absolute timeout (default 8 hours) later. No refresh. See [Sessions and login](sessions-and-login.md#sessions-stores-and-timeouts) | a later revalidation option (contract section 6.4 says what it needs) |
| **The platform must be reachable at the issuer URL** | `auth.issuer` is both the browser-facing URL and the one the application calls; a separate internal URL (container networking) is not supported | open |
| **No application UI yet** | A built SPA can be served ([static assets and the SPA](static-assets-and-spa.md)), typed operations write an OpenAPI document ([typed operations and OpenAPI](openapi.md)), and the UI package has its components, partials, shell and runtime (MU U0 to U2), but no example SPA calls a real backend yet; HTML from Java is hand-built strings; Datastar works at the wire level only | MU U3 (the Java orders demo's `web/`) and U4 (the optional JTE module M-UI later, if needed) |
| **Dispatch jobs cannot name a client by identifier** | `plan.andDispatch(DispatchJob)` writes `DISPATCH_JOB` outbox rows, but the platform's dispatch-jobs batch stores `clientId` unresolved, so a job is platform-scoped unless you pass the platform's own client id (`platformClientId`). See [Dispatch jobs](write-path.md#dispatch-jobs) | platform work: resolve `clientCode` in `/api/dispatch-jobs/batch` |
| **Per-client secrets from files and environment only** | `SecretResolver` resolves a client's secret reference through `file` and `env` stores; the `platform` driver fails start-up because the platform has no endpoint for it. The platform-config read endpoint also returns SECRET values unmasked to any anchor-tier caller, which includes every application's service account (a platform gap, reported). See [Per-client secrets](secrets.md) | platform work ([contract](../contracts/secrets.md), section 4); M5 (platform config and secret store) |

## Other things to know

- **Receivers are at-least-once**: a dispatch handler can run twice for one job. Make it idempotent
  (`DispatchDelivery.once` helps); see [Receivers](receivers.md#at-least-once-the-rule).
- **Receiver audit rows carry no cause**: the platform's audit entry has no causation (or
  correlation) field, so the dispatch job id reaches a receiver-run use case's events but not its
  audit row (platform work, flowcatalyst contract section 5 item 6).
- **The framework does not deliver the outbox.** The platform's outbox processor does. Without a
  platform, rows accumulate and nothing consumes them; the `outbox` health check goes down when the
  oldest undelivered row is older than `FlowCatalystOptions.outboxStaleAfter`.
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
- **No TLS, HTTP/2, CORS, WebSockets, multipart or compression** in the HTTP layer. No CORS is
  deliberate for sessions: no credentialed cross-origin request is ever allowed.
- **Sessions over plain http** only when `APP_ENV` is `local` or `test` (start-up fails otherwise).
- **Not verified against a real platform or a real browser.** The login flow is tested against a
  test identity provider over real HTTP, and one test runs it over Jetty with the JDK's client and
  cookie manager; no run against the FlowCatalyst platform or a browser has been made.
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
- The contract amendments of 2026-10-14 for the SPA are built (MO): `contracts/sessions-and-login.md`
  sections 4.4 (`endSessionUrl` omitted) and 4.5, `contracts/core.md` section 5. What the build found
  is recorded in the contracts, marked (MO): in `contracts/openapi.md` notably the `openapi` command is
  claimed by `JettyHttpModule` (the contract said `framework-http`, which has no module), holder
  classes add nothing to component names, explicit `@JsonSubTypes` names, the tighter-bound and
  pattern-over-`notBlank` merge rules, the longer ECMA refusal list (every `\p{...}`), `default` in the
  keyword order. `contracts/openapi.md` section 12's end-to-end check (generated client compiled in the
  UI workspace, Playwright with response validation) is not built: it is MU's U3. (`contracts/http.md` section 4, `nosniff` and the SPA fallback,
  was built with MS.)
- `contracts/static-assets-and-spa.md` section 7 (amended 2026-10-14 after U0) makes the strict CSP the default; `Spa` still defaults to the baseline, strict opt-in. The flip is a follow-up in the Java `Spa` class.
- `contracts/static-assets-and-spa.md` section 4 says an unclean path answers `404`; over Jetty most
  of them never reach the framework: Jetty's URI checks answer `400 VALIDATION` first (recorded in
  the contract's section 4.1 amendment). The in-process `TestClient` and the conformance vector show
  the framework's `404`.
