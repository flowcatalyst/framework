# Handover (2026-10-14)

The plan and status live in `implementation-plan.md` and `guide/STATUS.md`; contracts in
`contracts/`; developer docs in `guide/` (keep them current: see `../CLAUDE.md`). This note is
rewritten at hand-off and can lag the code in between.

- Done: M1, M2a, M2b, M3, core hooks (`started`, `find`, `Command`/`launch`), M4a (client, sync
  command, `outbox` backlog health check), M4b (dispatch and scheduled-job receivers, per-receiver
  secrets, `DispatchDelivery.once` with retention, lock opt-out, `flowcatalyst.receivers.enabled`),
  a start-up check for two config keys that read one environment variable.
- Review by the first application (2026-10-09): 28 findings on the guide, all handled: pages fixed,
  gaps turned into MA items 7 to 9 in the plan, three small code changes (env-variable collision
  check, receivers 404 / `enabled`, anchor check in `verify.sh`).
- MA built 2026-10-10, all nine items, each its own commit with contract, tests and guide:
  (7) informational health checks `9d73eb2`; (8) `framework-http` on core only, `ServiceError`,
  Enforcer rule `9cde933`; (2) `AppBuilder.override`, test mode = `APP_ENV=test` `21704e4`; (1)
  `framework-bom`, GitHub Packages config and `.github/workflows/publish.yml` `60fe77a` (nothing
  published); (4) `DISPATCH_JOB` outbox rows `1b6510e`; (3) `Background` + `Metrics` `661080f`; (5)
  `SecretResolver` (env, file; platform driver blocked on the platform) `91a1cc6`; (6)
  `framework-httpclient` on Vert.x + adapter `ea72549`; (9) rate limiting `3e1a771`.
- MA item 10 (2026-10-13): per-request rate-limit policies (`RateLimit.by(...).policy(resolver)`)
  and, by owner ruling ("limiters in code, not YAML"), limiters built in code.
  **BREAKING for the first application:** the `rate-limits.instances` section, `RateLimiters`,
  `RateLimitModule` and `RateLimitRedisModule` are gone. Migration:

  ```java
  // before: rate-limits: { instances: { api: { driver: memory, limit: 100, period: 1m, burst: 20 } } }
  //         .module(new RateLimitModule())          and  app.get(RateLimiters.class).get("api")
  // after: drop the YAML section and the module(s); build the limiter in your own wiring
  RateLimiter api = MemoryRateLimiter.create("api", new RateLimitPolicy(100, Duration.ofMinutes(1), 20));
  // redis driver: keep RedisModule and redis.instances; drop RateLimitRedisModule
  RateLimiter login = RedisRateLimiter.create("login", app.get(RedisConnections.class).defaultInstance(), RateLimitPolicy.of(5, Duration.ofMinutes(1)));
  RateLimit.by(api, KeyResolvers.clientAddress()).build(); // unchanged
  ```

  `max-keys` is the third argument of `MemoryRateLimiter.create`; `prefix` the fourth of
  `RedisRateLimiter.create`. Names must match `[a-z][a-z0-9_-]*`. Numbers that should stay tunable
  become the application's own `Setting`s (guide: rate-limiting, "Numbers from your own settings").
  Rulings pending owner: `contracts/rate-limiting.md` section 5, item 4 (cap at an empty bucket
  after a policy change; null = default policy; a resolver failure follows `onStoreFailure`).
