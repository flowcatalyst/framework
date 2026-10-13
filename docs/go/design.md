# Go framework: design (draft 1, 2026-10-13)

Status: a design. Nothing is built; building starts after the owner has reviewed this page and
taken the decisions in section 9. Package and module names are placeholders (implementation plan,
open question 6).

The owner decided on 2026-10-13 to build a Go framework alongside the Java one. Later a part of a
business application is built on both and compared. The Rust and TypeScript frameworks are parked;
the FlowCatalyst platform stays in Go. The Go framework is a further implementation of the
contracts in [`../contracts/`](../contracts/), written for Go rather than translated from Java:
applications built on either must be interchangeable to the platform (the same outbox rows, event,
audit and dispatch-job payloads, TSIDs, receiver behaviour, config and secret semantics and error
envelope), and a Go service must be able to take over a Java service's database.

| Document | Contents |
|---|---|
| [`gap-analysis.md`](gap-analysis.md) | every Java capability against `pkg/fcsdk` and the Go platform: covered, partly, missing, and where the code is |
| this page | principles, where Go differs from Java, module layout, toolchain, reuse of fcsdk and the platform, SQL, testing, the owner decisions |
| [`capabilities.md`](capabilities.md) | capability by capability: the Go types, what is shared verbatim with the Java contract, what changes, open rulings |
| [`orders-showcase.md`](orders-showcase.md) | the orders example end to end, and what Go checks where |
| [`milestones.md`](milestones.md) | G0 to G7, testing and conformance against the Java framework, effort and risk |

