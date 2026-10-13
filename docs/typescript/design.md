# TypeScript framework: design (draft 1, 2026-10-13)

Status: a design. Nothing is built. Package names below use the scope `@fw/` for readability only
(implementation plan, open question 6); every package is private and none is published to npmjs.

TypeScript becomes the **default framework for new business applications** (owner, 2026-10-13).
Java stays for the applications already on it; the Rust implementation is paused after R2. The
TypeScript framework is a third implementation of the contracts in [`../contracts/`](../contracts/),
written for TypeScript and for a future native compiler rather than translated from Java.
Applications built on any implementation must be interchangeable to the FlowCatalyst platform: the
same outbox rows, event, audit and dispatch-job payloads, TSIDs, receiver behaviour, config and
secret semantics, error envelope, framework tables and Redis keys.

| Document | Contents |
|---|---|
| this page | principles, the scriptc rules, the runtime, the schema split, routing as values, the framework-written middleware and auth, worker-readiness, the UI stack, the dependency policy, workspace layout, the owner decisions |
| [`capabilities.md`](capabilities.md) | capability by capability: the TypeScript types, what is shared verbatim with the contracts, what changes, open rulings |
| [`orders-showcase.md`](orders-showcase.md) | the orders example end to end (wire DTO, parse, domain core, use case, repository, route, page), and what the type checker and scriptc guarantee |
| [`spike.md`](spike.md) | T0: the runnable plan for the spike that informs decision 0 and the T0 decisions |
| [`milestones.md`](milestones.md) | the spike (T0) and milestones T1 to T6 (with T4a auth and T4b workers) and after, testing, conformance, effort and risk |

Inputs read: [`framework-plumbing.md`](../framework-plumbing.md), [`implementation-plan.md`](../implementation-plan.md),
every contract, the Rust design ([`../rust/`](../rust/design.md)) and what R0 to R2 found while
building, `conformance/`, `ui-kit/` (specs, fixtures, tokens), the TypeScript SDK
(`flowcatalyst-go/clients/typescript-sdk`, 0.11.28), the platform SPA's stack
(`flowcatalyst-go/frontend`), and the scriptc documentation (`scriptc.dev/docs`, `/docs/contracts`,
`/docs/limitations`, `/docs/dependencies`, `/docs/coverage`, `/docs/cli`, `/llms.txt`, read
2026-10-13). The brief named `flowcatalyst-go/ui-kit`; there is none. The Datastar kit and its golden
fixtures live in this repository's `ui-kit/`, and the Go platform's templ port in
`flowcatalyst-go/internal/ui`.

## 1. Principles, and where TypeScript differs

The framework's design rules (plumbing section 3) hold: contracts first; no reflection and no global
state; fail at start-up; three lifetimes (application, request or job, transaction); errors are
values; the tenant is always present. TypeScript enforces fewer of them in the type checker than
Rust does and more than Java does; scriptc, once used, proves two more (no throw, no cycle).

### 1.1 Run on Bun, keep Node green, stay ready for scriptc

