# Rust framework: milestones, testing and conformance (draft 1, 2026-10-11; status kept current)

Companion to [`design.md`](design.md). The Java order (M1, M2a, M2b, M3, M4a, M4b, then the MA
usability items, then M-UI, M4c, M4d to M4f, M5) is kept where a dependency is real. The MA items
were gaps found after the fact; in Rust they are built in the milestone where they belong instead of
after it.

## 0. Status

Built so far (owner ruling 2026-10-12: build R0 to R2, hold R4 onwards). Each milestone's
deviations from this plan and from [`design.md`](design.md) are recorded here.

| # | State | Notes |
|---|---|---|
| R0 | **Built** (2026-10-12), commit `e116b5c` | see below |
| R1 | **Built** (2026-10-12), commits `0598015`, `60361b3` | see below |
| R2 | **Built** (2026-10-12) | see below |

**R0 as built.** `rust/` workspace (edition 2024, toolchain 1.98.1, the platform's lint table plus
`missing_docs`, `unreachable_pub`, `rust_2018_idioms`), `deny.toml` (permissive licences only, MPL
not allowed either; duplicate versions warn while the workspace is young), `.cargo/config.toml`
(`SQLX_OFFLINE=true`), `.github/workflows/rust.yml` (fmt, clippy, test against service containers,
deny), `fw-test`, the `fw-conformance` runner, the Rust build contract
([`rust-build.md`](../contracts/rust-build.md)). Exit criterion met: `conformance/tsid/tsid.json`
is written by the Java `IdsConformanceTest` and read by the Rust `tsid` test. Deviations:

- `fw-core` exists from R0, holding only the TSID codec, so that the first vector has Rust code to
  check; the rest of `fw-core` is R1.
- Vector inputs are in the vector files; the Java test fills in the expected outputs and, in compare
  mode, requires the file to be exactly what it would write. The helper (`Conformance`,
  `JsonVectors`) is in the `framework-core` test-jar, which `framework-core` now publishes (with
  Jackson in its test scope; `framework-bom` manages the test-jar).
- `fw-test` uses an existing server when `FW_TEST_DATABASE_URL` / `FW_TEST_REDIS_URL` are set (CI's
  service containers, or a throwaway local server), else Testcontainers. A container shared by a
  test binary lives in a static that is never dropped, so it is removed by an `atexit` hook (the
  workspace's one `unsafe`, with a reasoned `#[expect]`); testcontainers-rs has no reaper.

**R1 as built.** `fw-core`: `AppBuilder` → `Configured` → `Built` → `Running` (the design's
typestate as four types), `Module::declare` returning typed handles, `Boot` with `on_started`,
`on_shutdown`, `command`, health, background loops, metrics and ids; `launch` for `main`. Config:
`const Setting<T, Optional | Present>` read through `SettingHandle`s (`get` for `Present` cannot
fail), every Java rule and message (vectors `conformance/config/config.json`: environment names,
value parsing, sources in order, problems, collisions), and instance sections checked **at
configure** against each driver's declared keys. `fw-db`: named pools, `Databases::connect` (ping,
health `database:<name>`, close at shutdown), `Databases::migrate`, the runner on
`fw_schema_history`, `Db`, `Tx`. Deviations:

- No generic `DriverFactory` trait: a capability declares its drivers' key schemas
  (`InstanceSection`, `DriverSpec`, `KeySpec`) so `configure` validates every instance, and its own
  `build` function constructs the driver named in each instance (it knows which dependencies each
  needs). Problems found per instance are all reported, where Java stops at an instance's first bad
  key.
- `configure` is `async` from R1, as capabilities section 1 recommends.
- `Context::fresh` is not on `Context` (it belongs to `fw-usecase`, R2).
- `sqlx::raw_sql` makes a future that is not `Send` under `tokio::spawn` (an `Executor` lifetime
  that is "not general enough"); the runner executes each file with `Executor::execute(&str)`, which
  also uses the simple-query protocol, so a file may hold several statements.
