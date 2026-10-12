# Rust framework: capability by capability (draft 1, 2026-10-11)

Companion to [`design.md`](design.md). For each Java contract: the key Rust types (sketches, not
final signatures), what is taken from the contract verbatim, what changes and why, and the open
rulings. "Verbatim" means the rule and its observable behaviour; the conformance suite
([`milestones.md`](milestones.md) section 3) checks it against the Java framework's output.

Sketches omit `pub`, docs and derives where they add nothing. `BoxFuture<'a, T>` is
`Pin<Box<dyn Future<Output = T> + Send + 'a>>`.

## 1. Core: lifecycle, instances, Context, config, health, commands

Contract: [`core.md`](../contracts/core.md).

### Types

```rust
// --- lifecycle ---------------------------------------------------------------
struct AppBuilder<S> { /* phase S: Declaring | Configured */ }

trait Module {
    type Declared;                                 // the typed handles this module hands back
    fn id(&self) -> ModuleId;                      // const-checked `[a-z][a-z0-9-]*`
    fn declare(self, d: &mut Declarations<'_>) -> Self::Declared;
}

impl AppBuilder<Declaring> {
    fn new(name: AppName) -> Self;
    fn environment(self, env: Environment) -> Self;  // tests: an explicit map, else the process env
    fn clock(self, clock: Arc<dyn Clock>) -> Self;
    fn config_dir(self, dir: PathBuf) -> Self;
    fn module<M: Module>(&mut self, m: M) -> M::Declared;
    fn configure(self) -> Result<AppBuilder<Configured>, StartupError>;   // every config problem at once
}

struct Boot<'a> { /* config, clock, ids, metrics, health, background, shutdown stack, commands */ }
impl Boot<'_> {
    fn read<T: Settings>(&self, h: &SettingsHandle<T>) -> T;          // infallible: declared and validated
    fn health(&mut self) -> &mut Health;
    fn background(&mut self) -> &mut Background;                    // loops start after every started action
    fn on_started(&mut self, name: &'static str, f: impl AsyncFnOnce() -> Result<(), StartupError> + Send + 'static);
    fn on_shutdown(&mut self, name: &'static str, f: impl AsyncFnOnce() + Send + 'static);
    fn command(&mut self, c: impl Command + 'static);
    fn ids(&self) -> &Ids;
    fn metrics(&self) -> &Metrics;
}

async fn launch<S, F>(app: AppBuilder<Declaring>, args: impl IntoIterator<Item = String>, build: F) -> ExitCode
where F: AsyncFnOnce(&mut Boot<'_>) -> Result<Serve<S>, StartupError>;

trait Command: Send + Sync {
    fn name(&self) -> CommandName;
    fn run(&self, args: Vec<String>, out: &mut dyn Write) -> BoxFuture<'_, ExitCode>;
}

// --- named instances and drivers -----------------------------------------------
struct DriverRegistry<D: ?Sized> { /* name -> (KeySchema, factory) */ }
trait DriverFactory<D: ?Sized>: Send + Sync {
    fn keys(&self) -> KeySchema;                   // declared at `declare`, so `configure` can check every section
    fn build<'a>(&'a self, name: &'a InstanceName, cfg: InstanceConfig<'a>, built: &'a Instances<D>)
        -> BoxFuture<'a, Result<Arc<D>, DriverError>>;
}
struct Instances<D: ?Sized> { default: Option<InstanceName>, by_name: BTreeMap<InstanceName, Arc<D>> }

// --- context -------------------------------------------------------------------
#[derive(Clone, Debug)]
#[non_exhaustive]
struct Context {
    client: ClientId,                 // always
    principal: Option<PrincipalId>,
    application: Option<ApplicationCode>,
    correlation: CorrelationId,       // always; generated when absent
    causation: Option<CausationId>,
    execution: Option<ExecutionId>,
}

// --- config ----------------------------------------------------------------------
struct Setting<T: SettingType> { key: Key, kind: Kind, default: Option<T::Const>, required: bool, store: Store, description: &'static str }
// T::Const is a const-constructible form: &'static str for String, Duration for Duration, ...
const HTTP_PORT: Setting<u16> = Setting::value(Key::of("http.port"), "port to listen on").default(8080);
const FC_SECRET: Setting<Secret> = Setting::secret(Key::of("flowcatalyst.client-secret"), "...").required();

trait Settings: Sized {                       // a module's group of settings, read as one struct
    fn declare(d: &mut SettingsDecl<'_>);
    fn read(v: &Values<'_>) -> Self;          // runs after validation; every get is infallible
}

// --- health ----------------------------------------------------------------------
enum Criticality { Critical, Informational }
enum CheckResult { Up, Down(String), TimedOut }
impl Health { fn add(&mut self, name: CheckName, c: Criticality, check: impl HealthCheck + 'static); }
```

### Verbatim

The four phases' meaning and order (declare, configure, build, start; started actions after every
part is built; loops after every started action); shutdown order and `app.shutdown.drain`; health
checks with `app.health.timeout`, criticality and the `app.health.critical` /
`app.health.informational` overrides (a name in both fails start-up, an unmatched name is a WARN);
the instance rules (unknown driver lists the registered names, unread keys fail start-up, `default`
rules, defaults for single-driver capabilities, wrappers see the instances already built); every
`Context` field and the `ClientId` and `ApplicationCode` patterns; every config rule: key grammar,
types, kinds, sources and their order, `APP_ENV`, `config/application.yaml` and
`application-{env}.yaml`, the env-name mapping and the collision check, value syntax (durations,
booleans), errors that name the key and source and never a secret's text, `secrets.local.yaml` only
for `local` and `test`, platform addressing and the reserved components. Commands: a claimed first
argument runs the command after build, without started actions or loops; two claims fail start-up.

### Changes

| Java | Rust | Why |
|---|---|---|
| `register(AppBuilder)` and `boot(App)` hooks on a `Module` | `Module::declare` returns typed handles; building is the application's `build` function | compile-time wiring (design section 1.1) |
| `app.get(Class)`, `find(Class)` | none; values are passed | no container |
| `AppBuilder.override(Class, instance)` in test mode | the application's `build` takes its replaceable collaborators as arguments; tests pass fakes | no runtime type map; nothing to guard against in production |
| `config.get(setting)` throws for an undeclared setting | `boot.read(&handle)` cannot be called without the handle that declaring returned | the declaration is the proof |
| `StartupException` with a list of problems | `StartupError { problems: Vec<Problem> }`, `Display` prints one per line | same report |
| `InstantSource`, `MonotonicClock` seeded and not overridable | `Clock` on the builder; monotonic time is `tokio::time::Instant` | tokio's paused time drives tests |
| `EnvironmentVariables` seeded service | `Environment` given to the builder; read by exact name, never listed | same rule |
| health checks are `Callable`s | `trait HealthCheck { fn check(&self) -> BoxFuture<'_, CheckResult>; }`, each run under `tokio::time::timeout` | async |

