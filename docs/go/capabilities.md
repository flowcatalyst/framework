# Go framework: capability by capability (draft 1, 2026-10-13)

Companion to [`design.md`](design.md). For each Java contract: the key Go types (sketches, not final
signatures), what is taken from the contract verbatim, what changes and why, what is reused from
`pkg/fcsdk` or the Go platform ([`gap-analysis.md`](gap-analysis.md)), and the open rulings.
"Verbatim" means the rule and its observable behaviour; the conformance tests
([`milestones.md`](milestones.md) section 3) check it against the Java framework's output.

Sketches leave out doc comments and most error handling. `cx` is the framework's `fw.Context`,
`ctx` the standard `context.Context`.

## 1. Core: lifecycle, instances, Context, config, health, commands

Contract: [`core.md`](../contracts/core.md).

### Types

```go
// --- lifecycle -------------------------------------------------------------------
package fw

type App struct{ /* declarations; sealed when Launch starts */ }

func New(name string, opts ...Option) *App           // name checked: [a-z][a-z0-9-]*
func WithEnvironment(env map[string]string) Option   // tests: an explicit map, else the process environment
func WithClock(c Clock) Option
func WithConfigDir(dir string) Option
func WithLogger(l *slog.Logger) Option

// Module returns a declarer scoped to one module id; every capability's Declare uses it,
// so problems name their module.
func (a *App) Module(id string) *Declarer

type BuildFunc func(ctx context.Context, b *Boot) (Serve, error)
func (a *App) Launch(ctx context.Context, args []string, build BuildFunc) int // exit code

type Boot struct{ /* config values, clock, ids, metrics, health, loops, shutdown stack, commands */ }
func (b *Boot) Logger(module string) *slog.Logger
func (b *Boot) Clock() Clock
func (b *Boot) Ids() *Ids
func (b *Boot) Metrics() *metrics.Registry
func (b *Boot) Health(name string, c Criticality, check HealthCheck)
func (b *Boot) Loop(name string, opts background.Options, run background.Func)
func (b *Boot) OnStarted(name string, f func(ctx context.Context) error)
func (b *Boot) OnShutdown(name string, f func(ctx context.Context) error)  // reverse order, bounded by app.shutdown.drain
func (b *Boot) Command(name string, run CommandFunc)                     // claimed first argument

type CommandFunc func(ctx context.Context, args []string, out io.Writer) int

// --- Context -----------------------------------------------------------------------
type Context struct{ /* unexported: client, principal, kind, application, correlation, causation, execution */ }
func NewContext(client ClientID, correlation CorrelationID, opts ...ContextOption) (Context, error)
func (c Context) Client() ClientID                         // never zero
func (c Context) Principal() (PrincipalID, bool)
func (c Context) Application() (ApplicationCode, bool)
func (c Context) Correlation() CorrelationID
func (c Context) WithCausation(id CausationID) Context
func (c Context) WithExecution(id ExecutionID) Context
func (c Context) LogAttrs() []slog.Attr

type ClientID struct{ v string }                           // [A-Za-z0-9._-]{1,100}
func ParseClientID(s string) (ClientID, error)
func MustClientID(s string) ClientID                       // literals; checked by fwvet
func (id ClientID) String() string
func (id ClientID) IsZero() bool
// PrincipalID, ApplicationCode ([a-z][a-z0-9-]*), CorrelationID, CausationID, ExecutionID likewise.

// --- config ---------------------------------------------------------------------------
package config

type Setting[T any] struct{ /* key, kind, default, required, description; immutable */ }
func String(key, description string, opts ...Opt[string]) Setting[string]
func Int(key, description string, opts ...Opt[int]) Setting[int]
func Duration(key, description string, opts ...Opt[time.Duration]) Setting[time.Duration]
func SecretSetting(key, description string, opts ...Opt[Secret]) Setting[Secret] // never a real default
// Bool, Int64, URL, Enum[E], StringList likewise; Default(v), Required() as options.

type Handle[T any] struct{ /* only Declare makes one */ }
func Declare[T any](d *fw.Declarer, s Setting[T]) Handle[T]
func (h Handle[T]) Get(b *fw.Boot) T                       // cannot fail: declared and validated

type Secret struct{ /* unexported */ }
func (s Secret) Reveal() string
func (s Secret) String() string                            // "Secret[****]"
func (s Secret) Format(f fmt.State, verb rune)             // every verb, including %#v, prints Secret[****]
func (s Secret) MarshalJSON() ([]byte, error)              // always an error: a secret is not serialisable
func (s Secret) LogValue() slog.Value                      // Secret[****]
func (s Secret) Matches(candidate string) bool             // SHA-256 digests compared in constant time

// --- instances --------------------------------------------------------------------------
type DriverSpec struct{ Name string; Keys []KeySpec }      // declared, so configure checks every section
type Instances[D any] struct{ /* default name, by name */ }
func (i Instances[D]) Get(name string) (D, error)          // error names the known instances
func (i Instances[D]) Default() D
```

### Verbatim

The phases' meaning and order (declare, configure, build, started actions after every part is
built, loops after every started action); shutdown order and `app.shutdown.drain`; health checks
with `app.health.timeout`, criticality and the `app.health.critical` / `app.health.informational`
overrides (a name in both fails start-up, an unmatched name is a WARN); the instance rules (unknown
driver lists the registered names, unread keys fail start-up, `default` rules, default drivers for
single-driver capabilities); every `Context` field and id pattern; every config rule (key grammar,
types, kinds, sources and their order, `APP_ENV`, `config/application.yaml` and
`application-{env}.yaml`, env names and the collision check, durations and booleans, errors naming
key and source and never a secret's text, `secrets.local.yaml` only for `local` and `test`,
platform addressing and the reserved components). Commands: a claimed first argument runs after
build, without started actions or loops; two claims fail start-up. Checked by
`conformance/config/config.json`.

### Changes

