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
| M4c | **Sessions and login** (owner rulings 2026-10-13: one language-neutral, JSON-first contract; the application is its own backend-for-frontend; no framework-rendered login pages, so no dependency on M-UI; behaviour in code, only connections and secrets in configuration). Contract `contracts/sessions-and-login.md`: `/auth/login`, `/auth/callback`, `/auth/session`, `/auth/logout`; the OIDC relying party (PKCE S256, `state`, `nonce`, discovery, JWKS, RS256-only id_token checks); platform bearer tokens; `__Host-fw_session` with `database` (default), `redis`, `cookie` stores; the platform's F16 CSRF rules and login-state binding; the real `ContextResolver` with client selection and permission mapping in code; `Context.permissions`. Guide deliverable: the sessions-and-login page and the migration page from the first application's `flowcatalyst-token` library | M3, M4a | **Built** 2026-10-13 (`framework-auth`, `framework-auth-database`, `framework-auth-redis`; nothing published). Rulings in the contract's section 12, **RULED by the owner 2026-10-14** (confirmed as written): no platform tokens in the session and no refresh; the JDK HTTP client, not `framework-httpclient`; `/auth/session` answers 200; http only in local and test; CSRF on logout; no session permissions until roles are mapped; `Context` gains `permissions` |
| MS | **Static assets and the SPA fallback** (contract [`contracts/static-assets-and-spa.md`](contracts/static-assets-and-spa.md), written 2026-10-14, mirrors the platform's F16 handler): `framework-http` serves one built SPA from a classpath root or a directory as the router's fallback; API prefixes derived from the routes answer the JSON 404; missing assets 404; `405` for other methods; `index.html` `no-cache` + ETag, hashed assets `immutable`; nosniff, Referrer-Policy, X-Frame-Options and the baseline CSP; Maven copies `web/dist`, never runs Node. Guide deliverable: a page on serving an SPA | M3 | **Built** 2026-10-14 (`Spa`, `Csp`, `Routes.spa`, `SpaTesting` in `framework-http`; `nosniff` on every response; the `conformance/http/spa.json` vector; guide `guide/static-assets-and-spa.md`). Strict CSP stays opt-in until U0 |
| MO | **Typed operations and the OpenAPI document** (contract [`contracts/openapi.md`](contracts/openapi.md), written 2026-10-14, shared with Go): `Operation` values declared in `register` and bound in `boot`, `Outcome<R>` results, permissions on operations; an emitter from records, enums and sealed interfaces with constraints from the `Validator`; the error envelope and implied statuses; security schemes from the resolving module; deterministic OpenAPI 3.1; the declaration-phase `openapi` command (core amendment); `OpenApiTesting.assertCommitted`; `/auth/session` and `/auth/logout` as operations (sessions contract 4.5); the `conformance/openapi/` vectors; the hey-api client generation and drift checks. Guide deliverable: a page on typed operations and the generated client | M3, M4c | **Built** 2026-10-14 (commits d91242b, 7de03a0); the TypeScript generation and drift check move to MU U3 |
| MU | **The shared UI package** (owner ruling 2026-10-14: Nuxt UI v4 on plain Vue 3 and Vite, strict TypeScript, no Nuxt or Nitro, the primary UI of the Java and Go frameworks; design in [`go/ui.md`](go/ui.md), now shared): U0 (**built 2026-10-14**, [`ui/u0.md`](ui/u0.md)) the pnpm workspace rooted at `ui/` (`ui/packages/ui`, `ui/apps/gallery`, `ui/samples/openapi`), tsconfig and type-aware lint, Nuxt UI wired without Nuxt, the theme from `ui-kit/theme/tokens.css`, bundled icons, the gallery under the CSP with axe; U1 the components (**built 2026-10-14**, [`ui/u1.md`](ui/u1.md)); U2 the shell, list, drawer and form partials, the composables and `@flowcatalyst/api-runtime` (**built 2026-10-14**, [`ui/u2.md`](ui/u2.md)); U3 the Java `examples/orders-demo` SPA on its generated client, served by MS; U4 the session guard and the signed-out, not-permitted and error pages on the built M4c endpoints | U0 to U2: none (may start at once); U3: MS, MO; U4: U3 | **U0, U1, U2 built** (2026-10-14, commits 48a545b, bbed2bb, 9b712b7; [`ui/u1.md`](ui/u1.md), [`ui/u2.md`](ui/u2.md)); U3 and U4 next |
| MC | **Control plane migration guide:** a guide page taking the first application's Java control plane (`inhance/mono-repo`, `apps/ingest-control-plane`) from its placeholder `GET /` page and plain routes to typed operations, a committed document, a `web/` SPA on `@flowcatalyst/ui` (ingestions list, record drawer, the form for the sealed `Source`/`Split`/`DispatchTarget` unions) served by MS, and the CI order (web build, then Maven). The application-side work is the application's | MO, MU (U3, U4) | **Written** 2026-10-16 ([guide](guide/migrating-the-ingest-control-plane.md)); the control plane already holds most of it, the open items are listed there |
| M-UI | **`framework-ui`, optional (owner ruling 2026-10-14):** JTE templates (precompiled, no reflection) for the `ui-kit/` components and a view response helper that returns a full page or a Datastar element patch from one template, for applications that want server-rendered pages without a JavaScript build. No longer the framework's UI (the shared Nuxt UI package, MU, is); lower priority, not built now ("we can add JTE later if needed"). `ui-kit/` stays its specification and fixtures | M3 | **Built** 2026-10-16 (owner-approved; optional, secondary to the Nuxt UI package; guide `guide/ui-jte.md`) |
| M4d | **Admin skeleton** (guide deliverable: a page on the built-in admin area: what ships, permissions and roles, how to add and replace pages): server side, the `admin` module's typed operations under `/api/admin` (or the application's API prefix) with `admin:*` permissions and roles declared and synced, start-up fails if an admin operation has no required permission; client side, **shared `@flowcatalyst/ui` pages** (shell, navigation from the session's permissions, permission gating) that an application mounts in its SPA and can replace. Built once for Java and Go (owner ruling 2026-10-14) | M4c, MO, MU | Not started |
| M4e | **Admin pages, one slice each, as shared `@flowcatalyst/ui` pages over typed operations:** users and invitations, event types, config and secret status, then scoped events and dispatch jobs (needs a scoped read endpoint on the platform and read methods in the Java SDK) | M4d | **Partly built** 2026-10-16: event types, roles and config (see STATUS); users/invitations and scoped events/dispatch jobs wait on the platform |
| M4f | **Trial business service** on these contracts, with a Nuxt UI front end on the shared package (was: Datastar) | M4e | Not started |
| MA | **Usability gaps raised by the first application (2026-10-09), in order:** (1) a `framework-bom` and published artifacts (GitHub Packages first; the application had to import `framework-parent` and copy its compiler setup); (2) test overrides: `AppBuilder.override(Class, instance)` allowed only in test mode so a service can be replaced without leaving the App; (3) background work and metrics: a managed `Background` primitive (named loop on a virtual thread, lifecycle-owned, optionally lock-gated, stops within the drain, health-visible) and a `Metrics` capability (counters, gauges, timers with a no-op and an in-memory driver, Prometheus text endpoint); (4) dispatch-job rows in the framework's outbox writer (the platform's outbox processor already delivers `DISPATCH_JOB` rows to `/api/dispatch-jobs/batch`); (5) a `SecretResolver` for per-client credentials an application references (SFTP and similar), with env, file and platform drivers; (6) an HTTP client library and framework adapter (blocking API on virtual threads over Vert.x, a client per consumed service, auth strategies with token caching and refresh, JSON mapping, timeouts, retries, Context propagation; wanted before M4c because login uses it); (7) informational health checks: a per-check flag so a check such as `flowcatalyst` or `outbox` is reported but does not make `/health/ready` answer 503 (today a platform outage that outlasts the cached token marks every replica not ready; raised by the first application's review, 2026-10-09); (8) split `UseCaseError` and `ErrorKind` out of `framework-usecase` (or move `HttpErrors.of(UseCaseError)` to the use case side) so `framework-http` depends on `framework-core` only and an HTTP-only application no longer gets `framework-database`, HikariCP and the PostgreSQL driver; (9) rate limiting for HTTP routes (a middleware, in-memory for one process and Redis-backed across replicas); (10) rate limits per request, changing at run time (each ingestion's limit from a control plane), and, by owner ruling 2026-10-13, limiters built in code rather than declared in configuration | M3 | Built 2026-10-10 (all nine; nothing published): (7) built 2026-10-10 (core contract section 1, criticality); (8) built 2026-10-10 as a core `ServiceError` interface (http contract section 1); (2) built 2026-10-10 (core contract section 1, test overrides; test mode = `APP_ENV=test`); (1) built 2026-10-10 (`contracts/build.md`; publishing ready, nothing published); (4) built 2026-10-10 (write-path contract section 7a); (3) built 2026-10-10 (`contracts/background-and-metrics.md`); (5) built 2026-10-10 for `file` and `env`, `platform` driver blocked on the platform (`contracts/secrets.md`); (6) built 2026-10-10 (`contracts/http-client.md`); (9) built 2026-10-10 (`contracts/rate-limiting.md`); (10) built 2026-10-13 (same contract, sections 1 and 3a; removes the `rate-limits` configuration, breaking) |
| CC | **Service and connection settings are configuration; things with many instances are code** (owner ruling 2026-10-14, clarified the same day: "don't use a config to define pollers or things that can have many instances"; generalises MA item 10): lock stores, secret stores, named HTTP clients with their policies, rate limiters and loops are named in code with the driver behind each; service settings (`app.*`, `http.*`, `flowcatalyst.*`) and connection instances (database, Redis, pools included) stay configuration; removed keys of what moved fail start-up naming their replacement; framework components are claimed so misspelt keys fail. **Breaking** for locks, secrets, HTTP clients and `driver` keys ([contract](contracts/config-and-code.md), [migration](guide/migrating-config-to-code.md)) | MA | Built 2026-10-14 in Java (nothing published); the first reading over-reached (every behavioural key to code) and was reversed the same day (contract section 11); Go and Rust to follow; conformance vector for the key rules to add with the Go port |
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

