# TypeScript framework: the T0 spike, a runnable plan (draft 1, 2026-10-13)

Status: a plan, not run. Owner go-ahead 2026-10-13. Companion to [`design.md`](design.md) (section 8
lists the decisions this spike informs) and [`milestones.md`](milestones.md) (T0).

## 1. Goal

Build one thin vertical slice twice, in TypeScript on the proposed design and in Go on the platform's
own SDK, and measure both, so that the owner can make **decision 0 (TypeScript or Go for business
applications)** on evidence, and so that the T0 decisions that depend on facts (runtime details, the
PostgreSQL adapter, TypeBox, brands, the test runner, scriptc readiness, the request hand-off cost)
are settled by measurement rather than by reading documentation.

The spike is throwaway code. It lives on a branch (`spike/t0`), under `ts/spike/` and `ts/spike/go/`,
and only its **results** come back to `main`, as `docs/typescript/spike-results.md` with the raw
outputs attached. Time box: 3 to 4 days of agent-assisted work; stop and report at the box even if
something is unfinished.

## 2. The slice (TypeScript)

One operation end to end: **add a line to an order**, the `add-line` use case of
[`orders-showcase.md`](orders-showcase.md), on the Java demo's `orders` and `order_lines` tables.

| # | Piece | What exactly |
|---|---|---|
| S1 | **Page** | `ts/spike/web/`: plain Vue 3 + Vite + Nuxt UI (v4, the Vite plugin, no Nuxt) single page with an "Add line" form (`UForm`, three fields) and the order's lines in a `UTable`. The form validates with a Standard Schema adapter over the **same TypeBox schema** the server checks. A server `400 VALIDATION` is mapped back onto the fields. Built to static files and served by the slice's server. |
| S2 | **Route** | `POST /api/orders/:id/lines` and `GET /api/orders/:id` as **route values** in an array, composed with `scope({ prefix: "/api" })`, compiled once into the framework's own **segment trie** (static > `:param` > trailing wildcard; the SPA fallback is a trailing-wildcard route). Params and body checked with TypeBox `Value.Check`/`Value.Errors` (closed objects); errors in the contract's envelope. A duplicate route in the array must fail the build. |
| S3 | **Use case** | `add-line` as in the showcase: a `NoThrow` `decide` function (pure, synchronous), an async shell, the edge parse from DTO to command with every field error collected, `Result` everywhere, `authorize` required (a test resolver supplies the principal). |
| S4 | **Unit of work + outbox** | the minimal runner: one `READ COMMITTED` transaction, the repository `UPDATE ... AND version = $n` and the lines rewrite, the event row written with **`conformance/sql/outbox/insert.sql`** (read as exact text, `?` numbered to `$1..$10`), one audit row, commit; stale version → retry per the policy. The outbox migration is the Java SDK file byte for byte (`V0001`) plus `V0002`. |
| S5 | **Two PostgreSQL adapters** behind one `SqlClient` port | **`Bun.sql`** (Bun's built-in client) and **`pg`** (node-postgres). The slice runs with either, chosen by configuration. |
| S6 | **Two runtimes** | the same server code on **Bun** (`Bun.serve` adapter) and **Node** (`node:http` adapter), the same tests on both. Also a `bun build --compile` executable of the server. |
| S7 | **Conformance** | a test that drives the slice's outbox writer with the inputs of `conformance/outbox/outbox.json` case 1 (fixed clock, ids, context) and compares every column and the **payload text byte for byte**. |
| S8 | **SqlClient contract suite** (small) | run against both adapters: a transaction on one connection sees its own writes and rolls back fully on error; `READ COMMITTED` is in effect; SQLSTATE `23505`, `40001`, `40P01` come back as the port's error kinds with the constraint name for `23505`; a two-statement migration file runs on the simple-query protocol; `int8` above 2^53, `numeric(20,4)`, `timestamptz` with microseconds and `jsonb` text come back without loss; parameters bind as text the way the shared statements expect. |
| S9 | **Hand-off cost** | a micro-benchmark for the `RequestDispatcher` worker-pool idea (design section 1.13): main thread → worker → main thread round trip of a representative request message (method, path, 20 headers, a resolved `Context`, a 2 KiB JSON body as a transferred `ArrayBuffer`) and its response, on Node and on Bun, with `node:worker_threads`. |

Rules the slice follows, so its friction is representative: no `as` outside one `unsafeBrand`
function, no `throw` or `try` in domain code, no classes in domain code, `noUncheckedIndexedAccess`,
`exactOptionalPropertyTypes`, `erasableSyntaxOnly`, the lint rules of design section 2.4 (at least
`consistent-type-assertions: never` and `switch-exhaustiveness-check`), Bun APIs only inside the two
adapters.

## 3. The Go slice (for decision 0)

The same operation in Go, written the way a Go business application on the platform would be today:

| # | Piece | What exactly |
|---|---|---|
| G1 | **Page** | the **same** built SPA (`ts/spike/web/dist`) embedded with `embed.FS` and served by Go, so the UI is held constant and the comparison is about the backend. (A templ page is optional and only recorded if time allows.) |
| G2 | **Route** | `net/http` `ServeMux` patterns (`POST /api/orders/{id}/lines`), JSON decode with unknown fields refused, the contract's error envelope. |
| G3 | **Use case** | `pkg/fcsdk/usecaseop` (the envelope the TypeScript design mirrors): validate, authorize, execute returning a plan. Domain types as Go would write them: named types for ids, constructor functions returning `(T, error)`, a sealed-interface or status-field order. |
| G4 | **Unit of work + outbox** | `pkg/fcsdk` with pgx (`usecasepgx`, `outboxpgx`), on the same tables. |
| G5 | **Conformance** | the same case-1 vector comparison. A difference between the Go SDK's row and the vector is a **finding** to report (the SDK writer is not the framework's), not a spike failure. |

