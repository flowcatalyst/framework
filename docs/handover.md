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
  taken, for the owner to confirm or reverse, are in the contract's section 12 (no platform tokens in
  the session and no refresh; JDK HTTP client rather than `framework-httpclient`; `/auth/session`
  answers 200; http only in local/test; CSRF on logout; sessions get no permissions until roles are
  mapped; `Context.permissions`). Not verified against a real platform or a real browser.
- The first application is `inhance/mono-repo` (not under `flowcatalyst-apps`): `apps/ingest-runtime`
  (Camel) and `apps/ingest-control-plane`, with `libs/java/flowcatalyst-token` to be retired by the
  migration page's steps (not done; app-side work).
- ~~Open owner decision for M-UI~~ **Decided 2026-10-14: Nuxt UI, shared** (section "UI direction"
  below). M-UI (JTE + Datastar) is optional and deferred.
- Next: MS (static assets and the SPA fallback), per the work order below. Platform work for scoped
  event/dispatch-job reads is listed in `contracts/flowcatalyst.md` section 5.
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
- Java follow-ups found while designing the others: (1) the dispatch receiver builds its `Context` with no principal and no causation, against the 2026-10-12 receiver-principal ruling (service principal of kind Service, dispatch job id as cause); (2) the Java tests have not yet written the HTTP, Datastar, metrics, rate-limit, secrets, receiver and htmlnorm conformance vectors the other implementations compare against; (3) the contracts need the principal kind and the source of the service principal's id spelled out; (4) the platform findings (dispatch-jobs `clientCode`, object-shaped `metadata`) are listed under 'Platform work found' above.
- Go framework: designed 2026-10-13, nothing built (owner decision: Go alongside Java; a part of a
  business application will later be built on both and compared). `go/gap-analysis.md` (fcsdk
  covers 2 of the 41 built Java capabilities, 14 partly, 25 not at all; none of the partly ones
  passes the shared vectors as is; the platform's templ UI kit was the big reuse until the 2026-10-13 ruling that the Go UI is Nuxt UI on Vue with strict TypeScript; see `go/ui.md`), `go/design.md`
  (stdlib-first one-way rules, explicit build function and typed handles, three Go modules under
  `go/`, sqlc plus go-jet/`Filter`, embedded PostgreSQL tests, 27 ordered owner decisions),
  `go/capabilities.md`, `go/orders-showcase.md`, `go/milestones.md` (G0 to G7). Findings: the Java
  dispatch receiver does not follow the 2026-10-12 receiver-principal ruling (no principal, no
  causation); fcsdk is MPL-2.0, has no module of its own and writes a snake_case outbox payload;
  the Java tests still have to write the HTTP, Datastar, metrics, rate-limit, secrets and receiver
  vectors the other implementations need.

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
  object-src 'none'; base-uri 'self'`), the strict CSP opt-in until U0; an index built at start-up;
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

**Work order and estimates** (days of agent-assisted work at the Java pace so far):

| # | Item | Estimate | Depends on |
|---|---|---|---|
| 1 | The contracts above | done (this step) | |
| 2 | **MS**: Java static assets + SPA fallback in `framework-http`, nosniff everywhere, the `conformance/http/spa.json` vector, guide page | 1 to 1.5 d | 1 |
| 3 | **MO**: Java typed operations and the OpenAPI emitter: core declaration-phase command (0.5 d), `Rule`/`Validator` constraints as data (0.5 d), `Operation`/`Outcome`/`bind` and permissions (0.5 to 1 d), the schema walk and canonical writer (1.5 to 2 d), `framework-auth`'s two endpoints rebound as operations and the `conformance/openapi/` vectors (0.5 to 1 d), guide page (0.5 d) | 3.5 to 5 d | 2 |
| 4 | **MU**: the shared UI package, U0 workspace, lint, Nuxt UI without Nuxt, theme, gallery under CSP and axe (1 d); U1 components (1.5 to 2 d); U2 shell, list, drawer and form partials, composables, `@flowcatalyst/api-runtime` (2.5 to 4 d); U3 Java `examples/orders-demo/web` on its generated client, served by MS (1 to 2 d); U4 session guard and default pages on the built M4c (1 to 1.5 d). U0 to U2 may run in parallel with 2 and 3 | 7 to 10.5 d | U3: 2, 3 |
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
4. **Strict CSP becomes the default if U0 shows Nuxt UI runs under it**; until then the baseline is
   the default (static assets section 7).
5. **Derived API prefixes accepted**: a client route cannot share a first segment with a server route
   (static assets section 5).
6. **`BigDecimal` as a JSON string and closed response schemas: confirmed** (openapi section 13,
   rulings 5 and 6).
7. **The Go platform's templ `/ui` port continues**, and the Go framework offers both options as Java
   does: Nuxt UI primary, an optional templ + Datastar module later (Go decision 32, `go/ui.md`
   section 16).
8. **U3 is built on the Java `examples/orders-demo`.**