**UI direction (owner ruling 2026-10-14):** the framework's UI is **Nuxt UI** (v4 on plain Vue 3
and Vite, strict TypeScript, no Nuxt or Nitro), the primary UI of both the Java and the Go framework:
**one backend-agnostic UI package** (components, page partials and pages extending Nuxt UI, plus the
shared admin pages) talking to a typed JSON API that each application describes with an emitted
OpenAPI document. The JTE + Datastar stack (`framework-ui`, M-UI, the `ui-kit/` specs and fixtures)
becomes an **optional module** for applications that want server-rendered pages without a JavaScript
build: lower priority, not built now. The first consumer is the Java control plane of the first
application (`inhance/mono-repo`, `apps/ingest-control-plane`). Work order: the contracts (done
2026-10-14) -> MS -> MO -> MU (U0 to U4) -> M4d and M4e as shared pages -> MC -> M-UI later if
needed; estimates in [`handover.md`](handover.md), "UI direction".

**Owner answers, 2026-10-14** (the questions the UI-direction step left open): typed operations
omit `null`s while plain routes keep Jackson's `null`s (the split stays); Maven never runs Node (the
SPA is built separately, Maven copies `web/dist`); the UI packages are named `@flowcatalyst/ui`,
`@flowcatalyst/api-runtime` and `@flowcatalyst/gallery` (the `@fw` placeholder is gone; the npm scope
must equal the GitHub owner), consumed for now by checking out this repository and linking the
package by path (pnpm `link:` to `ui/packages/ui`; the package exports TypeScript and Vue source, the
consumer's Vite compiles it; CI checks the framework out at a pinned tag or commit), published to
GitHub Packages later; the strict CSP becomes the default if U0 shows Nuxt UI runs under it; API
prefixes derived from the routes (a client route cannot share a first segment with a server route);
`BigDecimal` as a JSON string and closed response schemas; the Go platform's templ `/ui` port
continues and the Go framework offers both options as Java does (Nuxt UI primary, an optional templ +
Datastar module later); U3 is built on the Java `examples/orders-demo`.

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
shell and sidebar as the admin pages) so documentation and product look alike. From MU on,
the guide's examples are the example application's real pages: each page of the guide for
login, admin and UI points at the Vue files and the typed operations that produce a screen in that
application (shown as files, never inline: Vue's double-brace interpolation is Liquid syntax to the
site), and `docs/guide/verify.sh` keeps the types it names real. Later, the site itself may be
served by a framework application using the admin shell. (Until 2026-10-14 this said JTE markup
and Datastar interactions; that applies to the optional M-UI module only.)
