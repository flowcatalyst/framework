# TypeScript framework: capability by capability (draft 1, 2026-10-13)

Companion to [`design.md`](design.md). For each contract: the key TypeScript types (sketches, not
final signatures), what is taken from the contract verbatim, what changes and why, and the open
rulings. "Verbatim" means the rule and its observable behaviour; the conformance vectors
([`milestones.md`](milestones.md) section 3) check it against the Java output, as they do for Rust.
Where the Rust build (R0 to R2) already found a better shape than its design, the TypeScript design
starts from what was built.

Sketches omit `export`, docs and `readonly` where they add nothing. `Result<T, E>` is the
framework's union (design section 1.4). `NoThrow` and the other contracts are type-only imports from
`@scriptc/types`.

## 1. Core: Result, lifecycle, instances, Context, config, health, commands

Contracts: [`core.md`](../contracts/core.md), [`background-and-metrics.md`](../contracts/background-and-metrics.md).

### Types

```ts
// --- Result ------------------------------------------------------------------------
type Ok<T> = { readonly ok: true; readonly value: T };
type Err<E> = { readonly ok: false; readonly error: E };
type Result<T, E> = Ok<T> | Err<E>;
function ok<T>(value: T): NoThrow<Ok<T>>;
function err<E>(error: E): NoThrow<Err<E>>;
// map, mapErr, andThen are plain functions; `all` collects every error of a record of results:
function all<R extends Record<string, Result<unknown, FieldError>>>(r: R): NoThrow<Result<Values<R>, Violations>>;

// --- lifecycle ---------------------------------------------------------------------
interface Module<Handles> { readonly id: ModuleId; declare(d: Declarations): Handles }
const App: { declare(opts: { name: string; environment?: Environment; clock?: Clock; configDir?: string }): Declaring };
interface Declaring { module<H>(m: Module<H>): H; configure(): Promise<Result<Configured, StartupError>> }

interface Boot {
  read<T>(h: SettingHandle<T, "present">): T;                  // cannot fail: declared and validated
  read<T>(h: SettingHandle<T, "optional">): T | undefined;
  readonly health: Health;
  readonly background: Background;
  readonly metrics: Metrics;
  readonly ids: Ids;
  readonly clock: Clock;
  onStarted(name: string, f: () => Promise<Result<void, StartupError>>): void;
  onShutdown(name: string, f: () => Promise<void>): void;
  command(c: Command): void;
}
function launch<S>(app: Declaring, args: readonly string[],
                   build: (boot: Boot) => Promise<Result<Serve<S>, StartupError>>): Promise<number>;  // exit code

// --- context -----------------------------------------------------------------------
type Context = {
  readonly client: ClientId;                 // always
  readonly principal?: Principal;            // id, kind (user | service), permissions (M4c)
  readonly application?: ApplicationCode;
  readonly correlation: CorrelationId;       // always; generated when absent
  readonly causation?: CausationId;
  readonly execution?: ExecutionId;
};

// --- config ------------------------------------------------------------------------
const HTTP_PORT = Setting.integer("http.port", "port to listen on").default(8080);     // SettingHandle<number, "present">
const FC_SECRET = Setting.secret("flowcatalyst.client-secret", "...").required();       // SettingHandle<Secret, "present">

// --- health ------------------------------------------------------------------------
type Criticality = "critical" | "informational";
type CheckResult = { status: "up" } | { status: "down"; reason: string } | { status: "timed-out" };
interface Health { add(name: string, c: Criticality, check: (signal: AbortSignal) => Promise<CheckResult>): void }
```

### Verbatim

The phases and their order (declare, configure, build, start; started actions after every part is
built; loops after every started action); shutdown order and `app.shutdown.drain`; health with
`app.health.timeout`, criticality and the `app.health.critical` / `app.health.informational`
overrides; the instance rules (unknown driver lists the registered names, unread keys fail start-up,
`default` rules, single-driver defaults); every `Context` field and the `ClientId` and
`ApplicationCode` patterns; every config rule (key grammar, types and kinds, sources and their
order, `APP_ENV`, `config/application.yaml` and `application-{env}.yaml`, env names and the collision
check, durations and booleans, errors naming key and source and never a secret's text,
`secrets.local.yaml` only for `local` and `test`, platform addressing and reserved components);
commands (a claimed first argument runs after build without started actions or loops). The config
and TSID vectors are run as they are for Rust.

### Changes