### Open rulings

- `#[derive(Settings)]` to replace the hand-written `declare`/`read` pair (design, decision 17).
- Config is read synchronously at `configure` (files and environment). External stores (platform,
  AWS) in M5 make it async; `configure` becomes `async` then, which is a breaking change to plan for
  now: make it `async` from R1.

## 2. Database, migrations, Redis

Contract: [`database-and-redis.md`](../contracts/database-and-redis.md).

### Types

```rust
struct Databases { default: InstanceName, by_name: BTreeMap<InstanceName, Db> }
#[derive(Clone)] struct Db { name: InstanceName, pool: PgPool }        // sqlx
struct Tx<'c> { inner: sqlx::Transaction<'c, Postgres> }                // only the use-case runner and `once` begin one

impl Databases {
    async fn connect(boot: &mut Boot<'_>, decl: &DatabaseDeclared) -> Result<Self, StartupError>; // pings, health `database:<name>`, close on shutdown
    fn get(&self, name: &InstanceName) -> Option<&Db>;
    fn default_db(&self) -> &Db;
}

struct MigrationSet { module: ModuleId, files: &'static [MigrationFile] }  // include_str! of V<n>__<desc>.sql
impl Boot<'_> { async fn migrate(&mut self, dbs: &Databases) -> Result<(), StartupError>; }
```