| Java | Go | Why |
|---|---|---|
| `register` / `boot` / `started` / `shutdown` hooks on a `Module` | `Declare` functions returning handles; the application's `build` function; `Boot.OnStarted`, `Boot.OnShutdown` | compile-time wiring (design 1.2) |
| `app.get(Class)`, `find(Class)` | none; values are passed | no container |
| `AppBuilder.override` in test mode | `build` takes replaceable collaborators as parameters; tests pass fakes | no type map |
| `config.get(setting)` throws for an undeclared setting | `Handle[T].Get` exists only for declared settings | the declaration is the proof |
| `StartupException` with problems | `*fw.StartupError{Problems []Problem}`, one line each | same report |
| `Secret` not serialisable (Jackson fails at run time) | `Secret` implements `fmt.Formatter`, `slog.LogValuer` and a failing `MarshalJSON` | Go cannot make "does not compile"; every formatting path redacts and JSON fails loudly |
| `InstantSource`, seeded `MonotonicClock` | `fw.Clock` interface (`Now() time.Time`); monotonic time inside `time.Time` | Go keeps a monotonic reading in every `time.Time` |
| YAML reader keeps the last duplicate key | `go.yaml.in/yaml/v3` refuses it | the library's behaviour; an allowed difference in the vectors |

### Open rulings

- A setting declared in a package-level `var` is an immutable descriptor, not state; the
  `forbidigo` profile allows it. Confirm in G1 review.

## 2. Database, migrations, Redis

Contract: [`database-and-redis.md`](../contracts/database-and-redis.md).

### Types

```go
package database

type Declared struct{ /* the instance section and its keys */ }
func Declare(app *fw.App) *Declared
func Connect(ctx context.Context, b *fw.Boot, d *Declared) (*Databases, error) // ping each; health database:<name>; close on shutdown

type Databases struct{ /* by name */ }
func (d *Databases) Get(name string) (*DB, error)
func (d *Databases) Default() *DB

type DB struct{ name string; pool *pgxpool.Pool }
func (db *DB) Pool() *pgxpool.Pool                          // for sqlc's dbq.New(db.Pool()) and reads
type Tx struct{ tx pgx.Tx }                                  // only the use-case runner and receivers' Once begin one
func (t *Tx) Conn() pgx.Tx                                   // for dbq.New(tx.Conn()) inside a plan step

type MigrationSet struct{ Module string; Files fs.FS }       // //go:embed V*.sql in the contributing package
func (d *fw.Declarer) Migrations(set MigrationSet)
func (b *fw.Boot) Migrate(ctx context.Context, dbs *Databases) error

package redisfw
func Declare(app *fw.App) *Declared
func Connect(ctx context.Context, b *fw.Boot, d *Declared) (*Clients, error)  // go-redis v9; ping; health redis:<name>
```

### Verbatim