- Database `url` (owner ruling 2026-10-12): `jdbc:postgresql://` (a Java service's value) and
  `postgres://` / `postgresql://` are both accepted (`fw_db::parse_url`, tests `fw-db/tests/url.rs`
  and `databases.rs`); plain `key=value` pairs are not, sqlx cannot parse them. The `username` /
  `password` keys win over credentials in the URL. The Java-written config vectors are unaffected.
- A YAML mapping with a duplicate key is refused by `serde_norway`; the Java reader keeps the last.

`fw-redis` (named `ConnectionManager`s, ping, health `redis:<name>`) and `fw-locks` (memory,
`database` with feature `postgres`, `redis` with feature `redis`; `with_lock`; `LockGate`;
`testing::suite!`, which all three drivers pass: 18 cases each), and `examples/locks-demo` (the Java
demo's configuration unchanged; its test checks three instances, the report and the shutdown order).
Exit criteria: the suite is green on all three drivers; locks-demo starts with three instances and
shuts down loops first, then actions in reverse; the config and TSID vectors match; the lock
statements and scripts are the Java bytes (`conformance/sql/locks/`, `conformance/lua/locks/`), and
the Rust runner writes the Java history rows for `locks-database` and accepts a database holding
them (`conformance/migrations/locks-database.json`). Deviations and notes:

- The driver trait `LockStore` works on the stored key, owner token and fencing token (the Java
  `LockDriver` level) rather than on `LockKey`/`Lease` as the design sketched; `Lock` builds leases
  over it, as Java's `LockStore` does. It also carries the test hook `expire_now`.
- `Locks::builder(boot, &declared).databases(&dbs).redis(&redis).build()` instead of
  `Locks::build(boot, &locks, &dbs)`: the builder's methods exist only with the matching feature.
- `LocksModule` registers every driver compiled in (Java lists a module per driver); with
  `postgres` it always contributes the `locks-database` migration set, as listing
  `LocksDatabaseModule` does in Java.
- The shared statement files hold the Java text with JDBC `?` placeholders; the Rust driver numbers
  them `$1`, `$2`, ... at construction, which is what the PostgreSQL JDBC driver sends.
- Java side: the lock statement and script constants of `DatabaseLockDriver` and `RedisLockDriver`
  are now package-private, so `LockConformanceTest` and `LockScriptConformanceTest` (new) can check
  them against the shared files; both modules' tests depend on the `framework-core` test-jar.
- `with_lock` on loss: cancels `lease.lost()`, polls the action once more, then drops it.

**R2 as built.** `fw-usecase`: the `UseCase` trait (`authorize` required), the runner `UseCases`
(validate, authorize, execute, the client check, then in one `READ COMMITTED` transaction the writes,
the events in step order, the dispatch jobs, one audit row, commit), the contract's failure table
with the step codes, conflicts (`STALE_AGGREGATE`, `40001`, `40P01`) under `ConflictPolicy::Fail`
or `retry(n, backoff)` with jitter, `UNIQUE_VIOLATION` with the constraint, `Fresh`/`Loaded`/`Write`,
`Repository`, `Plan`, `DomainEvent` with const-checked `EventType`, `Events`, `OutboxWriter` (the SDK
table and payloads), `OutboxModule` (the SDK's `V0001` byte for byte, then `V0002`), `AuditData`,
`DispatchJob` with the Java rules and messages, the use-case metrics. Checked SQL: sqlx macros, one
`rust/.sqlx/` at the workspace root, `fw-sql.toml`, `tools/cargo-fw` (`sql prepare`, `sql check`),
a CI job running the check. `examples/orders-demo`: the Java demo's migration and use cases, one
type per order state, a read model with `!` overrides on a `LEFT JOIN`, a `demo` command.
Exit criteria: the outbox, audit and dispatch-job rows match the Java output **byte for byte**
(`conformance/outbox/outbox.json`: every column, the payload text and size), stronger than the
parsed-JSON rule; every failure row of write-path section 4 has a runner test (`VALIDATE`,
`AUTHORIZE`, `EXECUTE`, `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`, `CROSS_CLIENT_WRITE`,
`UNIQUE_VIOLATION`, stale, serialization failure, deadlock, row count above 1); conflict retry with
jitter; `.sqlx/` checked by `cargo fw sql check` (fails when stale) in CI; orders-demo runs against a
database holding the Java runner's history rows for `outbox` and `orders`
(`conformance/migrations/outbox.json`, `orders.json`). Deviations and notes:

- `Valid<'a, C>` and `Authorized<'a, C>` borrow the command, so one command serves every retry
  attempt; `authorize` takes `Valid` by value.
- `and_save` and `and_delete` take an event per step, as the Java `Plan` does (the design sketch
  had none).
- `Grant::public()` and `Grant::allowed()`; no `Context::require` until principals carry
  permissions (M4c/R6). `Fresh::new(ctx, value)` instead of `ctx.fresh(value)` (`Context` is in
  `fw-core`). `Tx` has no lifetime parameter.
- An unexpected failure inside a phase is `UseCaseError::failure(cause)` (`?` on a `sqlx::Error`
  does it); a panic in a phase is caught (`catch_unwind`) and is `INTERNAL` with the step's code.
- A `Sink` trait, as in Java, so tests substitute failing sinks; `UseCases::build(boot, &dbs)`.
- Event data leaves out null fields, as Jackson's `NON_NULL` does for record components; Jackson
  keeps a null **map value**, the Rust writer drops it. An `f64` outside 10^-3..10^7 prints as
  `10000000000.0` where Jackson prints `1.0E10`. Neither is in the vectors.
- The outbox `INSERT` is a shared file, `conformance/sql/outbox/insert.sql` (owner ruling 2026-10-12),
  like the lock statements: the Java `OutboxWriter.INSERT_SQL` and the Rust `INSERT_SQL` are checked
  against it (`OutboxConformanceTest`, `conformance/tests/outbox.rs`), and the Rust writer numbers
  the `?` placeholders when it is built (`fw_db::number_placeholders`, also used by the lock driver).
  The Java text lost its trailing newline to match the file.
- The load-once check (`INTERNAL`/`RELOADED` in debug builds) is not built (open ruling).
- `cargo-fw` runs as `cargo run -p cargo-fw -- sql prepare|check` (`cargo fw ...` once installed),
  prepares with `--all-targets --all-features`, and needs `sqlx-cli` 0.8.6.
- The orders demo has no HTTP handler yet (R3); the showcase's 100-character customer limit is not
  applied, so the Rust demo accepts what the Java demo accepts.
- Java side: `framework-usecase` and `examples/orders-demo` tests depend on the `framework-core`
  test-jar; new `OutboxConformanceTest` and `OrdersConformanceTest` write the vectors.

## 1. Milestones

Milestones are numbered R0 to R8 so they are not confused with the Java ones; the Java milestone each
covers is named.

| # | Scope | Java equivalent | Depends on | Exit criteria |
|---|---|---|---|---|
| R0 | **Workspace.** `rust/` with the toolchain pin, lint table, `deny.toml`, `.cargo/config.toml`, CI jobs (fmt, clippy `-D warnings`, test, deny), `fw-test` with Testcontainers PostgreSQL 18 and Redis, the empty `conformance/` runner, and the Java-side helper that writes and compares conformance vectors | M1 step 1, MA 1 (in part) | decisions 1 to 4 | CI green on an empty workspace; one vector written by a Java test and read by a Rust test |
| R1 | **Foundation and locks.** `fw-core` (builder and phases, modules and handles, `Boot`, config with every rule, `Secret`, `Context` and ids, instances and drivers, health with criticality, `Ids`, `Clock`, `Background`, `Metrics` and the exposition writer, commands), `fw-db` (pools, the migration runner on `fw_schema_history`), `fw-redis`, `fw-locks` (memory, postgres, redis) with the contract suite, `examples/locks-demo` | M1, MA 3, MA 7 | R0, decisions 5 to 8, 10, 15 | the locks contract suite green on all three drivers; locks-demo starts with three instances and shuts down in the contract's order; config vectors (env names, durations, collisions, errors) and TSID vectors match Java; a Java-migrated database is accepted by the Rust runner (same history rows, same checksums) |
| R2 | **Write path and checked SQL.** `fw-usecase` (trait, runner, `Plan`, `Fresh`/`Loaded`, repositories, events, the outbox writer and its migrations, audit rows, `DispatchJob` rows), `cargo fw sql prepare` and `check`, `examples/orders-demo` on the Java demo's migration | M2a, M2b, MA 4 | R1, decision 9 | outbox, audit and dispatch-job row vectors match the Java output; every failure row of write-path section 4 has a test; conflict retry with jitter; `.sqlx/` check in CI; orders-demo runs against a database first migrated by the Java demo |
| R3 | **HTTP.** `fw-http` (routes, envelope, extractors, validation, correlation id, access log, health and metrics endpoints, server, drain, `TestClient`), Datastar, `fw-ratelimit` | M3, MA 8 (by construction), MA 9 | R1 (R2 for use-case error mapping), decisions 13, 16 | every test requirement of http section 12 that applies to axum; error, health, Datastar, rate-limit and Prometheus vectors byte-identical to Java; graceful shutdown with an in-flight request |
| R4 | **Platform.** `fw-httpclient` (+ adapter), `fw-secrets` (env, file), `fw-flowcatalyst` (client wrapper, definitions, `sync`, the outbox health check, both receivers, `once` and its purge) | MA 6, MA 5, M4a, M4b | R3; the Rust SDK (decision 11); decision 14 | the signature vectors (the ones shared by the SDKs and the Java framework) and every receiver response vector match; the full receiver test list of flowcatalyst section 6; end to end: the Go `fc-dev` outbox processor delivers a Rust orders-demo's outbox to a local platform, and a dispatch job and a scheduled job reach the Rust receivers |
| R5 | **UI.** `fw-ui`: askama components for the kit, the HTML normaliser, the conformance test over `ui-kit/fixtures`, the view helper | M-UI | R3, decision 12 | every fixture of the components the admin needs passes (the same set JTE starts with), then the rest |
| R6 | **Sessions and login.** `fw-session` (cookie, database, redis), `fw-oidc`, the real `ContextResolver`, the default pages | M4c | R4, R5, **the Java M4c contract** | as the Java contract's test list; security review |
| R7 | **Admin.** `/admin` skeleton and pages | M4d, M4e | R6 | as the Java milestones |
| R8 | **A trial service in Rust** | M4f | R7 | one real service on the framework |
| later | cache, storage, platform config driver, metering, messaging | M5 | the platform | as the Java contracts |

A milestone is done when its tests and conformance vectors pass, the guide has a Rust page for each
public capability (section 4), and STATUS records it, by the same documentation rules as the Java
code.

## 2. Testing strategy

| Level | What | How |
|---|---|---|
| unit | pure logic: config parsing, key and code validation, GCRA, TSID, path templates, signature checks, the exposition writer, the HTML normaliser | `#[test]`, property tests (`proptest`) where the input space is large (key grammar, path encoding, GCRA arithmetic) |
| time-dependent | lock leases, loop schedules, retries, token expiry, rate limits | `#[tokio::test(start_paused = true)]` and `tokio::time::advance`, the Rust form of Java's injected monotonic clock; wall time through the injected `Clock` |
| integration | database and Redis drivers, the use-case runner, migrations, receivers with `once` | Testcontainers PostgreSQL 18 and Redis, **one container per test binary** (shared through a `OnceCell`), **a fresh database per test** created from a template database migrated once, so tests run in parallel |
| contract suites | every driver of a capability passes the same suite (locks, rate-limit stores, secret stores, session stores, repositories) | the capability crate's `testing` feature exports the suite as a `macro_rules!` that a driver's test file invokes with its harness; one `#[tokio::test]` per case |
| HTTP | routing, middleware order, envelopes, validation, receivers | `TestClient` (in-process, no socket); a few real-socket tests for the server, body limit, drain and bind failure |
| conformance | wire and storage output equal to the Java framework's | section 3 |
| UI | rendering equal to the goldens | section 3.4 |
| end to end | the platform really accepts what the framework writes | section 3.5 |

## 3. Conformance against the Java framework

### 3.1 The shared vectors

A language-neutral directory at the repository root, `conformance/`, beside `ui-kit/`. Each vector
is an input and the expected output, as files; both implementations read the same files.

```
conformance/
  README.md                  format, comparison rules, how to add a vector
  tsid/                      64-bit values -> text; prefixed ids; invalid texts
  config/                    keys -> env names; duration and boolean texts; collision cases; error lines
  outbox/                    (context, fixed clock, fixed ids, use case, event) -> row columns + payload JSON
                             for event, audit and DISPATCH_JOB rows, each optional field present and absent
  errors/                    situation -> status, headers, envelope
  health/                    check results and criticality -> status code and body
  receivers/signature/       (secret, timestamp header, body, now) -> accepted | refusal code (incl. the SDKs' shared vector)
  receivers/dispatch/        request (headers, body) + handler outcome -> status, headers, body
  receivers/scheduled-jobs/  firing + outcome -> HTTP answer, completion body, call count
  datastar/                  event + options -> exact bytes (.sse)
  metrics/                   recorded snapshot -> exact exposition bytes (.prom)
  ratelimit/                 (policy, sequence of now and cost) -> decisions and headers
  secrets/                   (prefix, client, name) -> variable name | refused
  htmlnorm/                  HTML in context -> normalised lines (shared with the Go platform's normaliser)
  sql/locks/, lua/locks/, lua/ratelimit/   the statement and script texts both implementations load
```

The lock statements and the Lua scripts move into files that both implementations load (Java from
the classpath, Rust with `include_str!`), so "the same SQL" is the same bytes rather than two copies
kept in step.

### 3.2 Where the expected outputs come from

The Java framework is the reference until the owner rules otherwise (decision 18). Each Java module
gets a small test helper that, for every vector of its area, runs the Java code on the input and
compares the result with the expected file; with `-Dconformance.update=true` it writes the file
instead. A vector is created by writing its input, running the Java test in update mode, reviewing
the output and committing both. The Rust tests only compare. A Rust mismatch therefore means Rust
is wrong, or the vector exposes a Java bug, which is fixed in Java first.

### 3.3 Comparison rules

- JSON (payloads, envelopes, health, completion bodies) is compared as parsed values: key order and
  whitespace are free, values exact. The platform reads payloads as JSON, so byte order does not
  matter there.
- SSE, Prometheus text and HTTP status lines and header values are compared as **exact bytes**,
  because the contracts fix them.
- Generated values are pinned: vectors run with a fixed clock, a seeded id generator and a fixed
  correlation id, so ids and times in the output are deterministic.
- An allowed difference (for example the 405-versus-404 of an unused receiver route, capabilities
  section 11) is recorded in the vector as `"rust": {...}` with a reason, so a difference is always
  deliberate and visible.

### 3.4 UI fixtures

`ui-kit/fixtures` is already the conformance authority for templ and JTE. The Rust test reads it in
place, with the README's normalisation, and `conformance/htmlnorm/` checks that the three
normalisers agree with one another.

### 3.5 End to end (manual, then nightly)

1. **Outbox:** the Rust orders-demo writes events, audit rows and dispatch jobs; the Go platform's
   outbox processor (`fcdev outbox`) delivers them with the application's service account; the
   platform shows the same events, audit entries and jobs as for the Java demo running the same
   scenario (compared field by field, ignoring ids and times).
2. **Receivers:** a subscription pointed at the Rust receiver gets a dispatch job (signed) and a
   scheduled-job firing; acknowledgement, deferral, rejection and completion are seen on the
   platform as for Java.
3. **Shared storage:** a Java and a Rust process contend for one `fw_lock` key and one Redis
   rate-limit key; mutual exclusion and one shared budget hold.

## 4. Documentation

The guide gains Rust pages beside the Java ones, under the same rules (`docs/guide/verify.sh`,
snippets from compiled code, STATUS kept current). Rust code is documented with rustdoc; the guide
links to it. Templates for the UI are shown as files, never inline in a guide page, because the site
would read their syntax as Liquid.

## 5. Effort and risk

Effort is relative, in days of agent-assisted work at the pace the Java framework was built (M1 to
MA in three days); a human team should multiply. The estimates assume the decisions are taken as
recommended.

| # | Effort | Main risk | Mitigation |
|---|---|---|---|
| R0 | 0.5 d | none | |
| R1 | 3 to 4 d | the builder API: an async build closure borrowing `Boot`, typed handles and collected config errors could be awkward to use | build `locks-demo` first and adjust the API before the other crates depend on it |
| R2 | 3 to 4 d | `Plan` holds heterogeneous aggregates and repository references with lifetimes inside `Send` futures; `.sqlx/` across a workspace with examples | prototype `Plan` and the runner on two aggregate types in the first day; one `.sqlx/` at the workspace root, as the platform has |
| R3 | 3 to 4 d | axum's defaults differ from the contract in many small ways (405 bodies, `Allow`, `OPTIONS`, body-limit status and connection close, drain 503, JSON rejection statuses) | the framework keeps its own route table and fallbacks; every row of the error table is a vector |
| R4 | 4 to 5 d, plus about 2 d for the SDK in the platform repository | the SDK decision; cancellation semantics of handler timeouts and the scheduled-job lock; signature edge cases | decide 11 and 14 before starting; the shared signature vectors; the receiver test list is long and already written for Java |
| R5 | 3 to 5 d | escaping and whitespace differences between askama output and the goldens; many components | the normaliser absorbs whitespace and quoting; start with the components the admin needs |
| R6 | 4 to 6 d | security-sensitive; no contract yet | wait for the Java contract; Opus for the slice (owner ruling for security-sensitive work); review |
| R7, R8 | as Java | | |

Cross-cutting risks:

- **Two implementations drift.** Controlled by the shared vectors and fixtures, one repository, and
  the rule that a contract change lands with both implementations or with a STATUS note on the one
  that lags.
- **The ecosystem moves** (axum, sqlx and reqwest have breaking releases). Versions are pinned in
  the workspace and upgraded deliberately, one crate family at a time.
- **async and blocking.** A blocking call on the runtime stalls every task on that worker; the review
  checklist and `tokio-console` in development, and `spawn_blocking` for file and CPU work.
- **Owner bandwidth.** Two framework implementations double the review load; starting with R0 to R2
  only (decision 1) keeps it bounded.

## 6. How much of each contract is reused verbatim

| Contract | Verbatim | Replaced or changed for Rust |
|---|---|---|
| core | sections 1 (phases, shutdown, health, criticality) semantics, 2, 3, 4 in full, 5 | the API of section 1 (modules, `get`/`find`, `override`); see capabilities section 1 |
| database-and-redis | sections 2 and 3, and section 1's rules | HikariCP and Jedis become sqlx and `redis`; `jdbc:` URLs |
| locks | all of sections 1 to 5, the SQL and the scripts | exceptions become results; interruption becomes cancellation; the test suite's form |
| write-path | sections 2, 4 (except `INTERRUPTED`), 5, 6, 7, 7a, 8 | section 1 (`Fresh`/`Loaded`), section 3's API, section 9's helper |
| checked-sql | section 2's semantics and section 5's scope | sections 1, 3, 4, 6: sqlx macros and offline data instead of the generator |
| http | sections 2, 5, 6, 7 (outcomes), 8 (behaviour), 10 | sections 1, 3, 4, 9 (axum, tower, extractors, `TestClient` over tower) |
| rate-limiting | all, including the Redis script | the middleware is a tower layer |
| secrets | all | `Secret` zeroises; errors become results |
| background-and-metrics | all rules and bytes | the API; panics in place of `Error`; abort in place of interrupt |
| http-client | all behaviour | the engine (reqwest), errors as results, no `INTERRUPTED` |
| flowcatalyst | all wire behaviour and settings | typed handlers and their decode rule, cancellation on timeout, unused receiver routes not mounted |
| build | none | a Rust build contract (crates.io, features, MSRV) to be written in R0 |
