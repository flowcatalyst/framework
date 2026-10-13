# Rust framework: design (draft 1, 2026-10-11)

**PARKED (owner, 2026-10-13).** R0 to R2 are built (workspace, core, config, database, locks, Redis, the use-case write path with checked SQL; outbox rows byte for byte with Java). R3 onward is not started and nothing more is built until the owner resumes. To resume: read [`milestones.md`](milestones.md) section 0, re-run the Rust CI locally (`CARGO_INCREMENTAL=0`, see the notes there), and re-check the conformance vectors still pass against the Java writer. Nothing is published.

Status (original draft): a design. Nothing is built. Names are placeholders (implementation plan, open question 6);
crate names below use the prefix `fw-` for readability only.

The Rust framework is a second implementation of the contracts in [`../contracts/`](../contracts/),
written for Rust rather than translated from Java. Applications built on either implementation
must be interchangeable to the FlowCatalyst platform: the same outbox rows, the same event, audit
and dispatch-job payloads, the same TSIDs, the same receiver behaviour, the same config and secret
semantics, the same error envelope.

| Document | Contents |
|---|---|
| this page | principles, where Rust differs from Java, workspace and crate layout, lint and feature policy, the owner decisions |
| [`capabilities.md`](capabilities.md) | capability by capability: the Rust types, what is shared verbatim with the Java contract, what changes, open rulings |
| [`orders-showcase.md`](orders-showcase.md) | one business use case (orders, client scoping, permissions, events) end to end, and what the compiler guarantees |
| [`milestones.md`](milestones.md) | the milestone plan, testing and conformance against the Java framework, effort and risk |

Inputs read: [`framework-plumbing.md`](../framework-plumbing.md) (rules and owner rulings),
[`implementation-plan.md`](../implementation-plan.md), every contract, the guide, `ui-kit/`
(specs, fixtures, porting notes), and the Rust platform repository (`flowcatalyst-rust`: workspace
manifest, lint table, `CLAUDE.md`, `crates/fc-sdk`, `crates/fc-common-types`, `crates/fc-web`).

## 1. Principles, and where Rust deliberately differs from Java

The framework's design rules (plumbing section 3) hold unchanged: contracts first; no reflection
and no global state; fail at start-up; three lifetimes (application, request or job,
transaction); errors are values; the tenant is always present. What changes is how each rule is
enforced. Java enforces most of them at start-up; Rust can enforce many of them in the compiler,
and where it can, it should.

### 1.1 No container: an explicit, typed application

Java's `AppBuilder` is a typed map from `Class` to provider, resolved eagerly at start-up; a missing
provider is a `StartupException`. Rust does not get a container, not even a `TypeId` map:

- **Services are plain values built in plain code.** The application writes one `async fn build`
  that constructs its services in order, passing each its dependencies as arguments, and returns
  its own state struct (`AppState`). A missing dependency is a compile error, because the
  constructor cannot be called without its argument. There is no `get::<T>()`.
- **Modules remain, for declaration and lifecycle only.** A framework module declares settings,
  instance sections and their driver key schemas, migration sets, platform definitions and
  commands, and returns **typed handles** for what it declared. A handle is the proof that a
  setting was declared and validated, so reading it after configuration cannot fail.
- **Lifecycle is a handle, not a hook trait.** The `build` function receives a `Boot` through which
  it registers health checks, background loops, started actions and shutdown actions. Spawning is
  deferred: nothing runs until every part has been built.