Named instances, `password` as a secret, a start-up ping per instance; migrations: file naming, one
set per module in declaration order, the default instance only and only when `migrate` is true,
**`fw_schema_history` and its columns, SHA-256 of the file bytes**, a transaction per file with its
history row, the advisory lock, every start-up failure (changed checksum, missing file, out-of-order
unapplied file, duplicate version), the table check for non-default instances. Redis: standalone
only, health, start-up ping. Checked by `conformance/migrations/*.json` (a database holding the Java
runner's history rows is accepted, and the Go runner writes the same rows).

### Changes

- The pool is `pgxpool`; `pool.max`, `pool.min-idle`, `pool.connect-timeout` map to its config.
  `url` accepts `jdbc:postgresql://` (prefix dropped, owner ruling 2026-10-12 for Rust) and
  `postgres://`/`postgresql://`; `username` and `password` win over URL credentials. pgx also
  parses `key=value` strings; whether to accept them is a G1 ruling (Rust could not, Java does).
- Migration files are embedded with `//go:embed` by the package that contributes the set.
- A file may hold several statements: pgx's simple protocol executes it as one `Exec` without
  arguments.
- Blocking JDBC on virtual threads becomes pgx on goroutines; nothing in the contract depended on
  blocking.

### Reuse

The platform's `shared/database.NewPool` (tuning defaults) and `testpg` (the fixture) as patterns.

## 3. The write path

Contract: [`write-path.md`](../contracts/write-path.md). The full example is
[`orders-showcase.md`](orders-showcase.md).

### Types

```go
package usecase

// --- persistence state is the framework's, not the aggregate's ---------------------
type Fresh[A any] struct{ value A; client fw.ClientID }              // made in this attempt, the context's client
func NewFresh[A any](cx fw.Context, a A) Fresh[A]
type Loaded[A any] struct{ value A; client fw.ClientID; version int64 }
func LoadedFrom[A any](client fw.ClientID, version int64, a A) Loaded[A] // for repositories: the row's client and version
func (l Loaded[A]) Get() A
func (l Loaded[A]) With(a A) Loaded[A]                                    // new value, same client and version

type Repository[A any, I comparable] interface {
	Find(ctx context.Context, db *database.DB, client fw.ClientID, id I) (Loaded[A], bool, error)
	Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, a A) error                  // stores version 1
	Update(ctx context.Context, tx *database.Tx, client fw.ClientID, a A, v VersionCheck) (int64, error)
	Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, a A, v VersionCheck) (int64, error)
}
type VersionCheck struct{ Expected, Next int64 }                          // computed by the runner

// --- plans -------------------------------------------------------------------------
type Step interface{ step() }                                            // sealed
func Insert[A any, I comparable](f Fresh[A], r Repository[A, I], entity Entity) Step
func Update[A any, I comparable](l Loaded[A], r Repository[A, I], entity Entity) Step
func Delete[A any, I comparable](l Loaded[A], r Repository[A, I], entity Entity) Step
type Entity struct{ Type, ID string }                                    // for the audit row

type Plan[E event.Event] struct{ /* steps, first event, more events, dispatch jobs */ }
func NewPlan[E event.Event](first E, steps ...Step) *Plan[E]             // every plan carries an event
func (p *Plan[E]) Emit(ev event.Event) *Plan[E]
func (p *Plan[E]) Dispatch(job DispatchJob) *Plan[E]

// --- use cases ---------------------------------------------------------------------
type Valid[C any] struct{ cmd C }                                        // only the runner makes one
func (v Valid[C]) Command() C
type Authorized[C any] struct{ cmd C }
func (a Authorized[C]) Command() C

type Grant struct{ ok bool }
func Public() Grant                                                      // Authorize.PUBLIC: deliberate and greppable
// cx.Require(permission) (Grant, error) arrives with permissions on Context (M4c)

type UseCase[C any, E event.Event] interface {
	Name() Name
	Validate(ctx context.Context, cx fw.Context, cmd C) error            // nil when there is nothing to check
	Authorize(ctx context.Context, cx fw.Context, cmd Valid[C]) (Grant, error)
	Execute(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB) (*Plan[E], error)
}

type Defined[C any, E event.Event] struct{ /* the use case, its policy, the runner */ }
func Define[C any, E event.Event](r *Runner, uc UseCase[C, E], opts ...DefineOption) (*Defined[C, E], error)
func WithConflictPolicy(p ConflictPolicy) DefineOption
func (d *Defined[C, E]) Run(ctx context.Context, cx fw.Context, cmd C) (Committed[E], error) // error is *Error

type Committed[E any] struct{ event E }                                   // non-zero only from the runner
func (c Committed[E]) Event() E

type ConflictPolicy struct{ /* fail, or retry n (2..10) with backoff */ }
func Fail() ConflictPolicy
func Retry(attempts int, backoff time.Duration) (ConflictPolicy, error)

type Runner struct{ /* default DB, outbox writer, clock, ids, metrics, logger */ }
func NewRunner(b *fw.Boot, db *database.DB, opts ...RunnerOption) *Runner

// --- errors --------------------------------------------------------------------------
type Kind int
const (KindValidation Kind = iota; KindAuthorization; KindNotFound; KindConflict; KindBusinessRule; KindInternal)
type Error struct{ /* kind, code, message, details map[string]string, cause (logged, never shown) */ }
func (e *Error) Error() string
func (e *Error) Status() int                                            // fw.ServiceError, what httpfw maps
func NotFound(code ErrorCode, message string) *Error                    // Validation, BusinessRule, ... likewise
```

### Verbatim

Section 2 (repository operations, `WHERE id AND client_id AND version`, row counts 0 and above 1,
`23505` → `CONFLICT`/`UNIQUE_VIOLATION` with the constraint, not retried); section 3 (name,
validate, authorize required, execute returns a plan or an error, conflict policy, load once);
section 4 (the order: validate, authorize, execute, the client check, READ COMMITTED on the default
instance, writes in plan order, events, dispatch jobs, one audit row, commit; the failure table and
the step codes `VALIDATE` to `COMMIT`; `INTERRUPTED`; retry with jitter, 2 to 10 attempts);
section 5 (kinds and statuses); section 6 (event metadata and the message-group default); **sections
7 and 7a in full** (the outbox table, `V0001` byte for byte and `V0002`, every column and payload
field, `contextData`, `deduplicationId`, the dispatch-job rules and defaults, `kind` default
`TASK`, the fields never written, `metadata` as an array); section 8 (TSIDs). The `INSERT` is the
shared `conformance/sql/outbox/insert.sql`. Metrics `fw_usecase_runs_total` and
`fw_usecase_duration_seconds`. Checked by `conformance/outbox/outbox.json` byte for byte (every
column, the payload text and size; `payload_size` is the UTF-8 byte length, as Java computes it).

### Changes

| Java | Go | Why |
|---|---|---|
| `Aggregate.client()` and `version()` on the domain record | `Fresh[A]` / `Loaded[A]` hold them | the aggregate knows nothing of storage; insert versus update is a type |
| runner checks `version() == 0` | `Insert` takes `Fresh`, `Update` and `Delete` take `Loaded` | a loaded aggregate cannot be inserted, nor a new one updated |
| `CROSS_CLIENT_WRITE` for every aggregate | impossible for `Fresh` (built from the context's client); still checked for `Loaded` (the row's `client_id`) | catches a repository whose `Find` forgot `client_id = $` |
| `UseCase.named(..)...execute(..)`, a missing `authorize` throws at definition | an interface: a missing `Authorize` method does not compile | compile time |
| any caller can run a phase function | `Valid[C]` and `Authorized[C]` have unexported fields; only the runner makes a non-zero one | `Execute` is reachable only through `Run` (a test can still pass a zero `Authorized`; it then gets the zero command) |
| `Plan.save(...).andSave(...)` | `NewPlan(event, Insert(...), Update(...))` | Go methods cannot have type parameters, so steps are values built by generic functions |
| `UseCaseResult.Committed / Failed` | `(Committed[E], error)` with `*Error` | Go's two return values |
| an exception in a phase | an `error` (wrapped as `INTERNAL` with the step code unless it is a `*Error`) or a recovered panic (`INTERNAL`, step code) | errors are values; panics are bugs |
| `AuditData` on the command | an optional `AuditData() map[string]string` method on the command, found by a type assertion | the one optional-method check in the write path |
| event data = the record's fields (Jackson) | the event struct marshalled with `encoding/json/v2`, `json:"camelCase"` tags, metadata `json:"-"`; optional fields are pointers with `omitzero` | the same JSON as the vectors |
| `Ids.generate("ord")` | `ids.Next(prefix)` and `id.New[K](ids)` for typed ids | typed ids |

Compared with fcsdk's `usecaseop`/`usecasepgx` ([gap analysis](gap-analysis.md) rows 17 to 26): the
envelope's shape is the same; the framework adds versions, the conflict policy, client scoping, one
audit row per use case, the contract's step codes, dispatch jobs, panic recovery and the Java SDK's
payload. A platform developer reads a framework use case without surprise.

### Open rulings

- Decision 12 (interface versus `usecaseop`'s struct of functions).
- `Context` gains the principal's permissions and kind in M4c; until then `Authorize` checks what
  `Context` has, as the Java demo does.

## 4. Checked SQL and dynamic queries

Contract: [`checked-sql.md`](../contracts/checked-sql.md). Design section 5 has the go-jet versus
`Filter` comparison.

### Static queries: sqlc

```yaml
# sqlc.yaml of an application (the platform's style)
version: "2"
sql:
  - engine: postgresql
    schema: [".fw/schema", "migrations"]      # fw sql schema writes the framework's sets to .fw/schema
    queries: "sql"
    gen:
      go:
        package: dbq
        out: internal/dbq
        sql_package: pgx/v5
        emit_pointers_for_null_types: true
        emit_db_tags: true
        overrides:
          - column: "orders.id"
            go_type: { import: "example.com/orders/domain", type: "OrderID" }   # an alias of id.ID[OrderKind]
```

`make sql` runs `sqlc generate` (no database). `fw sql check` (CI) starts an embedded PostgreSQL 18,
applies every set with the framework's runner, runs `sqlc vet` with that database (the
`sqlc/db-prepare` rule prepares every query) and `sqlc diff`.

### Verbatim

Section 2's semantics where sqlc can express them: the type mapping with Go types (`text` →
`string`, `int4` → `int32`, `int8` → `int64`, `numeric` → `pgtype.Numeric` or a decimal type by
override, `timestamptz` → `time.Time`, `uuid` → `pgtype.UUID` or `uuid.UUID`, `bytea` → `[]byte`,
`json`/`jsonb` → `json.RawMessage`, arrays → slices), nullability from the schema, typed values by
override. Section 5's scope: dynamic queries are not generated.