**The practical runtime is Bun, in JIT mode** (section 1.1a), with Node supported and green in CI.
Native TypeScript compilers are still churning: [scriptc](https://scriptc.dev/docs) (Vercel Labs,
0.2.x per the owner; its docs' sample diagnostics print `compilerVersion 0.3.0`), Bun's own
ahead-of-time compiler (**not shipped**), and Perry (about 0.5, aimed at GUI and mobile, with partial
`http`, `crypto` and `fs`; watched, not targeted). **scriptc is the later target**: the owner
considers it the better future, so the code is written inside its subset now and a non-gating CI job
measures that (section 1.9), but nothing is built or run with it yet. What the framework keeps for
it costs nothing on Bun or Node: lint rules, the `Result` and `Immutable` discipline, ports for every
external system, the `Workers` port.

scriptc compiles TypeScript to native executables and WebAssembly using the type information, with
**no JavaScript engine fallback**: what cannot compile statically is a blocker (the build fails) or a
deferred operation (compiles to a throw). Its limitations are a useful description of "boring,
analysable TypeScript", and the framework's rules follow them where that is free.

What that subset means for the framework, taken from scriptc's limitations page and the owner's
rulings:

| scriptc fact | Consequence in the framework |
|---|---|
| no `eval`, `new Function`, computed dynamic `import()`, decorators on local classes; `Proxy` refused in several places; no reflective static inheritance | no code generation at run time, no compiled validators (TypeBox `Compile`, Ajv), no decorators, no DI container, no ORM that builds classes; wiring is an explicit `build` function (section 1.3) |
| `JSON.parse` returns `unknown`; a cast from a dynamic value to a static type is **checked at run time** and throws on mismatch | every value from outside (JSON, rows, headers, env) is `unknown` and is narrowed by a parse function returning `Result`, never by `as`; `as` is banned in domain code by lint (section 1.5) |
| `NoThrow<T>` proves no path to a throw; recursion, calls through function values that may throw, property reads that can throw and checked casts all count; `async` functions and generators cannot be `NoThrow` | errors as values everywhere; the **domain core is synchronous and pure** and marked `NoThrow`; I/O lives in thin `async` shells that catch at one boundary; no recursion in domain code (section 1.4) |
| runtime traps cannot be caught; out-of-range reads of statically typed arrays terminate the process | `noUncheckedIndexedAccess` on, indexed reads go through `at()` or a checked helper, a lint rule forbids `arr[i]!` |
| `Date`: no year/month constructors, setters or locale formatting; limited `Intl` | time is an `Instant` (epoch milliseconds) from an injected `Clock`; RFC 3339 formatting and parsing and the duration grammar are the framework's own pure functions; no locale formatting on the server |
| typed arrays: no BigInt arrays, no resizing | the TSID codec works on two 32-bit halves (`Int32`) or `bigint` scalars, decided in T1 |
| `node:crypto`: MD5, SHA-1, SHA-2 hashing, HMAC, PBKDF2, HKDF only; **no cipher and no public-key operations** | crypto is a port (section 1.8); HMAC-SHA256 for receiver signatures works natively; RS256 JWT verification and AES-GCM cookie sessions use `node:crypto` now and need an FFI adapter (mbedTLS or OpenSSL primitives, never our own) for a native build |
| FFI calls are **synchronous**; C ABI only; no variadics, no structs by value | libpq through FFI would block the event loop on every query; the long-term Postgres path is a TypeScript wire-protocol driver over the socket port, not libpq (decision 10) |
| `net`, `http`, `https`, `tls`, `dns` are native (mbedTLS); `fetch` is native | the HTTP server adapter, the HTTP client and the database socket can all exist natively; per-API coverage of `node:http` and of WHATWG `Request`/`Response`/`Headers` is **unverified** (section 1.7, doubt 2) |
| npm packages compile only when eligible: readable runtime source and a package-owned declaration surface; the executable never loads `node_modules` | the dependency policy (section 1.10) |
| contracts are erased type-only imports from `@scriptc/types`: `NoThrow<T>`, `Int32`, `Immutable<T>`, `Acyclic`, `ProgramLifetime`; written as the annotation of a declaration only | the framework uses them now (they cost nothing on Node) and documents where each belongs (section 1.4) |

None of this needs scriptc to be adopted. Each rule is also good TypeScript: values from outside
are `unknown` until parsed, errors are values, the domain is pure, time and randomness are injected.
Every native compiler (scriptc, Bun's AoT when it ships, Perry) needs readable `.ts` source rather
than `.d.ts` over JavaScript, which is what the dependency rule asks for anyway (section 1.10).

### 1.1a Runtime: Bun now, Node kept green (owner ruling 2026-10-13)

The owner considered Node-only as the boring baseline and ruled for Bun as the practical runtime,
because it has what the framework wants today:

- native TypeScript execution, a test runner, workspaces and a package manager in one tool;
- built-in PostgreSQL (`Bun.sql`), Redis (`Bun.redis`) and HTTP (`Bun.serve`) clients and server,
  each usable **behind the framework's ports** as an adapter;
- `bun build --compile`: one self-contained executable per application, which is the deployment
  shape the Go platform has.

Rules that keep this from becoming a lock-in:

- **Bun-specific APIs only inside adapters** behind ports (section 1.8). Framework and application
  code never import `bun`; a lint rule enforces it.
- **Node stays supported**: CI runs the unit tests and the conformance vectors on **both Bun and
  Node**. A difference between them is a finding, never a skip.
- **No AoT claims.** `bun build --compile` bundles the JavaScript engine with the program; it is not
  ahead-of-time compilation. Bun's AoT compiler has not shipped (the benchmark chart that prompted
  it says so in its footer, and was built with about 120 source patches), and the chart measured a
  CPU-heavy type-checking workload, not an I/O-bound service. A Bun AoT build job is added when it
  ships; like scriptc it will need readable `.ts` source, which the dependency rule already asks for.
- **scriptc stays the later target** with its non-gating coverage job (section 1.9).
- **Unverified, measured by the spike:** `Bun.sql` against the unit-of-work and outbox semantics:
  one transaction on one reserved connection with `READ COMMITTED`, SQLSTATE codes surfaced for
  `23505`, `40001` and `40P01`, multi-statement migration files on the simple-query protocol, the
  shared `INSERT` with numbered placeholders, and type conversion (`int8`, `numeric`, `timestamptz`
  with microseconds) without loss of precision. The `pg` adapter exists anyway (Node needs it); which
  adapter applications use on Bun is decided by the spike (decision 10).

### 1.2 TypeScript settings

- **Erasable syntax only** (`erasableSyntaxOnly`): no `enum`, no `namespace`, no parameter
  properties, no decorators. Bun runs `.ts` natively and Node by stripping types, so server packages
  need **no build step** in development or tests, and the same files are the input of scriptc (and
  of Bun's AoT compiler when it ships). Enums are `as const` objects with a literal-union type.
- `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`,
  `noPropertyAccessFromIndexSignature`, `verbatimModuleSyntax`, `isolatedDeclarations` for library
  packages (declarations are written, not inferred across packages), `module`/`moduleResolution`
  `nodenext`, ES modules only, `.ts` extensions in imports (`rewriteRelativeImportExtensions` when a
  package is built for distribution).
- `tsc --noEmit` is the type check. TypeScript 6.x pinned, as the SDK; the native TypeScript 7
  compiler is tried when it is released as stable and changes nothing in the source.

### 1.3 No container: an explicit, typed application

The Rust design's wiring model, in TypeScript:

- **Services are plain values built in plain code.** The application writes one `async function
  build(boot)` that constructs its services in order, passing each its dependencies as arguments,
  and returns its own state object. A missing dependency is a type error. There is no `get(Class)`
  and no service map, so nothing needs reflection or a registry keyed by constructor.
- **Modules are declarations.** A framework module is a function that declares settings, instance
  sections and driver key schemas, migration sets, platform definitions and commands, and returns
  **typed handles**. A handle is the proof that a setting was declared and validated, so reading it
  after configuration cannot fail.
- **Lifecycle is a handle.** `build` receives a `Boot` through which it registers health checks,
  background loops, started actions and shutdown actions. Nothing runs until every part is built.

```ts
const app = App.declare({ name: "orders-demo" });
const http = app.module(httpModule());
const db = app.module(databaseModule());
const locks = app.module(locksModule({ drivers: ["memory", "database"] }));
const fc = app.module(flowcatalystModule());
const orders = app.module(ordersModule());

process.exitCode = await launch(app, process.argv.slice(2), async (boot) => {
  const dbs = await Databases.connect(boot, db, pgAdapter());   // ping, health, close on shutdown
  await boot.migrate(dbs);                                        // every module's migration set
  const lockSet = Locks.builder(boot, locks).databases(dbs).build();
  const platform = fc.client(boot, fetchTransport());
  const deps = buildOrders(boot, orders, dbs, lockSet, platform);
  return Serve.http(http, [...healthRoutes(boot), ...orderRoutes(deps)], bunServer());    // routes are plain arrays; nodeServer() on Node
});
```

(The `Result` checks after `connect`, `migrate` and the builders are elided here; the closure returns
`Result<Serve, StartupError>`.)

`launch` runs declare → configure (files, environment, `secrets.local.yaml`; every problem collected
into one report) → build (the closure) → a command named by the first argument, or start (started
actions, loops, then the server binds) → run until SIGTERM or SIGINT → shutdown. TypeScript has no
move semantics, so the phases are separate objects (`Declaring`, `Configured`, `Running`) rather
than a typestate on one value: a declaration call after `configure` is a method that does not exist
on the configured object. Tests build the application with fakes passed to their own `build`
function, as in Rust; there is no `override`.

### 1.4 Errors as values; a pure, synchronous domain core

**The use-case envelope stays the contract's** (validate, authorize, execute returning a plan, one
runner applying it in one transaction). What changes is how errors travel:

- `Result<T, E>` is the framework's own discriminated union, `{ ok: true; value: T } | { ok: false;
  error: E }`, with plain functions (`ok`, `err`, `map`, `andThen`, `all`). Not a class, not
  `neverthrow` (section 6).
- **Expected outcomes are unions handled with an exhaustive `switch`**: `Acquire`, `Decision`,
  `DispatchResult`, `SecretResult`, `WithLock<T>`. The lint rule
  `@typescript-eslint/switch-exhaustiveness-check` plus a `never` default make a new member break
  every switch that must decide what it means.
- **Failures are `Result` with a tagged error type per capability** (`LockStoreError`,
  `HttpClientError` with its `kind`, `SecretStoreError`, `StartupError` with its problem list).
- **`UseCaseError` and `ErrorKind`** are the contract's: `{ kind, code, message, details }` with
  `kind` one of `VALIDATION`, `AUTHORIZATION`, `NOT_FOUND`, `CONFLICT`, `BUSINESS_RULE`, `INTERNAL`.
  (The TypeScript SDK uses lower-case kinds and `concurrency`/`infrastructure`; the framework follows
  the contract and the Java and Rust implementations, not the SDK.)
- **Throwing is for adapters only.** Node APIs and the JavaScript libraries behind ports throw. Every
  boundary that calls into them (the runner's steps, the router's handler call, a loop tick, a
  receiver, an adapter method) catches everything once and converts it: `INTERNAL` with the step
  code, a 500 envelope, a failed run. Domain and application code never writes `throw`; a lint rule
  forbids `throw` and `try` outside adapter directories.

**The shape of a use case** (full example in [`orders-showcase.md`](orders-showcase.md)):

```
async shell (application layer)           pure core (domain layer)
-------------------------------           -------------------------------------------
load aggregates through repositories  ->  decide(state, command, facts): NoThrow<Result<Change, UseCaseError>>
build the Plan from the Change        <-
```

The core is synchronous, takes everything it needs as arguments (the loaded state, the parsed
command, the time and new ids as values, never a clock or a generator), and returns a `Result`. It
is annotated `NoThrow`, which scriptc proves; on Bun and Node the annotation is a no-op type. The shell is
`async`, so it cannot be `NoThrow`; it is kept thin enough to read in one screen, and the runner
catches anything it throws.

**No recursion in domain code** (it cannot be `NoThrow`: the stack guard may throw). Trees are
walked with an explicit stack. **No `as` in domain code**: narrowing is by `typeof`, `in`,
discriminants, and parse functions. **No classes in domain code**: plain object types, discriminated
unions and functions; classes remain allowed in framework internals where a runtime identity helps
(`Secret`, the router), and `ProgramLifetime` applies to them (below).

Where each scriptc contract belongs:

| Contract | Use |
|---|---|
| `NoThrow<T>` | the domain core (`decide` functions, value-object parse functions, state transitions); framework pure functions: TSID encode and decode, the config key grammar and duration parser, route matching, GCRA arithmetic, signature comparison, the error envelope, the Prometheus writer |
| `Int32` | counters and indices where a 32-bit range is a fact (quantities, line positions, attempt numbers, the TSID halves) |
| `Immutable<T>` | module constants (definitions, route tables, metric descriptors, the shared SQL texts) and the validated configuration snapshot |
| `Acyclic` | aggregate and event types (trees by construction), so they are freed by reference counting alone |
| `ProgramLifetime` | classes that live for the whole process: the router, the metrics registry, pool wrappers (scriptc requires a hierarchy root and an executable build, which an application is) |

### 1.5 Brands, value objects and smart constructors

- **Every id is a type.** `ClientId`, `PrincipalId`, `ApplicationCode`, `CorrelationId`,
  `CausationId`, `ExecutionId`, `LockKey`, `SecretRef`, and aggregate ids through one generic
  `Id<K>` where `K` is the prefix literal (`type OrderId = Id<"ord">`), so `Id<"ord">` and
  `Id<"lin">` cannot be mixed. Each has a `parse(unknown): Result<T, FieldError>` and no other
  constructor.
- **Value objects are smart constructors**: `CustomerName.parse`, `Quantity.parse`, `Cents.parse`
  return `Result`. A command built from them is valid by construction.
- **How a brand is minted.** A phantom brand (`string & { readonly [brand]: "ord" }`) needs one
  cast. That cast lives in one framework function, `unsafeBrand`, in one file that lint exempts;
  every `parse` calls it after checking. Unknown: whether scriptc's checked casts accept a cast to a
  phantom intersection (the brand property does not exist at run time, so a structural check would
  fail). The spike measures it; if scriptc rejects it, ids become small frozen records `{ kind: "ord", value:
  string }`, which cost an allocation and need no cast (decision 9).
- **Codes and patterns that are literals** (event types, error codes, permissions, use-case names,
  route patterns, metric names) are checked twice: their *shape* by template-literal types where
  TypeScript can express it (`${string}:${string}:${string}:${string}` for an event type, `{name}`
  parameters extracted from a route pattern), their *character classes* when the module that
  declares them is loaded, which is before configuration, so a bad literal fails start-up with every
  other problem. Rust's `const fn` checks are compile errors; this is the nearest TypeScript gets.
- **`Secret`** is a class with a private field, a redacting `toString` and `toJSON`, and `reveal()`.
  TypeScript cannot forbid putting it in a log line at compile time; a lint rule flags `Secret` in a
  template literal or a JSON-serialised type, and `toJSON` returns the redaction. `matches(candidate)`
  compares SHA-256 digests with `timingSafeEqual` through the crypto port.

### 1.6 The schema split: TypeBox at the edge, domain types inside

Owner ruling. Two kinds of type, never mixed:

| | Wire types (DTOs) | Domain types |
|---|---|---|
| What | API request and response bodies, event payloads as published, SDK-facing shapes | commands, aggregates, value objects, ids, events as the domain builds them |
| Defined by | a **TypeBox** schema (pure JSON Schema, gRPC-like: objects, strings, numbers, integers, booleans, arrays, literals, unions with a discriminant, optional fields; no transforms, no refinements, no recursion) and `Static<typeof S>` | plain TypeScript types with brands and smart constructors |
| Checked by | `Value.Check` and `Value.Errors` (interpretive; **never** `Compile`, which generates code with `new Function`) | the type checker, then the parse functions at the edge |
| Lives in | `@fw/schema` helpers and the application's `api/` folder, importable by the browser | the application's `domain/` folder; never imports TypeBox |

The edge maps a wire DTO to a command with an **explicit parse**: `toAddLine(dto, orderId):
Result<AddLine, Violations>`, which collects every field error (the contract's validation rules:
first error per field, `"<code>: <message>"`). Object schemas are closed by default
(`additionalProperties: false`, set by the framework's `Obj` helper), so unknown properties are
refused as the contract requires.

Why the split: a schema library shapes whatever it touches (inference, refinements, transforms). Kept
at the boundary it is a description of bytes, shared with the platform (event-type schemas in
definition sync), the browser (form validation, the typed API client) and an OpenAPI document. The
domain stays free of it, which keeps the domain inside scriptc's subset whatever happens to the
library.

**Zod is not in the core.** It may appear in an application's browser code; Nuxt UI forms accept any
Standard Schema, and the framework ships a small Standard Schema adapter over the TypeBox schemas so
a form can validate with the same schema the API checks (capabilities section 13).

**Unverified:** TypeBox's eligibility under the dependency policy. TypeBox 1.x (package `typebox`;
`@sinclair/typebox` is the deprecated 0.x name) ships a JIT compiler that uses `new Function` and
falls back to dynamic checking where code generation is blocked. Whether importing only the type
builder and `typebox/value` keeps the `new Function` path out of the program, and whether scriptc
compiles `Value.Check`, is checked in T0: the first by tracing the import graph for `new Function`
(clause 2 of the dependency rule), the second by the spike's coverage run. If the first fails, the framework writes `@fw/schema` as
its own builder for the same subset, emitting the same JSON Schema with an interpretive checker
(about 600 lines; decision 8). The application-facing API is the same either way.

### 1.7 HTTP: our own thin layer on web-standard Request and Response

Owner rulings (2026-10-13): not Fastify, not Nitro, not Express, **no router library**, and **no
singleton or global route registry**. Hono is the model for the handler shape, not a dependency.

**A route is a value.** Plain data: method, path, TypeBox schemas for params, query, body and
response, the handler, and optional per-route middleware and authz. The path string's literal type
gives the parameter names, so the params schema and the handler's `params` must agree at compile
time.

```ts
const addLine = route({
  method: "POST",
  path: "/orders/:id/lines",
  params: Obj({ id: Str() }),            // keys checked against the path's ":id"
  body: AddLineBody,                     // TypeBox schema, closed object
  response: { 200: LineAddedView },
  handler: async (req, c) => { /* c.params.id: string, c.body: Static<typeof AddLineBody> */ },
});
```

**Modules export route arrays built from their dependencies**, never register into anything:
`orderRoutes(deps): readonly Route[]`. Composition replaces Fastify's encapsulation with one pure
function: `scope({ prefix, middleware, authz }, routes)` returns a **new array** with the prefix
prepended, the middleware wrapped around each handler and the authz requirement attached; nothing is
mutated. The application's `build` concatenates the arrays and the server **compiles them once at
start-up** into a segment trie.

- **The trie**: one node per segment, built from the plain data, no per-route code generation and no
  regular expressions. Precedence at each segment: static, then `:param`, then a trailing wildcard
  (`*` as the last segment, for the SPA fallback and asset prefixes). Matching is a pure `NoThrow`
  function. **Duplicate method + path fails the build** (parameter names ignored, so `/a/:x` and
  `/a/:y` collide), naming both routes; every duplicate is reported, as start-up problems are.
- The contract's wire rules hold: 404, 405 with a sorted `Allow` (`HEAD` with `GET`, always
  `OPTIONS`), `OPTIONS` 204, `HEAD` from `GET`, trailing slash distinct, no redirects, per-segment
  percent-decoding, routes logged at start-up.
- **Contract amendment needed** (decision 27): the http contract fixes `{name}` segments and no
  wildcards. The owner's syntax is `:name` and a trailing wildcard. The wire behaviour is unchanged;
  the pattern syntax and the trailing wildcard are an amendment to http section 2, for TypeScript or
  for all three implementations.
- **Middleware is one generic abstraction**, `Middleware<I, O> = (input: I, next: (i: I) =>
  Promise<O>) => Promise<O>`, shared by HTTP and the receivers' job pipeline, as the contract asks.
  The fixed outer order is the contract's: correlation id, error mapping, access log.
- **Errors leave as `Result`.** A handler returns `Promise<Result<Response, ServiceError>>` or a
  `Response`; the error-mapping middleware turns an `Err` into the envelope and a throw into 500.
- **The server is a port.** `ServerAdapter` takes `(req: Request, info: ConnInfo) =>
  Promise<Response>` and owns sockets, body limits, keep-alive and drain. Adapters: `Bun.serve`
  (native `Request`/`Response`, no conversion) and `node:http` (converting `IncomingMessage` to
  `Request` and streaming the body back), both run in CI; a native listener for scriptc later.
- **Static assets.** The built SPA is served by the same server through a trailing-wildcard route
  (decision 26 amends the http contract, which lists static assets as out of scope).

### 1.7a Middleware and auth the framework writes itself

Owner ruling (2026-10-13): none of these come from a library; all are framework code, behind ports
where they touch crypto or storage. **Crypto primitives are never hand-rolled**: HMAC, SHA-256,
RS256 verification and AES-GCM come from `node:crypto` (Bun implements it), reached
through the `Crypto` port. **Auth and session code is written by Opus only**, contract first, with
**negative tests written first**.

| Piece | Notes |
|---|---|
| request id (correlation id), access logging, body limits | the http contract's sections 6 and 8 |
| CORS | off by default (the contract has none and the platform has none); an opt-in middleware with an explicit origin allow-list, never `*` with credentials; a contract amendment (decision 27) |
| security headers | `Content-Security-Policy` for the SPA, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors`, `Strict-Transport-Security` when behind TLS |
| CSRF | for cookie-authenticated unsafe methods: `SameSite=Lax`, an `Origin` (or `Referer`) check against the configured origin, and a required custom header |
| rate limiting | the rate-limiting contract (capabilities section 8) |
| error to response | `Result` → envelope; throws → 500 without the cause |
| JWT verification | RS256 through `node:crypto` with a JWKS cache (fetched through the HTTP client, keyed by `kid`, refreshed on an unknown `kid` with a floor between refreshes); `alg` pinned by configuration, never taken from the token; `none` and HMAC algorithms refused; `iss`, `aud`, `exp`, `nbf`, `iat` checked with a bounded clock skew |
| OIDC login | authorization code with PKCE as a public client (no secret; flowcatalyst contract section 5 item 3), discovery, token exchange and refresh, logout |
| sessions | drivers `cookie` (AES-256-GCM through `node:crypto`), `database`, `redis`; rotation on login, idle and absolute expiry |
| authz context | the `ContextResolver` builds `Context` (client, principal, permissions) from the session or the verified token |
| HMAC receiver verification | the flowcatalyst contract's signature rules |