| Java | TypeScript | Why |
|---|---|---|
| `register`/`boot` hooks, `app.get(Class)` | `Module.declare` returns handles; services are built in the application's `build` | no container (design section 1.3) |
| `AppBuilder.override` in test mode | tests pass fakes to their own `build` | no runtime type map |
| `StartupException` thrown | `Result<_, StartupError>` with `problems: Problem[]`; `launch` prints one per line and returns exit code 1 | errors as values |
| driver factories registered by name | each capability declares its drivers' key schemas, so `configure` checks every instance section; its own `build` constructs the driver named (as built in Rust R1) | the capability knows each driver's dependencies |
| `InstantSource`, `MonotonicClock` | `Clock { now(): Instant; monotonicMs(): number }`; `Instant` is a branded epoch-millisecond number with the framework's own RFC 3339 format and parse | scriptc's `Date` limits |
| YAML reader keeps a duplicate key's last value | duplicate keys refused, as Rust does | Rust R1 finding; one documented difference with Java |
| health checks are `Callable`s with a timeout | `(signal) => Promise<CheckResult>` raced against `app.health.timeout`; the signal aborts on timeout | no preemption (design doubt 8) |
| `Background` loops on virtual threads | `setTimeout` chains, one in flight per loop, fixed delay without overlap; a tick gets a `LoopTick` with an `AbortSignal` | single-threaded event loop |
| a loop's `Error` (not `Exception`) stops it | a tick that returns `Err` is logged and the loop continues; a tick that **throws** is treated as Java's `Error`: logged, the loop stops, its health goes down | the closest equivalent of a programming error in a language where everything throws |
| SLF4J | the framework's structured JSON logger to stdout (`Logger` port, a no-op and a memory driver for tests), context fields on every line from the caller's `Context`, never from ambient state | no global logger; no pino (JavaScript, worker threads) |

### Metrics

The framework's own registry and exposition writer, exact contract bytes (names, labels, buckets,
truncation, the series bound, sorting, escaping, number formatting). Numbers: the writer formats
explicitly to the Java output, as Rust's does, because `String(1e21)` and Jackson differ for very
large and very small values; the vectors decide.

### Open rulings