### Changes

- Overrides live in `sqlc.yaml` per column, not in the query's alias; parameters use `@name` and
  `sqlc.narg(name)` for nullable ones. The Java `!`/`?` alias suffixes have no sqlc equivalent; a
  nullable column read where the domain needs a value is converted (and checked) in the repository.
- Queries are many per file with `-- name: X :one` headers, as in Java (sqlc's own syntax, which
  the Java contract copied).
- Not verified: outer-join nullability and a generic id alias as an override type (G2).

### Dynamic queries

```go
// go-jet, built from generated table types, executed by pgx
stmt := SELECT(Orders.ID, Orders.Customer, Orders.Status).
	FROM(Orders).
	WHERE(cond).                         // built from the filter's optional parts
	ORDER_BY(Orders.ID.DESC()).
	LIMIT(int64(page.Size))
rows, err := sqlfw.Query(ctx, db, stmt, pgx.RowToStructByName[OrderRow])

// Filter, the fallback (copied from the platform's repocommon)
var f database.Filter
f.Eq("client_id", cx.Client().String())
f.EqPtr("status", q.Status)
f.Clause("customer ILIKE $%d", "%"+q.Search+"%")
limit := f.Arg(page.Size)
sql := "SELECT id, customer, status FROM orders" + f.Where() + fmt.Sprintf(" ORDER BY id DESC LIMIT $%d", limit)
rows, err := db.Pool().Query(ctx, sql, f.Args()...)
```

### Open rulings

- Decisions 13 and 14.

## 5. HTTP

Contract: [`http.md`](../contracts/http.md). `net/http`, the 1.22+ `ServeMux`, `encoding/json/v2`.

### Types

```go
package httpfw

type HandlerFunc func(w http.ResponseWriter, r *http.Request) error   // the one handler shape
type ContextHandlerFunc func(w http.ResponseWriter, r *http.Request, cx fw.Context) error

type Routes struct{ /* module id, table: (method, shape, registrant) */ }
func NewRoutes(module string) *Routes
func (rt *Routes) Handle(pattern string, h HandlerFunc)               // "POST /orders/{id}/lines"
func (rt *Routes) HandleContext(pattern string, h ContextHandlerFunc) // resolves fw.Context first
func (rt *Routes) Mount(pattern string, h http.Handler)               // a foreign handler, wrapped in the framework's middleware
func (rt *Routes) Use(mw func(http.Handler) http.Handler)             // application middleware, inside the framework's

func Serve(b *fw.Boot, d *Declared, routes ...*Routes) fw.Serve       // checks the table, builds the mux, fixed middleware

type ContextResolver interface {
	Resolve(r *http.Request) (fw.Context, error)
}
// A route registered with HandleContext and no resolver given to Serve is a start-up problem.

// errors
type ServiceError interface{ error; Status() int; Code() string; Message() string; Details() map[string]string }
func WriteError(w http.ResponseWriter, r *http.Request, err error)    // the envelope; 500 never carries the cause

// JSON and validation
func DecodeJSON[T any](r *http.Request) (T, error)  // 415 unless application/json; json/v2 with RejectUnknownMembers;
                                                      // INVALID_JSON with the JSON Pointer, never the value; body limit
package validate
type Violations struct{ /* field -> first "<code>: <message>" */ }
func Field[T any](v *Violations, name string, parse func() (T, error)) T  // records the error, returns the zero T
func (v *Violations) Err() error                                          // nil, or 400 VALIDATION with details
```

### Verbatim

Section 2's rules as observed by a client (methods, `{name}` segments, no wildcards, trailing slash
distinct, no redirects, literal beats parameter, per-segment percent-decoding, duplicates fail
start-up naming both registrants, 404, 405 with a sorted `Allow` including `HEAD` with `GET` and
always `OPTIONS`, `OPTIONS` 204, `HEAD` from `GET`, routes logged at INFO, no CORS); the fixed
outermost order **correlation id, error mapping, access log**, then application middleware, then
context resolution on the routes that need it; section 5 in full; section 6 (correlation-id rules);
section 7's outcomes; section 8 (settings, health endpoints and JSON, 413 with the connection
closed, drain and 503, no `Server` header, no stack traces). Not implemented: section 10, the
Datastar adapter (decision 27).

### Typed operations and OpenAPI (decision 18)

The UI's typed spine needs an OpenAPI document ([`ui.md`](ui.md) sections 6 and 7). *Since
2026-10-14 the document is specified once for Java and Go by
[`../contracts/openapi.md`](../contracts/openapi.md)* (type table, "may be absent" for optional values,
closed schemas, the error envelope and implied statuses, security schemes contributed by the
resolver, deterministic output, the `openapi` command in the declare phase); the Go emitter must
reproduce its conformance vector `conformance/openapi/` byte for byte. The declaration API below is
Go's shape of the same rules; where they differ, the contract wins (notably: no `Nullable[T]`, and
constraints come from validation rules expressed as data).