```rust
#[tokio::main]
async fn main() -> ExitCode {
    let mut app = App::new(AppName::of("orders-demo"));    // AppBuilder<Declaring>
    let http = app.module(HttpModule::default());            // -> HttpDeclared (handles)
    let db = app.module(DatabaseModule::default());
    let locks = app.module(LocksModule::new().driver(locks::memory()).driver(locks::postgres()));
    let fc = app.module(FlowCatalystModule::default());
    let orders = app.module(OrdersModule);
    fw::launch(app, std::env::args(), async move |boot: &mut Boot| {
        let dbs = Databases::connect(boot, &db).await?;       // pings, registers health + close
        boot.migrate(&dbs).await?;                            // every module's migration set
        let locks = Locks::build(boot, &locks, &dbs)?;
        let platform = fc.client(boot)?;
        let state = AppState::new(boot, &orders, dbs, locks, platform)?;
        Ok(Serve::http(&http, routes(state.clone())).with_state(state))
    })
    .await
}
```

`fw::launch` runs: declare (already done above, synchronously) → configure (read files,
environment and `secrets.local.yaml`, validate every declared key and every instance section,
collect every problem) → build (the closure) → a command named by the first argument, or start
(started actions, loops, then the server binds) → run until a signal → shutdown. The phases are a
typestate on the builder (`AppBuilder<Declaring>` → `App<Configured>` → `Running<S>`), so a
setting cannot be declared after configuration and a service cannot be read before it.

**What is lost.** Java collects every start-up problem in one report. In Rust, configuration
problems (the large majority: missing, malformed, unknown, duplicate, env-name collisions,
unknown driver names, unread instance keys) are still collected in one report, because driver key
schemas are declared before configuration. Problems found while building (a database that does
not answer) stop at the first failing component; a component that checks several things
(`Locks::build` over all its instances) reports them together. `AppBuilder.override` (MA item 2)
has no equivalent: a test builds the application with fakes passed as arguments to its own
`build` function (section 1.3), which needs no runtime type map and cannot reach production by
accident.

### 1.2 Traits, generics and trait objects

| Where | Choice | Why |
|---|---|---|
| Domain and application code: use cases, repositories, aggregates, events, commands | traits with associated types and generics, static dispatch | the types are known at compile time; this is where the compiler checks the business rules |
| Driver contracts selected by configuration: `LockStore`, `RateLimitStore`, `SecretStore`, session stores | dyn-compatible traits held as `Arc<dyn Trait>` | the driver is named in YAML, so the type is chosen at run time |
| The user-facing capability API on top of a driver: `Locks`, `RateLimiter`, `SecretResolver` | concrete structs wrapping `Arc<dyn Driver>` | the shared logic (acquire with wait, `with_lock`, caching) is written once, as in Java's "store versus user-facing API" |
| Middleware | tower `Layer` and `Service` | the ecosystem's one middleware abstraction; generic over the request type, so it is shared by HTTP and job handlers (contract http section 4) |