Migration files are embedded with `include_str!` (or `include_dir`) by each crate that contributes a
set, so a binary carries its migrations. Redis: `RedisConnections` over the `redis` crate's
`ConnectionManager` (the platform's choice), `url` plus an optional `password` secret, health check
`redis:<name>`.

### Verbatim

Named instances, `password` as a secret, a start-up ping per instance; migrations: file naming,
one set per module in declaration order, the default instance only and only when `migrate` is true,
**the `fw_schema_history` table and its columns, SHA-256 of the file bytes**, a transaction per
file with its history row, the advisory lock, every start-up failure (changed checksum, missing
file, out-of-order unapplied file, duplicate version), the non-default-instance table check. Redis:
standalone only, the health check, start-up ping.

### Changes

- The pool is sqlx's `PgPool`, not HikariCP; settings `pool.max`, `pool.min-idle`,
  `pool.connect-timeout` map to `PgPoolOptions`. The `url` is a `postgres://` URL, not
  `jdbc:postgresql://`; both are accepted (owner ruling 2026-10-12): a `jdbc:` prefix is dropped so a
  Rust service can take over a Java service's configuration, and `postgres://` / `postgresql://` is
  Rust's own form. Plain `key=value` pairs are not accepted (sqlx cannot parse them). The separate
  `username` / `password` keys win over credentials in the URL.
- **The migration runner is the framework's own, not `sqlx::migrate!`.** sqlx's runner keeps its own
  table (`_sqlx_migrations`) with different columns and rules; using it would break decision 8 (a
  Rust service taking over a Java service's database) and the per-module sets.
- Blocking JDBC on virtual threads becomes async sqlx; nothing in the contract depended on blocking.

### Open rulings

None for this section.

## 3. The write path

Contract: [`write-path.md`](../contracts/write-path.md). The full example is
[`orders-showcase.md`](orders-showcase.md).

### Types

```rust
trait Aggregate: Send + Sync + 'static {
    type Id: AggregateId;                         // usually Id<Self>
    fn id(&self) -> &Self::Id;
}
// Persistence state is the framework's, not the aggregate's: no client or version fields on the domain type.
struct Fresh<A>  { value: A, client: ClientId }                       // ctx.fresh(a): the context's client, version 0
struct Loaded<A> { value: A, client: ClientId, version: Version }     // built by a repository from its row
impl<A> Loaded<A> {
    fn new(client: ClientId, version: Version, value: A) -> Self;    // for repositories
    fn get(&self) -> &A;
    fn map(self, f: impl FnOnce(A) -> A) -> Self;                     // keeps client and version
    fn try_map<T, E>(self, f: impl FnOnce(A) -> Result<(A, T), E>) -> Result<(Self, T), E>;
}

trait Repository<A: Aggregate>: Send + Sync {
    fn find(&self, db: &Db, client: &ClientId, id: &A::Id) -> impl Future<Output = Result<Option<Loaded<A>>, DbError>> + Send;
    fn insert(&self, tx: &mut Tx<'_>, client: &ClientId, a: &A) -> impl Future<Output = Result<(), DbError>> + Send;         // stores version 1
    fn update(&self, tx: &mut Tx<'_>, client: &ClientId, a: &A, v: VersionCheck) -> impl Future<Output = Result<u64, DbError>> + Send;
    fn delete(&self, tx: &mut Tx<'_>, client: &ClientId, a: &A, v: VersionCheck) -> impl Future<Output = Result<u64, DbError>> + Send;
}
struct VersionCheck { expected: Version, next: Version }   // computed by the runner

trait UseCase: Send + Sync {
    const NAME: UseCaseName;                       // const-checked `[a-z][a-z0-9-]*`
    type Command: Send + Sync;
    type Event: DomainEvent;

    fn conflict_policy(&self) -> ConflictPolicy { ConflictPolicy::Fail }
    fn validate(&self, _ctx: &Context, _cmd: &Self::Command) -> Result<(), UseCaseError> { Ok(()) }
    fn authorize(&self, ctx: &Context, cmd: &Valid<Self::Command>)
        -> impl Future<Output = Result<Grant, UseCaseError>> + Send;             // required: no default
    fn execute<'a>(&'a self, ctx: &'a Context, cmd: Authorized<'a, Self::Command>, db: &'a Db)
        -> impl Future<Output = Result<Plan<'a, Self::Event>, UseCaseError>> + Send;
    fn audit_data(&self, _cmd: &Self::Command) -> Option<BTreeMap<String, String>> { None }
}

struct Grant(/* private */);
impl Grant { fn public() -> Self; }                 // Authorize.PUBLIC: deliberate and greppable
impl Context { fn require(&self, p: &Permission) -> Result<Grant, UseCaseError>; }  // once principals carry permissions (M4c)

struct Plan<'a, E> { /* steps: Vec<Box<dyn Step + 'a>>, events, dispatch jobs */ }
impl<'a, E: DomainEvent> Plan<'a, E> {
    fn save<A, R>(a: impl Into<Write<A>>, repo: &'a R, event: E) -> Self where A: Aggregate, R: Repository<A>;
    fn delete<A, R>(a: Loaded<A>, repo: &'a R, event: E) -> Self where A: Aggregate, R: Repository<A>;
    fn emit(event: E) -> Self;
    fn and_save<A, R>(self, a: impl Into<Write<A>>, repo: &'a R) -> Self where A: Aggregate, R: Repository<A>;
    fn and_delete<A, R>(self, a: Loaded<A>, repo: &'a R) -> Self where A: Aggregate, R: Repository<A>;
    fn and_emit(self, event: impl DomainEvent) -> Self;
    fn and_dispatch(self, job: DispatchJob) -> Self;
}
enum Write<A> { Insert(Fresh<A>), Update(Loaded<A>) }   // From<Fresh<A>>, From<Loaded<A>>

struct UseCases { /* db, outbox writer, clock, ids, metrics */ }
impl UseCases {
    async fn run<U: UseCase>(&self, uc: &U, ctx: &Context, cmd: U::Command) -> Result<Committed<U::Event>, UseCaseError>;
}
struct Committed<E>(E);                             // constructor private to fw-usecase
enum ConflictPolicy { Fail, Retry { attempts: Attempts, backoff: Duration } }   // Attempts: 2..=10, checked

trait DomainEvent: Serialize + Send + Sync {
    const TYPE: EventType;                          // const-checked `{app}:{domain}:{aggregate}:{action}`
    fn metadata(&self) -> &EventMetadata;
}
```

### Verbatim

Section 2 (repository operations, `WHERE id AND client_id AND version`, row counts 0 and above 1,
`23505` → `CONFLICT/UNIQUE_VIOLATION` not retried); section 3 (name, validate, authorize required,
execute returns a plan or a rejection, conflict policy, load once); section 4 (the attempt's steps
in order: validate, authorize, execute, the client check, `READ COMMITTED` on the default instance,
writes in plan order, then events, then dispatch jobs, then one audit row, commit; the failure
table with the step codes `VALIDATE` … `COMMIT`; the retry policy with jitter, 2 to 10 attempts);
section 5 (kinds and statuses); section 6 (event metadata, every field rule, the message group
default); **section 7 and 7a in full** (the outbox table from the SDK file byte for byte plus
`V0002`, every column and payload field of event, audit and dispatch-job rows, `contextData`
entries, `deduplicationId`, the dispatch-job validation rules and defaults, `kind` default `TASK`,
the fields never written, `metadata` as an array); section 8 (TSIDs). Metrics
`fw_usecase_runs_total` and `fw_usecase_duration_seconds`.

### Changes

| Java | Rust | Why |
|---|---|---|
| `Aggregate.client()`, `version()` on the domain record | `Fresh<A>` / `Loaded<A>` wrappers hold them | the aggregate stays ignorant of storage, and insert versus update is a type |
| runner checks `version() == 0` to choose insert | `Write::Insert` / `Write::Update` by type | a found aggregate can never be re-inserted, nor a new one updated |
| `CROSS_CLIENT_WRITE` checked for every aggregate | impossible for `Fresh` (built from the context's client); still checked for `Loaded`, whose client is the row's `client_id` as the repository read it | catches a repository whose `find` forgot `client_id = $` |
| `UseCase.named(..).validate(..).authorize(..).execute(..)`; missing `authorize` throws at definition | a trait; `authorize` has no default, so a missing one does not compile | compile time |
| any caller can run a phase function | `Valid` and `Authorized` are constructible only by the runner | execute is unreachable except through `run` |
| `UseCaseResult.Committed / Failed` | `Result<Committed<E>, UseCaseError>` | idiomatic; `Committed` is still sealed |
| `INTERRUPTED` when interrupted between attempts | none: the caller dropped the future; the transaction rolls back on drop | cancellation model (design section 1.4) |
| `AuditData` interface on the command | `UseCase::audit_data` with a default of none | no specialisation in Rust |
| event data = the record's fields (Jackson) | the event struct's `Serialize` (serde, `rename_all = "camelCase"` where the Java field names are camelCase); metadata is `#[serde(skip)]` and goes into the DTO | same JSON |
| `Ids.generate("ord")` returning a string | `ids.next::<Order>() -> Id<Order>` (prefix from the kind) and `ids.generate(prefix) -> String` | typed ids |

A use case loads through its repositories with `&Db` (a pool, no transaction), as in Java. Load-once
stays a rule, not a type. A debug-build check (`INTERNAL / RELOADED`) was proposed and **dropped**
(owner ruling 2026-10-12): nothing enforces it, as in Java.

### Open rulings

- Whether `Context` gains the principal's permissions in M4c, which `Context::require` needs; until
  then `authorize` checks what `Context` has (principal present, application).

## 4. Checked SQL

Contract: [`checked-sql.md`](../contracts/checked-sql.md). This is where Rust has the stronger tool
already: sqlx's compile-time macros are what the Java generator was modelled on ("the approach is
that of sqlc and of sqlx's offline mode").

### Recommendation

Use **`sqlx::query!`, `query_as!`, `query_scalar!`** (and `query_file_as!` for a long query kept in
a `.sql` file) with **offline mode**: the query descriptions are committed in `rust/.sqlx/`,
`.cargo/config.toml` sets `SQLX_OFFLINE=true`, and **an ordinary build needs no database**, which is
exactly the Java property "generated code is committed; ordinary builds need no database".

`cargo fw sql prepare` (in `tools/cargo-fw`) starts a throwaway PostgreSQL 18 with Testcontainers
(or uses the empty database at `FW_SQL_DATABASE_URL`), applies every migration set **with the
framework's own runner**, then runs `cargo sqlx prepare --workspace -- --all-targets`.
`cargo fw sql check` does the same with `--check` and fails, naming the queries, when `.sqlx/` is
stale. CI runs the check. This is the platform's working setup (`scripts/sqlx-prepare.sh`),
generalised.

### Why not a Rust port of the Java generator

| | sqlx macros | port of the Java generator (`.sql` files → generated Rust) |
|---|---|---|
| checked against the real schema | yes, by describe, as Java | yes |
| a stale description fails the build | yes ("no cached data for this query"; `prepare --check`) | yes, by a check goal |
| IDE feedback | at `cargo check`, on the query in place | after regeneration |
| typed overrides `AS "id: OrderId"`, `!`, `?` | native; the Java syntax was copied from it | to be written |
| cost | none to build; the ecosystem maintains it | a code generator to write, test and maintain |
| keeps SQL in `.sql` files | optional (`query_file_as!`, one query per file) | always |

Closest third-party alternative: clorinde (the successor of cornucopia) generates Rust from
annotated `.sql` files, but over `tokio-postgres`, so an application would run two drivers. Not
recommended.

### Verbatim

Section 2's semantics: the type mapping (with Rust types: `text` → `String`, `int4` → `i32`,
`int8` → `i64`, `numeric` → `rust_decimal::Decimal` or `BigDecimal`, `timestamptz` →
`DateTime<Utc>`, `uuid` → `Uuid`, `bytea` → `Vec<u8>`, `json`/`jsonb` → `serde_json::Value` or
`Json<T>`, arrays → `Vec<T>`); nullability read from the schema; **every column of an outer join is
marked `?`** (sqlx does not always see it, the same weakness Java documents); `!` only where the
query guarantees it; typed values through overrides. Section 5's scope (dynamic queries stay
hand-written: `QueryBuilder` with `push_bind`, never `format!` with values).

### Changes

- Queries live in Rust source next to their mapping (the platform's convention), or one per `.sql`
  file with `query_file_as!`; not many per file with `-- name:` headers.
- Typed ids bind as `id as &OrderId` and read as `AS "id: OrderId"`; `Id<K>` implements
  `sqlx::Type`, `Encode` and `Decode` once, validating on decode (a corrupt row is a decode error
  naming the column, not a panic).
- A `!` override that meets a null is sqlx's decode error, which is an `INTERNAL` failure, as
  Java's `IllegalStateException`.
- The 63-byte alias limit and the `-- column:` workaround are unnecessary: Rust type names in
  overrides are short (`OrderId`, a type alias), not fully qualified.

### Open rulings

- None beyond decision 9. Note for the guide: a new `sqlx` version can change `.sqlx/` hashes; pin
  `sqlx-cli` to the library version (the platform pins `=0.8.6`).

## 5. HTTP

Contract: [`http.md`](../contracts/http.md). axum 0.8 on hyper, tower, tower-http.

### Types

```rust
struct Routes<S> { router: axum::Router<S>, table: RouteTable }   // RouteTable records (method, shape, module) for every route
impl<S: Clone + Send + Sync + 'static> Routes<S> {
    fn get<H, T>(self, path: RoutePath, module: ModuleId, h: H) -> Self where H: axum::handler::Handler<T, S>;
    // post, put, patch, delete, options; `route_layer` for per-route middleware
    fn layer<L>(self, l: L) -> Self;                       // application middleware, inside the framework's
    fn build(self) -> Result<axum::Router<S>, StartupError>; // duplicate detection, 404/405/OPTIONS fallbacks, fixed layers
}
const ORDER: RoutePath = RoutePath::of("/orders/{id}");      // const-checked pattern grammar

// errors
struct HttpError { status: StatusCode, code: ErrorCode, message: Cow<'static, str>, details: BTreeMap<String, String> }
impl IntoResponse for HttpError { /* the envelope */ }
impl<E: ServiceError> From<E> for HttpError { /* status, code, message, details */ }

// extractors
struct Json<T>(T);              // 415 unless application/json; unknown fields refused (serde_ignored); errors with JSON path (serde_path_to_error); never echoes values
struct Valid<T>(T);             // Json<T> then T::validate, every error collected; 400 VALIDATION with details
struct Ctx(Context);            // needs a ContextResolver in the router state, else does not compile
struct Correlation(CorrelationId);
struct RemoteAddr(IpAddr);      // from ConnectInfo, never a header

trait Validate { fn validate(&self, v: &mut Violations); }
// v.field("customer", &self.customer).not_blank().max_len(100);  v.nested("address", &self.address);  v.each("tags", &self.tags, |r| r.max_len(20));

trait ContextResolver: Send + Sync {
    fn resolve<'a>(&'a self, parts: &'a http::request::Parts) -> BoxFuture<'a, Result<Context, HttpError>>;
}
// impl<S> FromRequestParts<S> for Ctx where Arc<dyn ContextResolver>: FromRef<S>
```

### Verbatim

Section 2's routing rules (methods, `{name}` segments, no wildcards, trailing slash distinct, no
redirects, literal beats parameter, per-segment percent-decoding, duplicates fail start-up naming
both registrants, 404, 405 with a sorted `Allow` including `HEAD` with `GET` and always `OPTIONS`,
`OPTIONS` 204, HEAD answered from GET, routes logged at INFO, no CORS); the fixed outermost
middleware order **correlation id, error mapping, access log**, then application middleware, then
context resolution on routes that need it; section 5 in full (envelope, every status and code, use
case mapping, 500 never carries the cause); section 6 (correlation-id rules: header, pattern,
replaced not rejected, echoed on every response, forced into the resolved context); section 7's
outcomes (415, `INVALID_JSON` with the path and without the value, unknown properties refused, every
error collected, details `"<code>: <message>"`, first error per field, the rule set and their codes);
section 8's settings, health endpoints and their JSON, the body limit 413 with the connection
closed, graceful shutdown and the drain 503, no `Server` header or stack traces; section 10's
Datastar bytes.

### Changes

| Java | Rust | Why |
|---|---|---|
| own router and `Pipeline<I, O>` in core | axum's router (matchit: the same precedence and `{name}` syntax) wrapped by `Routes`, which keeps its own table to detect duplicates as start-up problems (axum would panic) and to compute `Allow` | reuse the ecosystem; keep the contract's errors |
| `Middleware<I, O>` shared with jobs | tower `Layer`/`Service`, generic over the request type; the dispatch receiver runs its handlers through the same timeout, panic-catching and metrics layers | one abstraction, as the contract asks |
| handler throws `HttpException` | handler returns `Result<impl IntoResponse, HttpError>`; panics become 500 through `CatchPanicLayer` | errors are values |
| `request.context()` throws without `requiresContext` | the `Ctx` extractor; a route that takes `Ctx` does not compile unless the state provides a `ContextResolver` | the start-up check becomes a compile error |
| Jackson `FAIL_ON_UNKNOWN_PROPERTIES` globally | `serde_ignored` around deserialisation in the framework's `Json`, so no type needs `deny_unknown_fields` | same strictness without per-type attributes |
| `Validator.of(..).field(..)` | the `Validate` trait (above); plus parse-don't-validate into domain commands (`TryFrom<Request> for Command`) | explicit functions, no attribute language |
| `TestClient` | `TestClient` over `tower::ServiceExt::oneshot` on the built router, `MockConnectInfo` for `127.0.0.1` | no socket |
| `http.request-timeout` (Jetty idle and read) | header-read timeout on hyper's builder plus a total-request `TimeoutLayer` | the closest equivalent; documented |
| Jetty `Handler`, virtual threads | `axum::serve`, tokio tasks | no "virtual thread asserted" test |

`Routes::build` installs, outermost first, with `tower::ServiceBuilder` (which reads top-down):
correlation id, error mapping (envelope for errors produced below, panics caught), access log
(`tracing`, method, route pattern, status, duration, correlation id), metrics, the drain guard, the
body limit, then the application's layers; route-level layers (`route_layer`) run inside routing.

### Open rulings

- Whether a handler may return a non-envelope error body (Java allows any `Response`): yes, as in
  Java; only the framework's own errors are guaranteed to be envelopes.

## 6. Locks

Contract: [`locks.md`](../contracts/locks.md). The contract is taken almost entirely verbatim.

### Types

```rust
struct LockKey { scope: LockScope, name: LockName }        // LockKey::service(name), LockKey::client(&client, name)
struct Lease { key: LockKey, owner: OwnerToken, fence: FencingToken, deadline: tokio::time::Instant, store: StoreId, lost: CancellationToken }
enum Acquire { Acquired(Lease), Busy }
enum Refresh { Refreshed(Lease), Lost }
enum Release { Released, NotHeld }
enum WithLock<T> { Completed(T), NotAcquired, Lost(Option<T>) }

trait LockStore: Send + Sync {                       // the driver contract, dyn
    fn try_acquire(&self, key: &LockKey, lease: LeaseLength) -> BoxFuture<'_, Result<Acquire, LockStoreError>>;
    fn refresh(&self, lease: &Lease, len: LeaseLength) -> BoxFuture<'_, Result<Refresh, LockStoreError>>;
    fn release(&self, lease: &Lease) -> BoxFuture<'_, Result<Release, LockStoreError>>;
}
struct Lock { store: Arc<dyn LockStore> }            // the user-facing API, one per named instance
impl Lock {
    async fn acquire(&self, key: &LockKey, lease: LeaseLength, max_wait: MaxWait) -> Result<Acquire, LockStoreError>;
    async fn with_lock<T>(&self, key: &LockKey, lease: LeaseLength, max_wait: MaxWait,
                          action: impl AsyncFnOnce(&Lease) -> T) -> Result<WithLock<T>, LockStoreError>;
}
struct Locks(Instances<dyn LockStore>);              // get(name), default_instance()
```

`LeaseLength` (100 ms to 24 h) and `MaxWait` (0 to 24 h) are checked newtypes, so the argument
limits are checked once, at construction, instead of on every call.

### Verbatim

Everything in sections 1 to 5: key scopes and stored forms, the name pattern, the seven driver
guarantees, the local deadline taken before the request, backoff 10 ms doubling to 250 ms with
jitter, `with_lock` refreshing every `lease / 3` and its result rules, **the `fw_lock` table and
sequence and the three SQL statements with `clock_timestamp()`**, **the Redis key format, the value
`<owner>:<fence>`, the counter key and the three Lua scripts** with `EVALSHA` and `NOSCRIPT`
reload, the memory driver's single-process scope, and the contract test cases of section 6.

### Changes

- `with_lock` runs the action and the refresher in one `tokio::select!`. On loss the action's
  future is dropped (decision 15) and the result is `Lost(None)`; before that, `lease.lost` is
  cancelled so a cooperative action can stop at a safe point and return, giving `Lost(Some(value))`.
  Java interrupts the thread; the outcome is the same.
- An error from the action is not wrapped: `T` is whatever the action returns, usually a `Result`.
  `LockedActionException` has no equivalent.
- `InterruptedException` from `acquire`: none; dropping the future abandons the wait.
- The memory driver's clock is tokio's monotonic clock; tests pause and advance it.
- The contract test suite is `fw_locks::testing::suite!(harness)`, a `macro_rules!` that expands to
  one `#[tokio::test]` per case, taking a harness that supplies the store, a way to let time pass
  and a hook that expires a key's lease in the store (design section 1.7).

### Open rulings

- None new.

## 7. Rate limiting

Contract: [`rate-limiting.md`](../contracts/rate-limiting.md).

### Types

```rust
struct RateLimitPolicy { limit: NonZeroU32, period: Duration, burst: NonZeroU32 }   // ranges checked at build
enum Decision { Allowed { remaining: u32, reset_after: Duration }, Limited { retry_after: Duration, reset_after: Duration } }
trait RateLimitStore: Send + Sync {
    fn acquire<'a>(&'a self, key: &'a str, cost: u32) -> BoxFuture<'a, Result<Decision, RateLimitStoreError>>;
}
struct RateLimitLayer<K> { limiter: RateLimiter, key: K, cost: u32, headers: bool, on_failure: OnStoreFailure, metrics: Metrics }
trait KeyResolver: Clone + Send + Sync + 'static { fn key(&self, req: &http::request::Parts) -> Option<String>; }
// KeyResolvers: client_address(), forwarded_for(n), header(name) (hashed), client(), principal()
```

### Verbatim

All of it: GCRA and its arithmetic, the policy ranges, `cost`, the memory driver's bound, purge and
overflow bucket, the Redis driver's key format `<prefix><instance>:<key>`, the server clock in
microseconds and **the Lua script** (so Java and Rust replicas share one budget), the 429 envelope
and `Retry-After` rounding, the `RateLimit-*` headers, `ALLOW`/`DENY` on store failure, the shared
`-` bucket for a null key, hashing keys over 200 characters and header values, IPv6 /64 grouping,
the `X-Forwarded-For` rule (the entry `n` from the right, literals only), where it runs (global
versus route), the metrics, and the abuse table.

### Changes

- The middleware is a tower `Layer`; as a global layer it runs before routing (no `Context`), as a
  `route_layer` after context resolution, matching the contract's two positions.
- GCRA is the framework's own (about 40 lines). `governor` implements GCRA but its keyed store has
  no bound, overflow bucket or the contract's decision shape; `tower::limit::RateLimit` is not
  keyed.
- Remote address from `ConnectInfo<SocketAddr>`; literals parsed with `IpAddr::from_str`, which
  never resolves names.

### Open rulings

- The three pending Java rulings (design section 6, last paragraph).

## 8. Per-client secrets

Contract: [`secrets.md`](../contracts/secrets.md).

### Types

```rust
struct SecretRef { store: Option<InstanceName>, name: SecretName }       // SecretRef::parse("files:sftp-password")
enum SecretResult { Found(Secret), NotFound, Refused(RefusalReason) }
trait SecretStore: Send + Sync {
    fn find<'a>(&'a self, client: &'a ClientId, name: &'a SecretName) -> BoxFuture<'a, Result<SecretResult, SecretStoreError>>;
    fn evict(&self, client: &ClientId, name: &SecretName);
    fn evict_all(&self);
}
struct SecretResolver { stores: Instances<dyn SecretStore>, cache: SecretCache }
impl SecretResolver {
    async fn resolve(&self, client: &ClientId, r: &SecretRef) -> Result<SecretResult, SecretStoreError>;
    async fn require(&self, client: &ClientId, r: &SecretRef) -> Result<Secret, SecretError>;   // NotFound, Refused, Store
}
```

### Verbatim

The threat model and every mitigation; the reference grammar; the client always from the caller;
the `env` driver's prefix rules, reserved prefixes and injective encoding; the `file` driver's
directory, client pattern, symlink resolution and the last-two-elements check, `max-bytes`, strict
UTF-8, one trailing newline removed, empty is not found, world-readable files warned once; the
cache (`cache-ttl`, `cache-max`, LRU, never caches not-found); `platform` fails start-up until the
platform has the endpoint; no value in any message or log.

### Changes

- `Secret` is zeroised on drop, which the contract lists as out of scope for Java; the contract's
  out-of-scope line changes for Rust.
- Exceptions become `SecretError` / `SecretStoreError` variants; `IllegalArgumentException` for an
  unknown store becomes `SecretError::UnknownStore`.
- File reads use `tokio::fs` (or `spawn_blocking` around `std::fs::canonicalize` and the open), and
  the permission check uses `std::os::unix::fs::PermissionsExt` on Unix.

### Open rulings

- The two pending Java rulings and the platform endpoint.

## 9. Background work, metrics, logging

Contract: [`background-and-metrics.md`](../contracts/background-and-metrics.md).

### Types

```rust
struct LoopOptions { initial_delay: Duration, interval: Interval, gate: Option<Arc<dyn LoopGate>>, criticality: Criticality, down_after: u16, stuck_after: Option<Duration> }
struct LoopTick { name: LoopName, run: u64, fencing: Option<FencingToken>, stop: CancellationToken }   // stopping(), cancelled().await
enum GateOutcome<T> { Ran(T, Option<FencingToken>), Skipped(String), Lost }
impl Background {
    fn spawn_loop<F>(&mut self, name: LoopName, opts: LoopOptions, task: F)
    where F: FnMut(LoopTick) -> BoxFuture<'static, Result<(), LoopError>> + Send + 'static;
}

#[derive(Clone)] struct Metrics { inner: Arc<Registry> }   // explicit, never global
impl Metrics {
    fn counter(&self, name: MetricName, help: &'static str, labels: &[LabelName]) -> Result<Counter, MetricsError>;
    fn gauge(..) -> Result<Gauge, MetricsError>;
    fn gauge_fn(&self, name: MetricName, help: &'static str, f: impl Fn() -> f64 + Send + Sync + 'static) -> Result<(), MetricsError>;
    fn timer(..) -> Result<Timer, MetricsError>;
    fn snapshot(&self) -> Snapshot;
}
fn prometheus_text(s: &Snapshot) -> String;     // exposition format 0.0.4, exact contract bytes
```

### Verbatim

Background: names, registration rules, start after every started action, fixed-delay schedule
without overlap, interval limits, failures logged and the loop continued, the gate and its three
outcomes, the lock gate (`with_lock` with zero wait), stopping loops first at shutdown with the
drain, health `background:<name>` informational by default and its down rules (exception class only,
never the message), loop metrics. Metrics: every naming and label rule, idempotent re-creation,
truncation of label values to 200 characters, the series bound and
`fw_metrics_dropped_series_total`, the default buckets, the built-in metric names and labels
(`route` is the pattern or `<unmatched>`), the `memory` and `noop` drivers, **the exposition bytes**
(sorting, escaping, number formatting, histogram lines), the endpoint (off by default, bearer token
compared as digests, the start-up warnings).

### Changes

- An `Err` from a run is Java's exception (logged, loop continues). A **panic** is Java's `Error`:
  each run is spawned and its `JoinError::is_panic()` ends the loop and marks its health down.
- Stopping a running task after the drain is `JoinHandle::abort` (takes effect at the next
  `.await`; a CPU-bound task that never awaits cannot be stopped, which is the Rust form of a thread
  that ignores interrupts, and is logged the same way).
- Number formatting: Java's "shortest round-trip form" and Rust's `Display` for `f64` are both
  shortest round-trip, but differ in exponent style for very large and very small values; the
  writer formats explicitly to the Java output, checked by the conformance vectors.
- **Why not the `metrics` crate as the registry** (decision 10): its macros record into a
  process-global recorder, which design rule 2 forbids; its Prometheus exporter decides its own
  output (ordering, formatting), and the contract fixes the bytes; and the cardinality bound and
  naming rules would have to be wrapped anyway. `prometheus-client` keeps an explicit registry but
  writes OpenMetrics, not format 0.0.4. A feature `metrics-bridge` installs a `metrics::Recorder`
  that forwards into the framework's registry, so libraries that use the `metrics` macros still show
  up, under the same bounds.
- Logging is `tracing` (the SLF4J rule becomes: library crates use `tracing` macros only and bring
  no subscriber). Request, job, loop and use-case spans carry the context's fields
  (`correlation_id`, `client`, `principal`, `application`, `execution_id`), so every log line in
  them has them. OpenTelemetry export is an optional feature (`tracing-opentelemetry`), as in the
  platform.

## 10. HTTP client

Contract: [`http-client.md`](../contracts/http-client.md).

### Types

```rust
struct HttpClients { engine: reqwest::Client /* private */, clock: Arc<dyn Clock> }
struct ServiceOptions { name: ServiceName, base_url: BaseUrl, connect_timeout: Duration, request_timeout: Duration,
                        max_response_bytes: u64, retry: RetryPolicy, auth: Auth, default_headers: HeaderMap }
#[derive(Clone)] struct ServiceClient { /* Arc inner */ }
impl ServiceClient {
    fn get(&self, path: PathTemplate) -> RequestBuilder<'_>;      // post, put, patch, delete, head, request(method, path)
}
impl RequestBuilder<'_> {
    fn path_param(self, name: &str, value: &str) -> Result<Self, HttpClientError>;
    fn query(self, name: &str, value: &str) -> Self;
    fn header(self, name: HeaderName, value: &str) -> Result<Self, HttpClientError>;
    fn json<T: Serialize>(self, body: &T) -> Result<Self, HttpClientError>;
    fn idempotency_key(self, key: &str) -> Self;
    fn retryable(self, yes: bool) -> Self;
    async fn send(self) -> Result<ClientResponse, HttpClientError>;    // never Err for a status
    async fn send_json<T: DeserializeOwned>(self) -> Result<T, HttpClientError>;  // non-2xx is Kind::UnexpectedStatus
}
#[non_exhaustive] enum Kind { Connect, Timeout, ResponseTooLarge, Io, Decode, Auth, UnexpectedStatus, Closed }
enum Auth { None, Bearer(Arc<dyn TokenSource>), Basic { user: String, password: Arc<dyn SecretSource> }, Header { name: HeaderName, value: Arc<dyn SecretSource> }, ClientCredentials(ClientCredentials) }
```

### Verbatim

The whole behaviour: base-URL rules; the path rules that keep a request on its service's host (no
`//`, `?`, `#`, dot segments, backslash), placeholder encoding of one segment, refused empty and dot
values, unfilled placeholders; header injection refused and the four client-set headers refused;
`send` never fails for a status; the convenience error without the body; tolerant JSON on read;
failure kinds and messages that never carry headers, bodies, tokens or query strings; redirects not
followed; DEBUG per attempt; retries (which methods, which failures and statuses, exponential
backoff with full jitter, `Retry-After` up to `max-retry-after`, the last response returned); every
auth strategy; client credentials (form `POST` default, `BASIC`, skew `min(30s, expires_in/10)`, 5
minutes without `expires_in`, `token_type` Bearer, single flight, one repeat after a `401` that does
not count as a retry, `https` or loopback `http` token URL); the adapter's configuration keys, the
correlation-id propagation (and only that), the metrics; `User-Agent`; gzip and deflate, the size
limit on received bytes; `RESPONSE_TOO_LARGE` never retried.