```go
type Operation[Body, Resp any] struct {     // an immutable descriptor; package-level var allowed
	ID, Pattern, Summary string               // Pattern as for Routes.Handle: "POST /orders/{id}/lines"
	Status                int
	Tags                  []string
	Path, Query           Params               // typed parameters: PathParam[T]("id"), QueryParam[T]("status")
	Errors                []int                // statuses documented with the shared envelope
}
type NoBody struct{}                          // Body or Resp of an operation without one

type In[Body any] struct{ /* Body, typed path and query values, the *http.Request */ }
type OperationHandler[Body, Resp any] func(ctx context.Context, cx fw.Context, in In[Body]) (Resp, error)

func DeclareOperations(d *Declared, ops ...Described)                  // at declare time: the document needs no build
func Handle[Body, Resp any](op Operation[Body, Resp], h OperationHandler[Body, Resp]) Binding
func (rt *Routes) Bind(b Binding)                                     // start-up fails: undeclared, unbound or bound twice

// schema hooks, read by the emitter through interfaces (never struct tags beyond json names)
type EnumValuer interface{ EnumValues() []string }                    // string types with a closed set
type SchemaProvider interface{ JSONSchema() Schema }                  // value types: ids, Instant, Money
type Nullable[T any] struct{ /* explicit null in the document and on the wire */ }
type CursorPage[T any] struct{ Items []T; HasMore bool; NextCursor *string }
```

- The body is decoded by the same `DecodeJSON` (415, `INVALID_JSON`, closed objects), the response
  written with `Status` and the envelope on error, exactly as a plain route.
- The emitter walks the json/v2 types once, at the JSON edge (reflection allowed there): names from
  `json` tags, required unless `omitzero` or a pointer, enums from `EnumValuer`, `Instant` as
  `date-time`, arrays never nullable, `Nullable[T]` as `[T, null]`, generic types named by their
  arguments, each named type one component; a name collision is a start-up problem.
- The `openapi` command (registered by `httpfw.Declare`) runs the declare phase only and prints the
  OpenAPI 3.1 document; applications commit it and CI diffs it, as the platform's `make api-diff`.
- Routes that are not JSON (receivers, the SPA, health, metrics) stay plain routes and are not in
  the document, except the session endpoints of section 12, which are operations.

### Static assets, CSRF, security headers