- **CC built 2026-10-14, over-reach reversed the same day.** Owner rule (clarified): *"The
  configs for services and things like that are all fine. It is just don't use a config to define
  pollers or things that can have many instances."* Service and connection settings are
  configuration; things an application can have many of (lock stores, secret stores, named HTTP
  clients with their retry and auth policies, rate limiters, loops) and the driver behind each are
  code. Decision test: several of these with different values -> code; one setting of the service
  or a connection -> config. Contract `contracts/config-and-code.md` (section 11 records the
  reversal), migration `guide/migrating-config-to-code.md`. The first reading (`f8f8954`,
  `3444145`, `ecc2dca`, `53c1249`, `b4e0edb`) moved every behavioural key to code; the reversal
  commits put `app.*`, `http.*`, `flowcatalyst.*` (receivers included) and the database and Redis
  instance keys (pools, timeouts, `migrate`) back in configuration with the old names, defaults and
  environment variables, and removed `HealthPolicy`, `MetricsOptions`, `AppBuilder.shutdownDrain`
  / `health` / `metrics`, `HttpOptions`, `DatabasePool`, `RedisPool`, `DatabaseModule.builder()`,
  `RedisModule.builder()`, `FlowCatalystOptions`, `ReceiverOptions` and `PlatformClient.timeout()`.
  Kept: `216bf89` (locks in code), `373a924` (HTTP clients in code), `6ff956a` (secrets in code),
  `55ecb8d` (auth claims its component), `9326692` (driver registry removed), the removed-key check
  and claimed components. New against before 2026-10-14: database and Redis pool keys are
  range-checked at start-up (a connect timeout under 250 ms is refused, no longer raised).
  **BREAKING** for the first application, now only:

  | Old key | New code |
  |---|---|
  | `locks.*` | `LocksModule.builder().store(name, LockStores.memory() / DatabaseLockStores.database() / RedisLockStores.redis(..))`; drop `LocksRedisModule` |
  | `secrets.*` | `SecretsModule.builder().store(name, SecretStores.env(..) / file(..))` with `CachePolicy` |
  | `http-clients.default`; per client `driver`, timeouts, `max-response-bytes`, `retry.*`, `auth.type`, `auth.method`, `auth.scope`, `auth.audience`, `auth.name` | `HttpClientModule.builder().client(name, HttpClientOptions..withAuth(ClientAuth..))`; the section keeps `base-url` and credentials |
  | `database.instances.<n>.driver`, `redis.instances.<n>.driver` | delete |
  | `rate-limits.*` (ignored since MA 10) | now fails start-up |

  `inhance/mono-repo` needs (re-checked against the final state): ingest-runtime
  `IngestRuntime.builder()` lists `SecretsModule.builder().store("env",
  SecretStores.env("INGEST_SECRET_")).build()` instead of `new SecretsModule()`, and its
  `config/application.yaml` loses the `secrets:` section and the comment naming
  `SECRETS_INSTANCES_FILES_*` / `SECRETS_DEFAULT`; everything else stays (`flowcatalyst.receivers.enabled:
  false`, `http.port`, `FlowCatalystModule.TIMEOUT`, `new HttpClientModule()` with
  `ServiceClients.register`, the control plane's `database` section with `pool` and `migrate`).
  Owner ruling (2026-10-14, resolves the former open question): `http-client.event-loops` and
  `.max-connections-per-host` are configuration (one engine setting each), not code; they are
  declared by `HttpClientModule` (`EVENT_LOOPS`, `MAX_CONNECTIONS_PER_HOST`), range-checked, and
  not removed keys, so nothing changes for the first application. Go G1 follows the contract since
  2026-10-15 (commit 24b2be1; no driver registry, locks in code, removed keys and claimed components,
  ranges); Rust R1 (parked) still reads driver names for locks, secrets and HTTP clients; the
  key-rules conformance vector (`conformance/config/key-rules.json`) is still to be written, with the
  Java side (the Go tests carry table tests for the checks meanwhile). Nothing published: the mono-repo needs a fresh `./mvnw install` of the framework.
- Owner decisions waiting (each recorded in its contract under "Rulings pending owner"): publishing
  the Java SDK and the group id before the first tag (`contracts/build.md`); rate-limit store
  failure default ALLOW, IPv6 /64, the memory overflow bucket (`contracts/rate-limiting.md`);
  service-scoped secrets and world-readable files (`contracts/secrets.md`).
- Platform work found: dispatch-jobs batch should resolve `clientCode`; the SDKs' object-shaped
  dispatch-job `metadata` fails the whole batch (`contracts/write-path.md` 7a); no secret-resolution
  endpoint, and the platform-config read returns SECRET values unmasked to any anchor-tier caller,
  application service accounts included (`contracts/secrets.md` section 5).
- **M4c built 2026-10-13** (sessions and login). Contract `contracts/sessions-and-login.md`, written
  once, language-neutral and JSON-first, for Java, Go, Rust and TypeScript (Go capabilities section
  12 now points at it). Java: `framework-auth` (the module, the relying party, `PlatformTokens`, the
  `memory` and `cookie` stores, the `ContextResolver`, CSRF, `AuthTesting`, `TestOidcProvider` in its
  test-jar), `framework-auth-database` (the default store), `framework-auth-redis`. Core: `Context`
  gained `permissions` with the platform's wildcard matching. http: `Request.rawQuery()`,
  `Request.contentLength()` public, `Response.redirect`. Guide: `guide/sessions-and-login.md`,
  `guide/migrating-from-flowcatalyst-token.md` (the first application's app-side steps). Rulings
  **RULED by the owner 2026-10-14** (confirmed as written; contract section 12) (no platform tokens in
  the session and no refresh; JDK HTTP client rather than `framework-httpclient`; `/auth/session`
  answers 200; http only in local/test; CSRF on logout; sessions get no permissions until roles are
  mapped; `Context.permissions`). Not verified against a real platform or a real browser.
- The first application is `inhance/mono-repo` (not under `flowcatalyst-apps`): `apps/ingest-runtime`
  (Camel) and `apps/ingest-control-plane`, with `libs/java/flowcatalyst-token` to be retired by the
  migration page's steps (not done; app-side work).
- ~~Open owner decision for M-UI~~ **Decided 2026-10-14: Nuxt UI, shared** (section "UI direction"
  below). M-UI (JTE + Datastar) is optional and deferred.
- **MS built 2026-10-14** (static assets and the SPA fallback, `framework-http`): `Spa`
  (`classpath`/`directory`, index at start-up, limits), `Csp`, `Routes.spa`, `new Routes(environment)`
  (the HTTP module passes `APP_ENV`), `SpaTesting`, `nosniff` everywhere, `HEAD` keeps `Content-Length`;
  vector `conformance/http/spa.json`; guide `guide/static-assets-and-spa.md`; orders-demo's pom copies
  `web/dist` to `fw-spa`. Over Jetty most unclean paths are Jetty's `400` before the framework's
  `404` (contract 4.1 amendment). Strict CSP default waits on U0.
- **MO built 2026-10-14** (typed operations and the OpenAPI document): core `DeclarationCommand`,
  `Declarations`, `AppBuilder.declarations()` (`d91242b`); `framework-http` `Operation`, `PathParam`,
  `QueryParam`, `Outcome`, `Routes.bind`/`declare`, `Json.operationMapper()`, `Rule.describe()` and
  `Validator.fields()`, the emitter in `http.openapi` (`OpenApi`, `Schema`, `SecurityScheme`,
  `OpenApiDocument`, `OpenApiTesting`), the `openapi` command claimed by `JettyHttpModule`;
  `framework-auth`'s `getAuthSession` and `logout` as operations (`endSessionUrl` omitted); orders-demo
  `OrdersApi`/`OrdersApiModule`/`OrdersWeb` with the committed `examples/orders-demo/web/openapi.json`
  (the input for U3); vectors `conformance/openapi/` (`7de03a0`). Contract amendments marked (MO) in
  `contracts/openapi.md`, `sessions-and-login.md` 4.4/4.5, `core.md` 5. Found for the UI package:
  hey-api's Zod plugin writes `int64` as `z.coerce.bigint()` while the type is `number` (0.93.1 and
  0.99.0), which a form posting `unitPriceCents` would trip over; configure it at U2/U3.
- **M4d built 2026-10-16** (admin skeleton). Contract `contracts/admin.md` (written first, owner
  questions in its section 9, defaults applied), guide `guide/admin.md`. Java: new module
  `framework-admin` (core and http only; `AdminSection`, `Admin`, `AdminModule`, `AdminRegistry`,
  `AdminApi`): every operation of a section needs a four-segment permission and a path under
  `/api/admin/` (start-up fails otherwise), `getAdminNavigation` lists only the sections the caller may
  use, `getAdminAbout` reports checks without failure messages, both behind
  `{app}:admin:console:view`; the gate is the existing operation permission check (403 `FORBIDDEN`
  envelope). Vector `conformance/admin/navigation.json` (Java writes, Go compares). `orders-demo` has
  an administrator, a section of its own and a committed `web/openapi.json` with admin
  (`openapi/orders-demo-admin.json`; `openapi/orders-demo.json` stays the orders-only vector). UI:
  `FwAdminShell`, `FwAdminAbout`, `AdminPage`, `adminRoutes`, `adminNavigation`, `useAdminNavigation`;
  gallery page `/admin-shell` (5 new behaviour rows) and the orders `/admin` area with Playwright
  against the real demo. Next: M4e, one slice at a time (users and invitations first), each a section +
  operations + an `AdminPage`; owner decision 4 of the contract (how admin reaches the platform) comes
  before the first slice.
- Next: MU (U1 onward; U3 can generate from the committed orders-demo document now). Platform work
  for scoped event/dispatch-job reads is listed in `contracts/flowcatalyst.md` section 5.
- Rulings: the platform delivers the outbox; handler failure/timeout in a receiver is a
  transient error; a receiver without handlers answers 404 (reverses "routes stay and refuse");
  sessions have cookie/database/redis drivers; Opus for security-sensitive slices, Sonnet for
  wiring.
- Rust framework: designed 2026-10-11, nothing built. `rust/design.md` (principles, crate layout,
  the ordered owner decisions), `rust/capabilities.md`, `rust/orders-showcase.md`,
  `rust/milestones.md`. Findings: a Rust SDK exists after all, `flowcatalyst-rust/crates/fc-sdk`
  (Apache-2.0, unpublished), but its outbox DDL and dispatch-job shape diverge from the Java SDK and
  the batch endpoint; recommendation is a new `flowcatalyst-sdk` in `flowcatalyst-go/clients/rust-sdk`
  seeded from it. `contracts/flowcatalyst.md` 4a's "Unix seconds must be ASCII digits" should say
  the millisecond ISO-8601 form is accepted too (the code does).
- TypeScript framework: designed 2026-10-13, nothing built. Owner: TypeScript becomes the default
  for new business applications (Java stays for existing ones; Rust paused after R2), pending
  decision 0 (TypeScript or Go), which the approved T0 spike informs. `typescript/design.md`
  (principles, scriptc readiness, Bun now with Node green, the TypeBox/domain schema split, routes as
  values on an own trie, framework-written middleware and auth, worker-ready rules and the
  `RequestDispatcher` port, Nuxt UI without Nuxt, the dependency policy, 29 ordered owner decisions
  0 to 28), `typescript/capabilities.md`, `typescript/orders-showcase.md`, `typescript/spike.md` (the
  runnable T0 plan, TypeScript slice against a Go slice), `typescript/milestones.md` (T0 to T9 with
  T4a auth, T4b workers, T4c worker-pool dispatcher). Contract amendments proposed: http routing
  syntax and wildcard, opt-in CORS, static assets; an M4c auth contract written once for all three.
- UI kit (`ui-kit/`) is now being dogfooded by the Go platform's templ port (`/ui` in
  `flowcatalyst-go`). Since 2026-10-09 the kit gained: Props tables and 124+ fixtures with golden
  HTML (the conformance authority), specs for row actions, flash stack, form dialog, autocomplete,
  confirm dialog and server-loaded drawer, a 90-icon sprite built by `ui-kit/icons/build-sprite.sh`,
  an error contract for Datastar actions (handled failures answer 200 plus patches; mutations send
  `retryMaxCount: 0`), a phone-width shell and server-rendered theme cookies. Defects and gaps are
  in `ui-kit/PORTING-NOTES.md`. JTE (`framework-ui`, M-UI) has not started; it reuses the same
  fixtures. Plan: `ui-dogfood-plan.md`.
