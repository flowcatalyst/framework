# TypeScript framework: milestones, testing and conformance (draft 1, 2026-10-13)

Companion to [`design.md`](design.md). Milestones are numbered T0 to T9 so they are not confused with
the Java (M) or Rust (R) ones; the Java milestone each covers is named. T4a, T4b and T4c are inserted
after T4 by owner rulings of 2026-10-13 (auth as its own milestone after the use-case and HTTP layer;
threads and workers after T4). Like the Rust plan, the MA usability items are built in the milestone
where they belong rather than after it.

## 0. Status

Nothing is built. The owner has approved the spike ([`spike.md`](spike.md)); everything after T0
waits on decision 0 (TypeScript or Go for business applications), which the spike informs.

## 1. Milestones

| # | Scope | Java equivalent | Depends on | Exit criteria |
|---|---|---|---|---|
| **T0** | **Spike, skeleton, CI.** The spike of [`spike.md`](spike.md) (one slice in TypeScript on Bun and Node, the same slice in Go, the measurements). Then, if decision 0 is TypeScript: the `ts/` workspace (Bun workspaces, `bun.lock`, TypeScript 6.x pin, the tsconfig of design section 1.2, the lint rule set of section 2.4 with the local plugin), `@fw/test` (throwaway PostgreSQL 18 and Redis per run under the isolation rule, a database per test), the conformance reader, `tools/fw-embed`, and CI: type check, lint, tests and conformance on **Bun and Node**, the licence check, and the **non-gating `scriptc coverage` job** with a pinned version | M1 step 1, MA 1 (in part) | owner go-ahead (given) | the spike's results page with a recommendation per decision; CI green on an empty workspace on both runtimes; one shared vector (`conformance/tsid/`) read by a TypeScript test; the scriptc job producing its JSON report |
| **T1** | **Core.** `Result` and its helpers; ids (brands or records per decision 9, `Id<K>`, the TSID codec and generator with the monotonic rules); `Clock`, `Instant`, the RFC 3339 and duration parsers; config (`Setting` handles, files, environment, `secrets.local.yaml`, instance sections with driver key schemas, every rule, the YAML reader per decision 12); `Secret`; `Context`; `App` declare/configure/launch, `Boot`, health with criticality, `Background`, `Metrics` and the Prometheus writer, logging, commands; the `Crypto`, `FileSystem`, `Environment`, `Signals` ports and their adapters | M1, MA 3, MA 7 | T0 | the TSID and config vectors match; the Prometheus bytes match (new vector, Java-written); start-up reports every config problem at once; shutdown runs loops first, then actions in reverse; `NoThrow` on the pure functions with zero `SC7001` in the coverage report (reported, non-gating) |
| **T2** | **Database port, outbox, unit of work.** `SqlClient` and its adapters (decision 10, from the spike), the `SqlClient` contract suite, `Databases`, `Db`/`Tx`, the migration runner on `fw_schema_history`; `Fresh`/`Loaded`, `Repository`, `Plan`, `DomainEvent` and `Events`, the `OutboxWriter` on the shared `INSERT`, `OutboxModule` (the SDK file byte for byte, then `V0002`), audit rows, `DispatchJob` with the contract's rules, the framework's JSON writer (decision 25), the unit of work inside the runner skeleton | M2a, MA 4 | T1 | `conformance/outbox/outbox.json` matches **byte for byte** (payload text and size); `conformance/migrations/*.json` accepted and reproduced; the contract suite green on every adapter on both runtimes; a database first migrated by the Java demo is accepted |
| **T3** | **Locks and Redis.** The RESP2 client (decision 11) behind `RedisClient`, `RedisConnections`; `Locks`, `LockStore` with the memory, database and redis drivers, `withLock`, the lock gate for loops; the contract suite as `lockSuite(harness)` | M1 (locks), MA 3 (lock gate) | T2 | the suite green on all three drivers on both runtimes; the shared statements and scripts (`conformance/sql/locks/`, `conformance/lua/locks/`) are the bytes sent; `conformance/migrations/locks-database.json` matches; a Java and a TypeScript process contend for one `fw_lock` key and one Redis key correctly |
| **T4** | **Use cases, HTTP layer, authorisation model.** `UseCase`, the runner with the full failure table and step codes, conflict retry with jitter, `Grant`, `requirePermission`; route values, `route()`, `scope()`, the trie compiler, the fixed middleware (correlation id, error mapping, access log), body limits, security headers, CORS (opt-in), CSRF primitives, `ContextResolver` (a test resolver), health and metrics endpoints, the error envelope, static SPA serving, `ServerAdapter` with the `Bun.serve` and `node:http` adapters, drain, `TestClient`; the **in-process `RequestDispatcher`** (semaphore, bounded per-tenant queue, 429 when full); `@fw/schema` (TypeBox helpers or the own checker per decision 8); `@fw/ratelimit` (memory and redis); `examples/orders-demo` server with the three use cases | M2a (runner), M3, MA 8, MA 9 | T2, T3; decisions 8, 24, 26, 27 | every failure row of write-path section 4 has a test; every row of http section 5 and the routing rules have tests; error, health and rate-limit vectors (new, Java-written) match; graceful shutdown with an in-flight request on both server adapters; orders-demo's outbox rows match the vector |
| **T4a** | **Auth.** Contract first (decision 21: one language-neutral M4c contract). `conformance/auth/` vectors and the **negative tests written first** (expired, not yet valid, wrong issuer, wrong audience, `alg: none`, RS256→HS256 confusion, unknown `kid`, tampered payload and signature, wrong key type); JWT verification (RS256 through `node:crypto`, JWKS cache); OIDC code + PKCE as a public client, token exchange and refresh, logout; sessions with `cookie` (AES-256-GCM), `database` and `redis` drivers, rotation, idle and absolute expiry; the session `ContextResolver`; the auth routes; 401 versus 302; CSRF wired | M4c | T4, the HTTP client part of T5 (moved forward: login needs it), the M4c contract | every vector accepted or refused as written; a security review; Opus only for the slice (owner rule); login against a local platform (the platform on its own port, never the developer's 8080 instance unless the owner says so) |
| **T4b** | **Workers.** The `Workers` port on `node:worker_threads` (Bun implements it), `Immutable` snapshots published by the main thread (config, route table, authz tables, schema cache, definitions), per-worker database and Redis pools sized from per-process settings, per-worker health and metrics merged on the main thread, leader work pinned to the main thread behind the existing locks, the listener-sharing method chosen by measurement (`reusePort` first) | none (Java has virtual threads) | T4; design section 1.12, decision 28 | the orders demo serves on `n` workers on Bun and Node with the same tests; connection counts stay at the configured totals; a killed worker is replaced; leader loops run once across two replicas; the lint rule against module-level mutable state is green across the codebase |
| **T4c** | **Worker-pool `RequestDispatcher`** (conditional). Main thread owns accept, parse, route and the central queue; workers pull; bodies transferred; per-tenant fairness; timeout terminates and replaces a worker | none | T4b; built only if the spike's hand-off cost (M11) and a measured CPU-bound load justify it | under a CPU-heavy route, throughput scales with workers while the in-process adapter saturates one core; 429 shedding and per-tenant fairness tested; streaming routes stay on the main thread |
| **T5** | **Receivers and the platform.** `@fw/httpclient` (the library and its adapter; its core moves into T4a if login needs it first), `@fw/secrets` (env, file), `@fw/flowcatalyst` (the platform client per decision 15, definitions and `sync`, the outbox health check, both receivers with the receiver principal ruling, `once` and its purge) | MA 6, MA 5, M4a, M4b | T4 (T4a for nothing here: receivers are signature-authenticated); decisions 15, 16 | the shared signature vectors and every receiver response vector match; the receiver test list of flowcatalyst section 6; end to end: the Go `fc-dev` outbox processor delivers the TypeScript orders demo's outbox to a local platform, and a dispatch job and a scheduled job reach its receivers |
| **T6** | **UI package.** `@fw/ui` on Nuxt UI (decision 17): theme preset from the kit's tokens, the components, page partials and pages of capabilities section 13, the composables, the Standard Schema adapter; a gallery app with Playwright screenshots and axe checks (decision 20); the orders demo's `web/` (list, detail, form pages behind login) | M-UI | T4, T4a; decisions 17 to 20, 23 | every component and page in the gallery, light and dark, screenshots stable, no axe violations at "serious" or above; the orders demo usable end to end in a browser |
| T7 | **Checked SQL.** `tools/fw-sql` (decision 14) and the orders demo's repositories moved onto it | M2b | T2 | the checked-SQL contract's tests that apply; a stale generated module fails CI |
| T8 | **Admin.** The `/admin` area as `@fw/ui` pages over the platform client | M4d, M4e | T5, T6 | as the Java milestones |
| T9 | **A trial service in TypeScript** | M4f | T8 | one real service on the framework |
| later | cache, storage, the platform config and secret drivers, metering, messaging | M5 | the platform | as the Java contracts |

A milestone is done when its tests and conformance vectors pass **on Bun and on Node**, the guide has
a TypeScript page for each public capability (section 4), and STATUS records it, by the same
documentation rules as the Java code.

## 2. Testing strategy

| Level | What | How |
|---|---|---|
| unit | pure logic: `Result` helpers, value objects, TSID, config parsing, durations, RFC 3339, route matching, GCRA, signature checks, the Prometheus writer, the JSON writer | `node:test` and `node:assert` (decision 5), run on Bun and Node; property tests where the input space is large (key grammar, path encoding, GCRA arithmetic) with a small framework generator rather than a dependency |
| time-dependent | lock leases, loop schedules, retries, token expiry, rate limits | the injected `Clock` plus `node:test` mock timers; no real sleeps in unit tests |
| integration | adapters, the runner, migrations, receivers with `once` | a throwaway PostgreSQL 18 and Redis per test run under the isolation rule (never port 15432 or 8080, never a database the run did not create), **a fresh database per test** from a template migrated once, so tests run in parallel |
| contract suites | every adapter or driver of a port passes the same suite: `SqlClient`, `RedisClient`, `LockStore`, rate-limit stores, secret stores, session stores, repositories | the capability's `testing` subpath exports a function that registers one test per case for a given harness |
| HTTP | routing, middleware order, envelopes, validation, auth, receivers | `TestClient` (in-process, `Request` in, `Response` out); real-socket tests per server adapter for body limits, drain, bind failure |
| auth | token acceptance and refusal, sessions, CSRF | the shared vectors, negative cases first; a review by a second agent before T4a closes |
| conformance | wire and storage output equal to the Java framework's | section 3 |
| UI | rendering, behaviour, accessibility | vitest with `@vue/test-utils` for composables and components; the gallery under Playwright for screenshots and axe |
| scriptc | readiness | the non-gating coverage job; its report is reviewed at each milestone's end |
| end to end | the platform accepts what the framework writes | section 3.4 |

## 3. Conformance

### 3.1 The shared vectors

TypeScript joins as a third reader of `conformance/`. The Java framework stays the reference (Rust
decision 18); TypeScript tests only compare. A TypeScript mismatch means TypeScript is wrong, or the
vector exposes a Java bug, which is fixed in Java first.

| Area | Exists | TypeScript milestone |
|---|---|---|
| `tsid/`, `config/` | yes | T0, T1 |
| `outbox/`, `sql/outbox/`, `migrations/outbox.json`, `migrations/orders.json` | yes | T2 |
| `sql/locks/`, `lua/locks/`, `migrations/locks-database.json` | yes | T3 |
| `errors/`, `health/`, `metrics/`, `ratelimit/`, `lua/ratelimit/` | planned in the Rust plan, not written | T1/T4: written by Java tests first |
| `receivers/signature/`, `receivers/dispatch/`, `receivers/scheduled-jobs/`, `secrets/` | planned, not written | T5 |
| `auth/` (new) | no | T4a: a fixed JWKS, tokens and expected outcomes; **generated once by a script using a standard tool (OpenSSL), reviewed, committed**, because no implementation exists yet to be the reference; Java and Rust read it when they build M4c |
| `datastar/`, `htmlnorm/`, `ui-kit/fixtures` | partly | not applicable to TypeScript (decision 24) |

An allowed difference is recorded in the vector as `"typescript": {...}` with a reason, beside the
existing `"rust": {...}` form. Known ones to record: the event-data null rule (shared with Rust), a
`GET` to an unused receiver route answering 404 rather than Java's 405 (shared with Rust), the
duplicate-key YAML refusal (shared with Rust), the route pattern syntax (decision 27, until amended).

### 3.2 Comparison rules

As the conformance README: JSON compared as parsed values, except where the vector holds exact text
(outbox payload text, SQL and Lua files, Prometheus and HTTP header bytes), which is compared byte for
byte. Generated values are pinned with fixed clocks and ids.

### 3.3 Runtimes

Every vector test runs on Bun and on Node in CI. A vector that passes on one and fails on the other is
a runtime finding, reported in STATUS, never skipped.

### 3.4 End to end (manual, then nightly)

1. **Outbox:** the TypeScript orders demo writes events, audit rows and dispatch jobs; the platform's
   outbox processor delivers them with the application's service account; the platform shows the same
   events, audit entries and jobs as for the Java demo running the same scenario.
2. **Receivers:** a subscription pointed at the TypeScript receiver gets a signed dispatch job and a
   scheduled-job firing; acknowledgement, deferral, rejection and completion are seen on the platform
   as for Java.
3. **Shared storage:** Java, Rust and TypeScript processes contend for one `fw_lock` key and one Redis
   rate-limit key; mutual exclusion and one shared budget hold.
4. **Login** (T4a): the TypeScript demo logs a user in against a local platform as a public client.

All against a local platform and databases started for the purpose, never the developer's running
instance.

## 4. Documentation

The guide gains TypeScript pages beside the Java and Rust ones, under the same rules
(`docs/guide/verify.sh`, snippets from compiled code, STATUS kept current). `verify.sh` gains a check
for TypeScript type names declared under `ts/packages/`, as it has for Rust. Vue templates are shown
as files, never inline in a guide page, because the site would read their interpolation syntax as
Liquid.

## 5. Effort and risk

Effort is relative, in days of agent-assisted work at the pace the Java framework was built; a human
team should multiply. Estimates assume the decisions are taken as recommended.

| # | Effort | Main risk | Mitigation |
|---|---|---|---|
| T0 | spike 3 to 4 d; skeleton 1 d | the spike turns into the framework; Bun or scriptc surprises | throwaway branch, time box, results only to `main`; every surprise is a finding |
| T1 | 3 to 4 d | the config rules are many and exact; YAML parser eligibility | the config vectors already exist; the subset parser is the fallback |
| T2 | 3 to 4 d | byte-for-byte JSON against Jackson; `Bun.sql` semantics; `int8`/`numeric` precision | the framework's own JSON writer, tested on the vector first; the `SqlClient` suite; strings for big numbers |
| T3 | 2 to 3 d | the RESP client's edge cases (reconnect, pipelining, `NOSCRIPT`) | a small surface (four scripts and a ping) and the lock suite |
| T4 | 4 to 5 d | many small HTTP contract details on two server adapters; TypeBox error paths mapped to the contract's details | every row of the error table is a test; the adapters share everything but socket handling |
| T4a | 4 to 6 d | security-sensitive, new contract, three session drivers | contract first, vectors first, negative tests first, Opus only, a review |
| T4b | 3 to 4 d | listener sharing differs by platform; adapters inside workers | measure first (spike M11 and doubts 9 and 10); keep single-thread as the default |
| T4c | 3 d, if built | main thread as bottleneck; clone cost | conditional on measurements; `reusePort` alternative |
| T5 | 4 to 5 d, plus SDK work in the platform repository | the SDK decision; timeouts without cancellation | decide 15 and 16 first; the receiver test list exists |
| T6 | 4 to 6 d | Nuxt UI churn; two UI stacks diverging in look | extend, never fork; the kit's tokens as the single source; screenshots in CI |
| T7 to T9 | as Java | | |

Cross-cutting risks:

- **Three implementations drift.** The shared vectors, one repository, and the rule that a contract
  change lands with every active implementation or with a STATUS note on the ones that lag (Rust is
  paused; its lag is recorded, not hidden).
- **Runtime and compiler churn.** Bun, Node, scriptc, TypeScript and Nuxt UI all move; pinned exact
  versions, upgraded one at a time with CI on both runtimes, and the ports keep each runtime's APIs in
  adapters.
- **Writing more ourselves.** The dependency rule turns several libraries into framework code (RESP
  client, possibly the schema checker and YAML parser, the router, the auth pieces). Each is small and
  contract-tested, but it is code to own.
- **Owner bandwidth.** A third implementation adds review load; decision 0 and the spike exist to
  decide whether it is worth it before T1 starts.

## 6. How much of each contract is reused verbatim

| Contract | Verbatim | Replaced or changed for TypeScript |
|---|---|---|
| core | sections 1 (phases, shutdown, health, criticality) semantics, 2, 3, 4 in full, 5 | the API of section 1 (modules, `get`/`find`, `override`); see capabilities section 1 |
| database-and-redis | sections 2 and 3, section 1's rules | the drivers (`pg`, `Bun.sql`, the RESP client) |
| locks | sections 1 to 5, the SQL and the scripts | results instead of exceptions; signals instead of interrupts; the suite's form |
| write-path | sections 2, 4 (except `INTERRUPTED`), 5, 6, 7, 7a, 8 | section 1 (`Fresh`/`Loaded`), section 3's API |
| checked-sql | section 2's semantics, section 5's scope (from T7) | the generator's form |
| http | sections 5, 6, 7 (outcomes), 8 (behaviour); section 2's wire rules | section 2's pattern syntax and wildcard, CORS and static assets (decisions 26, 27); sections 1, 3, 4, 9 (route values, WHATWG types); section 10 (Datastar) not implemented |
| rate-limiting | all, including the Redis script | the middleware's form |
| secrets | all | errors as results |
| background-and-metrics | all rules and bytes | the API; a thrown tick ends a loop as Java's `Error` does |
| http-client | all behaviour | the transport (`fetch`), errors as results, no `INTERRUPTED` |
| flowcatalyst | all wire behaviour and settings | typed handlers and their decode rule; timeouts without cancellation; unused receiver routes not mounted; the receiver principal applied |
| build | none | a TypeScript build contract (`contracts/typescript-build.md`) written in T0: workspace, pins, lint, CI on two runtimes, the scriptc job, licences, distribution |