- `httpfw/spa.Handler(fs.FS, Options)`: the embedded SPA by the shared
  [`../contracts/static-assets-and-spa.md`](../contracts/static-assets-and-spa.md) (2026-10-14,
  mirroring the platform's F16 handler): the router's fallback for a path no route matches (no
  wildcard route), API prefixes derived from the routes, immutable hashed assets, the JSON 404 for a
  missing asset and an unknown API path, `index.html` `no-cache` with an ETag, the baseline CSP and
  headers; the shared `conformance/http/spa.json` vector.
- `httpfw.CSRF`: for cookie-authenticated unsafe methods, `http.CrossOriginProtection` plus refusing
  a request with neither `Sec-Fetch-Site` nor `Origin` (the platform's `/ui` guard); bearer-token
  requests skip it ([`ui.md`](ui.md) section 8.2).
- `httpfw.SecurityHeaders`: the headers of the static-assets contract section 6.1 on the SPA's
  responses (the strict CSP of section 7 opt-in); `X-Content-Type-Options: nosniff` everywhere.

### Changes

| Java | Go | Why |
|---|---|---|
| the framework's own router | `ServeMux`, wrapped; the framework's own table checks duplicates and conflicts (the mux would panic) and answers 404, 405 and `OPTIONS` (the mux's answers are plain text) | stdlib first (ruling a), contract errors kept |
| first difference decides between overlapping patterns | the mux refuses two patterns that overlap with neither more specific (`/a/{x}/c`, `/a/b/{y}`): a start-up problem in Go | `ServeMux`'s conflict rule; a recorded difference (decision 16) |
| the router never redirects | the mux redirects `.`, `..` and `//` paths; the framework answers 404 for a non-canonical path before the mux sees it | contract: no redirects |
| `Handler` returns a `Response` or throws `HttpException` | `HandlerFunc` returns `error`; a `ServiceError` becomes its envelope, any other error 500; a panic 500 | errors are values |
| `request.context()` throws without `requiresContext` | `HandleContext`; a missing resolver is a start-up problem | Go cannot make it a compile error without a generic router |
| Jackson strict mapping | `encoding/json/v2`: case-sensitive names, duplicate names refused, `RejectUnknownMembers(true)` | strictness without struct tags beyond names |
| `Validator.of(..).field(..)` | `validate.Field` with parse functions; parse-don't-validate into commands | explicit functions, no tag language |
| `TestClient` | `httptest.NewRecorder` on the built handler; `httptest.NewServer` for socket tests | stdlib |
| `http.request-timeout` (Jetty idle and read) | `http.Server` `ReadHeaderTimeout`, `ReadTimeout`, `IdleTimeout`; a total-request timeout middleware | closest equivalent; documented |

Fixed middleware, outermost first: correlation id, error mapping and panic recovery, access log
(`slog`: method, route pattern from `r.Pattern`, status, duration, correlation id), metrics, the
drain guard, the body limit (`http.MaxBytesReader`, 413 with `Connection: close`), then the
application's middleware, then context resolution for `HandleContext` routes.

### Reuse

The platform's `shared/httperror` envelope and `shared/middleware` correlation-id rules as patterns;
nothing imported (internal, and chi-based).

### Open rulings

- Decisions 15, 16, 17 and 18 (the typed operations and OpenAPI).

## 6. Locks

Contract: [`locks.md`](../contracts/locks.md). Taken almost entirely verbatim.

### Types

```go
package locks

type Key struct{ /* scope, name */ }                       // ServiceKey(name), ClientKey(client, name)
type Lease struct{ /* key, owner, fence, local deadline, lost <-chan struct{} */ }
func (l Lease) Fence() int64
func (l Lease) Lost() <-chan struct{}                       // closed when a refresh finds the lease gone

type Acquisition interface{ acquisition() }                 // sealed: Acquired{Lease} | Busy{}
type Acquired struct{ Lease Lease }
type Busy struct{}

type Store interface {                                      // the driver contract (Java's LockDriver level)
	TryAcquire(ctx context.Context, key string, owner string, lease time.Duration) (fence int64, ok bool, err error)
	Refresh(ctx context.Context, key, owner string, fence int64, lease time.Duration) (bool, error)
	Release(ctx context.Context, key, owner string, fence int64) (bool, error)
}

type Lock struct{ /* store, clock, logger */ }              // the user-facing API, one per named instance
func (l *Lock) Acquire(ctx context.Context, k Key, lease, maxWait time.Duration) (Acquisition, error)
func With[T any](ctx context.Context, l *Lock, k Key, lease, maxWait time.Duration,
	action func(ctx context.Context, lease Lease) (T, error)) (WithResult[T], error)
type WithResult[T any] struct{ Outcome Outcome; Value T }  // Outcome: Completed | NotAcquired | Lost (exhaustive)

type Locks = fw.Instances[*Lock]
func Declare(app *fw.App, drivers ...Driver) *Declared     // Memory(), Postgres(), Redis()
func Build(ctx context.Context, b *fw.Boot, d *Declared, opts ...BuildOption) (*Locks, error)
func WithDatabases(dbs *database.Databases) BuildOption
func WithRedis(c *redisfw.Clients) BuildOption
type Gate struct{ /* for background loops: With with zero wait */ }
```

### Verbatim

Sections 1 to 5: key scopes and stored forms, the name pattern, the seven driver guarantees, the
local deadline taken before the request, backoff 10 ms doubling to 250 ms with jitter, `With`
refreshing every `lease / 3` and its result rules, **the `fw_lock` table and sequence and the three
statements with `clock_timestamp()`** (the shared files), **the Redis key format, the value
`<owner>:<fence>`, the counter key and the three Lua scripts** with `EVALSHA` and the `NOSCRIPT`
reload (go-redis `Script.Run` does this), the memory driver's single-process scope, the contract
test cases of section 6. Lease and wait limits (100 ms to 24 h; 0 to 24 h) checked on every call.

### Changes

- `With` runs the action with a child context that is **cancelled when the lease is lost**; the
  action stops at its next `ctx` check, as a Java thread at its next interrupt check. If it returns
  after the loss, the result is `Lost` with its value. A Go action that ignores `ctx` keeps running:
  the same rule as a Java thread ignoring interrupts.
- An error from the action is returned unwrapped beside the outcome; `LockedActionException` has no
  equivalent.
- The contract suite is `lockstest.Run(t, harness)`: one `t.Run` per case, the harness supplying the
  store, a way to let time pass and a hook that expires a key in the store. Every driver's test file
  calls it.

### Reuse

None from fcsdk's `lock` (no fencing or refresh, different storage; gap analysis rows 14 to 16).

## 7. Rate limiting

Contract: [`rate-limiting.md`](../contracts/rate-limiting.md). Verbatim in full: GCRA and its
arithmetic, policy ranges, `cost`, the memory driver's bound, purge and overflow bucket, the Redis
driver's key format, microsecond server clock and **the Lua script** (Java and Go replicas share a
budget), the 429 envelope and `Retry-After` rounding, the `RateLimit-*` headers, `ALLOW`/`DENY` on
store failure, the shared `-` bucket, hashing long keys and header values, IPv6 /64, the
`X-Forwarded-For` rule, where it runs, the metrics, the abuse table.

```go
package ratelimit
type Policy struct{ /* limit, period, burst; ranges checked by NewPolicy */ }
type Decision interface{ decision() }                       // Allowed{Remaining, ResetAfter} | Limited{RetryAfter, ResetAfter}
type Store interface{ Acquire(ctx context.Context, key string, cost int) (Decision, error) }
func Middleware(l *Limiter, key KeyFunc, opts ...Option) func(http.Handler) http.Handler
type KeyFunc func(r *http.Request) (string, bool)          // ClientAddress(), ForwardedFor(n), Header(name), Client(), Principal()
```

Changes: the middleware is a plain `func(http.Handler) http.Handler`, global (before routing, no
`Context`) or per route (after context resolution). GCRA is the framework's own (about 40 lines);
`x/time/rate` (a token bucket, what the platform uses) is not GCRA and has no shared store. Remote
address from `r.RemoteAddr` parsed with `netip.ParseAddrPort`, never a header unless configured.

## 8. Per-client secrets

Contract: [`secrets.md`](../contracts/secrets.md). Verbatim in full (threat model, reference
grammar, client always from the caller, the `env` encoding, the `file` rules including symlink
resolution, `max-bytes`, strict UTF-8, the world-readable warning, the cache, `platform` failing
start-up until the platform has the endpoint, no value in any message).

```go
package secrets
type Ref struct{ /* store, name */ }                       // ParseRef("files:sftp-password")
type Result interface{ result() }                          // Found{Secret} | NotFound{} | Refused{Reason}
type Store interface{ Find(ctx context.Context, client fw.ClientID, name string) (Result, error) }
type Resolver struct{ /* instances, cache */ }
func (r *Resolver) Resolve(ctx context.Context, client fw.ClientID, ref Ref) (Result, error)
func (r *Resolver) Require(ctx context.Context, client fw.ClientID, ref Ref) (config.Secret, error)
```

Changes: errors are values (`ErrUnknownStore`, `*StoreError`). `Secret` cannot be zeroised
reliably in Go (strings are immutable, the collector moves memory); the contract's out-of-scope line
stays as for Java. The permission check uses `fs.FileMode` from `os.Lstat`/`os.Stat`.

## 9. Background work, metrics, logging

Contract: [`background-and-metrics.md`](../contracts/background-and-metrics.md).

```go
package background
type Options struct{ InitialDelay, Interval time.Duration; Gate Gate; Criticality fw.Criticality; DownAfter int; StuckAfter time.Duration }
type Func func(ctx context.Context, tick Tick) error       // ctx is cancelled at shutdown or on gate loss
type Tick struct{ Name string; Run uint64; Fence int64 }

package metrics
type Registry struct{ /* explicit, never global */ }
func (r *Registry) Counter(name, help string, labels ...string) (*Counter, error)
func (r *Registry) Gauge(name, help string, labels ...string) (*Gauge, error)
func (r *Registry) GaugeFunc(name, help string, f func() float64) error
func (r *Registry) Timer(name, help string, labels ...string) (*Timer, error)
func WritePrometheus(w io.Writer, s Snapshot) error        // exposition format 0.0.4, the contract's exact bytes
```

Verbatim: every loop rule (names, start after started actions, fixed delay without overlap, failures
logged and the loop continued, the gate's three outcomes, stop first at shutdown, health
`background:<name>` informational by default, its down rules, loop metrics); every metric rule
(naming and labels, idempotent creation, 200-character label values, the series bound and
`fw_metrics_dropped_series_total`, default buckets, built-in names, `route` is the pattern or
`<unmatched>`, `memory` and `noop` drivers, **the exposition bytes**, the endpoint off by default with
the bearer token compared as digests).

Changes: an `error` from a run is Java's exception (logged, the loop continues); a **panic** is
Java's `Error` (recovered, the loop ends, health down). A loop that ignores `ctx` past the drain is
abandoned and logged. Number formatting follows Java's output, checked by vectors (Go's
`strconv.FormatFloat(v, 'g', -1, 64)` differs in exponent style for very large and small values).
Not `prometheus/client_golang`: its default registry is global and its output bytes are its own
(decision 20). Logging is `slog`; library packages log only through the logger `Boot` gives them.

## 10. HTTP client

Contract: [`http-client.md`](../contracts/http-client.md). Verbatim: the whole behaviour (base URL
and path rules, placeholder encoding, header rules, `Send` never failing for a status, failure kinds
and messages without headers, bodies, tokens or query strings, no redirects, retries with full
jitter and `Retry-After`, every auth strategy, client credentials with skew `min(30s,
expires_in/10)`, single flight, one repeat after a 401 outside the retry count, the adapter's keys,
correlation-id propagation, metrics, `User-Agent`, gzip and deflate, the size limit).

```go
package httpclient                                          // no fw import: usable alone, as framework-httpclient
type Service struct{ /* *http.Client, base URL, options */ }
func NewService(name string, base *url.URL, opts ...Option) (*Service, error)
func (s *Service) Get(path string) *Request                  // Post, Put, Patch, Delete, Head, Do(method, path)
func (r *Request) PathParam(name, value string) *Request     // errors collected, returned by Send
func (r *Request) JSON(body any) *Request
func (r *Request) Send(ctx context.Context) (*Response, error)          // never an error for a status
func ReceiveJSON[T any](ctx context.Context, r *Request) (T, error)   // non-2xx is KindUnexpectedStatus
type Error struct{ Kind Kind /* Connect, Timeout, ResponseTooLarge, IO, Decode, Auth, UnexpectedStatus, Closed */ }
```

Changes: the engine is `net/http` (`CheckRedirect` returns `http.ErrUseLastResponse`, a per-attempt
context timeout, the response read through a counting reader); retries are a plain loop. fcsdk's
`ClientCredentialsProvider` is not reused here because its skew rule differs; the FlowCatalyst
module keeps using it for the platform client (section 11).

## 11. The FlowCatalyst module

Contract: [`flowcatalyst.md`](../contracts/flowcatalyst.md). Module `go/flowcatalyst`, the only
importer of fcsdk.

### Types

```go
package flowcatalyst

func Declare(app *fw.App) *Declared                        // flowcatalyst.* settings, migration set "flowcatalyst", the sync command
func (d *Declared) Client(b *fw.Boot) (*PlatformClient, error)

type PlatformClient struct{ /* wraps fcsdk client.FlowCatalystClient twice: with and without transport retry */ }
func (c *PlatformClient) Enabled() bool
func (c *PlatformClient) AccessToken(ctx context.Context) (config.Secret, error)
func (c *PlatformClient) CompleteJobInstance(ctx context.Context, id InstanceID, done Completion) error
func (c *PlatformClient) LogJobInstance(ctx context.Context, id InstanceID, level LogLevel, msg string) error

type Definitions struct{ /* event types, permissions, roles, subscriptions, connections, pools, scheduled jobs, processes, docs */ }
func (d *Declared) Definitions() *Definitions
func EventTypeOf[E event.Event](defs *Definitions, name, description string)   // code from the event's Type

// --- receivers -------------------------------------------------------------------------
type Receivers struct{ /* sealed when Serve builds the routes */ }
func (d *Declared) Receivers(b *fw.Boot, c *PlatformClient, l *locks.Locks) *Receivers
func OnDispatch[T event.Event](r *Receivers, h func(ctx context.Context, cx fw.Context, d Delivery[T]) (DispatchResult, error))
func (r *Receivers) OnDispatchPattern(pattern string, h func(ctx context.Context, cx fw.Context, d Delivery[json.RawMessage]) (DispatchResult, error))
func (r *Receivers) OnScheduledJob(code string, opts JobOptions, h func(ctx context.Context, cx fw.Context, f Firing) (JobResult, error))
func (r *Receivers) Routes() *httpfw.Routes

type DispatchResult interface{ dispatchResult() }          // Ack{} | Defer{Delay} | Reject{Reason}
type JobResult interface{ jobResult() }                    // Success{Value} | Failure{Message}
func Once[R any](ctx context.Context, d DeliveryMeta, db *database.DB, handler string,
	work func(ctx context.Context, tx *database.Tx) (R, error)) (OnceResult[R], error)   // Ran{Value} | AlreadyDone
```

### Verbatim

Every setting and default; the client as the application's service account; the `flowcatalyst` and
`outbox` health checks, informational by default, and the outbox query (status 0 or 9, the
application's clock, strictly older than `stale-after`); definitions, their validation (stricter
than the platform's, as built), the category order, `DRY_RUN` making no requests, sync exit codes
0 / 1 / 2, sync off on boot; **the receivers in full** (prefix, routes, signature rules and refusal
codes and the 401 body, per-receiver secrets and fallbacks, `require-signature`, the bearer gate,
dispatch headers and patterns, envelope versus data-only, matching order, every result with its
status and body, reasons cleaned and cut to 500 characters, 503 for an error, a timeout or no
result, 422 for no handler, `once` on `fw_dispatch_once` with its retention purge; scheduled jobs:
validation, 202 at once, 404 for an unknown code, the overlap lock `scheduled-job:{code}` with lease
and refresh, every outcome and its completion body, the 10 000-character cut, completion retried 4
calls in all with doubling delay and the transport retry off for those calls, the log hook; a
receiver with no handler answers 404 before any signature work; `receivers.enabled: false` mounts
nothing; the start-up checks).

### Changes

- **Typed dispatch handlers.** `OnDispatch[OrderPlaced]` takes its pattern from the event type and
  decodes `data` before the handler runs; a payload that does not decode answers `Reject` (decision
  23, as Rust decision 14). Untyped handlers for `prefix:*` and `*` get `json.RawMessage`.
- **Timeouts.** The handler runs with `context.WithTimeout`; on expiry the response is 503 at once
  and the goroutine is left to finish, tracked so shutdown waits for it within the drain. A
  scheduled job's overlap lock is held until the handler really returns (the Java rule).
- **No 405 for an unused receiver.** Routes are built after registration in `build`, so a receiver
  with no handler can be left unrouted (404 for every method); Java answers `GET` with 405. Recorded
  as an allowed difference, as for Rust, unless the owner prefers identical behaviour.
- **Signature**: ported from the Java code (not fcsdk's `webhook`, whose rules differ); time from
  `fw.Clock`.

### The receiver principal (owner ruling, 2026-10-12)

A use case run by a dispatch receiver always has a principal: the application's **service
principal, kind Service**, the one its credentials identify. The receiver builds `fw.Context` with
that principal, the client from `X-FlowCatalyst-Client` (or `_platform`), the application, the
envelope's correlation id, **causation = the dispatch job id** and execution id = the job id. So the
job id reaches the events' `causationId` and the audit row. *2026-10-14:* Java now does the same
(flowcatalyst contract section 4b: the id is the `sub` of the client-credentials token; vector
`conformance/receivers/context.json`); `fw.PrincipalKind` is on `Context` since G2 (design
section 7, decision 22).

### Reuse

fcsdk `client` and `auth.ClientCredentialsProvider` (wrapped), `sync` (extended with connections,
scheduled jobs, docs in the platform repository), `scheduledjobs.Envelope` as the reference for the
firing's fields.

## 12. Sessions and login (M4c)

**The contract is written:** [`contracts/sessions-and-login.md`](../contracts/sessions-and-login.md)
(2026-10-13, Java built against it), language-neutral and JSON-first as decision 29 recommended. Go
implements it like any other contract. It settles what this section used to sketch:

- **The application is the backend-for-frontend**: `GET /auth/login?returnTo=`, `GET
  /auth/callback`, `GET /auth/session`, `POST /auth/logout` (contract section 4). Difference from the
  earlier sketch: `/auth/session` always answers `200`, with `authenticated: false` when there is no
  session (contract ruling 3). In Go the session endpoint is still a typed operation, so the SPA's
  client is generated.
- **Sessions**: `__Host-fw_session`, drivers `database` (default), `redis`, `cookie` (AES-256-GCM
  with the contract's exact sealed format and key list, so a Go and a Java replica can share keys),
  idle and absolute expiry, rotation on login, revocation on logout; the session JSON and the
  `fw_session` DDL are in the contract (section 6).
- **Relying party**: discovery, JWKS with refresh on an unknown `kid` at most once a minute, the
  ordered id_token checks (RS256 only, `token_use` absent, `nonce`, `azp`) and the access-token
  checks (contract section 5). A public client by default, confidential when `auth.client-secret`
  is set. fcsdk's `auth.OAuthClient` and `auth.TokenValidator` can be reused if they pass the
  contract's checks; `lestrrat-go/jwx/v2` must be pinned to RS256 (no algorithm from the header).
- **No platform tokens in the session, no refresh** (contract ruling 1).
- **CSRF**: the platform's F16 rules as the contract states them (`Sec-Fetch-Site`, else `Origin`,
  neither refused, `403 CROSS_ORIGIN_REQUEST`; `415` for non-JSON bodies). `http.CrossOriginProtection`
  alone is not enough: it lets a request with neither header through.
- **401 versus 302**: contract section 7.4 (a `302` only for an HTML navigation).
- The real `ContextResolver` builds `fw.Context` with the principal, the selected client and the
  permissions (the core contract's `Context` gained `permissions` in M4c).

## 13. UI: Nuxt UI on Vue, strict TypeScript

Owner ruling 2026-10-13; the whole design is [`ui.md`](ui.md). **Since 2026-10-14 the UI package is
shared with the Java framework** (decision 31, decided): built first against the Java backend, and
Go implements the same two contracts ([`../contracts/static-assets-and-spa.md`](../contracts/static-assets-and-spa.md),
[`../contracts/openapi.md`](../contracts/openapi.md)) to serve it. In short:

- **Stack:** Nuxt UI v4 through its Vite and Vue plugins (no Nuxt, no Nitro), Vue 3, vue-router,
  Vite, pnpm, TypeScript with the platform's strict settings plus `exactOptionalPropertyTypes` and
  `verbatimModuleSyntax`, vue-tsc, typescript-eslint type-aware, no `any` (decision 7).
- **Packages:** `@flowcatalyst/ui` (components, partials and pages that extend Nuxt UI, the app shell,
  composables ported from the platform SPA, the theme preset mapping `ui-kit/theme/tokens.css`),
  `@flowcatalyst/api-runtime`, `@flowcatalyst/gallery`; an application's SPA in its Go module's `web/`, embedded.
- **Typed spine:** operations (section 5) → OpenAPI 3.1 → hey-api types, SDK and Zod schemas →
  `UForm` (decision 19). The Go parse function stays the authority.
- **Not implemented in Go:** the Datastar adapter and the `ui-kit/fixtures` goldens (decision 27).
  The kit's specs and tokens remain the reference; conformance is a gallery with Playwright, axe,
  approved screenshots and a CSP check (decision 30).
- Vue files are shown as files in the guide, never inline in a page: Vue's double-brace
  interpolation is Liquid syntax to the site.

## 14. Cache (M5)

fcsdk's `cache` (memory, `postgrescache`, `rediscache`, typed `Get`/`Set`/`GetOrSet`, a required TTL)
is the starting point once the Java cache contract exists. Not before.