The Go slice lives in `ts/spike/go/` as its own module (not built by Maven or Cargo) and depends on
`flowcatalyst-go/pkg/fcsdk` through a `replace` directive to the sibling checkout.

## 4. Isolation: never the development database

Owner rule. The spike **never** touches the developer's PostgreSQL on port **15432** or anything
listening on port **8080** (the local platform), and never uses a database it did not create.

- Every run starts its **own throwaway PostgreSQL 18**: Testcontainers when Docker is available,
  otherwise an embedded server (`initdb` + `pg_ctl` into a temporary data directory under the spike's
  own `tmp/`, deleted afterwards), on a **free port chosen at run time above 20000**.
- The harness **refuses to start** if the configured database URL or any server port is 15432, 5432
  or 8080, or if the database already contains tables it did not create.
- The slice servers bind to free ports above 20000 too. Nothing is installed globally except the
  pinned scriptc release (needs Node 24+), and that only in CI or a disposable environment.
- Redis is not part of the slice.

## 5. Measurements

Every number is recorded with the machine, the runtime versions and the command used. Warm,
steady-state numbers are quoted for long-running servers; start-up is measured separately.

| # | Measurement | How | Recorded as |
|---|---|---|---|
| M1 | **scriptc coverage** of the slice | pinned scriptc, `scriptc coverage <entry> --print=diagnostics` for entries: domain (`decide`, value objects), api (schemas and edge parse, so TypeBox), router, runner, each adapter, and the `pg` package | statements static %, blockers grouped by code, deferred operations, divergences, npm package outcomes, and whether `NoThrow`/`Acyclic`/`Int32` annotations were proved (`SC7001`-`SC7008` absent) |
| M2 | **Start-up time** | process start to the first `200` from `/health/live`, median and p95 of 20 runs: Bun, Node, the `bun build --compile` executable, Go | ms |
| M3 | **Memory** | RSS idle after start, and after 10 000 `add-line` requests at concurrency 50 | MB, per runtime and Go |
| M4 | **Request latency and throughput** (context only, not a goal) | `oha` against `POST /api/orders/:id/lines`, concurrency 50, 60 s after a warm-up | p50/p99 ms and req/s; Bun with each adapter, Node with `pg`, Go |
| M5 | **Lines of code** | `scc` per layer: domain, api/handlers, use case and repository, wiring, tests | lines and files, TypeScript against Go |
| M6 | **Type-safety friction** | a log kept while building: every cast, `any`, lint exception, `@ts-expect-error`, workaround for a scriptc or TypeBox limit; then **ten seeded bugs** applied to each slice one at a time (an order id passed as a line id; `authorize` removed; a new order status not handled; a nullable column read as required; a route param renamed in the path only; the response shape changed; the version check dropped from `UPDATE`; a blank customer accepted; a field added to the request body; the wrong event type string) | for each bug: caught by the compiler, by lint, by a test, at start-up, or not at all; TypeScript and Go side by side |
| M7 | **Test run time** | the slice's unit and integration tests, warm, three runs: on Bun, on Node, and the Go tests | s |
| M8 | **Bun and `node:test`** | the TypeScript tests are written against `node:test`/`node:assert` and run under Bun | which features worked (hooks, subtests, mock timers, `--test-name-pattern`) |
| M9 | **TypeBox and `new Function`** | trace the import graph of `typebox` + `typebox/value` as the slice imports them; search the loaded modules for `new Function`/`eval` | present or absent on the loaded path |
| M10 | **`Bun.sql` contract** | S8 against `Bun.sql` and `pg` | pass/fail per case |
| M11 | **Hand-off cost** | S9: round-trip time per message and the main thread's ceiling (messages per second on one core), on Node and Bun | µs and msg/s |
| M12 | **Single executable** | `bun build --compile`: size, start-up (M2), whether the migrations, shared SQL and SPA assets embed and load | MB, yes/no |