### Changes

- Engine: **reqwest 0.12** (rustls, `default-features = false`, as the platform), hidden behind the
  framework's types exactly as Vert.x is hidden in Java. `redirect::Policy::none()`, per-attempt
  `timeout`, the response streamed and counted for the size limit.
- Retries are a plain loop in the client, not `tower::retry`: the contract's rules (idempotency,
  `Retry-After` handling, the 401 repeat outside the retry count) are easier to read and audit as one
  function than as a tower `Policy`, and reqwest is not a tower `Service`.
- Single flight for tokens: a `tokio::sync::Mutex<Option<CachedToken>>` held across the token
  request.
- The library has no `fw-core` dependency; the adapter (named instances, `Context` propagation,
  metrics, lifecycle) is the crate's `module` feature.
- `INTERRUPTED`: none (cancellation). `IllegalStateException` after close becomes `Kind::Closed`.

### Open rulings

- None new.

## 11. The FlowCatalyst module

Contract: [`flowcatalyst.md`](../contracts/flowcatalyst.md). Depends on decision 11 for the
client.

### Types

```rust
struct FlowCatalystModule;                 // declares flowcatalyst.* settings, migration set `flowcatalyst`, the `sync` command
struct PlatformClient { /* wraps flowcatalyst_sdk::Client; SDK types never in signatures */ }
impl PlatformClient {
    fn is_enabled(&self) -> bool;
    async fn access_token(&self) -> Result<Secret, PlatformError>;
    async fn complete_job_instance(&self, id: &InstanceId, c: Completion, result: Option<Value>) -> Result<(), PlatformError>;
    async fn log_job_instance(&self, id: &InstanceId, level: LogLevel, msg: &str) -> Result<(), PlatformError>;
}

struct Definitions { /* collected at declare: event types, permissions, roles, subscriptions, connections, pools, scheduled jobs, processes, docs */ }
impl Definitions { fn event_type<E: DomainEvent>(&mut self, name: &str, description: &str); /* code from E::TYPE */ }
enum SyncMode { Apply, DryRun }

struct Receivers { /* sealed at start */ }
impl Receivers {
    fn on_dispatch<T, H>(&mut self, h: H) where T: DomainEvent + DeserializeOwned, H: DispatchHandler<T>;     // pattern = T::TYPE
    fn on_dispatch_pattern<H: DispatchHandler<Value>>(&mut self, pattern: TypePattern, h: H);                // `prefix:*`, `*`
    fn on_scheduled_job<H: JobHandler>(&mut self, code: JobCode, opts: JobOptions, h: H);
}
trait DispatchHandler<T>: Send + Sync + 'static {
    fn handle(&self, ctx: Context, d: DispatchDelivery<T>) -> BoxFuture<'static, Result<DispatchResult, HandlerError>>;
}
enum DispatchResult { Ack, Defer(Duration), Reject(RejectReason) }
enum JobResult { Success(Option<Value>), Failure(String) }
enum OnceResult<T> { Ran(T), AlreadyDone }
impl<T> DispatchDelivery<T> {
    async fn once<R>(&self, db: &Db, handler: HandlerName, work: impl AsyncFnOnce(&mut Tx<'_>) -> Result<R, DbError>) -> Result<OnceResult<R>, DbError>;
}
```