- Rust and TypeScript are **parked** (owner, 2026-10-13): no further work on either until resumed. Rust: R0 to R2 built, R3 onward not started (banner in `rust/design.md`). TypeScript: design only, drafted and parked the same day in favour of Go, spike not run (banner in `typescript/design.md`).
- Java follow-ups found while designing the others: (1) the dispatch receiver builds its `Context` with no principal and no causation, against the 2026-10-12 receiver-principal ruling (service principal of kind Service, dispatch job id as cause; *done in Java 2026-10-14, vector `receivers/context.json`, which Go G4 passes*); (2) the Java tests have not yet written the HTTP, Datastar, metrics, rate-limit (the Lua script and the GCRA numbers: Go compares the script with `RedisRateLimiter.java`'s text block), secrets, signature, receiver-response and htmlnorm conformance vectors the other implementations compare against; (3) the contracts need the principal kind and the source of the service principal's id spelled out; (4) the platform findings (dispatch-jobs `clientCode`, object-shaped `metadata`) are listed under 'Platform work found' above.
- Go framework: G0 to G4 built (G4 on 2026-10-15: `ratelimit`, `httpclient`, `secrets` and the `flowcatalyst` module with both receivers; differences, the fcsdk resolution (now the released `pkg/fcsdk/v0.0.2`; `FCSDK=<path>` for unreleased work) and the end-to-end result are in `go/milestones.md` under "G4 as built"; pages `guide/go/{rate-limiting,http-client,secrets,flowcatalyst}.md`; G3 on 2026-10-15: `httpfw`, `httpfw/spa`, typed operations and the
  OpenAPI emitter, `fw` declaration-phase commands, `orders-demo serve`; the SPA vector and the
  three OpenAPI vectors pass byte for byte; differences and what is not built are in
  `go/milestones.md` under "G3 as built"; pages `guide/go/http.md` and `guide/go/openapi.md`); G5a (the
  orders SPA on the Go server) and G5b (sessions and login) are next. Designed 2026-10-13 (owner decision: Go alongside Java; a part of a
  business application will later be built on both and compared). `go/gap-analysis.md` (fcsdk
  covers 2 of the 41 built Java capabilities, 14 partly, 25 not at all; none of the partly ones
  passes the shared vectors as is; the platform's templ UI kit was the big reuse until the 2026-10-13 ruling that the Go UI is Nuxt UI on Vue with strict TypeScript; see `go/ui.md`), `go/design.md`
  (stdlib-first one-way rules, explicit build function and typed handles, three Go modules under
  `go/`, sqlc plus go-jet/`Filter`, embedded PostgreSQL tests, 27 ordered owner decisions),
  `go/capabilities.md`, `go/orders-showcase.md`, `go/milestones.md` (G0 to G7). Findings: the Java
  dispatch receiver does not follow the 2026-10-12 receiver-principal ruling (no principal, no
  causation); fcsdk was MPL-2.0 (Apache-2.0 since 2026-10-13), has no module of its own and writes a snake_case outbox payload;
  the Java tests still have to write the HTTP, Datastar, metrics, rate-limit, secrets and receiver
  vectors the other implementations need.

## BREAKING: Spa default CSP (2026-10-15, G1 of `ui/u3.md`, owner approved)

`Spa.classpath(..)/directory(..).build()` without `contentSecurityPolicy(..)` now sends the strict
policy (`Csp.strict()`, no script hashes, plus `Cross-Origin-Opener-Policy` and `Permissions-Policy`)
instead of the baseline. Go already defaulted to strict.

| Before | After |
|---|---|
| default `Csp.baseline()` (`frame-ancestors 'none'; object-src 'none'; base-uri 'self'`) | default `Csp.strict()`; `Csp.baseline()` is the explicit opt-in |
| an inline script with the default | blocked: pass its hash, `Csp.strict("sha256-...")` (`COLOR_MODE_SNIPPET_SHA256` of `@flowcatalyst/ui/csp` for the shared UI package) |

`inhance/mono-repo` impact (read-only here, so stated, not edited): `apps/ingest-control-plane`
serves a placeholder `GET /` page and no SPA through `Spa` yet, so nothing changes until it mounts
one; the Camel `ingest-runtime` has no SPA. When the control plane mounts its Nuxt UI SPA it either
passes the colour-mode hash or, for an interim server-rendered page with inline scripts or styles,
calls `.contentSecurityPolicy(Csp.baseline())`. Needs a fresh `./mvnw install` of the framework.

## UI direction (decided 2026-10-14)

**Owner ruling:** the framework's UI is **Nuxt UI** (v4 on plain Vue 3 and Vite, strict TypeScript,
no Nuxt or Nitro), the **primary UI of both the Java and the Go framework**: **one backend-agnostic
UI package** (components, page partials and pages extending Nuxt UI, plus the shared admin pages)
talking to a typed JSON API. The JTE + Datastar stack (`framework-ui`, M-UI, the `ui-kit/` specs and
fixtures) becomes an **optional module** for applications that want server-rendered pages without a
JavaScript build: lower priority, not built now ("we can add JTE later if needed"). The first
consumer is the Camel-based application's control plane (`inhance/mono-repo`,
`apps/ingest-control-plane`: five operator endpoints under `/api/v1/ingestions`, a placeholder
`GET /` page, request and response records whose `Source`, `Split` and `DispatchTarget` are sealed
interfaces with Jackson type names).

