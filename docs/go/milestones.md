# Go framework: milestones, testing and conformance (draft 1, 2026-10-13)

**UN-PARKED (owner, 2026-10-18; reverses the 2026-10-16 parking).** This is the Go *application framework* (`go/`), not the FlowCatalyst platform, which stays Go. G0 to G4 are built and tested (core, config, locks, write path and checked SQL, HTTP, OpenAPI and the SPA, rate limiting, HTTP client, secrets, the FlowCatalyst module and receivers; vectors pass against the Java writer). The owner resumed at G5b (sessions and login) and runs it in parallel with Java; **Java is the reference implementation**, and the contracts and the vectors under `conformance/` are the shared truth that Go must pass. G5a (the orders SPA on the Go server) and G6 onward are not started. Nothing is published.

Companion to [`design.md`](design.md). The Java order (M1, M2a, M2b, M3, M4a, M4b, the MA items,
M-UI, M4c, M4d to M4f, M5) is kept where a dependency is real; the MA items are built in the
milestone where they belong, as the Rust plan did.

## 0. Status

Building started 2026-10-14 (owner go-ahead for G0 to G2). The guide's Go pages
([`../guide/go/index.md`](../guide/go/index.md)) say how to use what is built.

| # | State |
|---|---|
| G0 | **Built** (2026-10-14): `go/` with the core module, `go/flowcatalyst` (empty until G4) and `go/tools` (the pinned golangci-lint v2.14.0, NilAway and gofumpt, run by `go tool` so the local Go builds them; a third module, so the tools never enter the framework's module graph); `.golangci.yml`; `make -C go go-check`; `cmd/fwvet` with `mustliteral`; `fwtest` (embedded PostgreSQL 18, template and a database per test, Redis from `PATH`, fake clock); `internal/conformance` and the TSID vector; `.github/workflows/go.yml` (not run); [`go-build.md`](../contracts/go-build.md); `verify.sh` checks Go type names. NilAway on G0: no findings, no suppressions |
| G1 | **Built** (2026-10-14; brought in line with [config and code](../contracts/config-and-code.md) 2026-10-15, commit 24b2be1: no driver registry (`config.Section` has one key schema), lock stores named in code (`locks.WithStore`, `dblock.Store`, `redislock.Store`), removed keys and claimed components checked at load (`config.KeyRules`; the Go moved-to texts name Go APIs), pool and port ranges as `Setting.Checked`, `Boot.HealthReport`/`Ready`/`ShutdownDrain`; the vector for the key rules is deferred until the Java side writes one): `fw` lifecycle, `config`, `background`, `metrics` (with the endpoint handler; mounting it is G3), `database` (migration runner, `Tx` constructible only inside the framework, `Filter`), `redisfw`, `locks` + `dblock` + `redislock` + `lockstest`, `examples/locks-demo`. Vectors: config (every entry), lock SQL and Lua (byte for byte, copies listed in `go/internal/conformance/shared-files.txt`), `locks-database` history (Go writes the Java rows and accepts a Java-migrated database). Rulings taken: instance sections are checked at configure (drivers declare their keys); `url` accepts `jdbc:postgresql://`, `postgres://`, `postgresql://` but not pgx's `key=value` form; a duplicate YAML key is refused (recorded in STATUS, the vector has no such case). NilAway: three findings, all fixed in code (an ok-returning `Default` now returns `nil, false` explicitly; a test slice), no suppressions |
| G2 | **Built** (2026-10-14): `usecase` (the four-method interface, `Define`, `Run`, the runner with the failure table and step codes, `INTERRUPTED`, panic recovery, conflict retry with jitter, `Fresh`/`Loaded`, `Insert`/`Update`/`Delete` steps, `Plan` with `Emit` and `Dispatch`, the outbox writer and migrations, audit rows with ordered `AuditData`, `NewDispatchJob`, metrics, `usecasetest.Repository`), `event`, `id` (typed ids), `sqlfw` (`Main`: `schema`, `generate`, `check`, `jet`; `Query`) with `sqlfw/jetgen` and `internal/pgembed`, `examples/orders-demo` (sqlc queries, go-jet and `Filter` list queries, the Java demo's scenario and concurrency tests, a Java-migrated database accepted). Vectors: `outbox/outbox.json` byte for byte (every column, payload text and size, the dispatch-job refusals), `sql/outbox/insert.sql`, `migrations/outbox.json` and `orders.json`. go-jet adopted only as G2 says (the read model's builder; v2.16.1). Missing `Authorize` proven a compile error by a type-check test. Design section 8's sqlc points answered there. Not done: the `RunInTx` question (G4), an `fwvet` check for `usecase.Authorized[...]{}` literals |
| G3 | **Built** (2026-10-15): `httpfw` (the contract's route table as the framework's own segment trie, fixed middleware, envelope, `DecodeJSON` on `encoding/json/v2`, correlation id, access log and HTTP metrics, health and Prometheus endpoints, the server with body limit, timeouts and drain, `ContextResolver`), `httpfw/validate` (rules as data), `httpfw/spa` (index at start-up, fallback, API prefixes from the routes, strict CSP default), typed operations and `httpfw/openapi` (the emitter), `httpfw/sessionapi` (the session operations' document side), `fw` declaration-phase commands (the `openapi` command), `fw.Boot` health/ready/drain accessors and a serve grace, `examples/orders-demo` serving its API (`orders-demo serve`) with a committed `web/openapi.json`. Vectors: `http/spa.json` (all 86 requests), `openapi/fixture.json`, `auth.json` and `orders-demo.json` **byte for byte**. G1 brought in line with [config and code](../contracts/config-and-code.md) first (24b2be1). Rulings and differences: see the notes under this table. **Leftover built with G4 (2026-10-15):** `ratelimit` (memory limiter, middleware, key functions) and `ratelimit/redisrl` (the Java script, compared byte for byte with `RedisRateLimiter.java`); no rate-limit vector exists (the Java tests have not written one, so the script and the arithmetic are checked against the Java source and the Java tests' numbers). Not done in G3 (`httpfw.CSRFGuard` and `SecurityHeaders` were built as G5b slice 1, 2026-10-18): a generated hey-api client compiled under the UI tsconfig from the Go document (the bytes equal `examples/orders-demo/web/openapi.json`, from which `ui/apps/orders` was generated and tested in U3), and the error, health, metrics and rate-limit vectors, which the Java tests have not written (the Go tests check those rules themselves) |
| G4 | **Built** (2026-10-15; the end-to-end check against a real platform: see "G4 as built", item *End to end*): `httpclient` (library on `net/http`) and `httpclient/module` (clients named in code, base URL and credentials in configuration, `http-client.max-connections-per-host` the only engine setting, metrics, correlation id), `secrets` (`Env`, `File`, cache policy, resolver), and the `flowcatalyst` module in `go/flowcatalyst` (client wrapper over fcsdk, definitions with validation, `Sync` with dry run and the `sync` command, the `flowcatalyst` and `outbox` health checks, both receivers running as the service principal, `Once` and its purge). Vectors: `receivers/context.json` (all 11 cases, Java-written; the signatures in it are recomputed by the Go code). No secrets, signature or receiver-response vector exists on the Java side; the Go tests carry the Java tests' signature vectors (`a7d62a...`, `0278cf...`) and the receiver test list of flowcatalyst section 6. fcsdk had every sync category already (connections, scheduled jobs and docs added 2026-10-13): no gaps to record in the platform repository. NilAway: findings in test helpers and two nil returns, all fixed, no suppressions |
| G5b | **In progress** (owner resumed 2026-10-18): slice 1 `httpfw.CSRFGuard` and `SecurityHeaders`; slice 2 package `auth` relying party, `PlatformTokens` and `auth/authtest.Provider`; slice 3 the stores (`memory`, `cookie`, `authdb`, `authredis`) and the manager; slice 4 (endpoints, resolver, test helpers, `login-failure.json`) follow. The plan row below names fcsdk `auth` for the relying party; the contract (section 2) and the Java code use no HTTP-client module and no SDK for the three platform calls, and Go does the same (`net/http`, `crypto/rsa`), so the module has no fcsdk dependency |
| G5a, G6, G7 | not started |

**G3 as built, and where it differs** (2026-10-15):

- *Router (decision 16).* The framework's own trie instead of `ServeMux` behind a table: the mux's
  404, 405 and redirect behaviour had to be bypassed anyway, and its refusal of two patterns that
  overlap with neither more specific (`/a/{x}/c`, `/a/b/{y}`) disappears, so Go and Java route
  alike and that expected difference is not needed. `net/http`'s server, `Handler`, `httptest` and
  `r.PathValue` are used as designed.
- *Validation (decision 17).* The OpenAPI contract wins over the parse-function collector sketched
  in the capabilities page: `httpfw/validate` is rules as data (the Java `Validator` and `Rule`), so
  the constraints of the document and the checks of the server have one source. The parse-style
  `validate.Field(&v, ...)` is not built; the orders demo keeps its domain constructors.
- *Strict CSP is the SPA default* (static-assets contract section 7, owner ruling); the Java builder
  has the same default since 2026-10-15, so `TestSPAVector` uses the default and the vector
  (regenerated that day) pins the strict policy.
- *Pointer fields need `omitzero`.* The document calls them "may be absent", so a nil must not be
  written as `null`; the emitter refuses a pointer field without `omitzero` or `omitempty`.
- *Refused types*: `int`, unsigned integers and `float32` (use `int32`, `int64`, `float64`);
  embedded structs; `Date`, `Decimal`, `UUID` and ISO-8601 duration value types are not provided
  (the fixture test defines them with `SchemaProvider`).
- *Declaration-phase commands* are new in `fw` (`Declarer.DeclarationCommand`; core contract
  section 5 describes the Java `DeclarationCommand`): `openapi` runs before configuration.
- *Shutdown*: `fw` waits for a `Serve` that has a `ShutdownGrace` for that long plus a second, not
  `app.shutdown.drain`; and it no longer reads a serve error that is not there yet (a data race when
  serve overran the drain).
- *Details of use-case errors* come from a map, so they are written sorted by key (Java writes its
  map's insertion order).
- *A request the client abandoned* is recorded as 499 (Java records 204); nothing is written.
- *Request lines `net/http` cannot parse* get its plain-text 400 where Jetty's errors use the
  envelope; the `Content-Type`/`Allow` and 413-with-`Connection: close` rules hold.
- *The `ETag` header is stored as `ETag`* (the contract's spelling) instead of Go's canonical `Etag`.
- *orders-demo*: a fixed-token `ContextResolver` (`orders.demo-token`) and `501 NOT_IMPLEMENTED`
  session endpoints stand in for G5b; the embedded SPA is a placeholder page until `ui/apps/orders`
  is built into `web/dist`.

**G4 as built, and where it differs** (2026-10-15):

- *Code, not configuration.* Rate limiters (`ratelimit`, `redisrl`), secret stores and cache policies,
  named HTTP clients with their `RetryPolicy` and `ClientAuth` are declared in code. The service
  settings stay configuration (`flowcatalyst.*`, `flowcatalyst.receivers.*`, `http-client.*`), with
  the component claimed and the removed keys of the Java names failing start-up with the Go API to
  use instead (`config.KeyRules`).
- *`http-client.event-loops` is not declared*: `net/http` has no event loops to size. `max-connections-per-host`
  is kept (it is `Transport.MaxConnsPerHost`). A decision, not an omission.
- *Errors are values*: `*httpclient.Error` with a `Kind` (adds `KindCanceled`), not exceptions. A decoded
  body is held to the size limit like a raw one.
- *Sections without a `default` instance* (`Section.NoDefault`; `fw.Boot.LookupEnv`): `http-clients.default`
  is a removed key, so instance sections could not insist on a default.
- *File secrets and case-insensitive file systems*: Go's `EvalSymlinks` keeps the case the caller typed,
  so the store compares the path with the directory's own spelling (`exactCase`); a path that differs only
  in case is refused on macOS as it would be on Linux.
- *fcsdk resolution.* fcsdk was unreleased during G4 and the end-to-end result was checked against a
  local checkout; it is now released (`pkg/fcsdk/v0.0.2`) and `go/flowcatalyst/go.mod` requires it, no
  `replace`. `make -C go go-check` and the flowcatalyst tests pass against the released module (the
  G4 "unverified against a release" note is closed). An uncommitted `go.local.work` (`make fc-modfile
  FCSDK=<path>`) remains the option for unreleased fcsdk; `make fc-tidy` runs `go mod tidy`.
  `go tool -modfile` is refused in workspace mode, so golangci-lint and NilAway are built into
  `go/bin` (`make tools`). CI is not edited: it needs a platform checkout (owner decision pending).
- *No fcsdk sync category is missing* (connections, scheduled jobs and docs exist). Docs are synced by
  the Go module; Java skips them.
- *fcsdk retries* 5xx and 425 itself; the platform client uses two SDK clients (retry 3, and 1 for
  reporting) sharing one client-credentials provider, so the token is fetched once.
- *The service principal* is the `sub` of the client-credentials token, accepted only when `type` is
  `SERVICE`; the refusal never shows the token. Causation is the dispatch job id, or the instance id
  for a scheduled job (`conformance/receivers/context.json`, 11 of 11).
- *Receivers*: no 405 for a receiver that has no handlers (its route is not mounted); optional
  integers use 0 for "unset"; `OnDispatch[T]` takes the event type pattern explicitly; a handler that
  ignores its context and returns late is cut off by a timer, not a context deadline, so a handler
  returning `ctx.Err()` is still a timeout. Responses are written by hand where json/v2's map order
  would vary (`{"ack":false,"delaySeconds":N}`).
- *`Once`* hands the work a `*database.Tx` (the transaction that also holds the claim row);
  `RunInTx` stays open. The purge is a background task under the `flowcatalyst-once-purge` health name.
- *Scheduled job builders* (`WithPayload`, `Targeting`, `WithTimeout`, `WithDeliveryAttempts`) were added
  after the end-to-end check showed a job target cannot otherwise be set; a job's target must be an
  absolute URL (a subscription's may be a path against `subscription-target-base-url`).
- *Vectors*: Java has none for rate limiting, secrets, signatures or receiver responses. The Lua script
  is compared byte for byte with the Java text block; the signature vectors of the Java tests are in
  the Go tests; IP literal parsing differs where Go's `netip` is stricter than Java's.
- *End to end* (2026-10-15, real platform, isolated: own embedded cluster on 25433, API 18480, state in
  the scratchpad, dropped afterwards; 8080, 15432 and the developer's fcdev untouched). Verified:
  `sync --dry-run` and `sync` against the platform (event type, connection, subscription, scheduled job
  created, then updated on re-sync); an event posted to `/api/events/batch` reaches the receiver as a
  signed `POST /fc/dispatch`, the signature verifies, the handler runs as the application's service
  principal (equal to the token `sub`), causation is the dispatch job id, `Once` records the delivery;
  a fired scheduled job is delivered, run and reported: instance `COMPLETED`, result `{"recorded":1}`.
  Not verified: `fcdev outbox` delivering orders-demo's outbox (the events were posted directly), and
  the `outbox` health check against a live outbox (covered by the database tests).
  Platform findings, for the platform repository (not edited here): a code-first connection stores the
  application's principal id (`prn_`) as its service account, and the delivery signer then looks it up
  as a service account id and delivers unsigned ("service account prn_... does not exist"); the
  deliveries were refused with `MISSING_SIGNATURE` until the row was set to the `sac_` id in the
  isolated database. The connection update API accepts the field but keeps the `prn_` value.
  That is a platform-side defect, not an fcsdk API gap: `git log pkg/fcsdk` in the platform repository
  has nothing after `pkg/fcsdk/v0.0.2`, and the G4 code compiles and passes `make -C go go-check`
  against the released `v0.0.2` (checked 2026-10-15).

*2026-10-14 (owner): the UI package is shared with Java and built first against the Java backend.*
G5a's U0 to U2 (and U3, U4 on the Java example) are therefore done once, outside this plan; Go's G5a
keeps U3 on the Go orders-demo, `httpfw/spa` and the emitter's conformance with
[`../contracts/openapi.md`](../contracts/openapi.md); G6 reuses the shared admin pages. Estimates below
are unchanged until the shared steps have real numbers.

## 1. Milestones

Numbered G0 to G7 so they are not confused with the Java (M) and Rust (R) ones.

| # | Scope | Java equivalent | Depends on | Exit criteria |
|---|---|---|---|---|
| G0 | **Skeleton, CI, conformance runner.** `go/` with two `go.mod` files (core, `flowcatalyst`; design 2.2), Go 1.27; `.golangci.yml` (the platform's profile plus `gochecksumtype`, `forbidigo`, revive `exported`), gofumpt and goimports; NilAway pinned; `cmd/fwvet` with its first analyzer (literal arguments of `Must*`); `fwtest` (embedded PostgreSQL 18 per test binary on a free port, a template database and a database per test, `redis-server` from `PATH` or `FW_TEST_REDIS_URL`, a fake clock); `internal/conformance` reading `../conformance` with the README's comparison rules and `"go": {...}` allowed differences; the TSID codec and its vector; `make go-check` (lint, vet, NilAway, `-race` tests); `.github/workflows/go.yml` (written, not run); a Go build contract (`contracts/go-build.md`, like `rust-build.md`); `docs/guide/verify.sh` extended to check Go type names on pages under `docs/guide/go/` against `go/` | M1 step 1, MA 1 (in part) | decisions 1 to 6 | lint, NilAway and tests green on the skeleton; `conformance/tsid/tsid.json` read and passed by a Go test; a test proves the fixture never touches 15432 or 8080 |
| G1 | **Foundation and locks.** `fw` (App, `Declare` handles, `Boot`, `Launch`, commands, `Context` and ids, `Clock`, `Ids`, health with criticality, shutdown order), `config` (every rule, `Secret`), instances and driver key schemas checked at configure, `background` (loops, gate), `metrics` (registry, exposition writer, endpoint), `database` (pgxpool instances, the migration runner on `fw_schema_history`, `DB`, `Tx`, `Filter`), `redisfw`, `locks` with `memory`, `dblock`, `redislock` (the shared statements and scripts, embedded copies checked byte for byte) and `lockstest`; `examples/locks-demo` | M1, MA 3, MA 7 | G0; decisions 8 to 11, 20, 21, 24, 25 | the lock suite green on all three drivers; locks-demo starts with three instances and shuts down loops first, then actions in reverse; the config vectors match (with the duplicate-key difference recorded); the lock SQL and Lua bytes equal the shared files; a database holding the Java runner's `locks-database` history is accepted and the Go runner writes the same rows |
| G2 | **Write path and checked SQL.** `usecase` (the interface and `Define`, the runner with the failure table and step codes, `INTERRUPTED`, panic recovery, conflict retry with jitter, `Fresh`/`Loaded`, steps and `Plan`, events and the factory, the outbox writer on the shared `INSERT`, the outbox migrations as the Java files, audit rows, `DispatchJob` rows, use-case metrics, `usecasetest.Repository`), `fw sql schema`, `fw sql check` (embedded PostgreSQL, framework runner, `sqlc vet` with `db-prepare`, `sqlc diff`), `fw sql jet` (generation and check for go-jet), `sqlfw.Query`; `examples/orders-demo` on the Java demo's migration, with the read model's list query in go-jet and in `Filter` | M2a, M2b, MA 4 | G1; decisions 12 to 15 | the outbox, audit and dispatch-job rows equal `conformance/outbox/outbox.json` **byte for byte** (payload text and size); the outbox `INSERT` equals `conformance/sql/outbox/insert.sql`; every failure row of write-path section 4 has a runner test; orders-demo runs against a database first migrated by the Java demo (`conformance/migrations/outbox.json`, `orders.json`); `fw sql check` fails on a stale query in CI; the unverified sqlc points of design section 8 are answered |
| G3 | **HTTP.** `httpfw` (routes on `ServeMux` with the framework's table, fallbacks, middleware order, envelope, `DecodeJSON` on `encoding/json/v2`, `validate`, correlation id, access log, health and metrics endpoints, server, body limit, drain, `ContextResolver`), `ratelimit` (memory, `redisrl` with the shared Lua); **typed operations and the OpenAPI emitter** (descriptors at declare time, `Bind`, the schema walk with `EnumValuer`, `SchemaProvider`, `Nullable`, `CursorPage`, the `openapi` command and its drift check); `httpfw.CSRF` and `httpfw.SecurityHeaders`; no Datastar adapter (decision 27) | M3, MA 8 (by construction), MA 9 | G1 (G2 for use-case errors); decisions 16 to 18, 26, 27 | every requirement of http section 12 that applies except section 10; error, health, rate-limit and Prometheus vectors byte-identical to the Java ones (**the Java tests write those vectors first**); graceful shutdown with an in-flight request; the router differences recorded as allowed; orders-demo's document dumped without a database, valid OpenAPI 3.1, and a generated hey-api client of it compiling under the UI's tsconfig |
| G4 | **Platform.** `httpclient` and its adapter, `secrets` (env, file), the `flowcatalyst` module (the client wrapper over fcsdk, definitions and `sync` with dry run, the `flowcatalyst` and `outbox` health checks, both receivers with the receiver principal ruling, `Once` and its purge) | MA 6, MA 5, M4a, M4b | G3; fcsdk as its own module (decision 4) and its missing sync categories; decisions 22, 23 | the shared signature vectors and every receiver response vector (Java-written) match; the receiver test list of flowcatalyst section 6; end to end: the Go `fcdev` outbox processor delivers the Go orders-demo's outbox to a local platform, and a dispatch job and a scheduled job reach the Go receivers |
| G5a | **The TypeScript UI package** ([`ui.md`](ui.md) section 17): U0 the pnpm workspace, strict tsconfig and type-aware lint, Nuxt UI wired without Nuxt, the theme preset from the kit's tokens, bundled icons, the gallery under a strict CSP with axe; U1 the covered, composed and small built components; U2 `FwAppShell`, `FwListPage`, `FwRecordDrawer`, `FwFormDialog`, `FwTableToolbar`, `FwCursorPager`, the composables, `@flowcatalyst/api-runtime`; U3 the orders-demo SPA on the generated client, `httpfw/spa` serving it embedded | M-UI (a different stack: Nuxt UI, not JTE) | U0 to U2: decisions 7, 30 (may start before G1); U3: G3, decisions 19, 28 | every component and partial in the gallery passes axe, the Playwright behaviour checks, the CSP check and the approved screenshots; orders-demo's list, drawer and add-line form work against the real Go server from the embedded build; drift checks for `openapi.json` and the client in CI |
| G5b | **Sessions and login for the SPA.** sessions (database, redis, cookie), the OIDC relying party on fcsdk `auth`, the BFF endpoints (`/auth/login`, `/auth/callback`, `/auth/session`, `/auth/logout`), CSRF on cookie-authenticated requests, the real `ContextResolver` (principal, kind, permissions); in `@flowcatalyst/ui`: the session guard, route permissions, the signed-out, not-permitted and error pages (U4) | M4c | G4, G5a, **the M4c contract written JSON-first** (decision 29) | as the contract's test list; the orders-demo E2E signs in against a test OIDC provider; the shared token vectors; a security review (Opus for the slice, the owner's ruling for security-sensitive work) |
| G6 | **Admin.** the admin skeleton and pages, as `@flowcatalyst/ui` pages over framework operations | M4d, M4e | G5b; decision 31 (shared with Java or not) | as the Java milestones |
| G7 | **The comparison slice.** The part of a business application chosen by the owner, built on both frameworks against the same contracts and database, and measured | M4f | G6 or earlier, depending on the slice (decisions 31, 34) | the measurements of decision 34, side by side with the Java build |
| later | cache (fcsdk `cache` as the start), storage, platform config driver, metering, messaging | M5 | the contracts and the platform | as the Java contracts |

A milestone is done when its tests and conformance vectors pass, the guide has a Go page for each
public capability (section 4), and STATUS records it.

## 2. Testing strategy

| Level | What | How |
|---|---|---|
| unit | config parsing, key and code rules, GCRA, TSID, route patterns, signature checks, the exposition writer, the OpenAPI schema walk | table tests; `testing/fuzz` where the input space is large (key grammar, path encoding, TSID decoding, signature headers) |
| time-dependent | lock leases, loop schedules, retries, token expiry, rate limits | a fake `fw.Clock`; `testing/synctest` bubbles for code that sleeps or waits on timers |
| integration | drivers, the use-case runner, migrations, receivers with `Once` | `fwtest`: one embedded PostgreSQL 18 per test binary, a fresh database per test from a migrated template, `t.Parallel()` allowed; a real Redis per test binary |
| contract suites | every driver passes the same suite (locks, rate-limit stores, secret stores, session stores, repositories) | `lockstest.Run(t, harness)` and siblings: one `t.Run` per case |
| HTTP | routing, middleware order, envelopes, validation, receivers | `httptest` in process; a few real-socket tests on `127.0.0.1:0` |
| conformance | wire and storage output equal to the Java framework's | section 3 |
| UI | components, partials and pages behave and look as the kit specs and the approved screenshots say | vitest; the gallery with Playwright, axe, screenshots and a CSP check; the example E2E against the Go server ([`ui.md`](ui.md) section 12) |
| end to end | the platform accepts what the framework writes | section 3.4 |

Rules: `go test -race ./...` everywhere; no build tags that hide tests; no test connects to 15432 or
binds 8080; time comes from the fake clock; every failure message names the vector, case and field.

## 3. Conformance against the Java framework

### 3.1 Vectors that exist

| Vector | Go reads it in | Milestone |
|---|---|---|
| `conformance/tsid/tsid.json` | `fw` (TSID codec, prefixes, layout) | G0 |
| `conformance/config/config.json` | `config` | G1 |
| `conformance/sql/locks/*.sql`, `conformance/lua/locks/*.lua` | `dblock`, `redislock` (embedded copies compared byte for byte) | G1 |
| `conformance/migrations/locks-database.json` | `database` runner | G1 |
| `conformance/sql/outbox/insert.sql` | `usecase` outbox writer | G2 |
| `conformance/outbox/outbox.json` | `usecase` (rows byte for byte, the dispatch-job refusals) | G2 |
| `conformance/migrations/outbox.json`, `orders.json` | `database` runner, orders-demo | G2 |
| `conformance/http/spa.json` | `httpfw/spa` through the dispatcher (`internal/conformance/http_test.go`; all 86 cases: status, headers, body length and hash) | G3 |
| `conformance/openapi/fixture.json`, `auth.json`, `orders-demo.json` | `httpfw/openapi` through `Declared.OpenAPI` (exact bytes) | G3 |

### 3.2 Vectors still to be written by the Java tests

The Rust milestones (section 3.1 there) planned these, and none was written: `errors/`, `health/`,
`datastar/`, `metrics/`, `ratelimit/` (and `lua/ratelimit/`), `secrets/`, `receivers/signature/`,
`receivers/dispatch/`, `receivers/scheduled-jobs/`, `htmlnorm/`. Go needs all but `datastar/` and
`htmlnorm/` (decision 27). Each is written by a Java test in update mode before the Go milestone
that needs it claims conformance (G3: errors, health, metrics, rate limiting; G4: secrets,
receivers). Java stays the reference: a Go
mismatch means Go is wrong, or the vector exposes a Java bug, fixed in Java first.

### 3.3 Comparison rules

The README's: JSON compared as parsed values, `sql/` and `lua/` as exact bytes, SSE, Prometheus text
and status lines and header values as exact bytes; pinned clocks and ids; an allowed difference
recorded in the vector as `"go": {...}` with a reason. Expected Go entries: the YAML duplicate-key
refusal (config), the router's overlap conflict and the unused receiver's 404 instead of 405 (HTTP,
receivers), if the owner accepts them (decisions 16, 23).

### 3.4 End to end (manual, then nightly)

1. **Outbox:** the Go orders-demo writes events, audit rows and dispatch jobs; the platform's outbox
   processor (`fcdev outbox`) delivers them with the application's service account; the platform
   shows the same events, audit entries and jobs as for the Java demo running the same scenario.
2. **Receivers:** a subscription pointed at the Go receiver gets a signed dispatch job and a
   scheduled-job firing; acknowledgement, deferral, rejection and completion look on the platform as
   for Java.
3. **Shared storage:** a Java and a Go process contend for one `fw_lock` key and one Redis
   rate-limit key; mutual exclusion and one shared budget hold.

## 4. Documentation

The guide gains Go pages under `docs/guide/go/` (an overview, then one per capability, as the Rust
pages), under the same rules: `<!-- types: ... -->` naming Go types that `verify.sh` finds declared
under `go/` (G0 extends the script), snippets from compiled code, STATUS kept current. Go code is
documented with doc comments (`go doc`); the guide links to them. Vue single-file components are
shown as files, never inline: their double-brace interpolation is Liquid syntax to the site. The UI
packages get their own guide pages (components, partials, the typed client, sessions).

## 5. Effort and risk

Days of agent-assisted work at the Java pace (M1 to MA in three days); a human team should multiply.
Go's `net/http`, `context` and goroutines match the Java contracts' blocking model more closely than
Rust's async does, so the estimates are lower than Rust's. The UI is now new work in TypeScript
(the 2026-10-13 ruling), not an extraction of the platform's templ kit.

| # | Effort | Main risk | Mitigation |
|---|---|---|---|
| G0 | 0.5 d | NilAway noise on generic code; embedded PostgreSQL download in CI | run NilAway on G0's small surface first and record suppressions; cache the binaries |
| G1 | 3 d | the `Declare`/handle API is awkward in practice | build locks-demo first and adjust before other packages depend on it |
| G2 | 3 to 4 d | byte-for-byte outbox JSON from `encoding/json/v2` (key order, escaping of `<`, `>`, `&` and U+2028, number and time formats against Jackson); sqlc's static analysis on the orders read model | the vector from day one; `jsontext` options for escaping; times formatted by the framework, not by `time.Time`'s default |
| G3 | 3.5 to 5 d (1.5 to 2 d of it the typed operations and the OpenAPI emitter) | `ServeMux`'s own 404/405 bodies, redirects and conflict panics against the contract; the schema walk (generics, naming, nullability) taking longer than planned | the framework's table decides before the mux; every error row is a vector; huma's schema registry as the fallback (decision 18) |
| G4 | 3 to 4 d, plus about 1 d in the platform repository for fcsdk as a module with the missing sync categories | the module path and licence of fcsdk; goroutines that outlive their timeout | decisions 3 and 4 before starting; tracked goroutines and the drain test |
| G5a | 6 to 9 d (U0 1 d, U1 1.5 to 2 d, U2 2.5 to 4 d, U3 1 to 2 d) | Nuxt UI gaps larger than the mapping table says (pick list, toolbar, drawer partial); `exactOptionalPropertyTypes` or the CSP forcing exceptions; screenshot flakiness | U0 answers the unverified points first; screenshots in the pinned Playwright container; partials built against fixture data before the server exists |
| G5b | 4 to 6 d | security-sensitive; no contract yet | wait for the JSON-first contract; negative tests first; review |
| G6, G7 | as Java | | |

Cross-cutting risks:

- **Three implementations drift** (Java, Rust parked at R2, Go). Controlled by the shared vectors and
  fixtures, one repository, and the rule that a contract change lands with every active
  implementation or with a STATUS note on the one that lags. Rust is parked, so its lag is expected
  and recorded.
- **What Go cannot check at compile time** (sum types, nil, literal formats) is checked by lint and
  `fwvet`; a lint that is not run is no check, so `make go-check` is what CI runs and what a commit
  needs.
- **Two SQL generators** (sqlc and go-jet) on the same tables: the generated code can disagree
  after a migration if only one is regenerated. `fw sql check` regenerates and checks both.
- **Owner bandwidth.** A second active framework doubles review load; G0 to G2 first keeps it
  bounded.
- **A second language in the Go framework.** The UI is TypeScript with its own toolchain, lockfile
  and upgrade cadence (Nuxt UI minors, hey-api 0.x). Exact pins, the drift checks and the gallery's
  screenshots make upgrades visible; they are deliberate, never automatic.
- **More UI stacks in the family** (the platform's PrimeVue SPA and templ port, Java's planned JTE,
  Go's Nuxt UI): decisions 31 and 32.

## 6. How much of each contract is reused verbatim

| Contract | Verbatim | Replaced or changed for Go |
|---|---|---|
| core | sections 1 (phases, shutdown, health, criticality) semantics, 2, 3, 4 in full, 5 | the API of section 1 (`Declare` handles, `build`, no `get`/`find`/`override`) |
| database-and-redis | sections 2 and 3, section 1's rules | HikariCP and Jedis become pgxpool and go-redis |
| locks | sections 1 to 5, the SQL and the scripts | interruption becomes context cancellation; the suite's form |
| write-path | sections 2, 4 (including `INTERRUPTED`), 5, 6, 7, 7a, 8 | section 1 (`Fresh`/`Loaded`), section 3's API (interface, steps), section 9 (sqlc and pgx) |
| checked-sql | section 2's semantics, section 5's scope | sections 1, 3, 4, 6: sqlc, overrides in `sqlc.yaml`, `fw sql check` |
| http | sections 2 (observable rules), 5, 6, 7 (outcomes), 8 (behaviour) | sections 1, 3, 4, 9: `ServeMux`, `HandlerFunc` returning `error`, `httptest`; one routing difference; section 10 (Datastar) not implemented; section 11 amended for static assets (decision 28); typed operations and OpenAPI are Go's own addition (decision 18) |
| rate-limiting | all, including the Redis script | the middleware's form |
| secrets | all | no zeroisation (as Java) |
| background-and-metrics | all rules and bytes | the API; panics for `Error` |
| http-client | all behaviour | the engine (`net/http`), errors as values |
| flowcatalyst | all wire behaviour and settings | typed handlers and their decode rule; the receiver principal per the ruling; unused receivers not routed |
| build | none | a Go build contract (modules, tags, toolchain, lint) written in G0 |