### Verbatim

Every setting and default; the client as the application's service account; the `flowcatalyst`
and `outbox` health checks, informational by default, and the outbox query (status 0 or 9, the
application's clock, strictly older than `stale-after`); definitions, their validation (stricter
than the platform's, as built), the category order (roles, event types, connections, pools,
subscriptions, processes, scheduled jobs, docs), `DRY_RUN` making no requests, sync exit codes 0 / 1
/ 2, sync off on boot by default; **the receivers in full**: prefix, both routes, signature rules
and refusal codes and the 401 body, per-receiver secrets with their fallbacks, `require-signature`,
the bearer gate, dispatch headers and their patterns, envelope versus data-only, the context built
from the client header (`_platform` otherwise), matching order, every result and its status and
body, reasons cleaned and cut to 500 characters, 503 for an error, a timeout or no result, 422 for
no handler, `once` with `fw_dispatch_once` and its retention purge (batches, lock, application
clock); scheduled jobs: validation, 202 at once, 404 for an unknown code, the overlap lock
`scheduled-job:{code}` with its lease and refresh, every outcome and its completion body, the 10 000
character cut, completion retried 4 calls in all with doubling delay, the SDK transport retry
disabled for those calls, the log hook; receivers with no handler answer 404 before any signature
work; `flowcatalyst.receivers.enabled: false` mounts nothing; the start-up checks.

