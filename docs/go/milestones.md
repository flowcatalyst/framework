# Go framework: milestones, testing and conformance (draft 1, 2026-10-13)

Companion to [`design.md`](design.md). The Java order (M1, M2a, M2b, M3, M4a, M4b, the MA items,
M-UI, M4c, M4d to M4f, M5) is kept where a dependency is real; the MA items are built in the
milestone where they belong, as the Rust plan did.

## 0. Status

Nothing is built. Building starts after the owner's review of [`design.md`](design.md) section 9.

| # | State |
|---|---|
| G0 to G7 | not started |

## 1. Milestones

Numbered G0 to G7 so they are not confused with the Java (M) and Rust (R) ones.

| # | Scope | Java equivalent | Depends on | Exit criteria |
|---|---|---|---|---|
| G0 | **Skeleton, CI, conformance runner.** `go/` with three `go.mod` files (core, `flowcatalyst`, `ui`; design 2.2), Go 1.27; `.golangci.yml` (the platform's profile plus `gochecksumtype`, `forbidigo`, revive `exported`), gofumpt and goimports; NilAway pinned; `cmd/fwvet` with its first analyzer (literal arguments of `Must*`); `fwtest` (embedded PostgreSQL 18 per test binary on a free port, a template database and a database per test, `redis-server` from `PATH` or `FW_TEST_REDIS_URL`, a fake clock); `internal/conformance` reading `../conformance` with the README's comparison rules and `"go": {...}` allowed differences; the TSID codec and its vector; `make go-check` (lint, vet, NilAway, `-race` tests); `.github/workflows/go.yml` (written, not run); a Go build contract (`contracts/go-build.md`, like `rust-build.md`); `docs/guide/verify.sh` extended to check Go type names on pages under `docs/guide/go/` against `go/` | M1 step 1, MA 1 (in part) | decisions 1 to 6 | lint, NilAway and tests green on the skeleton; `conformance/tsid/tsid.json` read and passed by a Go test; a test proves the fixture never touches 15432 or 8080 |
| G1 | **Foundation and locks.** `fw` (App, `Declare` handles, `Boot`, `Launch`, commands, `Context` and ids, `Clock`, `Ids`, health with criticality, shutdown order), `config` (every rule, `Secret`), instances and driver key schemas checked at configure, `background` (loops, gate), `metrics` (registry, exposition writer, endpoint), `database` (pgxpool instances, the migration runner on `fw_schema_history`, `DB`, `Tx`, `Filter`), `redisfw`, `locks` with `memory`, `dblock`, `redislock` (the shared statements and scripts, embedded copies checked byte for byte) and `lockstest`; `examples/locks-demo` | M1, MA 3, MA 7 | G0; decisions 7 to 10, 18, 19, 22, 23 | the lock suite green on all three drivers; locks-demo starts with three instances and shuts down loops first, then actions in reverse; the config vectors match (with the duplicate-key difference recorded); the lock SQL and Lua bytes equal the shared files; a database holding the Java runner's `locks-database` history is accepted and the Go runner writes the same rows |
| G2 | **Write path and checked SQL.** `usecase` (the interface and `Define`, the runner with the failure table and step codes, `INTERRUPTED`, panic recovery, conflict retry with jitter, `Fresh`/`Loaded`, steps and `Plan`, events and the factory, the outbox writer on the shared `INSERT`, the outbox migrations as the Java files, audit rows, `DispatchJob` rows, use-case metrics, `usecasetest.Repository`), `fw sql schema`, `fw sql check` (embedded PostgreSQL, framework runner, `sqlc vet` with `db-prepare`, `sqlc diff`), `fw sql jet` (generation and check for go-jet), `sqlfw.Query`; `examples/orders-demo` on the Java demo's migration, with the read model's list query in go-jet and in `Filter` | M2a, M2b, MA 4 | G1; decisions 11 to 14 | the outbox, audit and dispatch-job rows equal `conformance/outbox/outbox.json` **byte for byte** (payload text and size); the outbox `INSERT` equals `conformance/sql/outbox/insert.sql`; every failure row of write-path section 4 has a runner test; orders-demo runs against a database first migrated by the Java demo (`conformance/migrations/outbox.json`, `orders.json`); `fw sql check` fails on a stale query in CI; the unverified sqlc points of design section 8 are answered |
| G3 | **HTTP.** `httpfw` (routes on `ServeMux` with the framework's table, fallbacks, middleware order, envelope, `DecodeJSON` on `encoding/json/v2`, `validate`, correlation id, access log, health and metrics endpoints, server, body limit, drain, `ContextResolver`), `ratelimit` (memory, `redisrl` with the shared Lua); Datastar through `go/ui/ds` or the framework's adapter (decision 17) | M3, MA 8 (by construction), MA 9 | G1 (G2 for use-case errors); decisions 15 to 17, 24 | every requirement of http section 12 that applies; error, health, Datastar, rate-limit and Prometheus vectors byte-identical to the Java ones (**the Java tests write those vectors first**); graceful shutdown with an in-flight request; the router differences recorded as allowed |
| G4 | **Platform.** `httpclient` and its adapter, `secrets` (env, file), the `flowcatalyst` module (the client wrapper over fcsdk, definitions and `sync` with dry run, the `flowcatalyst` and `outbox` health checks, both receivers with the receiver principal ruling, `Once` and its purge) | MA 6, MA 5, M4a, M4b | G3; fcsdk as its own module (decision 4) and its missing sync categories; decisions 20, 21 | the shared signature vectors and every receiver response vector (Java-written) match; the receiver test list of flowcatalyst section 6; end to end: the Go `fcdev` outbox processor delivers the Go orders-demo's outbox to a local platform, and a dispatch job and a scheduled job reach the Go receivers |
| G5a | **UI kit module.** `go/ui`: `kit`, `htmlnorm`, `ds`, `assets`, `compress` moved from the platform's `internal/ui` with their tests; fixtures read in place from `ui-kit/fixtures`; the asset build script in this repository; the view helper (`view.Respond`) | M-UI | G3; decision 25 | every fixture passes, as in the platform today; a page and a Datastar patch from one component in orders-demo; the platform builds against `go/ui` through a local `go.work` (the switch itself waits for the repository to be fetchable) |
| G5b | **Sessions and login.** sessions (cookie, database, redis), the OIDC relying party on fcsdk `auth`, the real `ContextResolver` (principal, kind, permissions), the default login, callback, logout and not-permitted pages on the kit | M4c | G4, G5a, **the M4c contract** | as the contract's test list; a security review (Opus for the slice, the owner's ruling for security-sensitive work) |
| G6 | **Admin.** the `/admin` skeleton and pages | M4d, M4e | G5b | as the Java milestones |
| G7 | **The comparison slice.** The part of a business application chosen by the owner, built on both frameworks against the same contracts and database, and measured | M4f | G6 or earlier, depending on the slice (decision 27) | the measurements of decision 27, side by side with the Java build |
| later | cache (fcsdk `cache` as the start), storage, platform config driver, metering, messaging | M5 | the contracts and the platform | as the Java contracts |

A milestone is done when its tests and conformance vectors pass, the guide has a Go page for each
public capability (section 4), and STATUS records it.

## 2. Testing strategy

| Level | What | How |
|---|---|---|
| unit | config parsing, key and code rules, GCRA, TSID, route patterns, signature checks, the exposition writer, the HTML normaliser | table tests; `testing/fuzz` where the input space is large (key grammar, path encoding, TSID decoding, signature headers) |
| time-dependent | lock leases, loop schedules, retries, token expiry, rate limits | a fake `fw.Clock`; `testing/synctest` bubbles for code that sleeps or waits on timers |
| integration | drivers, the use-case runner, migrations, receivers with `Once` | `fwtest`: one embedded PostgreSQL 18 per test binary, a fresh database per test from a migrated template, `t.Parallel()` allowed; a real Redis per test binary |
| contract suites | every driver passes the same suite (locks, rate-limit stores, secret stores, session stores, repositories) | `lockstest.Run(t, harness)` and siblings: one `t.Run` per case |
| HTTP | routing, middleware order, envelopes, validation, receivers | `httptest` in process; a few real-socket tests on `127.0.0.1:0` |
| conformance | wire and storage output equal to the Java framework's | section 3 |
| UI | rendering equal to the goldens | the kit's conformance test over `ui-kit/fixtures` |
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

### 3.2 Vectors still to be written by the Java tests

The Rust milestones (section 3.1 there) planned these, and none was written: `errors/`, `health/`,
`datastar/`, `metrics/`, `ratelimit/` (and `lua/ratelimit/`), `secrets/`, `receivers/signature/`,
`receivers/dispatch/`, `receivers/scheduled-jobs/`, `htmlnorm/`. Each is written by a Java test in
update mode before the Go milestone that needs it claims conformance (G3: errors, health, Datastar,
metrics, rate limiting; G4: secrets, receivers; G5a: htmlnorm). Java stays the reference: a Go
mismatch means Go is wrong, or the vector exposes a Java bug, fixed in Java first.

### 3.3 Comparison rules

The README's: JSON compared as parsed values, `sql/` and `lua/` as exact bytes, SSE, Prometheus text
and status lines and header values as exact bytes; pinned clocks and ids; an allowed difference
recorded in the vector as `"go": {...}` with a reason. Expected Go entries: the YAML duplicate-key
refusal (config), the router's overlap conflict and the unused receiver's 404 instead of 405 (HTTP,
receivers), if the owner accepts them (decisions 15, 21).

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
documented with doc comments (`go doc`); the guide links to them. templ templates are shown as
files, never inline (Liquid).

## 5. Effort and risk

Days of agent-assisted work at the Java pace (M1 to MA in three days); a human team should multiply.
Go's `net/http`, `context` and goroutines match the Java contracts' blocking model more closely than
Rust's async does, and the UI kit exists, so the estimates are lower than Rust's.

| # | Effort | Main risk | Mitigation |
|---|---|---|---|
| G0 | 0.5 d | NilAway noise on generic code; embedded PostgreSQL download in CI | run NilAway on G0's small surface first and record suppressions; cache the binaries |
| G1 | 3 d | the `Declare`/handle API is awkward in practice | build locks-demo first and adjust before other packages depend on it |
| G2 | 3 to 4 d | byte-for-byte outbox JSON from `encoding/json/v2` (key order, escaping of `<`, `>`, `&` and U+2028, number and time formats against Jackson); sqlc's static analysis on the orders read model | the vector from day one; `jsontext` options for escaping; times formatted by the framework, not by `time.Time`'s default |
| G3 | 2 to 3 d | `ServeMux`'s own 404/405 bodies, redirects and conflict panics against the contract | the framework's table decides before the mux; every error row is a vector |
| G4 | 3 to 4 d, plus about 1 d in the platform repository for fcsdk as a module with the missing sync categories | the module path and licence of fcsdk; goroutines that outlive their timeout | decisions 3 and 4 before starting; tracked goroutines and the drain test |
| G5a | 1 to 2 d | the move breaks the platform's build | copy first, switch the platform in a separate step behind `go.work` |
| G5b | 4 to 6 d | security-sensitive; no contract yet | wait for the contract; review |
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

## 6. How much of each contract is reused verbatim

| Contract | Verbatim | Replaced or changed for Go |
|---|---|---|
| core | sections 1 (phases, shutdown, health, criticality) semantics, 2, 3, 4 in full, 5 | the API of section 1 (`Declare` handles, `build`, no `get`/`find`/`override`) |
| database-and-redis | sections 2 and 3, section 1's rules | HikariCP and Jedis become pgxpool and go-redis |
| locks | sections 1 to 5, the SQL and the scripts | interruption becomes context cancellation; the suite's form |
| write-path | sections 2, 4 (including `INTERRUPTED`), 5, 6, 7, 7a, 8 | section 1 (`Fresh`/`Loaded`), section 3's API (interface, steps), section 9 (sqlc and pgx) |
| checked-sql | section 2's semantics, section 5's scope | sections 1, 3, 4, 6: sqlc, overrides in `sqlc.yaml`, `fw sql check` |
| http | sections 2 (observable rules), 5, 6, 7 (outcomes), 8 (behaviour), 10 | sections 1, 3, 4, 9: `ServeMux`, `HandlerFunc` returning `error`, `httptest`; one routing difference |
| rate-limiting | all, including the Redis script | the middleware's form |
| secrets | all | no zeroisation (as Java) |
| background-and-metrics | all rules and bytes | the API; panics for `Error` |
| http-client | all behaviour | the engine (`net/http`), errors as values |
| flowcatalyst | all wire behaviour and settings | typed handlers and their decode rule; the receiver principal per the ruling; unused receivers not routed |
| build | none | a Go build contract (modules, tags, toolchain, lint) written in G0 |