## 6. Pass and fail criteria

**Must pass**, or the TypeScript design needs rethinking before T1 (report, do not patch around):

1. The TypeScript slice's outbox event row equals the vector **byte for byte** (S7).
2. The slice runs, and its tests are green, on **both Bun and Node** (S6), with at least one PostgreSQL
   adapter passing S8 on each runtime.
3. The Nuxt UI page builds and runs under plain Vue + Vite with no Nuxt, and the form validates with
   the server's TypeBox schema through the Standard Schema adapter (S1).
4. The rules of section 2 held: no `as` outside `unsafeBrand`, no `throw` in domain code; the router
   refused the seeded duplicate route.

**Decide by threshold:**

| Question | Rule |
|---|---|
| `Bun.sql` or `pg` on Bun (decision 10) | `Bun.sql` if it passes every S8 case **and** S7; otherwise `pg` on both runtimes, with the failing cases reported |
| TypeBox or the framework's own schema checker (decision 8) | keep TypeBox if M9 finds no `new Function` on the loaded path **and** M1 shows no blocker in `Value.Check`'s path; otherwise plan `@fw/schema` (about 600 lines) |
| Phantom brands or wrapper records (decision 9) | phantom brands unless M1 reports the brand cast as a blocker or a divergence |
| scriptc readiness (decision 7) | confirmed if the domain entry has **zero blockers** and the contracts are proved; blockers in adapters and npm packages are expected and only listed; blockers in domain code change the rules in design section 1.4 |
| Test runner (decision 5) | `node:test` on both runtimes if M8 shows hooks, subtests and mock timers working on Bun; otherwise the thin shim |
| Worker-pool dispatcher (design section 1.13, decision 28) | keep it on the roadmap only if the hand-off round trip is under about 50 µs and the main thread's ceiling is well above the slice's measured single-thread throughput (M4); otherwise prefer `reusePort` workers and drop the central queue |
| Single executable | adopted as the deployment shape if M12 embeds and runs everything and start-up stays under 1 s |

**TypeScript or Go (decision 0)** has no single threshold. The results page presents, side by side:
correctness parity (S7/G5), M5 lines of code, M6 seeded bugs caught and the friction log, M2/M3
start-up and memory (Go is expected to win both; TypeScript is acceptable at under 1 s start-up and
under 150 MB idle RSS on Bun), M4 for context, M7 test time, and a short written judgement of how each
slice reads to someone maintaining it. The spike makes a recommendation; the owner decides.

## 7. What the spike informs

| Decision (design section 8) | Settled or informed by |
|---|---|
| 0 TypeScript or Go | everything in section 6's last paragraph |
| 2 Runtime (Bun now, Node green) | S6, M2, M3, M7, M8, M12: confirms the ruling or reports a blocker |
| 3 Package manager | how `bun install` workspaces, `trustedDependencies` and the lockfile behaved for the slice and the Vite build |
| 5 Test runner | M8 |
| 7 scriptc | M1 |
| 8 Schema library | M1, M9 |
| 9 Brands | M1, M6 |
| 10 PostgreSQL adapters | S8, M10, M4 |
| 17, 19 Nuxt UI v4, forms | S1 |
| 22 What the orders showcase proves | the slice is its first fifth |
| 28 Workers and the dispatcher | M11 |

## 8. Steps (in order)

1. Branch `spike/t0`; `ts/spike/` workspace (Bun workspaces, TypeScript 6.x, the lint subset); the
   isolation harness of section 4 first, with a test that it refuses 15432, 5432 and 8080.
2. S4 and S7 first (the outbox writer against the vector), on `pg` under Node, because it is the
   must-pass with the least room for interpretation.
3. S5 and S8: the `SqlClient` port, both adapters, the contract suite on both runtimes.
4. S3, then S2 (router, schemas, envelope), then S6 (both server adapters).
5. S1 (the page), served by the slice.
6. G1 to G5 (the Go slice).
7. S9 and the measurements M1 to M12, in that order; M6's seeded bugs last, on both slices.
8. Write `spike-results.md`: the tables, the friction log, the recommendation per decision, and every
   surprise. Commit the results to `main`; leave the code on the branch.