### Changes

- **Typed dispatch handlers.** `on_dispatch::<OrderPlaced, _>(handler)` takes its pattern from
  `OrderPlaced::TYPE` and decodes `data` into the event type before the handler runs. A payload that
  does not decode answers `Reject` (it will never decode on a retry), logged at WARN with the job id;
  this is new and needs a contract line (decision 14). Untyped handlers for `prefix:*` and `*`
  receive `serde_json::Value`, as Java's `JsonNode`.
- A handler's `Err` is Java's throw (503). A panic is caught at the task boundary (503). Timeout:
  `tokio::time::timeout` around the handler's task; on expiry the response is 503 at once and the
  task is cancelled.
- **Scheduled jobs**: the firing runs in a tracked task. On timeout the handler is cancelled and
  `FAILURE {error:"timed out after …"}` is reported at once, but the overlap lock is released only
  when the task has really ended (a handler that never awaits keeps it), which is Java's rule for a
  handler that ignores its interrupt (decision 14).
- Registration happens during build; the router is built after, so the "routes registered in boot
  answer 404 themselves" workaround of follow-up 5 is unnecessary: a receiver with no handlers is
  simply not routed, and a `GET` to it is a 404, not a 405. A small, observable difference from Java
  (Java's `GET` is a 405); the conformance vectors record it as an allowed difference unless the
  owner prefers identical behaviour.
- The `sync` command is a `Command`; `my-app sync --dry-run` runs it after build.

### Ruling for R4: the receiver's principal (owner, 2026-10-12)

A use case run by a dispatch receiver **always has a principal**: the application's service
principal (kind Service; the principal types arrive with M4c, so there is no Rust type for the kind yet), the one the application's own credentials identify. The
dispatch job's id is recorded as the **cause** (`Context::with_causation`, so it reaches
the events' `causationId` and the audit row's causation) in the audit and outbox rows the use case
writes. There is no principal-less execution path: the receiver builds the `Context` with that
principal before running the handler, and `authorize` never sees an absent principal from a
receiver. The Java receiver's behaviour for this is to be checked when R4 is built; if it differs,
the difference is reported, not copied.