- None new. (Rust's `async configure` is the TypeScript default.)

## 2. Database, migrations, Redis

Contract: [`database-and-redis.md`](../contracts/database-and-redis.md).

### Types

```ts
interface SqlClient {                                // the port; adapters: pg now, a wire driver later
  query(text: string, values: readonly SqlValue[]): Promise<Result<SqlRows, DbError>>;
  begin(isolation: "read committed"): Promise<Result<SqlTx, DbError>>;
  close(): Promise<void>;
}
type SqlValue = string | number | bigint | boolean | null | Uint8Array | readonly SqlValue[];
type SqlRows = { rows: readonly Readonly<Record<string, unknown>>[]; rowCount: number };   // values are unknown until parsed
type DbError = { kind: "unique-violation"; constraint: string } | { kind: "serialization" } | { kind: "deadlock" }
             | { kind: "connection"; message: string } | { kind: "other"; sqlState?: string; message: string };

class Databases { static connect(boot: Boot, h: DatabaseHandles, adapter: SqlAdapter): Promise<Result<Databases, StartupError>>;
                  get(name: InstanceName): Db | undefined; defaultDb(): Db; migrate(boot: Boot): Promise<Result<void, StartupError>> }
type Db = { readonly name: InstanceName; query: SqlClient["query"] };   // no begin: only the runner and `once` open a Tx
type Tx = { readonly query: SqlClient["query"] };                       // created and finished by the framework only
```

### Verbatim

Named instances with `password` as a secret and a start-up ping; migration file naming, one set per
module in declaration order, the default instance only, **`fw_schema_history` with SHA-256 of the
file bytes**, a transaction per file with its history row, the advisory lock, every start-up failure
(changed checksum, missing file, out-of-order unapplied file, duplicate version), the non-default
instance table check. The vectors `conformance/migrations/*.json` (the Java runner's history rows)
must be accepted and reproduced. Redis: standalone, a ping, health `redis:<name>`.

### Changes

- Adapters: `Bun.sql` and `pg`, compared by the spike (design decision 10). `pg` is needed on Node;
  `Bun.sql` is used on Bun only if it passes the `SqlClient` contract suite (transactions on one
  reserved connection, SQLSTATE codes, multi-statement files, type conversion; design section 1.1a). Each worker thread gets its own pools, sized from the per-process settings
  (section 14).
- `url` accepts `postgres://`, `postgresql://` and `jdbc:postgresql://` (the prefix dropped), as the
  Rust ruling of 2026-10-12; separate `username`/`password` keys win.
- Values come back as `unknown` and are parsed into domain types; the adapter converts PostgreSQL
  types to `SqlValue`s with fixed rules (`int8` and `numeric` as strings unless a parser says
  otherwise, `timestamptz` as an `Instant` with microseconds kept as text when needed, `jsonb` as
  text), never with the `pg` default of `Date` objects and floats.
- Migration texts are generated modules (design section 2.5); a file with several statements is
  sent with the simple-query protocol, as Rust does.
- The Redis client is the framework's own (design decision 11).

### Open rulings

- Decision 10 (the driver), decision 13 (migration tooling).

## 3. The write path (unit of work, outbox)

Contract: [`write-path.md`](../contracts/write-path.md). The full example is
[`orders-showcase.md`](orders-showcase.md).

### Types

```ts
// Persistence state is the framework's: aggregates carry no client and no version (the Rust model).
type Fresh<A> = { readonly [FRESH]: true; readonly value: A; readonly client: ClientId };            // freshFor(ctx, a)
type Loaded<A> = { readonly [LOADED]: true; readonly value: A; readonly client: ClientId; readonly version: Version };
function loaded<A>(client: ClientId, version: Version, value: A): Loaded<A>;   // for repositories
function mapLoaded<A>(l: Loaded<A>, f: (a: A) => A): NoThrow<Loaded<A>>;       // keeps client and version

interface Repository<A, I> {
  find(db: Db, client: ClientId, id: I): Promise<Result<Loaded<A> | undefined, DbError>>;
  insert(tx: Tx, client: ClientId, a: A): Promise<Result<void, DbError>>;                         // version 1
  update(tx: Tx, client: ClientId, a: A, v: VersionCheck): Promise<Result<number, DbError>>;     // row count
  delete(tx: Tx, client: ClientId, a: A, v: VersionCheck): Promise<Result<number, DbError>>;
}
type VersionCheck = { expected: Version; next: Version };     // computed by the runner

// A plan: steps in order; built with these functions only (the PLAN key is module-private).
function save<A, E extends DomainEvent>(w: Fresh<A> | Loaded<A>, repo: Repository<A, unknown>, event: E): Plan<E>;
function remove<A, E extends DomainEvent>(l: Loaded<A>, repo: Repository<A, unknown>, event: E): Plan<E>;
function emit<E extends DomainEvent>(event: E): Plan<E>;
function andSave<E>(p: Plan<E>, w: Fresh<unknown> | Loaded<unknown>, repo: Repository<unknown, unknown>, event: DomainEvent): Plan<E>;
function andDispatch<E>(p: Plan<E>, job: DispatchJob): Plan<E>;

interface DomainEvent<T extends EventType = EventType> {
  readonly type: T;                   // `${app}:${domain}:${aggregate}:${action}`, checked at module load
  readonly meta: EventMetadata;       // id, source, subject, time, correlation, causation, principal, execution, group
  readonly data: JsonObject;          // what is written as the payload's data, in key order
}
```

### Verbatim

Section 2 (`WHERE id AND client_id AND version`, row counts 0 and above 1, `23505` →
`CONFLICT/UNIQUE_VIOLATION` not retried); section 4 (one attempt: validate, authorize, execute, the
client check, `READ COMMITTED` on the default instance, writes in plan order, then events, then
dispatch jobs, then one audit row, commit; the failure table with the step codes `VALIDATE` …
`COMMIT`; retry with jitter, 2 to 10 attempts); section 5; section 6 (metadata); **sections 7 and 7a
in full** (the SDK table byte for byte plus `V0002`; every column and payload field of event, audit
and dispatch-job rows; `contextData`; `deduplicationId`; dispatch-job validation, defaults, `kind`
default `TASK`, the fields never written, `metadata` as an array); section 8 (TSIDs). The outbox
vector compares **payload text byte for byte**, as Rust's does.

### Changes

| Java | TypeScript | Why |
|---|---|---|
| `client()`/`version()` on the aggregate; insert when `version() == 0` | `Fresh`/`Loaded` wrappers; the runner inserts a `Fresh` and updates a `Loaded` | the aggregate stays ignorant of storage (as Rust built it) |
| event data = the record's fields via Jackson | `data` is a plain object the use case builds in declared key order; the framework's JSON writer serialises it (decision 25 for nulls) | exact bytes; no reflection |
| `INTERRUPTED` | none; there is no interruption between attempts in JavaScript | no preemption |
| `Sink` with a default that throws | `Sink` interface; a sink without dispatch-job support returns an `OUTBOX` failure | errors as values |
| a phase that throws → `INTERNAL` with the step code | the same: the runner calls each phase and each repository method inside one `try` per step, because application shells and adapters may throw | the one place throwing is caught |

The **unit of work** is the runner's: it begins the transaction through the `SqlClient` port, runs
the plan's writes, calls the `OutboxWriter` (the shared `INSERT`, placeholders numbered), writes the
audit row, commits, and maps `DbError` kinds to conflicts. Nothing else can begin a transaction:
`Db` has no `begin`.

### Open rulings

- Decision 25 (nulls).

## 4. Use cases and authorisation

Contract: [`write-path.md`](../contracts/write-path.md) sections 3 to 5, the receiver principal
ruling (Rust capabilities section 11).

### Types

```ts
interface UseCase<C, E extends DomainEvent> {
  readonly name: UseCaseName;                                       // `[a-z][a-z0-9-]*`
  readonly conflict?: ConflictPolicy;                               // default fail
  validate?(ctx: Context, cmd: C): Result<void, UseCaseError>;      // usually absent: commands are parsed at the edge
  authorize(ctx: Context, cmd: Valid<C>): Promise<Result<Grant, UseCaseError>>;   // required: an object without it does not type-check
  execute(ctx: Context, cmd: Authorized<C>, db: Db): Promise<Result<Plan<E>, UseCaseError>>;
  auditData?(cmd: C): Readonly<Record<string, string>> | undefined;
}
type Valid<C> = { readonly [VALID]: C };            // VALID, AUTHORIZED, COMMITTED: module-private unique symbols
type Authorized<C> = { readonly [AUTHORIZED]: C };  // only the runner builds them; `command(a)` reads the value
type Committed<E> = { readonly [COMMITTED]: E };

type Grant = { readonly [GRANT]: "public" | "permission" | "service" };
const Grant: { public(): Grant };                                      // deliberate and greppable
function requirePermission(ctx: Context, p: Permission): Result<Grant, UseCaseError>;   // AUTHORIZATION when missing

class UseCases { static build(boot: Boot, dbs: Databases, sink?: Sink): UseCases;
                 run<C, E extends DomainEvent>(uc: UseCase<C, E>, ctx: Context, cmd: C): Promise<Result<Committed<E>, UseCaseError>> }
type ConflictPolicy = { kind: "fail" } | { kind: "retry"; attempts: Int32; backoffMs: number };   // attempts 2..10, checked at definition
type UseCaseError = { kind: ErrorKind; code: ErrorCode; message: string; details: Readonly<Record<string, string>> };
```

### Verbatim

The envelope (validate, authorize required, execute returns a plan or an error, load once, one
runner); `Grant.public()` as the contract's `Authorize.PUBLIC`; kinds and statuses; metrics
`fw_usecase_runs_total` and `fw_usecase_duration_seconds`; the receiver principal ruling: a use case
run by a dispatch receiver has the application's **service principal** and the dispatch job id as
its **cause**, and no use case runs without a principal from a receiver.

### Changes

- `authorize` is a required member, so a use-case object without it is a type error (Java: an
  exception when defined; Rust: a compile error).
- `execute` takes `Authorized<C>`, which only the runner can build. The guarantee is type-level: a
  cast defeats it, and the lint rule against `as` is what keeps it (design doubt 4).
- `Context.principal` carries permissions from M4c on; until then `requirePermission` checks a test
  resolver's principal, as Java's demo does.

### Open rulings

- None new.

## 5. Checked SQL

Contract: [`checked-sql.md`](../contracts/checked-sql.md).

Until T7, repositories write SQL by hand and parse each row with a function that returns `Result`
(a row is `Readonly<Record<string, unknown>>`). This is the contract's section 5 territory ("dynamic
queries stay hand-written") applied to everything for a while, and costs a mapping function per
query. T7 brings `tools/fw-sql`, a port of the Java generator's contract (decision 14): the type
mapping of section 2 with TypeScript types (`int4` → `Int32`-annotated `number`, `int8` and
`numeric` → `string` or a decimal value object, `timestamptz` → `Instant`, `jsonb` → `unknown`
parsed by the caller), nullability from the catalog with every outer-join column optional, typed
overrides (`AS "id: OrderId"` mapped through the type's `parse`), generated modules committed and
checked for staleness in CI.

## 6. HTTP

Contract: [`http.md`](../contracts/http.md).

### Types

```ts
// A route is plain data (owner ruling 2026-10-13). No registry, no mutation.
type Route<P extends string = string> = {
  readonly method: Method;
  readonly path: P;                               // "/orders/:id/lines"; ":name" segments, optional trailing "*"
  readonly params?: TObject;                      // keys must equal PathParams<P> (checked by the type of `route`)
  readonly query?: TObject;
  readonly body?: TSchema;
  readonly response?: Readonly<Record<number, TSchema>>;   // checked in tests and used for OpenAPI; not on every response in production
  readonly auth?: AuthRequirement;               // "public" | { context: true; permission?: Permission }
  readonly middleware?: readonly Middleware<Request, Response>[];
  readonly module: ModuleId;                     // for duplicate reports and the start-up route log
  readonly handler: Handler;
};
function route<const P extends string, Q, B>(r: RouteSpec<P, Q, B>): Route<P>;   // infers params, query, body types for the handler

type HandlerInput<P, Q, B> = {
  readonly params: Static<P>;                     // checked against the params schema before the handler runs
  readonly query: Static<Q>;
  readonly body: Static<B>;                        // 415 / INVALID_JSON / VALIDATION before the handler runs
  readonly correlation: CorrelationId;
  readonly remote: string | undefined;            // the peer's IP literal, never a header
  readonly ctx: Context;                           // present when auth requires a context (typed by AuthRequirement)
};
type Handler = (req: Request, c: HandlerInput<unknown, unknown, unknown>) => Promise<Response | Result<Response, ServiceError>>;

// Composition: returns a new array; prefix prepended, middleware wrapped outside the routes' own, authz attached.
function scope(opts: { prefix?: string; middleware?: readonly Middleware<Request, Response>[]; auth?: AuthRequirement },
               routes: readonly Route[]): readonly Route[];

// Compiled once at start-up into the segment trie: static > :param > trailing wildcard.
function compile(routes: readonly Route[], opts: { resolver?: ContextResolver; fixed: FixedMiddleware }):
  Result<CompiledRoutes, StartupError>;          // every duplicate method+path, every context route without a resolver

type Middleware<I, O> = (input: I, next: (i: I) => Promise<O>) => Promise<O>;
interface ContextResolver { resolve(req: Request, correlation: CorrelationId): Promise<Result<Context, ServiceError>> }
function json(status: number, body: JsonValue): Response;
function problem(e: ServiceError): Response;       // the envelope
interface ServerAdapter { listen(h: AppHandler, opts: ListenOptions): Promise<Result<Listening, StartupError>> }   // bun, node
```

A module exports `orderRoutes(deps): readonly Route[]`; the application writes
`compile([...healthRoutes(boot), ...scope({ prefix: "/api", auth: { context: true } }, orderRoutes(deps))], ...)`.
A context route without a resolver is a start-up problem (TypeScript cannot see across the array
concatenation; Java also checks this at start-up).

### Verbatim

Section 2's routing rules in full except the pattern syntax (decision 27): precedence, trailing
slash distinct, no redirects, per-segment decoding, duplicates fail start-up naming both, 404, 405
with `Allow`, `OPTIONS` 204, `HEAD` from `GET`, routes logged; the fixed outer middleware order (correlation id, error mapping,
access log), then application middleware, then context resolution on routes that need it; section 5
(envelope, every status and code, use-case mapping, 500 never carries the cause); section 6
(correlation-id rules, replaced not rejected, echoed on every response, forced into the resolved
context); section 7's outcomes (415, `INVALID_JSON` with the path and without the value, unknown
properties refused, every error collected, first error per field, `"<code>: <message>"`); section 8
(settings, health endpoints and JSON, body limit 413 with the connection closed, graceful shutdown
and the drain 503, no `Server` header or stack traces).

### Changes

| Java | TypeScript | Why |
|---|---|---|
| own `Request`/`Response` types | WHATWG `Request`/`Response` (design section 1.7; fallback to framework interfaces of the same shape if scriptc lacks them) | the owner's ruling; Hono-like |
| `routes.get(...)` on a provided `Routes` service, registered in `boot` | route values in arrays returned by module functions, composed with `scope`, compiled once | owner ruling: no registry, no mutation |
| `{name}` segments, no wildcards | `:name` segments and one trailing `*` (decision 27) | owner ruling; wire rules unchanged |
| `request.pathInt(name)` and friends | `params`, `query`, `body` checked against the route's TypeBox schemas before the handler; parsing into domain types is the application's `parse` | the schema split |
| `requiresContext` checked at start-up | `auth: { context: true }` on the route or its scope; checked when the array is compiled | start-up, as Java |
| `Validator.of(..).field(..)` | TypeBox schema for shape, then the explicit parse into a command (design section 1.6), every error collected into `Violations` | the schema split |
| handler throws `HttpException` | handler returns a `Response` or `Result<Response, ServiceError>`; a throw is caught by error mapping and becomes 500 | errors as values |
| Jetty | `ServerAdapter`: `Bun.serve` and `node:http` | port, both runtimes |
| no CORS | an opt-in CORS middleware with an explicit allow-list (decision 27) | owner ruling |
| static files out of scope | the SPA served from a directory with history fallback (decision 26) | the UI ruling |
| Datastar (section 10) | not implemented (decision 24) | the UI ruling |
| `TestClient` over the pipeline | `TestClient` calling the compiled handler with `Request` objects, no socket; a few real-socket tests for the adapter | same |

The body limit is enforced by the adapter while reading (and by `Content-Length` first): over the
limit, 413 and the connection closed, before a handler sees a byte. `http.request-timeout` maps to
the Node server's `headersTimeout` and `requestTimeout`, and to `Bun.serve`'s `idleTimeout` plus a framework total-request timer on Bun.

The framework-written middleware (security headers, CSRF, CORS) is listed in design section 1.7a and
belongs to this package; the auth pieces are section 12.

### Open rulings

- Decisions 24, 26 and 27.

## 7. Locks

Contract: [`locks.md`](../contracts/locks.md). Taken almost entirely verbatim.

### Types

```ts
type Acquire = { kind: "acquired"; lease: Lease } | { kind: "busy" };
type Refresh = { kind: "refreshed"; lease: Lease } | { kind: "lost" };
type Release = { kind: "released" } | { kind: "not-held" };
type WithLock<T> = { kind: "completed"; value: T } | { kind: "not-acquired" } | { kind: "lost"; value?: T };

interface LockStore {                     // the driver contract (stored key, owner token, fencing token: the Java LockDriver level, as Rust built it)
  tryAcquire(key: string, owner: string, leaseMs: number): Promise<Result<Acquire, LockStoreError>>;
  refresh(lease: Lease, leaseMs: number): Promise<Result<Refresh, LockStoreError>>;
  release(lease: Lease): Promise<Result<Release, LockStoreError>>;
}
class Lock {
  acquire(key: LockKey, lease: LeaseLength, maxWait: MaxWait, signal?: AbortSignal): Promise<Result<Acquire, LockStoreError>>;
  withLock<T>(key: LockKey, lease: LeaseLength, maxWait: MaxWait,
              action: (lease: Lease, lost: AbortSignal) => Promise<T>): Promise<Result<WithLock<T>, LockStoreError>>;
}
```

### Verbatim

Sections 1 to 5: key scopes and stored forms, the name pattern, the driver guarantees, the local
deadline taken before the request, backoff 10 ms doubling to 250 ms with jitter, refresh every
`lease / 3`, **the `fw_lock` table and sequence and the three statements** (`conformance/sql/locks/`),
**the Redis key format, `<owner>:<fence>`, the counter key and the three scripts**
(`conformance/lua/locks/`) with `EVALSHA` and `NOSCRIPT` reload, the memory driver's scope, and the
section 6 test cases as a suite every driver runs.

### Changes

- On loss, `withLock` aborts the action's `lost` signal and returns `lost` with the value if the
  action settles within one more refresh interval, without it otherwise; the action's promise keeps
  running (no cancellation). Java interrupts, Rust drops the future; the observable result is the
  same unless the action ignores the signal, which is Java's "ignores its interrupt" case.
- The contract suite is a function `lockSuite(harness)` that registers one `node:test` case per
  contract case, with a harness supplying the store, a way to let time pass (mock timers for memory,
  a real wait for the others) and a hook that expires a key's lease.

### Open rulings

- None new.

## 8. Rate limiting

Contract: [`rate-limiting.md`](../contracts/rate-limiting.md). All of it verbatim: GCRA and its
arithmetic, policy ranges, `cost`, the memory driver's bound, purge and overflow bucket, the Redis
key format, server clock in microseconds and **the Lua script** (shared with Java and Rust replicas),
the 429 envelope and `Retry-After`, the `RateLimit-*` headers, `ALLOW`/`DENY`, the shared `-` bucket,
hashed long keys and header values, IPv6 /64, the `X-Forwarded-For` rule, global versus route
position, the metrics.

Changes: the middleware is a `Middleware<Request, Response>`; GCRA is a `NoThrow` pure function over
numbers; microsecond arithmetic uses `number` (exact below 2^53 µs, about 285 years) with the Redis
values parsed as integers; IP literals are parsed by the framework's own parser (no name
resolution, no `node:net` dependency in the pure code).

## 9. Per-client secrets

Contract: [`secrets.md`](../contracts/secrets.md). Verbatim: the threat model and every mitigation,
the reference grammar, the client always from the caller, the `env` driver's prefix rules, reserved
prefixes and injective encoding, the `file` driver's directory, client pattern, real-path resolution
and the last-two-elements check, `max-bytes`, strict UTF-8, one trailing newline removed, empty is
not found, world-readable warned once; the cache (TTL, LRU bound, never caches not-found); `platform`
fails start-up; no value in any message or log.

```ts
type SecretResult = { kind: "found"; secret: Secret } | { kind: "not-found" } | { kind: "refused"; reason: string };
interface SecretStore { find(client: ClientId, name: SecretName): Promise<Result<SecretResult, SecretStoreError>>; evict(...): void; evictAll(): void }
class SecretResolver { resolve(client: ClientId, ref: SecretRef): Promise<Result<SecretResult, SecretStoreError>>;
                       require(client: ClientId, ref: SecretRef): Promise<Result<Secret, SecretError>> }
```

Changes: errors are results; strict UTF-8 through `TextDecoder` with `fatal: true`; `Secret` cannot
be wiped (JavaScript strings are immutable), the same out-of-scope line as Java.

## 10. HTTP client

Contract: [`http-client.md`](../contracts/http-client.md). All behaviour verbatim: base-URL and path
rules, placeholder encoding, header injection refused, `send` never fails for a status, failure kinds
and messages without headers, bodies, tokens or query strings, no redirects, retries (methods,
failures, statuses, full-jitter backoff, `Retry-After` up to the maximum, the last response
returned), every auth strategy, client credentials (form `POST` default, `BASIC`, skew, single
flight, one repeat after a 401 outside the retry count, `https` or loopback `http` token URL), the
adapter's keys, correlation-id propagation only, the metrics, `User-Agent`, gzip and deflate, the
size limit on received bytes.

Changes: the transport is `HttpTransport` over `fetch` with `redirect: "manual"`, an `AbortSignal`
per attempt for the timeout, and the response body read as a stream and counted; single flight for
tokens is one shared promise; the library has no `@fw/core` dependency and the adapter is the
`@fw/httpclient/module` subpath.

## 11. The FlowCatalyst module

Contract: [`flowcatalyst.md`](../contracts/flowcatalyst.md). Depends on decision 15 for the client.

### Types

```ts
interface Receivers {
  onDispatch<S extends TSchema>(type: EventTypeCode, data: S, h: DispatchHandler<Static<S>>): void;   // typed: data checked against the schema
  onDispatchPattern(pattern: TypePattern, h: DispatchHandler<unknown>): void;                         // `prefix:*`, `*`
  onScheduledJob(code: JobCode, opts: JobOptions, h: JobHandler): void;
}
type DispatchHandler<T> = (ctx: Context, d: DispatchDelivery<T>, signal: AbortSignal) => Promise<DispatchResult>;
type DispatchResult = { kind: "ack" } | { kind: "defer"; delayMs: number } | { kind: "reject"; reason: string };
type JobResult = { kind: "success"; result?: JsonValue } | { kind: "failure"; message: string };
type OnceResult<T> = { kind: "ran"; value: T } | { kind: "already-done" };
// d.once(db, handlerName, (tx) => Promise<Result<T, DbError>>): Promise<Result<OnceResult<T>, DbError>>
```

### Verbatim

Every setting and default; the client as the application's service account; the `flowcatalyst` and
`outbox` health checks, informational by default, and the outbox query; definitions, their
validation, the category order, `DRY_RUN` making no requests, sync exit codes; **the receivers in
full** (prefix, both routes, signature rules and refusal codes and the 401 body, per-receiver
secrets with their fallbacks, `require-signature`, the bearer gate, dispatch headers and patterns,
envelope versus data-only, the context, matching order, every result with its status and body,
reasons cleaned and cut, 503 for an error or a timeout, 422 for no handler, `once` with
`fw_dispatch_once` and its purge; scheduled jobs: validation, 202 at once, 404 for an unknown code,
the overlap lock with lease and refresh, every outcome and completion body, the 10 000-character cut,
completion retried 4 calls in all, the log hook); 404 for a receiver without handlers;
`flowcatalyst.receivers.enabled: false` mounts nothing; the start-up checks.

### Changes

- **The context of a dispatch delivery** follows the receiver principal ruling: principal = the
  application's service principal, causation = the dispatch job id, execution = the job id, client
  from `X-FlowCatalyst-Client` (or `_platform`). Java's current receiver builds the context without a
  principal; R4 was to check it. Reported, not copied.
- **Typed dispatch handlers** take a TypeBox schema for `data`; a payload that fails it answers
  `Reject` (it will never pass on a retry), logged at WARN with the job id, the Rust decision 14 rule.
- **Timeouts** (decision 16): 503 at once and the handler's signal aborted; for scheduled jobs
  `FAILURE {"error":"timed out after …"}` at once and the lock held until the handler settles.
- A receiver without handlers is simply not routed (registration happens during `build`, before the
  routes are compiled), so a `GET` to it is 404, not Java's 405. Recorded as an allowed difference,
  as for Rust.
- Signature checking uses the `Crypto` port's HMAC-SHA256 and constant-time comparison; the shared
  signature vectors apply.

### Open rulings

- Decisions 15 and 16.

## 12. Auth: tokens, login, sessions (M4c; milestone T4a)

No contract yet; the owner has ruled the pieces (design section 1.7a) and decision 21 lists what is
left. Security-sensitive: Opus only, contract first, negative tests first.

### Types

```ts
type TokenRefusal = "malformed" | "unsupported-alg" | "unknown-kid" | "bad-signature" | "expired" | "not-yet-valid"
                  | "wrong-issuer" | "wrong-audience" | "missing-claim" | "jwks-unavailable";
interface TokenVerifier {                                   // RS256 only; alg pinned by configuration, never read from the token's choice
  verify(token: string, now: Instant): Promise<Result<VerifiedClaims, TokenRefusal>>;
}
interface Jwks { key(kid: string): Promise<Result<PublicKeyHandle, "unknown-kid" | "jwks-unavailable">> }   // cache, refresh on unknown kid with a floor

interface OidcClient {                                      // public client: never sends a secret
  start(returnTo: string): Promise<Result<{ redirect: URL; pending: PendingLogin }, AuthError>>;   // state, nonce, PKCE verifier
  finish(callback: URL, pending: PendingLogin): Promise<Result<Tokens, AuthError>>;
  refresh(t: Tokens): Promise<Result<Tokens, AuthError>>;
}
interface SessionStore {                                    // drivers: cookie (AES-256-GCM via node:crypto), database, redis
  load(req: Request): Promise<Result<Session | undefined, SessionError>>;
  save(s: Session): Promise<Result<SetCookie, SessionError>>;
  destroy(s: Session): Promise<Result<SetCookie, SessionError>>;
}
function sessionResolver(store: SessionStore, authz: Immutable<AuthzTables>): ContextResolver;   // client, principal, permissions
function authRoutes(deps: AuthDeps): readonly Route[];        // /auth/login, /auth/callback, /auth/logout, /api/me
```

### Rules

- Primitives from `node:crypto` only, through the `Crypto` port: `createPublicKey` from the JWK and
  `verify("RSA-SHA256", ...)`, AES-256-GCM with a random 96-bit nonce and a key ring for rotation,
  HMAC-SHA256 and `timingSafeEqual`. Nothing hand-rolled: no own base64url beyond the standard
  `Buffer` encoding, no own constant-time compare, no own JSON Web Key parsing.
- Refused before any signature work: `alg` other than the configured one (so `none` and `HS256` with
  the public key as the secret are refused), a header that is not JSON, a token over a size limit.
- The shared vectors (`conformance/auth/`) are written first and cover every `TokenRefusal`; the
  tests that read them are written before the verifier.
- Prior art to read, not to depend on: the SDK's `fastify/oidc/*` (discovery, flow, claims with
  `jose`) and `fastify/session/*` (cookie, PostgreSQL and Redis stores). `jose` itself is not used:
  the verifier is small, and the rule is primitives from `node:crypto`.