Recorded in: `implementation-plan.md` (ruling paragraph; rows MS, MO, MU, MC; M-UI optional;
M4d to M4f on the shared package), `guide/STATUS.md` (rows, known gaps, contract differences),
`go/ui.md` (header, sections 6, 9, 15, 17, 18), `go/design.md` (ruling; decisions 1, 18, 28, 31
decided; section 7), `go/capabilities.md` sections 5 and 13, `go/milestones.md` status,
`ui-kit/README.md` and `ui-dogfood-plan.md` (the kit is the optional module's).

**Contracts written (this step, docs only):**

- `contracts/static-assets-and-spa.md`: the SPA as the router's **fallback** (no wildcard route);
  API prefixes = first segment of every route + `api`, `auth` + code, always the JSON 404; non-GET
  `405`; missing assets (under `assets/` or with an asset extension) `404 no-store`; `index.html`
  `no-cache` + strong SHA-256 ETag, hashed assets `immutable`; nosniff, `Referrer-Policy:
  same-origin`, `X-Frame-Options: DENY`, the platform's baseline CSP (`frame-ancestors 'none';
  object-src 'none'; base-uri 'self'`), the strict CSP opt-in until U0 (U0 passed: strict is the default; the Java `Spa` default flipped 2026-10-15, see "BREAKING: Spa default CSP" below); an index built at start-up;
  no `index.html` fails start-up outside `local`/`test`; Maven copies `web/dist` and never runs Node;
  nosniff on every framework response (http amendment).
- `contracts/openapi.md`: `Operation` values declared in `register`, bound in `boot`, handlers return
  a sealed `Outcome<R>`; schemas from records, enums and sealed interfaces (`@JsonTypeInfo` +
  `@JsonSubTypes` -> `oneOf` + `discriminator`); `@Nullable` = may be absent (typed operations omit
  nulls; no three-state values); closed schemas; `BigDecimal` as a string; constraints from the
  `Validator`; one `Error` component and implied statuses; security schemes contributed by the
  resolving module; deterministic bytes; the `openapi` **declaration-phase command** (core
  amendment: runs after register only, no config or database); `OpenApiTesting.assertCommitted`
  for drift in Maven; hey-api (`typescript` with `as const` enums, `client-fetch`, `sdk`, `zod` v4)
  with a CI drift check; Java and Go reproduce `conformance/openapi/` **byte for byte**.
- `contracts/sessions-and-login.md` section 4.5: `getAuthSession` and `logout` as typed operations
  with fixed components; the SPA's client rules (same-origin `fetch`, no CSRF token or header, JSON
  bodies, read the session at start and on focus, not on a timer; 401 -> navigate to sign-in);
  4.4: `endSessionUrl` omitted rather than `null` (code still writes `null`, STATUS records it).

**U0 built (2026-10-14, commit 48a545b; results in [`ui/u0.md`](ui/u0.md)).** The pnpm workspace is
rooted at `ui/` (accepted over the repository root the first design drew): `ui/packages/ui`
(`@flowcatalyst/ui`), `ui/apps/gallery` (`@flowcatalyst/gallery`), `ui/samples/openapi`. Example SPAs
are outside it and link by path (`link:../flowcatalyst-framework/ui/packages/ui` from another
repository; a relative path inside this one). Nuxt UI runs under the strict CSP in Chromium;
hey-api renders `const` as literal types and `oneOf` + `discriminator` as narrowing unions, so the
sessions contract's `const` form stands. U1 to U4 are pending. Follow-ups:

- ~~Java: flip the `Spa` builder's default CSP to strict~~ done 2026-10-15 (see "BREAKING: Spa default CSP").
- Zod's `jitless` (`z.config({ jitless: true })` before any schema exists) is owned by
  `@flowcatalyst/api-runtime` from U2; until then the gallery sets it.
- hey-api names the error component `Error`, which shadows the global `Error` where imported (import
  it under another name). `api-runtime` must not configure an `auth` callback: the cookie is the only
  credential (sessions contract 4.5).
- `UForm` 4.11.3 drops a submit made while input-event validation is running, keeps native
  constraint validation on (use `novalidate`), and its `dirty` is not "differs from initial"
  (`useDirtyForm` keeps the snapshot comparison); `UDashboardNavbar`'s `title` renders a second `h1`.
- Not verified: Firefox and WebKit, the real API, screenshots (U1), hey-api 0.93.1.

**U1 and U2 built (2026-10-14, commits bbed2bb and 9b712b7; results in [`ui/u1.md`](ui/u1.md) and
[`ui/u2.md`](ui/u2.md)).** Every ui-kit spec has its gallery entry (37 covered, 11 composed, 11 built,
1 not ported, checked against `ui-kit/components`); the partials (`FwListPage`, `FwRecordDrawer` with
`useDrawerRoute`, `FwFormDialog`), `FwAppShell`, the composables and `@flowcatalyst/api-runtime` run in
the gallery on a client generated from `conformance/openapi/orders-demo.json` against an in-page fake
server; strict CSP clean everywhere; axe clean except exact kit-token pairs. `pnpm check` passes from a
clean `pnpm install --frozen-lockfile` (84 vitest tests, 68 Playwright tests). Found on the way:

- `fwOpenApiConfig` (`@flowcatalyst/api-runtime/openapi-ts`) adds two settings to the contract's
  hey-api configuration: client-fetch `baseUrl: false` (a bare `openapi.json` input becomes the base
  URL) and a Zod number resolver so `int64` stays `z.int()` (hey-api writes `z.coerce.bigint()`, which
  `JSON.stringify` refuses; proven on `unitPriceCents`). Contract section 11 now says so (amended
  2026-10-14; the text landed in commit b4e0edb with a concurrent Java change).
- Reka UI 2.10.5's toast announcer is `aria-hidden`: toasts are announced through the package's
  `FwAnnouncer` instead (to report upstream).
- `UInputNumber` commits typed text only on blur or Enter; `FwFormDialog` blurs before submitting.
- Tabs follow the kit's look, which removed two of the four U0 contrast pairs; the kit's light 600
  shades and the dark destructive red still fail AA in several places (exact list in `ui/u1.md`).
- The orders example's page type has no `hasMore` and `listOrders` no search parameter (`ui/u2.md`).
- **U3 built 2026-10-15** (`ui/apps/orders`, `docs/ui/u3.md`): list, drawer, create, add line, cancel with confirm, sign-in/out, permissions and error paths on the real Java demo (`OrdersSpaModule`, strict CSP, `OrdersE2e` launcher with Testcontainers PostgreSQL and `TestOidcProvider`); 28 Playwright tests, strict axe. Found and fixed: the Vite dev server could not run (CSS lift), Zod's developer messages in forms. Gaps G1 to G14 in u3.md: G1 (Spa strict default, BREAKING, below), G2, G4, G5, G10, G11, G13 fixed 2026-10-15 (phone columns, the drawer pushes the list aside, `FwStandalonePage` / `FwSignedOutPage` / `FwSignInFailedPage` / `FwUnavailablePage`); G9 fixed 2026-10-15 (sessions contract 4.1.1: a browser navigation to a rate-limited or unreachable-platform `/auth/login` goes through the failure handler with `RATE_LIMITED` / `PLATFORM_UNAVAILABLE`, APIs keep the JSON; vector `conformance/auth/login-failure.json`; Go pending until G5b); open: G12, G14, the CSP hash shipped with the SPA.
- Next (after U3): U4.

**U1 revised: Nuxt UI used natively (2026-10-14, owner rulings A to D; audit in
[`ui/nuxt-native.md`](ui/nuxt-native.md), results in [`ui/u1.md`](ui/u1.md)).** The ui-kit specs are a
checklist of behaviours, not a visual or API contract. Deleted for Nuxt UI used directly:
`FwPageHeader` (`UPageHeader`, themed compact), `FwTag` (`UBadge`), `FwSpinner` (`UButton :loading`,
`UProgress`), `FwEmptyState` (`UEmpty`), `FwConfirmDialog` (`useConfirm` opens `FwAlertDialog` via
`useOverlay`). Rebuilt thin: `FwAlertDialog` on `UModal` (role `alertdialog` through its `content`
prop), `FwToggleGroup` on `UFieldGroup` + `UButton` with Reka's toggle-group behaviour. Kept: the shell,
list page, record drawer, form dialog, status badge (Nuxt UI colour names), toolbar, cursor pager,
paginator, row actions, pick list, detail fields, form section, local time, code/JSON/chips, colour
mode, announcer and the composables. Theme: `@flowcatalyst/ui/theme.css` (Nuxt UI `--ui-*` tokens only,
kit values checked by a unit test) replaces `theme/kit.css`; looks in `fcComponentTheme` (`tabs`,
`pageHeader`, `alert`). Contrast: light primary/success/info/warning/error one shade darker (600 to
700), dark error red-500 to red-400; **axe is strict** (no tolerated pairs), clean on every page in
both themes at desktop and phone and with overlays open. The spec catalog test became a **behaviour
checklist** (`apps/gallery/src/behaviours.ts`, 62 rows): each page marks its rows and a Playwright
reporter fails a full run in which a row is exercised by no passing test. `pnpm licences` now also
generates and checks `ui/THIRD-PARTY-NOTICES` from the gallery build (35 shipped packages; IBM Plex Sans OFL
text, vaul-vue with its repository's MIT text, MPL tools listed as not shipped); the vaul-vue request is
drafted in [`ui/upstream-vaul-vue-issue.md`](ui/upstream-vaul-vue-issue.md), not posted. `pnpm check`
green from a clean frozen install: vitest 43 + 36 + 5, Playwright 82 (+ 4 screenshot runs);
screenshots regenerated (`pnpm gallery:screenshots`, 44, git-ignored). Open: success and warning text
on their own 10 % tint stay at 4.38 and 4.48 (soft/subtle variants, unused and not shown); `go/ui.md`
sections 10 and 11 still describe the catalog and `kit.css`.

**TypeScript 7 (2026-10-14, owner ruling "Definitely TypeScript 7"; commit 646cc55; results in
[`ui/u0.md`](ui/u0.md), "TypeScript 7", the table in `ui/README.md`).** The compiler is `typescript`
7.0.2, installed as `@typescript/native` (`npm:typescript@7.0.2`); the name `typescript` is
Microsoft's `@typescript/typescript6` 6.0.2 compatibility package (TypeScript 6.0.3 API, pinned by a
pnpm override), the side-by-side layout of the TypeScript 7.0 release notes. TypeScript 7.0 ships no
programmatic API, so: `tsc --build` on 7.0.2 checks the tooling, `@flowcatalyst/api-runtime`, the
OpenAPI sample and the Node projects of `@flowcatalyst/ui` and the gallery (4x to 8x faster than 6.0.3);
`vue-tsc` 3.3.12 (the `.vue` projects) and typescript-eslint 8.71.1 (type-aware lint of everything)
run on 6.0.3; hey-api 0.99.0 prints with the 6.0.3 API (generated bytes unchanged). `pnpm check`
passes from a clean install; breaking types on purpose fails `tsc` 7, `vue-tsc` and the lint as before.
**Remaining blocker, upstream only; the 6.0.3 pin goes when all hold:** (1) a typescript-eslint
release whose `typescript` peer includes 7 with type-aware rules (today every release, 8.71.2 and the
8.71.3 canaries, says `<6.1.0`; draft PR typescript-eslint#12803 on the TypeScript 7.1 API, issue
#10940); (2) a vue-tsc release that checks `.vue` files on TypeScript 7 (3.3.13 still needs the 6 API);
(3) TypeScript 7.1 with its API, and hey-api working without the 6 API. Not adopted:
`typescript-native-bridge` (a one-maintainer TypeScript 6 fork with the Go checker over cgo, which
Vue's tools dogfood) and `vue-go-tsc` 0.3.1.

**Owner decisions pending from U1 and U2:** ~~the kit tokens~~ and ~~the text scale~~ decided
2026-10-14 (one shade darker; Nuxt UI's defaults). Pending: success and warning on their own tint
(a second shade, a lighter tint, or keep those variants unused), the review screenshots
(`pnpm gallery:screenshots`), a navigation command palette in the shell, posting the vaul-vue draft
and the Reka toast-announcer report (`ui/u1.md`, "Owner decisions"). (The two contract section 11
settings are in the contract since 2026-10-14.)

**Owner decisions pending from U0:**

1. The four light-theme colour pairs that fail WCAG AA 4.5:1 (white on primary emerald-600 3.76;
   subtle success 2.95; subtle info 3.61; muted foreground on `bg-elevated` 4.34): change the kit's
   light tokens, or accept them.
2. Licences: MPL-2.0 for `axe-core`, `@axe-core/playwright` and `lightningcss` (unmodified build and
   test tools): accept; the OFL-1.1 notice for the font (IBM Plex Sans since 2026-10-14; Inter at the time) must ship with an application's
   third-party notices; `vaul-vue` 0.4.1 declares no licence in its npm package (its repository is
   MIT) and ships in every bundle that uses `UDashboardSidebar`, `USidebar` or `UDrawer`: accept,
   ask upstream, or replace.
3. TypeScript: **decided 2026-10-14, TypeScript 7** (see "TypeScript 7" above); the remaining
   TypeScript 6 use waits on upstream, not on the owner.

**Work order and estimates** (days of agent-assisted work at the Java pace so far):

| # | Item | Estimate | Depends on |
|---|---|---|---|
| 1 | The contracts above | done (this step) | |
| 2 | **MS**: Java static assets + SPA fallback in `framework-http`, nosniff everywhere, the `conformance/http/spa.json` vector, guide page | done 2026-10-14 | 1 |
| 3 | **MO**: Java typed operations and the OpenAPI emitter: core declaration-phase command (0.5 d), `Rule`/`Validator` constraints as data (0.5 d), `Operation`/`Outcome`/`bind` and permissions (0.5 to 1 d), the schema walk and canonical writer (1.5 to 2 d), `framework-auth`'s two endpoints rebound as operations and the `conformance/openapi/` vectors (0.5 to 1 d), guide page (0.5 d) | done 2026-10-14 (`d91242b`, `7de03a0`) | 2 |
| 4 | **MU**: the shared UI package, U0 workspace (**built 2026-10-14**), lint, Nuxt UI without Nuxt, theme, gallery under CSP and axe (1 d); U1 components (**built 2026-10-14**); U2 shell, list, drawer and form partials, composables, `@flowcatalyst/api-runtime` (**built 2026-10-14**); U3 Java `examples/orders-demo/web` on its generated client, served by MS (1 to 2 d); U4 session guard and default pages on the built M4c (1 to 1.5 d). U0 to U2 may run in parallel with 2 and 3 | 7 to 10.5 d | U3: 2, 3 |
| 5 | **M4d** admin skeleton (typed admin operations, `admin:*` sync, shell and permission gating as `@flowcatalyst/ui` pages) then **M4e** slices (users and invitations, event types, config and secret status; scoped events and dispatch jobs wait on the platform) | M4d 2 to 3 d; M4e 1 to 2 d per slice | 4 |
| 6 | **MC**: the control plane migration guide (framework side) | 0.5 to 1 d; the application's own work about 3 to 5 d (typed operations for five endpoints, a `web/` SPA with list, drawer and the sealed-union form, CI order) | 3, 4 (U3, U4) |
| 7 | **M-UI**: the optional JTE + Datastar module | 4 to 6 d, when an application asks | none |

Total framework side through item 6: about 18 to 27 days. The Go framework then implements MS and
MO against the same vectors (inside its G3 and G5a) and reuses MU as is.

**Owner answers (2026-10-14)** to the questions this step left open (each recorded in its contract):

1. **Nulls: keep the split.** Typed operations omit `null` values; plain routes and `Response.json`
   keep Jackson's `null`s (openapi section 13, ruling 4). No wire change for existing routes.
2. **Maven never runs Node: confirmed.** The SPA is built separately (`pnpm --dir web build`); Maven
   copies `web/dist` as a resource (static assets section 12, ruling 4).
3. **UI packages: linked by path for now, GitHub Packages later.** An application in another
   repository checks out the framework repository and links the package by path (pnpm
   `"@flowcatalyst/ui": "link:../flowcatalyst-framework/ui/packages/ui"`); the package exports its
   TypeScript and Vue source, so the consumer's Vite compiles it; CI checks the framework out at a
   pinned tag or commit. Later the packages are published to GitHub Packages under the
   `@flowcatalyst` scope (the npm scope must equal the GitHub owner). The `@fw` placeholder is
   renamed in the docs: `@flowcatalyst/ui`, `@flowcatalyst/api-runtime`, `@flowcatalyst/gallery`.
4. **Strict CSP becomes the default if U0 shows Nuxt UI runs under it**; U0 did (see "U0 built"
   below), so strict is the contract's default (static assets section 7); the Java `Spa` default flipped
   2026-10-15.
5. **Derived API prefixes accepted**: a client route cannot share a first segment with a server route
   (static assets section 5).
6. **`BigDecimal` as a JSON string and closed response schemas: confirmed** (openapi section 13,
   rulings 5 and 6).
7. **The Go platform's templ `/ui` port continues**, and the Go framework offers both options as Java
   does: Nuxt UI primary, an optional templ + Datastar module later (Go decision 32, `go/ui.md`
   section 16).
8. **U3 is built on the Java `examples/orders-demo`.**