### Open rulings

- Decisions 11 and 14; the 404-versus-405 difference above.

## 12. Sessions and login (M4c)

No contract yet: the Java M4c contract comes first, and the Rust design follows it. What can be
said now:

- `fw-session`: a tower layer and a `Session` extractor, with `cookie` (AES-256-GCM, `aes-gcm`),
  `database` and `redis` stores, the drivers ruled for Java. fc-sdk's axum session store
  (`auth/axum/session.rs`, `pg_session_store.rs`, `redis_session_store.rs`) is prior art to read,
  not to depend on.
- `fw-oidc`: the relying-party flow (PKCE, discovery, JWKS, id_token validation, refresh, logout).
  Candidates: the `openidconnect` crate (mature; used by the platform) with the framework's HTTP
  client plugged in as its transport, or a port of fc-sdk's `auth/axum/oidc.rs`, which already works
  against the platform and knows the platform refuses a secret from a public client (flowcatalyst
  contract section 5, item 3). Decide with the contract.
- The real `ContextResolver` builds `Context` from the session; the principal's permissions arrive
  here (orders showcase, section 2).

## 13. UI: templates, fixtures, Datastar

Inputs: [`../../ui-kit/README.md`](../../ui-kit/README.md),
[`../../ui-kit/fixtures/README.md`](../../ui-kit/fixtures/README.md),
[`../ui-dogfood-plan.md`](../ui-dogfood-plan.md).