Async traits: application-facing traits declare methods as
`fn x(&self, ..) -> impl Future<Output = ..> + Send` so callers get `Send` futures, and
implementations write plain `async fn`. Native `async fn` in traits is not dyn-compatible, so driver
traits return a boxed future (`BoxFuture<'_, Result<..>>`), written out by hand or with
`async-trait` (already in the platform's workspace). If dyn-compatible async functions are stable on
the pinned toolchain when R1 starts, use them instead; check then.

### 1.3 Errors and results

- **Expected outcomes are enums handled with an exhaustive `match`**, the Rust form of Java's
  sealed result types: `Acquire::{Acquired, Busy}`, `Decision::{Allowed, Limited}`,
  `DispatchResult::{Ack, Defer, Reject}`, `SecretResult::{Found, NotFound, Refused}`. They are
  **not** `#[non_exhaustive]`: a new outcome must break every `match` that has to decide what it
  means. The platform's lint `clippy::wildcard_enum_match_arm` is on, so `_ =>` cannot hide one.
- **Failures are `Result<T, E>` with a `thiserror` enum per capability** (`LockStoreError`,
  `HttpClientError` with its `Kind`, `SecretStoreError`, `StartupError`). These error enums **are**
  `#[non_exhaustive]`, so the framework can add a failure kind without a major release; callers
  match the kinds they handle and log the rest.
- **`UseCaseError` and `ErrorKind`** are the contract's: a struct with kind, code, message,
  details; `ErrorKind` is an exhaustive enum. Codes are `ErrorCode`, a newtype checked at compile
  time (section 1.5). The core trait `ServiceError` is what the HTTP layer maps, as in Java after MA
  item 8.
- **Programming errors panic; nothing else does.** `unwrap`, `expect` and `panic` are lints at
  warn (denied in CI) with an `#[expect(.., reason = "..")]` exemption for start-up invariants, as
  in the platform. A panic inside a handler, a job or a loop is caught at the task boundary and
  treated as the contract treats an uncaught exception (500, 503, a failed run).
- **`anyhow` is for binaries and examples only**, never in a framework signature.

### 1.4 `async` on tokio, not virtual threads

Java runs blocking code on virtual threads and stops work by interrupting. Rust runs on tokio and
stops work by **dropping the future** (cancellation). This changes several contract mechanics,
never the wire behaviour:

| Java mechanism | Rust mechanism | Consequence |
|---|---|---|
| a virtual thread per request or job | a tokio task per request (hyper) or per job (`tokio::spawn`, tracked by a `TaskTracker`) | blocking work goes through `spawn_blocking`; a lint-level rule and review item |
| `Thread.interrupt()` on timeout or lock loss | the future is dropped at its next `.await`; a `CancellationToken` lets long work notice first | stronger: the work really stops. Code must be cancel-safe; a dropped `sqlx` transaction rolls back |
| `InterruptedException` → `INTERNAL / INTERRUPTED` | no result at all: the caller dropped the future | the contract's `INTERRUPTED` code does not occur in Rust; documented as a delta |
| JVM shutdown hook | `tokio::signal` (SIGTERM, SIGINT) cancels the root token | same drain order (section 1.6) |
| `InstantSource`, monotonic `LongSupplier` | `Clock` trait for wall time; `tokio::time::Instant` for elapsed time | tests use `tokio::time::pause()` and `advance()` to move monotonic time without sleeping, which is what Java's injected monotonic clock is for |

Async closures (`AsyncFnOnce`, stable since Rust 1.85) carry actions such as `with_lock`'s and
`once`'s without boxing at the call site.

### 1.5 Newtypes, `Secret`, and checks the compiler does

- **Every id is a type.** `ClientId`, `PrincipalId`, `ApplicationCode`, `CorrelationId`,
  `CausationId`, `ExecutionId`, `LockKey`, `SecretRef`: validated on construction (`FromStr`,
  `TryFrom<String>`, serde `try_from`), never `String` in a signature. Aggregate ids use one
  generic type, `Id<K>`, where `K` names the kind and its prefix (`impl IdKind for Order { const
  PREFIX: IdPrefix = IdPrefix::of("ord"); }`, `type OrderId = Id<Order>;`), so `Id<Order>` and
  `Id<Customer>` cannot be mixed and serde, sqlx and TSID support are written once with no macro.
- **Codes and patterns that are literals in source are checked in `const` evaluation.** A `const`
  item built with a `const fn` that panics on a bad value is a **compile error**:
  `const PLACED: EventType = EventType::of("orders-demo:sales:order:placed");`. The same holds for
  `ErrorCode` (`UPPER_SNAKE`), `UseCaseName`, `Permission`, setting keys, lock names, metric names
  (the `_total` and `_seconds` suffix rules), id prefixes and route patterns. Java checks all of
  these when the value is constructed at run time or start-up. (Values built at run time from
  input still go through the fallible constructor.)
- **`Secret`** is a type with no `Display`, no `Serialize` and a redacting `Debug`
  (`Secret[****]`), so formatting it into a log line or putting it in a serialised struct does not
  compile. The raw value is `reveal()`. Unlike Java, the value is zeroised on drop (`zeroize`).
  `matches(candidate)` compares SHA-256 digests in constant time (`subtle`).

### 1.6 Typestate

Used where an order of steps is a rule, and nowhere else:

- **The application builder**: `Declaring` → `Configured` → `Running` (section 1.1).
- **Use-case phases.** The runner moves an attempt through `Valid<C>` → `Authorized<'_, C>` →
  `Plan<E>` → `Committed<E>`. `Valid` and `Authorized` have crate-private constructors, so
  application code (a handler, another use case, a test) cannot call `execute` on a command that
  was not validated and authorised: the only way to get an `Authorized<C>` is the runner. Java
  relies on every caller using `UseCases.run`. `Committed<E>` is sealed the same way, as the
  platform's `Committed<T>` already is.
- **Aggregate persistence state.** `Fresh<A>` (made in this attempt, under the context's client)
  and `Loaded<A>` (found by a repository, carrying the stored version and client). The runner
  inserts a `Fresh` and updates a `Loaded`: the type decides, not `version() == 0`.
- **Builders with required parts**, such as `DispatchJob::builder(code, target_url)`: required
  parts are constructor arguments; `build()` validates and returns `Result`.

Not used: typestate for every builder with optional fields (plain builders, or `bon`, which the
platform already uses), or brand lifetimes for client scoping (considered and rejected in
[`orders-showcase.md`](orders-showcase.md) section 4).

### 1.7 Macros

None beyond `derive` without a stated reason. What replaces Java's annotation-free patterns:

| Need | Rust form | Macro? |
|---|---|---|
| typed ids | `Id<K>` generic (section 1.5) | no |
| codes checked at compile time | `const fn` constructors | no |
| JSON and database mapping | `#[derive(Serialize, Deserialize)]`, `sqlx::Type`, `sqlx::FromRow` | derive only |
| checked SQL | `sqlx::query!` / `query_as!` | sqlx's own function-like macros: justified, they are the checking |
| text enums stored in a column | a small trait `StrEnum` with a hand-written `as_str`/`parse` pair and a generic sqlx impl | no; a derive may come later |
| contract test suites stamped per driver | one `macro_rules!` per suite that expands to one `#[tokio::test]` per case | justified: Rust has no abstract test classes, and per-case test names are what make a failure readable |
| settings structs | `const` `Setting<T>` declarations read through handles | no; a `#[derive(Settings)]` is an open ruling (section 6, decision 17) |
| HTML templates | askama's `#[derive(Template)]` | derive only |

### 1.8 Cancellation and graceful shutdown

One root `CancellationToken` and one `TaskTracker` per application (tokio-util). Every task the
framework spawns (background loops, scheduled-job firings, purge loops, token refreshers) is
tracked and gets a child token. On SIGTERM, SIGINT or `Running::shutdown()`:

1. `/health/ready` answers 503 from this moment (http contract section 8).
2. Background loops are cancelled **first** (background contract section 1); each running task
   gets up to `app.shutdown.drain`, then it is aborted and logged at WARN as an overrun.
3. The HTTP server stops accepting (`axum::serve(..).with_graceful_shutdown`); in-flight requests
   get `http.shutdown-grace`; a new request on an existing connection during the drain gets
   `503 UNAVAILABLE` with `Connection: close` (a framework layer that watches the token).
4. Shutdown actions registered during build run in reverse registration order, each bounded by
   `app.shutdown.drain`; one failure is logged and does not stop the rest.
5. Pools close (`PgPool::close().await`), through the same reverse-order actions: Rust's `Drop`
   cannot await, and struct fields drop in declaration order, not reverse build order, so closing is
   never left to `Drop`.

A command run by `launch` builds the services, runs, and closes them the same way; it never starts
loops or the server.

### 1.9 What does not move

`Context` is a value passed explicitly; it is never stored in a task-local (tokio has them; the
framework does not use them). `tracing` spans carry the context's fields into every log line
automatically, but spans are output, never the source of the context. No global state:
no global metrics recorder, no global HTTP client, no `lazy_static` configuration.

## 2. Workspace, crates and build policy

### 2.1 Where it lives: a `rust/` workspace in this repository (recommended)

| | `rust/` in this repository | a separate repository |
|---|---|---|
| contracts | one copy; a contract change and both implementations in one commit | copied or linked; drift is likely |
| `ui-kit/` fixtures | read in place (`../ui-kit/fixtures`) by the conformance tests | vendored with a sync script (as the Go platform does) |
| shared conformance vectors (`conformance/`, [`milestones.md`](milestones.md) section 3) | read by both builds in one CI run | needs a third place |
| CI | Maven and Cargo jobs, filtered by path | simpler per repository |
| release | tags `v*` (Java) and `rust-v*` (Rust); Cargo cannot publish to GitHub Packages, so Rust publishes to crates.io or is consumed by git tag | independent |
| documentation | one site; the guide gains Rust pages | two sites |

Recommendation: **`rust/` in this repository**, because the contracts and the fixtures are the
point of having two implementations, and keeping them in one tree is what stops them drifting. The
Java build is unaffected (`rust/` is not a Maven module). Revisit if the Rust release cadence starts
to fight the Java one.

### 2.2 Crates

```
rust/
  Cargo.toml                 workspace: members, [workspace.package], [workspace.lints], [workspace.dependencies]
  rust-toolchain.toml        1.98.1, clippy + rustfmt (the platform's pin)
  deny.toml                  licences (no AGPL/GPL/LGPL), advisories, sources: crates.io only
  .cargo/config.toml         SQLX_OFFLINE=true
  crates/
    fw-core                  App builder and lifecycle, Module, Boot, Context and ids, config (Setting, sources, Secret),
                             instances and driver registry, health (criticality), Background, Metrics + Prometheus text,
                             Ids (TSID), Clock, ServiceError, commands
    fw-db                    named PgPool instances, the migration runner (fw_schema_history), Db and Tx
    fw-redis                 named Redis connections (redis crate, ConnectionManager)
    fw-locks                 Locks, LockStore, with_lock, LockGate; drivers: memory (default), postgres, redis (features)
    fw-usecase               UseCase, the runner, Plan, Repository, Fresh/Loaded, DomainEvent, outbox writer, audit,
                             DispatchJob, OutboxModule (migrations)
    fw-http                  axum integration: Routes, error envelope, extractors (Json, Valid, Ctx), correlation id,
                             access log, health and metrics endpoints, server and drain, TestClient, Datastar
    fw-ratelimit             RateLimiter, GCRA, the tower layer, key resolvers; drivers: memory (default), redis
    fw-secrets               SecretResolver, SecretRef, drivers env and file
    fw-httpclient            the HTTP client library (no fw-core dependency); feature `module` adds the adapter
    fw-flowcatalyst          platform client wrapper, Definitions and sync, receivers, outbox health check, once
    fw-session, fw-oidc      M4c (after the Java contract)
    fw-ui                    askama components for ui-kit, the view helper (page or Datastar patch)
    fw-test                  Testcontainers PostgreSQL 18 and Redis, a database per test, fixed clocks
  tools/cargo-fw             `cargo fw sql prepare|check`, `cargo fw conformance`
  conformance/               the conformance runner crate (publish = false)
  examples/locks-demo, examples/orders-demo
```

The capability crates match the Java modules one to one where the Java split exists to keep
dependencies out (`framework-http` versus `framework-http-jetty`; `framework-locks-redis`). In Rust,
**drivers are Cargo features of their capability crate**, not separate crates: a feature keeps the
dependency out exactly as a separate artifact does, with less ceremony. A separate crate is used
only where it is a different stack (`fw-httpclient` must be usable without the framework, as
`framework-httpclient` is).

### 2.3 Toolchain, editions, MSRV

- **Edition 2024** (stable since 1.85): greenfield code, async closures, the newer capture rules
  for `impl Trait`. The platform workspace is on 2021; nothing is shared at the source level, so
  the editions need not match.
- **`rust-version = "1.98"`, toolchain pinned to `1.98.1`**, the same pin as the platform, so a
  developer working on both has one compiler. Bump deliberately, in one commit, as the platform does.
- MSRV policy once published: the MSRV is the pinned toolchain at the time of a minor release and is
  raised only in a minor release.

### 2.4 Lints, formatting, supply chain

- `[workspace.lints]`: the platform's table verbatim (`unsafe_code = "deny"`,
  `wildcard_enum_match_arm`, `unwrap_used`, `expect_used`, `panic`, `let_underscore_must_use`,
  `await_holding_lock`, `await_holding_refcell_ref`, `dbg_macro`, `todo`, `unimplemented`,
  `absolute_paths` with two segments), plus `missing_docs`, `unreachable_pub` and
  `rust_2018_idioms` because these are public library crates. CI runs `cargo clippy --all-targets
  --all-features -- -D warnings` and `cargo fmt --check`.
- `clippy.toml`: the platform's (tests may unwrap, expect and panic).
- `cargo deny check`: crates.io only; licences Apache-2.0, MIT, BSD, ISC, Unicode, Zlib and
  similar; **no AGPL, GPL or LGPL** (the framework is Apache-2.0 and open source). This rules out
  depending on the platform's `fc-common` (AGPL-3.0-or-later). `cargo vet` is optional (decision 3).
- Once published: `cargo semver-checks` in the release workflow.
- Dependencies are declared once in `[workspace.dependencies]` with `default-features = false`,
  as in the platform. The stack: tokio, tokio-util, futures, axum 0.8, tower 0.5, tower-http 0.6,
  hyper (through axum), serde, serde_json, serde_path_to_error, serde_ignored, sqlx 0.8 (postgres,
  macros, chrono or time, json, runtime-tokio-rustls), redis, reqwest 0.12 (rustls), tracing,
  tracing-subscriber (binaries), thiserror, chrono, rand, sha2, hmac, subtle, zeroize, hex,
  askama, html5ever (tests), testcontainers (tests).

### 2.5 Features

- Features **add** drivers and integrations; they never change behaviour. Two crates that enable
  different features of one framework crate must both see correct behaviour under unification.
- Default features are the minimum that works with no infrastructure: memory drivers only. The
  `postgres` and `redis` driver features are opt-in, with the same names in every crate.
- Feature `testing` on a capability crate exposes its fakes and its contract test suite (the Java
  test-jars). A recording fake for every contract, as plumbing section 2 requires.
- A driver selected in configuration whose feature was not compiled in fails start-up with a
  problem naming the feature to enable (Java: "unknown driver, registered: ..."), so the error says
  how to fix it.

## 3. Shared with the Java framework, verbatim

These are wire or storage contracts and must be identical, checked by the conformance suite
([`milestones.md`](milestones.md) section 3):

| Area | What |
|---|---|
| outbox | the `outbox_messages` migration **byte for byte** from the Java SDK (`V0001`), the `V0002` widening; event, audit and `DISPATCH_JOB` row columns and payload JSON (`CreateEventDto`, `CreateAuditLogDto`, the batch item with array `metadata`) |
| ids | TSID layout and encoding (13 Crockford base-32 characters, optional `prefix_`), monotonic rules |
| errors | the envelope `{"error","message","details"}`, every status and code of http section 5, `ErrorKind` to status |
| HTTP behaviour | routing rules (precedence, 404/405/`Allow`/`OPTIONS`), correlation-id rules, health JSON (`UP`/`DEGRADED`/`DOWN`, `informational`), body limit 413, drain 503 |
| receivers | signature rules (HMAC-SHA256 over timestamp + body, millisecond ISO-8601 or Unix-seconds timestamp, 300 s / 60 s, refusal codes), headers, envelope parsing, every response, scheduled-job completion bodies and truncation |
| config | key grammar, env-variable names, sources and order, duration syntax, `secrets.local.yaml` rules, collision checks, platform addressing |
| secrets | `SecretRef` grammar, the `env` encoding, the `file` path rules |
| framework tables and keys | `fw_schema_history`, `fw_lock` and `fw_lock_fence`, `fw_dispatch_once`; the Redis lock and rate-limit key formats and Lua scripts. Recommended (decision 8) so a Rust service can take over a Java service's database, and Java and Rust replicas can share locks and rate-limit budgets |
| metrics | names, labels, buckets, and the exact Prometheus text bytes |
| Datastar | the exact SSE bytes of every event and option |
| UI | the `ui-kit/fixtures` goldens |

What changes is the API shape, recorded per capability in [`capabilities.md`](capabilities.md).

## 4. The FlowCatalyst SDK question

The brief assumed there is no Rust SDK. There is one, but not where the other SDKs live:

- `flowcatalyst-rust/crates/fc-sdk` (Apache-2.0 by owner decision #51, about 20 000 lines, in that
  workspace, **not published**): outbox writer and unit of work, use-case types, TSIDs, cache, locks,
  a platform API client (event types, roles, subscriptions, connections, dispatch pools, scheduled
  jobs including `complete_instance` and `log_for_instance`, ...), a definition synchroniser,
  webhook validation, JWT and OIDC helpers and an axum session layer. It depends on
  `fc-common-types` (Apache-2.0, TSIDs and outbox types), never on AGPL `fc-common`.
- The canonical SDK sources are `flowcatalyst-go/clients/*` (go, java, laravel, typescript); there
  is no Rust SDK there. The Rust platform port is paused except for its bug-fix list.

What fc-sdk would bring that the framework must **not** take: its outbox table DDL differs from the
Java SDK file the framework ships byte for byte (`payload JSONB`, `TEXT` and `INTEGER` columns,
other indexes), and its dispatch-job DTO writes `metadata` as an object and writes the fields the
batch endpoint ignores, the platform finding the Java framework already reported (write-path
section 7a). The framework owns its outbox writer, use cases, locks and cache anyway.

Recommendation (decision 11): **create `flowcatalyst-sdk`, a Rust SDK in
`flowcatalyst-go/clients/rust-sdk`**, seeded from fc-sdk's `client`, `sync`, `webhook` and TSID code
(Apache-2.0, so copying is allowed), without its outbox, use-case, cache, lock and axum modules,
with the dispatch-job shape fixed, released by the platform repository's tooling as the other SDKs
are (`rust-sdk/v*`). `fw-flowcatalyst` depends on it by version (crates.io) or git tag and is the
only framework crate that does, as `framework-flowcatalyst` is for the Java SDK. Until it exists,
nothing in R0 to R3 needs it.

## 5. Contract wording found ambiguous while reading

- `contracts/flowcatalyst.md` section 4a says "Unix seconds must be ASCII digits", which reads as if
  only Unix seconds were accepted. The platform sends a millisecond ISO-8601 timestamp
  (`m4-platform-facts.md` line 68), and the Java code (`DeliverySignature.parseTimestamp`) accepts
  both, the digits rule applying only to the Unix-seconds form. The Rust port follows the code; the
  contract sentence should say so.

## 6. Owner decisions, ordered by how much they block

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 1 | **Start now, or wait.** Plumbing section 10 step 6 says to port to Rust "once two Java services have used" the contracts; only one has. | Start R0 to R2 now (foundation, locks, write path, checked SQL): those contracts are built, reviewed and stable. Hold R4 onwards until the Java M4c contract exists and the first application has run on M4a/M4b in anger. | everything |
| 2 | **Where the code lives.** | `rust/` workspace in this repository (section 2.1). | R0 |
| 3 | **Licence and supply-chain policy.** | Apache-2.0 like the Java framework; `cargo deny` with no copyleft; `cargo vet` not required at first (the platform's vet exemptions would have to be re-audited here). | R0 |
| 4 | **Toolchain.** | Edition 2024, `rust-version` 1.98, pin 1.98.1 (the platform's). | R0 |
| 5 | **Runtime.** | tokio only; no runtime-agnostic abstractions. | R1 |
| 6 | **Wiring model.** | Explicit `build` function and typed state, declaration handles, no `TypeId` container, no `override` (section 1.1). | R1 |
| 7 | **Driver crates or features.** | Features of the capability crate (section 2.2). | R1 |
| 8 | **Framework tables and Redis keys are shared contracts** (`fw_schema_history`, `fw_lock`, `fw_dispatch_once`, lock and rate-limit keys and scripts). | Yes: identical, conformance-tested, so a service can move between implementations without a data migration. | R1 |
| 9 | **Checked SQL.** | sqlx compile-time macros with committed `.sqlx/` offline data and `cargo fw sql prepare/check` (capabilities section 4), not a Rust port of the Java `.sql` generator. | R2 |
| 10 | **Metrics.** | The framework's own explicit `Metrics` registry and exposition writer (exact contract bytes, no global recorder), with an optional bridge that installs a `metrics`-crate recorder for third-party libraries (capabilities section 9). | R1/R3 |
| 11 | **The platform client.** | A new `flowcatalyst-sdk` crate in `flowcatalyst-go/clients/rust-sdk`, seeded from fc-sdk (section 4). Alternatives: depend on fc-sdk by git (carries the outbox and dispatch-job divergences and lives in a paused repository), or a minimal client inside `fw-flowcatalyst` (fast, but a second Rust client for the platform). | R4 |
| 12 | **Template engine.** | askama (compile-time checked, typed, Jinja-like, the analogue of JTE and templ); not maud (markup as a Rust DSL, every golden would be translated), not minijinja (errors at render time). rinja is no longer separate: it was merged back into askama in 2025. | R5 |
| 13 | **Datastar.** | Our own adapter (about 300 lines, exact bytes shared with the Java golden tests), as Java did; the official `datastar` crate is the alternative (the Go platform chose its official SDK, wrapped). | R3 |
| 14 | **Receiver semantics specific to Rust**: a typed dispatch handler whose payload does not decode answers `Reject` (terminal); a handler past its timeout is cancelled, and the scheduled-job lock is held until its task has really ended. | Yes to both; contract amendment in `flowcatalyst.md` for the first. | R4 |
| 15 | **`with_lock` on loss**: cancel the action (drop its future) or let it run and report `Lost`. | Cancel, which is what Java's interrupt intends; the action can observe `lease.lost()` first. | R1 |
| 16 | **Request validation.** | A hand-written `Validate` trait with rule helpers that collect every error, mirroring Java's `Validator` (no attribute mini-language: plumbing section 2 drops it), plus "parse, don't validate" into domain commands. Not `validator` or `garde`. | R3 |
| 17 | **Macros.** | No framework proc-macro crate in R0 to R3; revisit `#[derive(Settings)]` after the first example if the `const Setting` declarations are too verbose. | R1 |
| 18 | **Conformance goldens come from the Java tests.** | A small Java-side change: the Java tests write and compare the shared `conformance/` vectors, so Java stays the reference until the owner says otherwise. | R1 |
| 19 | **Crate names and publication.** | Settle the name (open question 6) before R1 ends, then reserve the crate names on crates.io; publish to crates.io (GitHub Packages has no Cargo registry). | first release |
| 20 | **Contract wording**: signature timestamp formats (section 5). | Amend the sentence. | none |

The Java owner rulings still pending (rate-limit store failure default `ALLOW`, IPv6 /64, the memory
overflow bucket; service-scoped secrets and world-readable files; the SDK publication and group id)
apply to Rust unchanged; whatever is ruled for Java is ruled for both.