Inputs read: [`framework-plumbing.md`](../framework-plumbing.md) (rules, rulings, and section 3a, the
owner's 2026-10-08 rules for a Go implementation), [`implementation-plan.md`](../implementation-plan.md),
every contract, the guide and STATUS, the Rust design (the model for these pages), `conformance/`,
`ui-kit/`, and in `flowcatalyst-go`: `go.mod`, `.golangci.yml`, `Makefile`, `sqlc.yaml`,
`docs/sqlc.md`, all of `pkg/fcsdk`, `internal/ui`, `internal/platform/shared/repocommon`,
`internal/testpg`, `tools/analyzer`. go-jet, sqlc, NilAway, go-yaml, templ and datastar-go were
checked on GitHub on 2026-10-13 (section 5 and section 8 say which facts were verified and how).

## 1. Principles, and where Go differs from Java

The framework's design rules (plumbing section 3) hold: contracts first; no reflection and no
global state; fail at start-up; three lifetimes (application, request or job, transaction); errors
are values; the tenant is always present. Java enforces most of them at start-up, Rust many in the
compiler. Go's compiler checks less, so Go enforces more in **lint and small analyzers**, as
plumbing section 3a already rules.

### 1.1 One way to do each thing (owner ruling a)

Go's strength is that most problems have one obvious solution. The framework keeps it so:

| Need | The one way | Not |
|---|---|---|
| HTTP | `net/http`, the Go 1.22+ `ServeMux` patterns (`"POST /orders/{id}/lines"`), handlers as functions | chi, gin, echo, huma |
| logging | `log/slog`, a `*slog.Logger` handed out by the application | a logging facade, a global logger |
| cancellation, deadlines | `context.Context` | framework tokens |
| concurrency | goroutines, `errgroup`, channels where they fit | futures, executors, actor libraries |
| errors | values: `error`, `errors.Is`/`errors.As`, `%w` | panics for expected outcomes |
| optional settings of a constructor | functional options (`locks.WithDatabases(dbs)`) | config structs with zero-value traps, builders with ten setters |
| wiring | one explicit build function, typed handles (section 1.2) | a DI container, `reflect`, struct tags, code generation for wiring |
| static SQL | sqlc over pgx (section 5) | an ORM |
| dynamic SQL | go-jet as the typed builder, the `Filter` accumulator as fallback (section 5) | string concatenation with values |
| JSON | `encoding/json/v2` | third-party JSON libraries |
| templates | templ | `html/template` for kit components |

The standard library comes first. A dependency is added when it is the platform's existing choice
(pgx, go-redis, templ, datastar-go, sqlc, embedded-postgres) or when writing it ourselves would be
worse (YAML). `golang.org/x/sync` (errgroup) and `golang.org/x/net/html` count as standard.

### 1.2 No container: an explicit build function and typed handles (owner ruling a)

Java's `AppBuilder` is a typed map from `Class` to provider; Rust replaced it with a typed build
function. Go does the same, because a container in Go means `reflect` or `any` and a run-time
failure where the compiler could have helped.

- **Declaring returns typed handles.** Each capability package has a `Declare` function that
  registers its settings, instance sections and driver key schemas, migration sets, definitions and
  commands, and returns a handle. A handle is the proof that a setting was declared, so reading it
  after configuration cannot fail.
- **Building is plain code.** The application writes one `build` function that constructs its
  services in order, passing each its dependencies as arguments, and returns what to serve. A
  missing dependency is a compile error. There is no `Get(type)`.
- **Lifecycle is a value.** `build` receives a `*fw.Boot` through which it registers health checks,
  background loops, started actions, shutdown actions and commands. Nothing runs until `build` has
  returned.

```go
func main() {
	app := fw.New("orders-demo")                 // declaring
	web := httpfw.Declare(app)                   // typed handles
	db := database.Declare(app)
	lk := locks.Declare(app, locks.Memory(), locks.Postgres())
	fc := flowcatalyst.Declare(app)
	ord := orders.Declare(app)                   // migration set "orders", event types, permissions
	os.Exit(app.Launch(context.Background(), os.Args[1:], func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, db) // pings, health database:<name>, close at shutdown
		if err != nil {
			return nil, err
		}
		if err := b.Migrate(ctx, dbs); err != nil {
			return nil, err
		}
		lks, err := locks.Build(ctx, b, lk, locks.WithDatabases(dbs))
		if err != nil {
			return nil, err
		}
		runner := usecase.NewRunner(b, dbs.Default())
		platform, err := fc.Client(b)
		if err != nil {
			return nil, err
		}
		svc := orders.New(b, ord, runner, dbs.Default())
		rcv := fc.Receivers(b, platform, lks)    // signature checks, completion through the platform client
		svc.Receive(rcv)                         // registers dispatch and scheduled-job handlers
		return httpfw.Serve(b, web, svc.Routes(), rcv.Routes()), nil
	}))
}
```

`Launch` runs: configure (read the files, the environment and `secrets.local.yaml`, validate every
declared key and every instance section, collect every problem) → build → the command named by the
first argument, or start (started actions, then loops, then the server binds) → run until SIGTERM
or SIGINT (`signal.NotifyContext`) → shutdown (section 1.6). Declaring after `Launch` has begun
panics: it is a programming error, caught by the first test that starts the application.

**What is lost against Java**, as in Rust: problems found while building (a database that does not
answer) stop at the first failing component; configuration problems, the large majority, are still
reported together. `AppBuilder.override` has no equivalent: an application's `build` takes the
collaborators a test wants to replace as parameters (usually a small `Deps` struct with defaults),
and the test passes fakes. Nothing can reach production by accident because there is nothing to
switch on.

### 1.3 Small interfaces, concrete types, functional options

- **Interfaces are declared where they are consumed and are small.** A driver contract such as
  `locks.Store` has the three operations the contract names; the user-facing API on top (`*locks.Lock`
  with `Acquire`, `With`) is a concrete type, so shared logic (waiting, refreshing) is written once,
  as in Java's "store versus user-facing API".
- **Constructors return concrete types**, take required parts as parameters and optional parts as
  functional options (`func(*options)`), and validate in the constructor, returning an error.
- **Generics where the type is known at compile time**: typed ids, use cases, repositories, plans,
  settings handles. Not for drivers chosen by configuration (an interface value).
- **No `init()` with side effects, no package-level mutable state.** Package-level `var`s hold
  immutable descriptors only (setting declarations, event types, error codes).

### 1.4 Errors and outcomes

- **Failures are `error` values.** Each capability exports sentinel errors for conditions callers
  test (`errors.Is(err, locks.ErrStoreUnavailable)`) and one typed error with a kind for the rest
  (`*httpclient.Error` with `Kind`). `errorlint` (already in the platform's profile) keeps wrapping
  honest.
- **Expected outcomes are values, not errors.** An outcome with no data is a named integer type
  with constants, checked by `exhaustive` with `default-signifies-exhaustive: false`; an outcome that
  carries data is a sealed interface (an unexported marker method) checked by `gochecksumtype`
  (plumbing 3a). `locks.Acquired`/`locks.Busy`, `receivers.Ack`/`Defer`/`Reject`,
  `ratelimit.Allowed`/`Limited`, `secrets.Found`/`NotFound`/`Refused`.
- **The use-case envelope returns `(usecase.Committed[E], error)`** and the error is always a
  `*usecase.Error` (kind, code, message, details): the contract's `UseCaseResult` with Go's two
  return values. This follows `usecaseop`, which itself replaced fcsdk's older `usecase.Result[E]`
  sum type and its sealed token with the sealed `Plan` ("the Go-idiomatic replacement",
  `usecaseop/doc.go`). `Committed[E]` has unexported fields, so only the runner makes a non-zero one.
- **Panics are programming errors.** A panic inside a handler, a use-case phase, a job or a loop is
  recovered at that boundary and treated as the contract treats an uncaught exception (500, 503,
  `INTERNAL` with the step's code, a failed run). Nothing else panics after start-up.

### 1.5 Concurrency: goroutines, errgroup, context (owner ruling b)

Java runs blocking code on virtual threads and stops work by interrupting it. Go is the same model:
blocking code on goroutines, stopped cooperatively, here through `context.Context`. So most of the
contract's mechanics carry over unchanged, which is not true for Rust.

| Java mechanism | Go mechanism | Consequence |
|---|---|---|
| a virtual thread per request or job | a goroutine per request (`net/http`) or per job (started through the application's `errgroup` or a tracked `WaitGroup`) | blocking calls are fine |
| `Thread.interrupt()` on timeout or lock loss | the `ctx` passed to the work is cancelled | the same cooperative semantics: work that ignores `ctx` keeps running, as a Java thread that ignores interrupts does; the contract's rules for that case (the scheduled-job lock stays held until the handler returns) apply unchanged |
| `InterruptedException` → `INTERNAL / INTERRUPTED` | `ctx.Err()` while waiting between attempts → `INTERNAL / INTERRUPTED` | parity; unlike Rust, Go keeps this code |
| JVM shutdown hook | `signal.NotifyContext` for SIGTERM and SIGINT cancels the root context | the same drain order (section 1.6) |
| `InstantSource`, monotonic `LongSupplier` | `fw.Clock` (wall time) on the builder; durations from `time.Since` on the monotonic reading Go keeps inside `time.Time` | tests inject a fake `fw.Clock`; for monotonic waits, `testing/synctest` (standard since Go 1.25) runs time-dependent tests with a fake clock and no sleeping |

No framework-invented async abstraction: a capability that runs something in the background starts
a goroutine through `Boot` (so it is tracked and stopped), and an API that waits takes a `ctx`.

### 1.6 Graceful shutdown

One root context and one `errgroup.Group` per application. On SIGTERM, SIGINT or
`Running.Shutdown()`:

1. `/health/ready` answers 503 from this moment (http contract section 8).
2. Background loops are cancelled **first** (background contract section 1); each gets up to
   `app.shutdown.drain`, then is logged at WARN as an overrun (a goroutine cannot be killed; it is
   abandoned and the process exit ends it).
3. `http.Server.Shutdown` stops accepting and waits up to `http.shutdown-grace` for in-flight
   requests; a request arriving on a kept-alive connection during the drain gets `503 UNAVAILABLE`
   with `Connection: close` (a framework middleware watching the draining flag).
4. Shutdown actions registered during build run in reverse registration order, each bounded by
   `app.shutdown.drain`; a failure is logged and does not stop the rest.
5. Pools close (`pgxpool.Pool.Close`, `redis.Client.Close`) through those same actions.

A command run by `Launch` builds the services, runs, and closes them the same way; it never starts
loops or the server.

### 1.7 Zero values, nil, and what the compiler cannot check

Go's two traps for this framework are the **zero value** (every type has one, including types with
unexported fields, so "only the runner can make a `Committed`" really means "only the runner can
make a non-zero one") and **nil** (maps, slices, pointers, interfaces, and the typed-nil-in-an-
interface case). The framework's rules:

- **Small value types for every id and code**: `fw.ClientID`, `fw.PrincipalID`, `fw.ApplicationCode`,
  `fw.CorrelationID`, `usecase.Name`, `event.Type`, `usecase.ErrorCode`, `locks.Key`, `secrets.Ref`,
  and typed aggregate ids `id.ID[K]`. Each is a struct with one unexported field, built by
  `Parse(string) (T, error)` or, for literals in source, `Must(string) T`. Each has `IsZero()`, and
  every framework API that takes one rejects the zero value (an `INTERNAL` problem, never a
  silent default). `Context` cannot be built without a client.
- **No nil in signatures where a value will do.** Constructors return values or non-nil pointers
  with an error; optional results are `(T, bool)`; optional fields of commands and events are
  pointers only at the JSON edge, converted to domain types once.
- **NilAway** (Uber's nil-flow analyzer) runs on the framework and on example domain packages, as
  plumbing 3a rules; `exhaustive` and `gochecksumtype` cover what Java's sealed types and Rust's
  enums give for free.
- **Literal checks at lint time, not compile time.** Rust checks event types, error codes,
  permission codes and route patterns in `const` evaluation. Go cannot. Each `Must` constructor
  panics on a bad literal at package initialisation, so any test that imports the package fails;
  and a small analyzer suite, `fwvet` (the approach of the platform's `tools/analyzer/uowseal` and
  `idconv`), checks the literal arguments of `Must*` calls and route patterns at lint time, so the
  editor and CI report them before a test runs.

### 1.8 What does not move

`Context` is a value passed explicitly as a parameter, never stored in `context.Context` and never
in a package variable (decision 8). The `slog` attributes of the context (correlation id, client,
principal, application, execution id) are added by `cx.LogAttrs()` and by the framework's request,
job, loop and use-case loggers, so every log line in them carries them. No global metrics registry,
no global HTTP client, no global configuration, no `slog.SetDefault` in library code.

## 2. Modules, licence, toolchain

### 2.1 Where it lives: `go/` in this repository

As for Rust (Rust design 2.1): one copy of the contracts, the `ui-kit/` fixtures and `conformance/`
read in place by the tests, one documentation site. The Java and Rust builds are unaffected: `go/`
is neither a Maven module nor a Cargo crate, and the Maven and Cargo builds ignore it.

### 2.2 Three Go modules

| Module | Path in the repository | Imports | Why separate |
|---|---|---|---|
| `github.com/flowcatalyst/framework/go` | `go/` | stdlib, `x/sync`, `x/net`, pgx v5, go-redis v9, `go.yaml.in/yaml/v3`, embedded-postgres (test helper package only) | the framework; an application that never uses FlowCatalyst or the UI kit does not get them in its module graph (plumbing 9a: the framework does not depend on FlowCatalyst) |
| `github.com/flowcatalyst/framework/go/flowcatalyst` | `go/flowcatalyst/` | the core module, **fcsdk** | the only module that imports the platform SDK, as `framework-flowcatalyst` is the only Java module that imports the Java SDK |
| `github.com/flowcatalyst/framework/go/ui` | `go/ui/` | templ, datastar-go, `x/net/html`, brotli | **the UI kit as a shared Go module**, imported by both the framework and the platform (section 4.2); kept free of the framework so the platform can import it without a module cycle |

Inside the core module, packages give what Java's module split gives: a Go build compiles only the
packages it imports, so an HTTP-only application compiles no pgx even though pgx is in `go.sum`.
That is why there is no Go equivalent of `framework-bom`, the Java `framework-http` /
`framework-http-jetty` split, or the Rust driver features: a driver is a package
(`locks/redislock`) and is compiled only when imported.

Package layout of the core module (names placeholders):

```
go/
  go.mod                     module github.com/flowcatalyst/framework/go, go 1.27
  fw/                        App, Declare*, Boot, Launch, Serve, Context and ids, Clock, Ids (TSID), health, commands
  config/                    Setting[T], handles, sources, env names, Secret
  database/                  instances (pgxpool), Connect, the migration runner, DB, Tx, Filter
  redisfw/                   named go-redis clients
  locks/  locks/dblock/  locks/redislock/      Lock, Store, With, Gate; drivers
  usecase/                   UseCase, Define, Runner, Plan, Fresh/Loaded, Repository, Error, events, outbox writer, dispatch jobs
  event/                     Type, Metadata, the Event interface
  httpfw/                    routes on ServeMux, middleware, envelope, JSON, validation, health, server, drain
  ratelimit/  ratelimit/redisrl/
  secrets/                   Resolver, Ref, env and file stores
  httpclient/                the HTTP client library (no fw import) and httpclient/module (the adapter)
  background/  metrics/
  fwtest/                    embedded PostgreSQL and Redis per test binary, a database per test, fixed clocks
  sqlfw/                     the `fw sql` command: check (sqlc vet against a migrated database), jet generation
  cmd/fwvet/                 the analyzer suite (section 1.7)
  internal/conformance/      the conformance tests (read ../conformance)
  examples/locks-demo/, examples/orders-demo/
```

### 2.3 Licence

**Apache-2.0** for all three modules, like the Java framework, the platform repository and the Rust
framework. The dependencies are permissive (pgx MIT, go-redis BSD-2, templ MIT, datastar-go MIT,
embedded-postgres MIT, go-yaml Apache-2.0 and MIT, go-jet Apache-2.0, sqlc MIT as a tool). The
exception is **fcsdk, which is MPL-2.0** ([gap analysis](gap-analysis.md) 4.3): legal to import from
Apache-2.0 code, but against the Rust framework's no-MPL rule. Decision 3.

### 2.4 Toolchain, lint, formatting

- **Go 1.27**: `go 1.27` in every `go.mod`, the directive `flowcatalyst-go` uses; the machine this
  was written on has `go1.27.2`. Bump deliberately with the platform.
- **golangci-lint v2** with the platform's `.golangci.yml` as the base (errcheck, govet,
  staticcheck, unused, bodyclose, gocritic, gosec, misspell, revive, whitespace, errorlint,
  modernize, exhaustive with `default-signifies-exhaustive: false`; formatters **gofumpt** and
  goimports), plus:
  - `gochecksumtype` (sealed interfaces, plumbing 3a);
  - `forbidigo` in library packages for `time.Now`, `time.Since` outside `fw.Clock`, `os.Getenv`
    and `os.LookupEnv` outside the config package, `slog.Default`/`slog.SetDefault`,
    `context.Background` and `context.TODO` outside `main` and tests, `reflect` outside tests and
    the JSON edge: the Go form of "time from an injected clock" and "no global state";
  - revive's `exported` rule **on** (unlike the platform): these are public library packages.
  Build golangci-lint with the local Go, as the platform does.
- **NilAway** as a separate CI step over the framework's packages and the examples' domain packages.
  It has no tagged releases (checked on GitHub 2026-10-13: none; last push 2026-10-09), so it is
  pinned by pseudo-version in a `tools` file. Its false-positive rate on this code is unknown until
  G1; the first milestone that runs it records the suppressions it needs.
- **`fwvet`**: the framework's analyzers (section 1.7), run in CI and by `go vet -vettool`.
- `go test -race ./...` everywhere; `govulncheck` in CI.

## 3. Shared with the Java framework, verbatim

The table of Rust design section 3 applies unchanged: outbox (the `V0001` migration byte for byte,
`V0002`, every column and payload field of event, audit and `DISPATCH_JOB` rows), TSID layout and
encoding, the error envelope and statuses, HTTP routing and correlation rules, health JSON, body
limit and drain, receiver signatures and responses, config rules, secret references, the framework
tables (`fw_schema_history`, `fw_lock`, `fw_lock_fence`, `fw_dispatch_once`), the Redis lock and
rate-limit keys and Lua scripts, metric names and exposition bytes, Datastar SSE bytes, and the
`ui-kit/fixtures` goldens. Also the **use-case envelope and unit of work** (validate, authorize,
execute returning a plan; one transaction; the failure table), the **authorization model** (`Grant`,
`Authorize.PUBLIC`, permissions from the principal once M4c lands) and the **receiver principal
ruling** (capabilities section 11).

**How Go reads the shared files.** `//go:embed` cannot reach outside its module directory, so a
statement the Go code sends (`conformance/sql/outbox/insert.sql`, `conformance/sql/locks/*.sql`,
`conformance/lua/locks/*.lua`, the Java migration files) is **copied** into the Go package that uses
it, and a conformance test compares the copy with the shared file **byte for byte**. A `make
go-sync-shared` target refreshes the copies. The vectors themselves (`conformance/*.json`) are read
at test time with `os.ReadFile("../../conformance/...")`, which needs no copy. The alternative, a
`go.mod` at the repository root, would let `embed` reach `conformance/` directly but would put the
Java tree, `rust/` and `ui-kit/` into the Go module's zip (decision 10).

Placeholders: the shared statements use JDBC `?`; Go numbers them `$1`, `$2`, ... once at package
initialisation, as the Rust driver does (`fw_db::number_placeholders`).

## 4. Reuse of fcsdk and of the platform (owner ruling e)

The [gap analysis](gap-analysis.md) is the detail; the decisions are:

### 4.1 fcsdk

| fcsdk package | Used by the framework? | How |
|---|---|---|
| `client`, `auth` (client credentials) | **yes** | wrapped by `go/flowcatalyst`'s `PlatformClient`; fcsdk types never appear in framework signatures |
| `sync` | **yes**, extended | the framework's `Definitions` map onto `sync.DefinitionSet`; the missing categories (connections, scheduled jobs, docs) are added to fcsdk in the platform repository first |
| `auth` (`OAuthClient`, `TokenValidator`) | **yes**, in G5b | the OIDC relying party for sessions and login |
| `cache` | later | when the cache contract exists (M5) |
| `scheduledjobs`, `webhook` | **no**, ported | the contract's rules differ (section 4 of the gap analysis); the framework ports them, taking the envelope type and the HMAC from them as a reference |
| `usecase`, `usecaseop`, `usecasepgx`, `outboxpgx`, `lock`, `tsid` | **no** | the framework owns its write path, outbox and locks (they must pass the shared vectors); `usecaseop`'s envelope is the model for the framework's use-case shape |

How the framework module gets fcsdk (decision 4): fcsdk is today a package tree in the platform's
module, MPL-2.0, untagged, at a module path that does not match its Git remote. Recommended: make
`pkg/fcsdk` (or a move to `clients/go-sdk`, beside the other SDKs) **its own Go module** in the
platform repository, tagged by the platform's release tooling like the other SDKs. Until then,
development uses a `go.work` file (not committed) pointing at the sibling checkout, which is how the
TypeScript spike plan wired its Go slice.

### 4.2 The UI kit becomes a shared Go module (owner ruling d)

`internal/ui/kit` imports only templ; `htmlnorm` only `x/net/html`; `ds` only datastar-go;
`compress` only brotli; `assets` only `compress`. They move to `go/ui` in this repository
(`github.com/flowcatalyst/framework/go/ui`), next to the specs and fixtures they implement:

- the conformance test reads `../../ui-kit/fixtures` in place, so the platform's fixture-vendoring
  step disappears (the asset build, Tailwind and the sprite stay a script, now in this repository);
- the platform imports `go/ui` and keeps `shell` and `guard` (they import platform auth);
- JTE (Java) and templ (Go) are then two implementations of one kit in one repository, both tested
  against one set of goldens.

The move needs the platform to resolve this repository as a module, which needs it pushed (or a
`go.work`/`replace` for local development). Nothing is pushed until the owner says so; the platform
keeps its copy until then, and the first commits of G5a are a copy with history noted, not a move.

### 4.3 Patterns copied from the platform

sqlc's configuration style (type overrides for timestamps and JSON, `emit_pointers_for_null_types`,
`emit_db_tags`); the `testpg` fixture (embedded PostgreSQL per test binary, `freePort`, template
databases); `repocommon.Filter`; the lint profile; the analyzer approach.

## 5. SQL: sqlc for static queries, go-jet or `Filter` for dynamic ones (owner ruling c)

### 5.1 Static queries: sqlc over pgx

The contract's checked SQL (queries in `.sql` files, typed generated functions, committed output, a
check mode) is what sqlc is; the Java generator was modelled on it. sqlc generates from the
**migration files** by static analysis, so **generation needs no database**. The framework adds:

- `fw sql schema`: writes the migration sets of the framework packages an application uses (outbox,
  locks, `fw_dispatch_once`) next to the application's own, so sqlc's `schema:` sees every table;
- `fw sql check`: starts an embedded PostgreSQL 18, applies every set **with the framework's own
  runner** (the history table the application really gets), then runs `sqlc vet` with the
  database configured (its `sqlc/db-prepare` rule prepares every query against the real schema) and
  `sqlc diff` (generated code up to date). Static analysis can be wrong where PostgreSQL is
  subtle; the prepare step catches it. CI runs the check, as the platform runs `sqlc-verify`.

What sqlc does not do that the Java generator does: overrides **in the query** (`AS "id: OrderId"`,
`AS "total!"`). sqlc's overrides live in `sqlc.yaml`, per column or per database type, and its
`sqlc.narg`/`sqlc.arg` macros mark parameters. Typed ids therefore go in `sqlc.yaml`, one entry per
column, the platform's practice (its `sqlc.yaml` lists every id column explicitly and explains why
no wildcards). Whether sqlc's static engine marks every column of an outer-joined table nullable
(the Java contract's rule) is **not verified**; G2 tests it on the orders read model and records the
answer.

### 5.2 Dynamic list queries: go-jet as the typed builder, `Filter` as fallback

Ruling c names go-jet as the supported typed builder and a dependency-free `Filter` (the platform's
`repocommon.Filter`) as fallback. An honest comparison:

| | go-jet | `Filter` |
|---|---|---|
| what it is | a typed SQL builder: generated table and column types, `SELECT(...).FROM(...).WHERE(cond)` | about 60 lines: an accumulator of `col = $n` conditions and their arguments, `Where()`, `Args()` |
| column and type errors | **compile errors** (columns are generated identifiers with SQL types) | at run time; caught by a test that runs the query |
| generation | **needs a live database**: `jet -dsn=...` reads the information schema. The framework runs it like `fw sql check`: embedded PostgreSQL, the framework's migration runner, then `jet`; output committed; a check mode regenerates and diffs | none |
| a second generator | yes: jet's model and table code beside sqlc's for the same tables | no |
| pgx | the builder's output (`stmt.Sql()` gives the text with `$n` and the arguments) runs on pgx directly; **jet's own result mapper (QRM) needs `database/sql`** on the default branch; a `pgx` branch with a pgx v5 executor (`pgxV5.Query`, `pgxV5.Exec`) exists and is described in the README as experimental (checked 2026-10-13: 22 commits ahead of master, 0 behind, last merge of master 2026-10-12) | runs on pgx; rows scanned with `pgx.CollectRows` |
| reflection | QRM maps rows by reflection; used as a builder only, the framework scans with pgx (`pgx.RowToStructByName`, also reflection, the platform's practice) | none of its own |
| maintenance | Apache-2.0; 3 817 stars; releases v2.14.0 (2025-10-20), v2.14.1 (2026-01-22), v2.15.0 (2026-05-21), v2.16.0 (2026-08-31), v2.16.1 (2026-10-06); last push 2026-10-12; not archived; requires Go 1.24 (all from the GitHub API, 2026-10-13) | ours |
| dependencies | its `go.mod` requires `lib/pq`, the MySQL driver and `mattn/go-sqlite3` (cgo) "used by jet generator"; they enter the application's module graph but are compiled only if imported (which runtime packages import them was **not verified**) | none |
| injection safety | values are always bound | values are always bound; column names are strings, so the rule is that they are constants in source, never input (an `fwvet` check) |
| reads like | Go code that resembles SQL | SQL text with conditions appended |

**Design:** the framework supports both in one well-defined place: **dynamic list and search
queries of read models**. Static queries are always sqlc. In the read model, jet builds the
statement and pgx runs it (`sqlfw.Query(ctx, db, stmt, scan)`), never jet's QRM, so there is one
driver and one scanning style. `Filter` (in `database`) is used by the framework's own code (it may
not depend on jet) and by an application that does not adopt jet. The orders showcase writes the
same list query both ways ([`orders-showcase.md`](orders-showcase.md) section 6) so the comparison
slice can judge them on real code. Decision 12.

## 6. Testing and isolation (owner ruling h)

- **PostgreSQL: embedded, never shared.** `fwtest` boots **one embedded PostgreSQL 18 per test
  binary** (`fergusstrange/embedded-postgres`, default version 18.3.0, the platform's choice) on a
  free port taken from `127.0.0.1:0`, in a temporary directory, removed at the end (the platform's
  `testpg.RunMain` pattern). It migrates a **template database** once and gives each test a fresh
  database created from it (`CREATE DATABASE ... TEMPLATE`, the platform's `CloneDB`), so tests run
  in parallel without the platform's "no table-wide counts" discipline. It never connects to the
  development database on 15432, never binds 8080, and refuses a `FW_TEST_DATABASE_URL` that points
  at port 15432. The first run downloads the PostgreSQL binaries (Maven Central, as the platform's
  fixture does) and caches them.
- **No build tag for integration tests.** The platform's `integration` tag makes a plain `go test`
  skip them silently (a lesson recorded in the platform's notes); the owner's rule is "never skip
  silently". Database tests always run; they are slower, not hidden.
- **Redis: a real server.** There is no embedded Redis for Go. Lock and rate-limit tests need real
  Lua semantics (the shared scripts), so `fwtest` starts `redis-server` (or `valkey-server`) from
  `PATH` on a free port per test binary, or uses `FW_TEST_REDIS_URL`; when neither exists the tests
  **fail** with the instruction to install one. miniredis (an emulation whose Lua is gopher-lua)
  is allowed for unit tests of code above the driver, never for the contract suite or the
  conformance tests. Decision 5.
- **HTTP** tests use `httptest` (in process, no socket); the few real-socket tests (body limit,
  drain, bind failure) bind `127.0.0.1:0`.
- **Time**: a fake `fw.Clock`; `testing/synctest` for code that waits.

## 7. Contract wording and findings

- **The receiver principal.** The ruling (owner, 2026-10-12, Rust capabilities 11) is that a use
  case run by a dispatch receiver has the application's service principal (kind Service) and the
  dispatch job's id as its cause. The Java receiver as built does **not** do this:
  `DispatchReceiver` builds `new Context(client, null, application, correlationId, null, jobId)`,
  so principal and causation are empty and the job id is the execution id. Reported, not copied
  (the ruling says so). Go follows the ruling, which needs two contract amendments shared by all
  implementations: a principal **kind** on `Context` (User, Service), and where the service
  principal's id comes from (the client-credentials token's `sub`, or `GET /api/me`; neither was
  checked against the platform).
- **Conformance vectors that do not exist yet.** `conformance/` has `tsid`, `config`, `sql/locks`,
  `lua/locks`, `migrations`, `sql/outbox` and `outbox`. The vectors the Rust plan lists for HTTP
  errors, health, Datastar, metrics, rate limiting, secrets and the receivers (Rust milestones 3.1)
  were never written, because Rust stopped at R2. The Java tests must write them before G3 and G4
  can claim conformance (decision 24).
- `contracts/flowcatalyst.md` 4a's "Unix seconds must be ASCII digits" should say the millisecond
  ISO-8601 form is accepted too (Rust design 5; still open).
- YAML: `go.yaml.in/yaml/v3` refuses a duplicate key, as Rust's reader does; the Java reader keeps the
  last. Recorded as an allowed difference in the config vectors.

## 8. What was verified, and how

| Fact | How |
|---|---|
| Go 1.27 in `flowcatalyst-go`, `go1.27.2` installed | `go.mod`, `go version` |
| `encoding/json/v2` builds without `GOEXPERIMENT` on Go 1.27 | `go list encoding/json/v2` lists its files; its doc says case-sensitive matching and duplicate names rejected by default |
| `ServeMux` 1.22+ behaviour: `GET` matches `HEAD`, conflicting patterns panic, unclean paths are redirected, `Handler(r)` returns an empty pattern for 404 and 405 | `go doc net/http.ServeMux` |
| go-jet facts in section 5.2 | GitHub API and README, 2026-10-13 |
| sqlc MIT, last release v1.31.1 (2026-04-22), active | GitHub API |
| NilAway Apache-2.0, no releases or tags, active | GitHub API |
| `gopkg.in/yaml.v3` archived; `yaml/go-yaml` maintained, tags up to `v3.0.5` (and `v4.0.0-rc.6`) | GitHub API |
| templ v0.3.1070 (2026-10-04) latest; the platform pins v0.3.1020 | GitHub API, `go.mod` |
| embedded-postgres v1.34.0 defaults to PostgreSQL 18.3.0 | the module source in the Go module cache |
| the platform's UI packages' imports | `go list` of each package's imports |

**Not verified** (each is checked in the milestone named): sqlc's outer-join nullability and its
handling of a type alias of a generic id as an override (G2); which go-jet runtime packages import
`lib/pq` (G2); whether `ServeMux`'s 405 response can be replaced without wrapping every handler (G3,
the design wraps); whether datastar-go writes the bytes the Java adapter writes (G3, needs the Java
vectors); the platform's acceptance of fcsdk's snake_case outbox payload (reported only); `go get`
of fcsdk through the repository-name mismatch (G4); NilAway's noise on this code (G1).

## 9. Owner decisions, ordered by how much they block

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 1 | **Start now, and how far.** | Start G0 to G4 now: those Java contracts are built and stable. G5a (UI) may run ahead of Java's M-UI, because the kit fixtures, not Java, are the authority and the templ port exists. Hold G5b (sessions, login) for the M4c contract. | everything |
| 2 | **Where the code lives and the module split.** | `go/` in this repository; three modules: core `github.com/flowcatalyst/framework/go`, `.../go/flowcatalyst` (the only fcsdk importer), `.../go/ui` (shared with the platform) (section 2.2). | G0 |
| 3 | **Licence.** | Apache-2.0 for all three. fcsdk is MPL-2.0: relicense it to Apache-2.0 when it becomes its own module (the owner holds the copyright), so the framework and the Rust policy stay consistent. Otherwise accept MPL in `go/flowcatalyst` only, and say so in its README. | G0, G4 |
| 4 | **How the framework consumes fcsdk.** | Make `pkg/fcsdk` its own module in the platform repository (or move it to `clients/go-sdk`), tagged like the other SDKs; add the missing sync categories (connections, scheduled jobs, docs) there. Until it is tagged, a local `go.work`. Not: depending on the whole platform module (pseudo-versions, the platform's dependencies in the module graph, the path/remote mismatch); not copying. | G4 |
| 5 | **Test infrastructure.** | Embedded PostgreSQL 18 per test binary, a database per test from a migrated template, never 15432 or 8080; no `integration` build tag; Redis tests on a real `redis-server`/`valkey-server` from `PATH` (fail with instructions when absent), miniredis only above the driver. | G0 |
| 6 | **Toolchain and lint.** | Go 1.27; the platform's golangci-lint v2 profile plus `gochecksumtype`, `forbidigo` (clock, env, global logger, background contexts) and revive `exported`; gofumpt and goimports; NilAway pinned by pseudo-version as its own CI step; `fwvet`; `-race` always. | G0 |
| 7 | **Wiring model.** | An explicit `build` function, typed handles from `Declare` functions, `Boot` for lifecycle; no container, no `reflect`, no `override` (tests pass fakes to `build`) (section 1.2). | G1 |
| 8 | **The framework's `Context`.** | Keep the contract's name, `fw.Context`, passed explicitly as a parameter beside `context.Context` (by convention `cx`), never stored in `context.Context`. The alternative (a typed accessor over `context.Value`) is the common Go habit but hides a required value behind a run-time lookup. | G1 |
| 9 | **Framework tables and Redis keys are shared contracts.** | Yes, byte for byte, as Rust decision 8: a Go service can take over a Java service's database, and Java and Go replicas share locks and rate-limit budgets. | G1 |
| 10 | **Shared statement files.** | Copies inside the Go packages that embed them, checked byte for byte against `conformance/` by a test (section 3). Not a root `go.mod`. | G1 |
| 11 | **Use-case shape.** | A generic interface (`Name`, `Validate`, `Authorize`, `Execute`) registered with `usecase.Define`, so a missing `Authorize` is a **compile error**, `Execute` takes `Authorized[C]` that only the runner can make, and the conflict policy is a registration option. Plans are `usecase.NewPlan(event, steps...)` with `Insert`/`Update`/`Delete` steps typed by `Fresh`/`Loaded` (Go methods cannot have type parameters). The alternative is `usecaseop`'s struct of phase functions, familiar from the platform, checked at run time and by `uowseal`. | G2 |
| 12 | **Dynamic SQL.** | Keep ruling c, scoped: sqlc for every static query; go-jet only for dynamic list and search queries of read models, used as a builder executed by pgx (not jet's QRM, not the experimental `pgx` branch); `Filter` for the framework's own code and as the fallback. Revisit after the comparison slice whether a second generator earns its place (section 5.2). | G2 |
| 13 | **Static SQL check.** | sqlc generates without a database; `fw sql check` adds the prepare-every-query check against an embedded, framework-migrated PostgreSQL, plus `sqlc diff` in CI. | G2 |
| 14 | **JSON.** | `encoding/json/v2` (case-sensitive, duplicate names refused, unknown members refused with an option, JSON Pointer in errors). Event data: optional fields as pointers with `omitzero`, so absent values are left out as Java's `NON_NULL` does. | G2, G3 |
| 15 | **Router.** | The stdlib `ServeMux`, wrapped: the framework keeps its own route table to report duplicates and conflicts as start-up problems (the mux would panic), to answer 404, 405 with `Allow` and `OPTIONS 204` with the envelope, and to refuse unclean paths instead of redirecting. Accept one recorded difference: two routes that overlap with neither more specific (`/a/{x}/c` and `/a/b/{y}`) fail start-up in Go and are accepted in Java. | G3 |
| 16 | **Validation.** | A hand-written collector with typed parse functions and parse-don't-validate into commands; no struct-tag validation library. | G3 |
| 17 | **Datastar.** | Extract the platform's `ds` over datastar-go if its bytes equal the Java vectors; otherwise a framework adapter (about 300 lines, as Java's). | G3 |
| 18 | **Metrics.** | The framework's own registry and exposition writer (the contract fixes the bytes), not `client_golang`'s registry; an optional bridge later. | G1 |
| 19 | **Logging.** | `log/slog` only; library packages receive a `*slog.Logger` from `Boot`; `Context` fields as attributes. | G1 |
| 20 | **Receiver principal.** | Implement the ruling (service principal of kind Service; causation = the dispatch job id; execution id = the job id as Java). Amend the contracts with the principal kind and the source of the service principal's id; report the Java receiver's difference. | G4 |
| 21 | **Receiver semantics specific to Go.** | A typed dispatch handler whose payload does not decode answers `Reject` (as Rust decision 14); on a handler timeout the response is 503 at once and the goroutine is left running but tracked, so shutdown waits for it within the drain; the scheduled-job lock stays held until the handler returns. | G4 |
| 22 | **Redis client.** | go-redis v9 (the platform's). | G1 |
| 23 | **YAML.** | `go.yaml.in/yaml/v3` (the maintained successor of the archived `gopkg.in/yaml.v3`); a duplicate key is refused, recorded as an allowed difference from Java. | G1 |
| 24 | **Missing conformance vectors.** | The Java tests write the errors, health, Datastar, metrics, rate-limit, secrets and receiver vectors as Go reaches each area; Java stays the reference (Rust decision 18). | G3, G4 |
| 25 | **UI kit module.** | `go/ui` in this repository, extracted from the platform with its fixtures read in place; the platform switches to it once this repository can be fetched (section 4.2). | G5a |
| 26 | **Names and publication.** | Nothing published, nothing pushed (ruling g). Module paths stay placeholders until naming (open question 6) is settled; tags `go/v*`, `go/flowcatalyst/v*`, `go/ui/v*` when publishing is allowed. CI workflow written but not run, as for Rust. | first release |
| 27 | **The comparison.** | Fix the business slice and the measurements before G7, borrowing the TypeScript spike's list (lines per layer, seeded bugs caught, start-up, memory, test time, latency for context only). | G7 |

The Java owner rulings still pending (rate-limit store failure default `ALLOW`, IPv6 /64, the memory
overflow bucket; service-scoped secrets and world-readable files; SDK publication) apply to Go
unchanged.