Token acceptance and refusal get **shared conformance vectors** (`conformance/auth/`): a fixed JWKS,
tokens and the expected accept or refusal code, starting with the negative cases (expired, not yet
valid, wrong issuer, wrong audience, `alg: none`, algorithm confusion RS256→HS256 with the public key
as the secret, unknown `kid`, tampered payload, tampered signature, a key of the wrong type). The
Java and Rust implementations read the same vectors when they build M4c. Auth is its own milestone
after the use-case and HTTP layer ([`milestones.md`](milestones.md), T4a).

**Unverified:** whether scriptc compiles the WHATWG `Request`, `Response` and `Headers` classes; its
compatibility table (3 662 Node API rows) did not render when read. If it does not, the framework's
handler types become its own `FwRequest`/`FwResponse` interfaces with the same member names
(`method`, `url`, `headers.get`, `arrayBuffer`, `json`, `status`, `body`), so application handlers
change only an import. The spike measures it (doubt 2).

### 1.8 Ports and adapters for every external system

Every external system sits behind a framework-owned interface with swappable adapters. Domain and
application code never import an adapter, a driver, `bun` or a `node:*` module.

| Port | Adapters now (Bun and Node) | Later (scriptc) |
|---|---|---|
| `SqlClient` (pool, query, transaction) | `Bun.sql` on Bun if it passes the contract suite; `pg` (node-postgres) on Node, and on Bun otherwise (decision 10) | a TypeScript PostgreSQL wire-protocol driver over `SocketPort` |
| `RedisClient` | the framework's own RESP2 client over `node:net` on both; a `Bun.redis` adapter if it covers `EVALSHA`/`NOSCRIPT` (decision 11) | the RESP client over the native `net` |
| `Crypto` (SHA-256, HMAC-SHA256, constant-time compare, random bytes, RS256 verification, AES-256-GCM) | `node:crypto` (both runtimes) | native hashing and HMAC; FFI to a vetted library for AES-GCM and RS256 |
| `HttpTransport` (outbound) | `fetch` | native `fetch` |
| `ServerAdapter` (inbound) | `Bun.serve`; `node:http` | native listener |
| `Workers` (spawn, publish a snapshot, message) | `node:worker_threads` (Bun implements it) | `@scriptc/threads` (section 1.12) |
| `Clock` (wall `Instant`, monotonic milliseconds), `Random` | `Date.now`, `performance.now`, `crypto.getRandomValues` | native equivalents |
| `FileSystem` (config files, secret files, static assets) | `node:fs/promises`; `Bun.file` for static assets and files embedded by `bun build --compile` | native `fs` |
| `Environment` (read by exact name, never listed) | `process.env` | the same |
| `Signals` | `process.on("SIGTERM")` | static signal listeners |