- scriptc: RS256 and AES-GCM are outside its crypto, so a native build needs an FFI adapter of the
  `Crypto` port to a vetted library; the rest of the auth code is ordinary framework code.

## 13. UI: `@fw/ui` on Nuxt UI

Inputs: [`../../ui-kit/README.md`](../../ui-kit/README.md), the kit's component specs and tokens,
[`../ui-dogfood-plan.md`](../ui-dogfood-plan.md), the platform SPA's composables
(`useDirtyForm`, `useListState`, `useCursorPagination`, `useTableFilters`, `useReturnTo`,
`useDrawerRoute`), which are prior art for the package's composables.

### What the package holds

| Layer | Contents |
|---|---|
| theme | a preset for the Nuxt UI Vite plugin options (colours, radius, fonts, component slot overrides) mapped from `ui-kit/theme/tokens.css`; dark mode through Nuxt UI's colour mode |
| components | extensions of Nuxt UI components for the kit's composite pieces: filter toolbar, cursor pager, detail field, JSON and code blocks, code chips, local time, row actions, confirm and form dialogs, server-loaded drawer, flash stack (Nuxt UI toasts), client switcher, permission gate |
| page partials | list section (table + toolbar + pager + empty state), detail header, form section with dirty guard, error and empty states |
| pages | application shell (Nuxt UI dashboard layout and sidebar), not permitted, not found, error, profile; the admin pages later (M4d/M4e equivalent) |
| composables | `useApi` (typed fetch over the API schemas' static types, envelope errors as values, 401 → login redirect), `useSession` (principal, permissions, current client), `usePermission`, `useDirtyForm`, `useListState`, `useCursorPagination` |
| forms | `standardSchema(schema)`: a Standard Schema adapter over a TypeBox schema (`Value.Errors` → issues with paths), so `UForm` validates with the API's schema; server `400 VALIDATION` details mapped back onto form fields by path |

### Rules

- Extend, never fork, Nuxt UI: components wrap Nuxt UI components and pass slots and props through.
- No server-side rendering, no Nuxt modules, no auto-imports in the package's own source (explicit
  imports, so the package type-checks without the plugin's generated declarations).
- The SPA is built by Vite in the application (`web/`), output to a directory the server serves
  (decision 26). The server never imports Vue.

### Open rulings

- Decisions 17 to 20.

## 14. Workers (milestone T4b)

No contract in Java or Rust (Java has virtual threads, Rust a multi-threaded runtime); the rules are
design section 1.12. What the port looks like:

```ts
interface Workers {                                              // adapters: node:worker_threads (Bun implements it), later @scriptc/threads
  publish<T>(name: SnapshotName, value: Immutable<T>): SnapshotVersion;   // main thread only; replaces, never mutates
  spawn(entry: WorkerEntry, count: Int32): Promise<Result<WorkerSet, StartupError>>;
}
type WorkerEntry = {                                             // a module whose build function runs in each worker
  readonly module: string;                                       // a static, literal module path (no computed import)
  readonly build: (w: WorkerBoot) => Promise<Result<AppHandler, StartupError>>;
};
interface WorkerBoot {                                           // what a worker may touch
  snapshot<T>(name: SnapshotName): Immutable<T>;                 // config, route table, authz tables, schema cache, definitions
  readonly pools: PoolShare;                                     // this worker's share of each database and Redis pool
  readonly metrics: Metrics;                                     // merged on the main thread
}
```

- **Sizing.** `database.instances.<name>.pool.max` and the Redis equivalent are process totals; a
  worker gets `ceil(max / workers)`, and start-up fails if a share would be zero. The main thread
  keeps a small pool for leader work and health.
- **Leader work** (the `fw_dispatch_once` purge, lock-gated loops, the scheduled-job overlap lock,
  sync on boot) runs on the main thread only, gated across replicas by the existing lock drivers.
  Scheduled-job firings are accepted by whichever worker receives the request and handed to the main
  thread's runner by message, so the overlap lock and completion retries stay in one place.
- **Health and drain.** Workers report health to the main thread, which answers `/health/ready`
  from the aggregate; at shutdown the main thread stops loops, then tells workers to drain within
  `http.shutdown-grace`, then closes its own pools.
- **Listener sharing** is chosen in T4b by measurement (design doubt 9).
- **Snapshots** are plain, acyclic, `Immutable` data, so the scriptc adapter can share them by
  reference and the Node adapter by one structured clone per worker per version.