### Template engine: askama (decision 12)

| | askama | maud | minijinja | rinja |
|---|---|---|---|---|
| checked at compile time | yes: templates compiled into Rust, fields type-checked | yes | no: a missing field is a render-time error | merged back into askama in 2025; not a separate choice |
| markup looks like the specs and goldens | yes: HTML files | no: a Rust macro DSL; every golden is translated by hand | yes | |
| props are typed | yes: one struct per component | yes | no (a value map) | |
| closest to | JTE (precompiled, typed) and templ | | Jinja at run time | |
| escaping | automatic for HTML | automatic | configurable | |

Components are structs with typed props, so an invalid variant does not compile:

```rust
#[derive(Template)]
#[template(path = "kit/button.html")]
struct Button<'a> {
    variant: ButtonVariant,          // enum: Default, Outline, Ghost, Destructive, ...
    size: ButtonSize,
    disabled: bool,
    icon: Option<Icon>,
    attrs: &'a Attrs,                // ordered attribute map; `true` renders a bare attribute
    slot: Markup,                    // caller HTML, inserted verbatim
}
```

Templates live under `fw-ui/templates/kit/`, one per spec, written from the spec's Structure section
and its golden. (Their syntax is not shown here: the documentation site would treat it as Liquid.)

### Conformance against the same fixtures

A test in `fw-ui` walks `../../ui-kit/fixtures/<component>/<case>.json`, decodes `props` **strictly**
into the component's props struct (`deny_unknown_fields`, as the fixtures README requires) and
`slots` into `Markup`, renders, and compares with `<case>.html` after the README's normalisation:
HTML5 fragment parsing **in the right context element** (`html5ever::parse_fragment` with `tbody`,
`tr`, `table`, `select` or `body`), comments dropped, attributes sorted with class token order kept,
whitespace collapsed, one node per line, a line diff on failure. The normaliser is a port of the Go
platform's `internal/ui/htmlnorm` and gets its own vectors in `conformance/htmlnorm/` so the three
normalisers (Go, Java, Rust) are tested against one another. A component is done when every fixture
in its Props table passes, the rule the README states for every implementation.

### Datastar (decision 13)

`fw_http::datastar`: `signals::<T>(&Parts, body)` (query parameter `datastar` for GET and HEAD,
otherwise the body); `DatastarStream`, a streaming body (`text/event-stream`, `Cache-Control:
no-cache`, `Connection: keep-alive` only on HTTP/1.1) with `patch_elements(html, opts)`,
`patch_signals(json, opts)`, `remove(selector)`, written exactly as http section 10 (only non-default
options emitted, a `data:` line per line of HTML, `id:` and `retry:` rules, flushed per event, a
disconnect reported as `Err(Disconnected)` and logged at DEBUG). axum's own `Sse` type is not used
because it formats events itself; the bytes are the contract. The action error contract of the kit
(handled failures answer 200 with patches; mutations send `retryMaxCount: 0`) is followed by the
view helper.

### The view helper

`View::respond(&parts, template)` renders the full page for an ordinary request and an element patch
for a Datastar request (the `datastar-request` header), from one template, as planned for JTE
(`Response.view`).