### 1.9 scriptc in CI

- A **non-gating** CI job (owner ruling) runs a **pinned** scriptc version (installed with
  `npm install -g scriptc@<exact>`; it needs Node 24+ and its install scripts allowed) against one
  entry file per package (`scriptc/entry.ts`, importing the package's public modules) and per
  example domain, with `scriptc coverage <entry> --print=diagnostics`. The JSON report (`stats`,
  blocker `groups`, deferred operations, Node divergences, npm package outcomes) is stored as a
  build artifact and a summary is posted on the run. Perry and Bun's AoT compiler are watched, not
  targeted; the same job shape would serve them.
- `NoThrow`, `Int32`, `Immutable` and `Acyclic` annotations are only proved by scriptc. A non-gating
  job means a broken contract is reported, not refused. Decision 7 asks whether `SC7001`-`SC7008`
  diagnostics on the domain entries should gate.
- The pinned version moves deliberately, in one commit, with the coverage numbers before and after.

### 1.10 Dependency policy

Owner ruling, server side. A runtime dependency is allowed only when it:

1. is **TypeScript-authored**, with readable source and its own declarations (not `@types/*`);
2. uses no `eval`, `new Function`, `Proxy` or reflection;
3. compiles under `scriptc coverage` (measured by the non-gating job, section 1.9; a dependency
   that fails is replaced by framework code).

Otherwise the framework writes the code itself. A JavaScript library is never a conscious choice.
Two consequences the owner has accepted: the PostgreSQL driver is an exception for now (a JavaScript
driver behind the port; decision 10), and every external system is behind a port (section 1.8).

Not covered by the rule:

- **Browser code** (Vue, Nuxt UI, vue-router, the UI package's components) is exempt.
- **Development tools** that never ship (TypeScript, the linter, the test runner, Testcontainers,
  Playwright) are outside the rule, kept few, pinned, and installed with pnpm's install scripts off
  except for an allow-list (decision 3).

**Nothing has been measured yet.** Clauses 1 and 2 are checked in T0 by reading each candidate's
source and import graph; clause 3 by the spike's coverage run ([`spike.md`](spike.md)). The candidates and what happens if each fails are in [`milestones.md`](milestones.md)
section 1 (T0). Bun's built-in clients are not dependencies in this sense, but they are
runtime-specific, so they live only in adapters (section 1.1a).

### 1.11 Lint-enforced module hygiene

No module-level mutable state anywhere on the server: a module may export functions, types and
`Immutable` constants, never a `let`, a mutable collection or a singleton instance. State lives in
values that `build` creates and passes. This is design rule 2 (no global state) and also the
precondition for section 1.12. A lint rule flags top-level `let`, top-level `new Map`/`new Set`/array
literals not typed `Immutable`, and exported mutable objects.

### 1.12 Worker-ready (owner ruling 2026-10-13)

The owner considers scriptc the better long-term target, and scriptc's `@scriptc/threads` gives it
real parallelism: a Node-compatible `Worker` and `postMessage`, plus `publish()`, which shares an
immutable object graph with other threads **by reference, without copying** (on Node it runs with
structured cloning instead). The framework is written so that request handling can move onto
workers without redesign:

- **Share nothing per request.** A request, a job firing or a dispatch delivery is handled entirely
  on one thread, with its own `Context`, connections from that thread's pools, and no reads of
  another thread's mutable state.
- **All shared state is an `Immutable<T>` snapshot**, built and published by the main thread, read by
  workers: the validated configuration, the compiled route table, the authz tables (roles to
  permissions), the schema cache, definitions, metric descriptors. A change (a configuration reload
  later, a role sync) publishes a **new** snapshot; nothing is updated in place.
- **No module-level mutable state** (section 1.11), so loading a module in a worker creates nothing
  shared by accident.
- **A framework-owned `Workers` port**: spawn a worker with an entry module, publish a snapshot,
  exchange messages, stop with the drain. Adapter now: `node:worker_threads` (Bun implements it);
  later: `@scriptc/threads`.
- **Per-worker PostgreSQL and Redis pools**, with sizing in configuration expressed **per process**
  and divided across workers (`database.instances.<name>.pool.max` is the process total; each of `n`
  workers gets `ceil(max / n)`), so connection counts do not multiply with the worker count. Health
  checks report per worker and are aggregated on the main thread.
- **Leader work stays singular**, on one thread (the main thread), gated by the existing locks
  (Redis or database driver) across replicas: the `fw_dispatch_once` purge, lock-gated background
  loops, the scheduled-job overlap lock, definition sync on boot. (Outbox delivery is not here: the
  platform delivers the outbox, ruling 2026-10-09; the framework only reads the backlog for its
  health check.)
- **Metrics** are recorded per worker and merged on the main thread for the exposition endpoint;
  the contract's bytes are produced once.
- scriptc interaction: in programs that use worker threads, **`try` no longer discharges a
  `NoThrow` contract**. The domain core never uses `try` (it has no throw to catch), so its proofs
  survive; framework functions that rely on a local `try` (for example around a checked cast in
  `unsafeBrand`) lose their `NoThrow` annotation in a threaded build. The framework keeps `try` out
  of `NoThrow` functions from the start.

Unverified, listed as doubts 9 to 11 (section 7): how one listening socket is shared across workers
(Node and Bun can share a port through `SO_REUSEPORT` on Linux, `Bun.serve({ reusePort })`, or a main
thread that accepts and hands off connections; what `@scriptc/threads` offers is not documented);
whether the chosen PostgreSQL and Redis adapters run inside workers on each runtime; and the maturity
of scriptc and `@scriptc/threads` (0.2.x per the owner, new). Workers are a milestone after T4
([`milestones.md`](milestones.md), T4b), and until then everything runs on one thread with the rules
above already enforced.

### 1.13 The `RequestDispatcher` port (owner idea, 2026-10-13)

Every HTTP request (and every receiver delivery) is handed by the server to a **`RequestDispatcher`**
rather than to the handler directly. The dispatcher decides when and where the request runs. Two
adapters:

| Adapter | How it works | When |
|---|---|---|
| **in-process** | one thread; a semaphore bounds how many requests run at once; the rest wait in a bounded queue | T4, the default |
| **worker pool** | the main thread owns accept, parse, routing and a central queue; workers signal ready and **pull** the next request; a worker handles it end to end, including its own database transaction, and posts the result back; body buffers are **transferred**, not cloned; each worker has its own PostgreSQL and Redis pools (section 1.12) | a later milestone (T4c) |

What a central queue gives, in the vocabulary of the platform's dispatch pools:

- **admission control** in one place: a concurrency limit, an exact rate limit (decided on one
  thread, no shared counters), backpressure, and **429 load shedding** when the queue is full;
- **per-tenant fairness**: the queue is keyed by client, so one tenant's burst cannot starve the
  others (the same idea as message groups);
- **crash isolation** (worker pool): a dead or hung worker is replaced; a request past its timeout
  gets its answer at once and its worker is **terminated**, because synchronous CPU work cannot be
  interrupted any other way;
- **thread safety by construction**: share-nothing workers reading `Immutable` snapshots.

Caveats, stated plainly:

- Business applications are **I/O-bound**. One event loop already overlaps thousands of database and
  HTTP waits; a worker pool helps with **CPU** (JSON parsing and serialisation, schema checks,
  crypto) and with using more than one core, not with I/O concurrency.
- The main thread becomes the **bottleneck and the single point of failure** for accept, parse,
  route and queue.
- Node and Bun share only bytes between threads (`SharedArrayBuffer`), so the queue and its state
  live on the main thread and every request and response is a message: a **structured-clone cost per
  message** on Node and Bun (bodies transferred as `ArrayBuffer`s avoid copying the bytes, headers and
  metadata are cloned), against scriptc's zero-copy `publish()` for immutable graphs. The spike
  measures the hand-off cost on both runtimes ([`spike.md`](spike.md)).
- **Streaming** bodies, server-sent events and WebSockets do not fit request-response messages; such
  routes stay on the main thread (or are proxied with a stream protocol, not planned).
- The **simpler alternative** is each worker accepting on the same port (`reusePort`), with no central
  queue: less control (admission and fairness become per-worker), less machinery.

**Rule from now on, so either adapter works:** handlers are **message-shaped**: request data in
(method, path, headers, body bytes, the resolved `Context`), response data out (status, headers,
body bytes), no shared mutable state, no reference to the server, the socket or the main thread's
objects. The in-process adapter ships first; the worker pool is a later milestone, built only if a
measured CPU-bound load needs it.

## 2. Workspace, packages and build policy

### 2.1 Where it lives: `ts/` in this repository (recommended)

The reasons the Rust design gave hold unchanged: one copy of the contracts, `conformance/` and
`ui-kit/` read in place, a contract change and every implementation in one commit, one documentation
site. The Java and Rust builds are unaffected (`ts/` is not a Maven module or a Cargo member).

### 2.2 Packages

```
ts/
  package.json                 private workspace root: workspaces packages/*, examples/*, tools/*; catalog; scripts
  bun.lock                     committed text lockfile
  bunfig.toml                  exact installs, trustedDependencies allow-list, test settings
  tsconfig.base.json           section 1.2
  eslint.config.ts             the rule set (section 2.4)
  packages/
    core         Result, App/Boot/launch, modules and handles, config (Setting, sources, Secret), instances and
                 drivers, Context and ids (brands, TSID), Clock, Instant and durations, health, Background, Metrics
                 and the Prometheus writer, logging, commands, the Crypto/FileSystem/Environment/Signals ports
    schema       TypeBox boundary helpers: Obj (closed objects), the Violations mapping, Standard Schema adapter;
                 importable by the browser
    db           SqlClient port, Databases, Db and Tx, the migration runner on fw_schema_history; adapter subpath `@fw/db/pg`
    redis        RedisClient port, the RESP2 client, RedisConnections
    locks        Locks, LockStore; drivers memory, database, redis; the contract suite (subpath `@fw/locks/testing`)
    usecase      UseCase, the runner, Plan, Fresh/Loaded, Repository, DomainEvent, the outbox writer and its migrations,
                 audit rows, DispatchJob
    http         route values, scope(), the trie compiler, middleware (request id, logging, body limits, CORS,
                 security headers, CSRF), error envelope, ContextResolver, health and metrics endpoints, static assets,
                 ServerAdapter with `@fw/http/bun` and `@fw/http/node`, TestClient
    workers      the Workers port, snapshot publishing, per-worker pool sizing, the RequestDispatcher port with its
                 in-process and worker-pool adapters; worker_threads now, later scriptc
    ratelimit    RateLimiter, GCRA, the middleware, key resolvers; drivers memory, redis
    secrets      SecretResolver, SecretRef; drivers env, file
    httpclient   the HTTP client library (no `@fw/core` dependency) and its adapter (`@fw/httpclient/module`)
    flowcatalyst platform client, Definitions and sync, receivers, once and its purge, the outbox health check
    auth         JWT verification (RS256, JWKS cache), OIDC code + PKCE, sessions (cookie, database, redis), the
                 session ContextResolver; milestone T4a (decision 21)
    ui           Vue 3 + Nuxt UI component package: components, page partials, pages, theme preset (browser)
    test         fixtures: a database per test, Redis, fixed clocks and ids, the conformance reader
  examples/
    locks-demo
    orders-demo   server/ and web/ (the SPA)
  tools/
    fw-embed      generates TypeScript modules holding exact SQL, Lua and migration texts (section 2.5)
    fw-sql        checked SQL generator (T7, decision 14)
```

Drivers are **subpath exports** of their capability package (`@fw/locks/redis`), never separate
packages, the TypeScript form of Rust's features: an application that never imports the subpath
never loads the code. A separate package exists only where it is a different stack
(`@fw/httpclient` usable without the framework; `@fw/ui` is browser code).

### 2.3 Runtime, toolchain, package manager

- **Bun** (JIT, exact version pinned) for development, tests and running; **Node** (current LTS
  line, 24 now) supported and green in CI, and needed for scriptc's installer (decision 2).
- **Bun workspaces** (`bun install`, the text lockfile `bun.lock` committed, exact versions, a
  catalog of shared versions, lifecycle scripts off except `trustedDependencies`, a minimum release
  age for new versions if the pinned Bun supports it; check at T0). The Node CI job runs on the same
  installed tree (decision 3).
- **TypeScript 6.x**, exact pin.

### 2.4 Lint and format

typescript-eslint (type-aware) in a flat config, with the framework's rules in a local plugin
(decision 6):

| Rule | Where |
|---|---|
| no `as` assertions (`consistent-type-assertions: never`), no non-null `!` | domain and application code; framework code outside the one `unsafeBrand` file |
| no `throw`, no `try` | domain and application code (adapters and boundaries exempt) |
| no `class` | domain code |
| no recursion (a local rule: a function that calls itself by name; indirect recursion is scriptc's to find) | domain code |
| `no-restricted-syntax`/`no-restricted-globals`: `eval`, `Function`, `Proxy`, `Reflect`, `with`, decorators, computed `import()`, `WeakRef`, `AsyncLocalStorage` | everywhere on the server |
| `switch-exhaustiveness-check`, `strict-boolean-expressions`, `no-floating-promises`, `no-misused-promises`, `require-await` | everywhere |
| no import of an adapter, `node:*` or a driver package; no `bun` or `@scriptc/threads` import outside adapters | domain and application code; framework code outside adapters |
| no module-level mutable state (section 1.11) | everywhere on the server |
| indexed read with `!` | everywhere |

Formatting: one formatter (Prettier or Biome's formatter), chosen at T0; it is a development tool.

### 2.5 Exact texts: SQL, Lua and migrations

The shared statements (`conformance/sql/**`, `conformance/lua/**`), the outbox migrations (the
Java SDK's `001_create_outbox_messages.sql` byte for byte, then `V0002`) and every module's
migrations must reach the program as exact bytes, with no file system read at run time in a native
build. `tools/fw-embed` generates a TypeScript module per set with the texts as string constants
and their SHA-256, and a test compares each constant with its file byte for byte. (scriptc's
`comptime` could embed a file at build time, and Bun has text imports, but neither works on every
runtime; generated modules work everywhere.) Placeholders: the shared files use JDBC `?`; the TypeScript writer numbers them `$1, $2, ...`
when the module loads, as the Rust writer does.

### 2.6 Licence and publication

Apache-2.0. Dependencies' licences permissive only (Apache-2.0, MIT, BSD, ISC); a CI step lists
licences from the lockfile and fails on copyleft. Packages are `private: true`; nothing is published
to npmjs. Distribution outside this repository, if needed, is a private registry (decision 18).

## 3. Shared with the Java and Rust implementations, verbatim

Wire and storage contracts, checked by the conformance vectors ([`milestones.md`](milestones.md)
section 3):

| Area | What |
|---|---|
| outbox | the `outbox_messages` migration byte for byte (`V0001` = the SDK file, then `V0002`); event, audit and `DISPATCH_JOB` rows and payload text **byte for byte** (`conformance/outbox/outbox.json`), the shared `INSERT` (`conformance/sql/outbox/insert.sql`) |
| ids | TSID layout and encoding, prefixes, monotonic rules (`conformance/tsid/`) |
| errors | the envelope, every status and code of http section 5, `ErrorKind` to status |
| HTTP behaviour | routing rules, correlation id, health JSON (`UP`/`DEGRADED`/`DOWN`), body limit 413, drain 503 |
| receivers | signature rules, headers, envelope parsing, every response, scheduled-job completion bodies and truncation, the receiver principal ruling |
| config | key grammar, env names, sources and order, durations, `secrets.local.yaml`, collisions (`conformance/config/`) |
| secrets | `SecretRef` grammar, the `env` encoding, the `file` path rules |
| framework tables and keys | `fw_schema_history` (rows and SHA-256 checksums, `conformance/migrations/`), `fw_lock`/`fw_lock_fence` and the three statements, `fw_dispatch_once`; the Redis lock and rate-limit keys and Lua scripts |
| use cases | the envelope, the unit of work (one `READ COMMITTED` transaction: writes, events, dispatch jobs, one audit row), the failure table and step codes, the conflict policy, the authz model (`authorize` required, deliberate public grants), the receiver principal |
| metrics | names, labels, buckets, exact Prometheus text |

**Not shared:** the Datastar adapter (http section 10) and the `ui-kit/fixtures` goldens. TypeScript
applications use the Nuxt UI SPA (section 4), so those contracts do not apply; this is recorded as a
deliberate difference (decision 24), not drift. The kit's tokens and component specs remain the
visual reference.

## 4. UI: Nuxt UI on plain Vue, no Nuxt

Owner ruling: TypeScript applications replace Datastar with **Nuxt UI used without Nuxt**: Vue 3 and
Vite with Nuxt UI's Vite plugin. The SPA is built to static files and served by the application's own
server; no Nitro, no server-side rendering.

- **`@fw/ui`** is a shared package of Vue components, page partials and pages that **extend Nuxt
  UI** (never fork it): the application shell (sidebar, header, client switcher, user menu), list
  pages (Nuxt UI's table with the framework's filter toolbar and cursor pager), detail pages, form
  pages and form dialogs, confirm dialogs, drawers, empty and error states, the flash and toast
  stack, dirty-form guard, local time, JSON and code blocks, and the default pages (not permitted,
  not found, error, profile). The look follows `ui-kit/components/*.md` and `ui-kit/theme/tokens.css`.
- **Theme** through the Vite plugin's options (`ui.colors`, component slot overrides), with a preset
  exported by `@fw/ui` that maps the kit's tokens; applications override the same options.
- **Major version.** The owner named Nuxt UI v3. Since September 2025 v4 is the current major: it
  merged Nuxt UI Pro into the free package (dashboard layout, sidebar and page components are now
  free) and keeps the same Vite-plugin setup for plain Vue. Recommendation: v4 (decision 17).
- **Data**: the SPA calls the application's own JSON API on the same origin with the session cookie.
  The API's TypeBox schemas are imported as types by the SPA (a typed fetch client with no code
  generation), and as Standard Schema for forms.
- **Dependency policy:** browser code is exempt, but the package stays small: Vue, Nuxt UI (and what
  it brings: Reka UI, Tailwind CSS 4, TanStack Table), vue-router. Policy for state libraries in
  decision 19.

## 5. The FlowCatalyst SDK question

The TypeScript SDK exists (`@flowcatalyst/sdk` 0.11.28, Apache-2.0): a generated platform client
(`@hey-api/openapi-ts`, fetch-based), a definition synchroniser, webhook signature verification, an
outbox writer and unit of work, use-case types, TSIDs, cache, locks, a scheduled-job runner, and a
Fastify plugin with sessions and OIDC.

What the framework must **not** take from it: its outbox use-case layer (the framework owns the
writer, the envelope and the runner, and the SDK's error kinds differ from the contract), its
dispatch-job DTO (`metadata` written as an object, which the batch endpoint rejects for the whole
batch, and the fields the endpoint ignores; write-path section 7a), its lock tables (`fc_locks`, not
`fw_lock`), and its Fastify layer. Its outbox migration is identical to the Java SDK's file (checked
2026-10-13), so that part is consistent.

What the framework needs from a platform client: client-credentials tokens, definition sync (the
SDK's synchroniser is about 1 150 lines and is the canonical logic), scheduled-job completion and
log calls. Recommendation (decision 15): depend on the SDK's core entry, as the Java framework
depends on the Java SDK, **after** platform-repository work makes that entry pass the dependency
policy (`fastify-plugin` is a hard dependency today though only the `./fastify` entry uses it;
`neverthrow` is a dependency; the generated client must compile under scriptc), and fix the
dispatch-job `metadata` shape there. Until then `@fw/flowcatalyst` carries a minimal client over
`@fw/httpclient` for tokens, completion and log calls, and sync waits.

## 6. Considered and not chosen

- **Fastify, Nitro, Express, Hono as a dependency.** Owner ruling; also each brings a plugin or
  decorator model the framework does not want, and none is measured against scriptc.
- **A router library** (find-my-way, rou3, Hono's routers) and **a global route registry**. Owner
  ruling: the trie is a small piece of the framework's own code built from plain route values;
  registries and `app.get(...)` mutation are what `scope` and route arrays replace.
- **Claiming AoT now.** Bun's AoT compiler has not shipped; `bun build --compile` is a bundled
  executable, not native code (decision 2).
- **Node-only.** Considered as the boring baseline; the owner chose Bun for what it offers today,
  with Node kept green (decision 2).
- **Effect** (the SDK offers an Effect integration). A second programming model, generator-based
  (scriptc limits generators and async iteration), large, and its value (typed errors, DI, resource
  scopes) is what the framework provides with plain functions and `Result`.
- **neverthrow.** TypeScript-authored, but a class with methods, and a dependency for about 60 lines.
- **Zod or Valibot in the core.** The schema split (section 1.6) keeps one boundary schema; Zod is a
  browser-side option for forms only.
- **Compiled validators** (TypeBox `Compile`, Ajv). Code generation at run time.
- **AsyncLocalStorage for the context.** Design rule 2 (`Context` is passed explicitly), and its
  scriptc support is unknown.
- **An ORM or query builder** (Drizzle, Kysely, Prisma). Repositories are explicit SQL, as in Java
  and Rust; a query builder would also own the migration table.
- **libpq through FFI** as the long-term driver. FFI calls are synchronous (section 1.1).
- **Decorator-based routing or validation** (NestJS style). Decorators are out of the subset.

## 7. Open doubts, stated plainly

1. **scriptc eligibility of every library is unknown** until measured: TypeBox, `yaml`, `pg`
   (expected to fail: JavaScript), any Redis client, and the SDK. The design has a fallback for each,
   and each fallback is framework-owned code to write and maintain.
2. **WHATWG `Request`/`Response`/`Headers` under scriptc** are unverified (section 1.7).
3. **Phantom brands under checked casts** are unverified (section 1.5).
4. **`unique symbol` sealing** (used for `Valid`, `Authorized`, `Committed`, `Plan`): scriptc requires
   symbol keys on instance fields to be stable module-level `Symbol()` bindings, which these are; not
   yet measured. Sealing is type-level only: a cast defeats it, which only the lint rule prevents.
5. **scriptc's version.** The owner names 0.2.x; the docs show 0.3.0 in sample output. Its object ABI
   is marked experimental before 1.0. Pinning and measuring is cheap; relying on it is not planned.
6. **Two UI stacks.** The Datastar kit (templ in the Go platform, JTE in Java) and `@fw/ui` (Nuxt UI)
   share tokens and specs but no markup and no goldens. The platform's own admin is being moved off
   PrimeVue onto the kit (`ui-dogfood-plan.md`); TypeScript applications go the other way. Screens
   designed once are built in two technologies.
7. **Byte-for-byte JSON.** The outbox vectors compare payload text exactly. `JSON.stringify` and
   Jackson agree on ordinary text (key order is insertion order; non-ASCII is not escaped; `/` is not
   escaped), but number formatting (large and small floats), lone surrogates and the null rule
   (decision 25) need the framework's own writer or careful checks. Integer values above 2^53 cannot
   be represented as `number` at all; `bigint` fields need explicit encoding.
8. **No preemptive cancellation.** JavaScript cannot drop a running task the way Rust drops a future
   or Java interrupts a thread. Timeouts answer at once and signal an `AbortSignal`; the work runs
   until it notices or settles, which matches Java's documented rule for a handler that ignores its
   interrupt (capabilities section 11).
9. **Sharing the listener across workers.** `SO_REUSEPORT` (Linux; `Bun.serve({ reusePort })`, Node's
   `reusePort` on recent versions) lets each worker accept on one port, with kernel balancing and
   platform differences (macOS balances poorly); the alternative is a main thread that accepts and
   hands sockets to workers, which `node:worker_threads` cannot do with a live socket. What
   `@scriptc/threads` offers here is not documented.
10. **Adapters inside workers.** Whether `pg` and the RESP client over `node:net` work inside
    `worker_threads` without surprises (each worker has its own event loop and sockets) is unmeasured,
    and so is the same for `Bun.sql` and `Bun.redis`.
11. **scriptc and `@scriptc/threads` are new** (0.2.x per the owner). The worker design is built on
    the portable `node:worker_threads` API first, so it does not depend on them.
12. **Bun specifics:** `Bun.sql` semantics against the unit of work and outbox (section 1.1a), and
    how far Bun runs tests written against `node:test`, are unverified until the spike.
13. **The worker-pool dispatcher's value is unproven** for I/O-bound business applications; its
    hand-off cost per request on Node and Bun is measured by the spike, its benefit only under a
    CPU-heavy load (section 1.13).
14. **The routing syntax differs from the contract** (`:param` and a trailing wildcard against
    `{name}` and none). Until the contract is amended (decision 27) the TypeScript router is a
    documented deviation.

## 8. Owner decisions, ordered by how much they block

Ruled by the owner on 2026-10-13 and recorded here so the list is complete: routing as values with
no registry (section 1.7), the framework-written middleware and auth set with its rules (section
1.7a), worker-readiness (section 1.12). Decision 0 comes before everything else.

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 0 | **TypeScript or Go for business applications.** | The call the owner makes first; the T0 spike is meant to inform it. **TypeScript:** one language and shared types with the Nuxt UI frontend (wire schemas imported by the SPA, no codegen), a richer domain type system (discriminated unions as sum types, branded ids, `Result` with exhaustive switches), a larger pool of application developers. **Go:** stability with no runtime or compiler churn, a single static binary, real concurrency on every core without a worker design, and it is already the platform's language with `pkg/fcsdk` (the use-case envelope originated there) and the templ UI kit port. Against TypeScript: Node is single-threaded per process (section 1.12 is the answer, and it costs design), the native-compiler story is unsettled (section 1.1), the dependency rule means writing more ourselves (Redis client, YAML perhaps, schema checker perhaps), and errors-as-values goes against the ecosystem's throwing libraries. Against Go: no sum types (sealed interfaces and linters instead), more ceremony for the same domain rules, and the browser stays a second language. **The spike** ([`spike.md`](spike.md)) builds one vertical slice in TypeScript on Bun and Node (a Nuxt UI form page, a route on the framework's trie with TypeBox schemas, the `add-line` use case returning `Result`, the unit of work and the shared outbox `INSERT`) and the same slice in Go on `pkg/fcsdk`, and compares: lines and files per slice, what the type systems catch, conformance to the outbox vector, test time, start-up and memory, and how each reads to the people who will maintain it. Recommendation: decide after T0, not before. | T0 |
| 1 | **Where the code lives.** | `ts/` workspace in this repository (section 2.1). | T0 |
| 2 | **Runtime and compilers.** | **Ruled (owner, 2026-10-13, superseding a Node-only note of the same day): Bun in JIT mode for development, tests and running; Node supported and green in CI** (unit tests and conformance vectors on both). Why Bun: native TypeScript, a test runner, workspaces, built-in PostgreSQL, Redis and HTTP behind our ports, `bun build --compile` for one executable. Bun-specific APIs only in adapters. **No AoT now**: Bun's AoT compiler is not shipped (the chart's own footer; about 120 source patches; a CPU-heavy type-checking workload, not an I/O-bound service); add a build job when it ships. scriptc remains the later target with a non-gating coverage job; Perry is watched. Unverified: `Bun.sql` against the outbox and transaction semantics (the spike measures it). | T0 |
| 3 | **Package manager and workspace tool.** | Bun workspaces with `bun.lock` committed, exact versions, a catalog, lifecycle scripts off except `trustedDependencies` (esbuild for Vite, scriptc), a minimum release age if the pinned Bun supports it (check at T0). No Nx or Turborepo. pnpm is the fallback if Bun's installer lacks a supply-chain control we need; the SDK and the platform SPA use pnpm today. | T0 |
| 4 | **TypeScript version and options.** | TypeScript 6.x exact pin, the options of section 1.2 (`erasableSyntaxOnly`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `isolatedDeclarations` for packages); `.ts` runs directly on both runtimes, `tsc --noEmit` checks. | T0 |
| 5 | **Test runner.** | Tests written against `node:test` and `node:assert`, run by `node --test` on Node and by Bun on Bun, so one suite proves both runtimes. The spike checks how much of `node:test` Bun runs (hooks, mock timers, subtests); if it falls short, a thin framework shim (`describe`, `it`, hooks, mock timers) maps onto `node:test` or `bun:test`. Not vitest on the server. vitest only in `@fw/ui` and the example SPA, where Vue single-file components need Vite's transform. | T0 |
| 6 | **Lint tool.** | typescript-eslint, type-aware, with the rule set of section 2.4 in a local plugin; the domain rules need type information, which Biome and oxlint do not yet offer for these checks. | T0 |
| 7 | **scriptc: version pin, what is measured, gating.** | Pin the release current at T0 exactly; the spike runs `scriptc coverage --print=diagnostics` on the slice ([`spike.md`](spike.md)). After T0 the job runs on every package entry, **non-gating** as ruled. Until scriptc proves them, `NoThrow` and the other contracts are documented intent and the lint rules carry the weight; if the owner wants the proofs to bite, gate only `SC7001`-`SC7008` on domain entries. | T0 |
| 8 | **Boundary schema library.** | TypeBox 1.x, type builder and `Value.Check`/`Value.Errors` only, **if** T0 shows the `new Function` path stays out of the import graph and the spike's coverage run compiles it. Otherwise the framework's own `@fw/schema` builder for the same JSON Schema subset, with the same application API. | T0/T4 |
| 9 | **Branded ids: phantom brands or wrapper records.** | Phantom brands minted by one `unsafeBrand` (zero run-time cost); frozen `{ kind, value }` records if the spike shows scriptc rejects the cast. | T1 |
| 10 | **PostgreSQL driver adapters.** | Two adapters behind `SqlClient`, compared by the spike: `Bun.sql` (built in, no dependency) and `pg` (node-postgres: most used, explicit `query(text, values)` that fits the shared statement files, a checked-out client per transaction, its protocol package `pg-protocol` is TypeScript; needed on Node anyway). Recommendation: on Bun use `Bun.sql` **if** it passes the `SqlClient` contract suite and the outbox vector; else `pg` on both. Not postgres.js (tagged-template SQL fights the shared `.sql` texts). For scriptc later: a framework TypeScript wire-protocol driver over `SocketPort`, not libpq via FFI (FFI calls block the event loop). | T0/T2 |
| 11 | **Redis client.** | The framework's own RESP2 client (connect, `AUTH`, `PING`, `EVALSHA`/`SCRIPT LOAD` with `NOSCRIPT` reload, `GET`, `SET`, `DEL`, `TIME`, pipelining; a few hundred lines) over `node:net`, so it runs on Bun, Node and later scriptc. A `Bun.redis` adapter only if it exposes raw commands with `NOSCRIPT` errors intact and passes the lock and rate-limit suites. ioredis and `@redis/client` are TypeScript but large and unmeasured. | T3 |
| 12 | **YAML parser.** | `yaml` (TypeScript-authored) if T0 finds it within clauses 1 and 2, used through a framework function that also refuses duplicate keys (the Rust reader refuses them; Java keeps the last, a known difference); else a framework parser for the block-mapping subset the config files use. | T1 |
| 13 | **Migration tooling.** | The framework's own runner on `fw_schema_history` (contract and vectors shared), files embedded by `fw-embed`. Not node-pg-migrate, Knex, Drizzle Kit or Prisma Migrate: each keeps its own history table, which breaks shared databases (Rust decision 8). | T2 |
| 14 | **Checked SQL.** | Hand-written row parse functions (a row is `unknown`, parsed into domain types with `Result`) until T7. Then `tools/fw-sql`, a TypeScript port of the checked-SQL contract: `.sql` files with `-- name:` headers, described against a migrated PostgreSQL 18 over the wire (parameter types and row description), nullability from the catalog as Java does, generated modules committed so ordinary builds need no database. Not pgtyped or sqlc's TypeScript plugin: they generate code tied to one driver and their output would need the cast rule relaxed. | T7 |
| 15 | **The platform client.** | Depend on `@flowcatalyst/sdk`'s core entry after platform-repository work makes it pass the dependency policy and fixes the dispatch-job `metadata` shape (section 5); until then a minimal client in `@fw/flowcatalyst`. | T5 |
| 16 | **Receiver timeouts without cancellation.** | Dispatch: answer 503 at the timeout and abort the handler's `AbortSignal`; the handler keeps running until it settles. Scheduled job: report `FAILURE {"error":"timed out after …"}` at once and hold the overlap lock until the handler's promise settles. Both are Java's documented behaviour for a handler that ignores its interrupt. | T5 |
| 17 | **Nuxt UI major version.** | v4 (current major since September 2025; Pro merged in, free; same plain-Vue Vite plugin). The owner named v3. | T6 |
| 18 | **How the UI package (and the server packages) are distributed.** | pnpm workspace inside this repository for the examples. For applications in other repositories: a private registry (GitHub Packages, the scope the Java build plans to use), never npmjs, published only when the owner says so. Until then applications consume a git tag. | T6 |
| 19 | **vue-router and Pinia under the dependency rule (browser exempt).** | vue-router: yes, in `@fw/ui` (history-mode SPA, Nuxt UI's link and navigation components integrate with it, route meta carries required permissions). Pinia: not in `@fw/ui`; shared state is composables with `provide`/`inject` (the session, the current client, permissions). Applications may add Pinia. Forms: Nuxt UI's `UForm` with the framework's Standard Schema adapter over the TypeBox schemas; Zod allowed in application browser code. | T6 |
| 20 | **UI conformance.** | The `ui-kit/fixtures` goldens do not apply (Nuxt UI renders Reka UI markup). `@fw/ui` gets a gallery app (every component and page, light and dark) with Playwright screenshot comparisons and axe accessibility checks; the kit specs remain the behaviour and look reference. | T6 |
| 21 | **Authentication and sessions without Nitro (the Java M4c equivalent).** | The owner has ruled the pieces (section 1.7a: framework-written, `node:crypto` primitives only, Opus only, negative tests first, shared token vectors, its own milestone T4a). Open for the owner: **(a)** write the M4c contract once, language-neutral, before T4a code, with TypeScript implementing it first and Java and Rust following (recommended; it is the repository's contracts-first rule); **(b)** the shape: the application server as backend-for-frontend, OIDC code + PKCE as a public client, sessions with `cookie`, `database` and `redis` drivers, an opaque id or sealed cookie `HttpOnly; Secure; SameSite=Lax`, 401 for API calls and 302 for HTML navigation (as the TS SDK since 0.11.8), platform calls as the application's service account (login tokens cannot call `/api/*`); **(c)** id_token validation: verify RS256 always (recommended, the vectors cover it), rather than rely on OIDC Core's allowance to trust a token-endpoint response by TLS alone; **(d)** the default session driver: `database` (revocable, no key rotation to manage) rather than `cookie`. | T4a |
| 22 | **What orders-showcase proves.** | That a TypeScript service is interchangeable with the Java demo: the Java demo's migration unchanged, the same use cases and events, outbox rows byte for byte with the vectors, running against a database the Java runner migrated; that the domain core is pure, synchronous and written to the `NoThrow` rules (as far as the spike's scriptc coverage run proves it); that the edge parse turns a TypeBox DTO into a command with every field error collected; that routes are plain arrays composed with `scope`; one list page and one form page in Nuxt UI calling the API behind a login; a dispatch receiver running a use case with the service principal; the same tests green on Bun and Node; the same slice built in Go for decision 0. Not: performance, the admin area, workers (T4b has its own demo). | T0 (scope), T4 to T6 |
| 23 | **Package names and scope.** | Placeholders `@fw/*`, private. Settle the name with open question 6 before T6, together with the registry scope. | T6 |
| 24 | **Datastar and the HTML goldens are not implemented in TypeScript.** | Record http section 10 and the kit fixtures as not applicable to TypeScript, in the conformance README and STATUS. | T4 |
| 25 | **Null in event data.** | Follow the Rust ruling (2026-10-12): the framework's JSON writer leaves out null object members and null map values and keeps null array elements; domain types use optional fields, never `null`. The Java mapper keeps some nulls; a known, documented divergence. | T2 |
| 26 | **Static assets in the HTTP contract.** | Amend http section 11: the server may serve a built SPA from a directory (embedded later) through a trailing-wildcard route, with an `index.html` fallback for HTML navigations under the UI prefix, immutable caching for hashed assets, `no-cache` for `index.html`, and a strict CSP. | T6 |
| 27 | **HTTP contract amendments from the TypeScript rulings.** | Amend http section 2 to allow `:name` segments and one trailing wildcard (precedence static > parameter > wildcard), keeping every wire rule; amend sections 2 and 11 so CORS is an opt-in middleware with an explicit allow-list (off by default). Recommended for all three implementations, so the contract stays one text; the Java and Rust routers would accept both syntaxes. | T4 |
| 28 | **Threads and workers.** | Adopt the worker-ready rules and message-shaped handlers now (sections 1.12, 1.13; lint rules and conventions, free on one thread); ship the in-process `RequestDispatcher` (semaphore, bounded per-tenant queue, 429 when full) in T4; build the `Workers` port and per-worker pools in T4b on `node:worker_threads`; build the worker-pool dispatcher in T4c only if a measured CPU-bound load needs it, compared against the simpler `reusePort` alternative; choose the listener-sharing method in T4b from measurements on Linux (`reusePort` first); keep leader work on the main thread behind the existing locks; add the `@scriptc/threads` adapter only after scriptc builds the application. Pool sizing is per process, divided across workers. | T4b |

The pending Java rulings (rate-limit store failure default `ALLOW`, IPv6 /64, the memory overflow
bucket; service-scoped secrets and world-readable files; SDK publication and group id) apply to
TypeScript unchanged.
