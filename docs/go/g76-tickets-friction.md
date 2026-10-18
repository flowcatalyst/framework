# G7.6: the tickets trial in Go, differences from Java and the friction log

Step G7.6 of [`scoping-2026-10-18.md`](scoping-2026-10-18.md), owner decision 1 (the tickets trial in Go is
the parity gate). The Java service is [`examples/tickets-trial`](../../examples/tickets-trial) with its
analysis and friction log in [`m4f-trial-service.md`](../m4f-trial-service.md); this page is the Go side of
the same exercise, kept in the same form: a second service built only on the Go framework's public
packages, and what it made hard.

The Go service is `go/flowcatalyst/examples/tickets-trial` (module `.../go/flowcatalyst`, because the whole
service depends on the platform):

| Path | What |
|---|---|
| `domain/` | typed ids (`tkt_`, `cmt_`), priority, status, action, the status machine, the `Ticket` aggregate with its comments |
| `tickets/` | settings (`tickets.sla` in `clientsettings`), permissions and roles, events, commands, `Repository` (sqlc), reads, **six use cases**, **eight operations** + the admin overview (**nine**), definitions, the notify receiver, the SLA sweep, the gauge loop |
| `sql/`, `internal/dbq/`, `tools/fwsql/` | the checked SQL (`TicketSql.sql` ported), `make -C go sql-check` runs it |
| `ticketsweb/` | `Declare`/`BuildWeb` (the whole application), the fake platform and notification service, the e2e launcher, the tests |
| `web/` | the embedded SPA placeholder and the committed `openapi.json` |
| `e2e/playwright.config.ts` | the kit's `defineE2eConfig` with the Go binary as web server |
| `main.go` | `tickets-trial` (serve), `sync`, `openapi`, `e2e` |

## Results

| Check | Result |
|---|---|
| OpenAPI document, `tickets-trial openapi` against `examples/tickets-trial/web/openapi.json` (Java's) | **byte for byte equal** (`cmp` exit 0, and `TestTheCommittedDocumentIsCurrentAndIsTheJavaServicesDocument`), on the first generation: no Go-side change was needed |
| `make -C go e2e-tickets` (`ui/apps/tickets/e2e`, unchanged, Chromium, ports 4394 and 4395) | **24 passed**, 0 failed, 0 skipped (Java: 24) |
| Go tests of the service | **36**: the Java suite's 35 mirrored one for one (use cases 13, API 9, platform 10, document 1, SPA 2) and one more, the repository contract (`usecasetest.Repository`) |
| `make -C go sql-check` | clean, tickets included (the target now checks both examples) |
| `make -C go go-check` (gofumpt, golangci-lint, `go vet` with fwvet, NilAway, `go test -race`, sql-check) | clean for both modules |
| Mutation check | the sweep flagging with the platform's context instead of the ticket's client: `TheSweepFlagsEvery...` fails |

## What the Go framework did without a change

Everything the Java trial needed was already there after G7.2 to G7.5: `Context.ForClient`/`fw.EachClient` for the
sweep, `LoadedUseCase` + `RecordAuthorizer` for the three record-level use cases, `clientsettings` for the SLA
(same table, key and JSON as Java), the typed operations and the generated document, `httpfw.SessionsGuard`, the
session store and `auth.Testing`, the receivers with the overlap lock, `locks.ServiceGate` for the gauge loop, the
named HTTP client and the `env` secret store. **No framework package was changed** to build the service. The three
changes outside the example: the Makefile (`e2e-tickets`, `sql-check`), the docs, and nothing else.

## Differences from Java

Behaviour a client can see is the same (the 24 browser tests and the 35 mirrored tests are the evidence). These are
the differences in how it is built and in details nothing asserts.

| # | Java | Go | Why |
|---|---|---|---|
| D1 | One Maven module `tickets-trial` | In the `flowcatalyst` Go module (`go/flowcatalyst/examples/tickets-trial`) | The service imports the platform packages, which only that module has. `orders-demo` splits a library (core module) from a command (platform module); a service that always has the platform cannot |
| D2 | 14 modules (`TicketsModule`, `TicketsApiModule`, ...), `register`/`boot` | `ticketsweb.Declare` (declaration phase) and `BuildWeb` (one build function, about 150 lines) | The Go design has no container; the order is explicit (friction G76-4) |
| D3 | `UseCase.named(...).validate(...).authorize(...).load(...).authorizeLoaded(...).execute(...)` | A struct per use case with methods; `usecase.Define` or `usecase.DefineLoaded`; `AuthorizeLoaded` is the optional `RecordAuthorizer` | Owner decision 2 of the scoping. The optional interface is found at run time (friction G76-3) |
| D4 | `requires(permission)` answers `PRINCIPAL_REQUIRED` or `FORBIDDEN` | The same codes, written by the service around `usecase.Require` (whose own code is `PERMISSION_REQUIRED`) | Wire parity (friction G76-5) |
| D5 | Enum body fields rejected by Jackson | Strings with `EnumValues` plus a `validate.OneOf` rule | See friction G76-6 |
| D6 | Absent values are `null` in the aggregate | `""` for a string, `nil` for a time, converted at the repository and the wire (`omitzero` pointers) | Go has no nullable string; the wire is identical (the field is absent) |
| D7 | Length rules count UTF-16 units everywhere | The HTTP validators do (`validate.MaxLength` is UTF-16 as Java's); the use cases' `Validate` counts runes | A receiver-run use case would accept an astral-plane string Java refuses at the same length. Nothing asserts it |
| D8 | `hasNonNull("assigneeId")` | An empty `assigneeId` or `ticketId` in a delivery is "missing" (rejected) | Typed delivery data cannot tell empty from absent |
| D9 | The completion result is a `LinkedHashMap` | A `struct{Flagged, Skipped}` | Same JSON, same key order |
| D10 | Checked SQL generated by `framework-sql` | sqlc (`sqlc.yaml`, `tools/fwsql`), the framework's Go mechanism; the queries are the same file, with `sqlc.narg` for the optional filters | Design 5.1. Enum columns come back as `string` and the repository validates them |
| D11 | In-memory `TestClient` for the API tests | A real HTTP server on a reserved port with `auth.Testing` cookies | The Go `httpfw` has no in-memory client over the session middleware (friction G76-11) |
| D12 | `FakePlatform` answers the sync and the instance callbacks | `fakeplatform` answers the token endpoint only; the example adds the sync and instance routes | Friction G76-2 |
| D13 | `ReceiverTesting` signs deliveries | The example re-implements the signature, the firing body and the envelope (`ticketsweb/platform.go`) | Friction G76-1 |
| D14 | One test run starts Testcontainers PostgreSQL | One embedded PostgreSQL per test binary, a database per test; the first start of a binary takes about 28 s | `fwtest`; a fixed cost per test binary, not per test |

## Friction log

Severity: **blocker** (could not do it the intended way), **high** (works but a second team would need the same
workaround), **medium**, **low**. All are open: nothing was fixed in the framework by this step. Numbering is this
page's (G76-n); Java's F-numbers are in `m4f-trial-service.md`.

| # | What I tried | What the framework made hard | Sev | Suggested fix |
|---|---|---|---|---|
| G76-1 | Test the receivers: post a signed delivery, fire a job, build the dispatch envelope from an outbox row | There is no public helper. The signature (`hmacHex`) is package-private in `flowcatalyst`; the example re-implements `Sign`, `FiringBody`, the envelope builder and the signed request (about 100 lines, in non-test code because the browser launcher needs them too). This is Java's F12 again, which Java fixed with `ReceiverTesting` | medium | A `flowcatalyst/flowcatalysttest` package with `Sign`, `Delivery`, `FiringBody` and `EnvelopeOf(outboxRow)` |
| G76-2 | Run the service against `flowcatalyst/fakeplatform` | The fake answers `/oauth/token` and canned routes; the sync endpoints (`POST /api/applications/{app}/{resource}/sync`) and the instance callbacks (`/api/scheduled-jobs/instances/{id}/complete\|log`) 404, so every service registers them (`StartPlatform`, 70 lines) and the module's own tests keep a private, richer fake (`fake_test.go`) | medium | Move the sync and instance routes, with recording and `AwaitInstanceCalls`, into `fakeplatform`; delete the private copy |
| G76-3 | The record-level rule of `add-comment` (`AuthorizeLoaded`) | `RecordAuthorizer` is found by `any(uc).(RecordAuthorizer[C, L])` at run time. A method with a drifted signature (a changed command type, a pointer receiver) **silently skips the access check**. I added `var _ usecase.RecordAuthorizer[AddCommentCmd, usecase.Loaded[domain.Ticket]] = addComment{}` by hand; nothing makes a service do that. A security check that disappears without an error is worse than the one Java's builder cannot lose | **high** | `DefineLoadedChecked(r, uc)` taking a `CheckedLoadedUseCase` that requires `AuthorizeLoaded`, so the choice is explicit and the compiler checks it; or a `go vet` analyzer in fwvet for types that have an `AuthorizeLoaded` method but do not satisfy the interface |
| G76-4 | Assemble the application | `BuildWeb` has hard ordering rules that fail late: `RegisterReceivers` before `fc.Routes()` (else `ErrSealed` at start-up), locks before `flowcatalyst.Build` (else the sweep's overlap lock is missing), the gauge loop needs the same `*Locks`. Each `Build` returns a handle that has to be threaded to the next. It is correct and explicit, but it is 150 lines every service re-learns, and the two examples already differ in order | medium | A documented order in the flowcatalyst guide page, and a `flowcatalyst.Assemble` helper that takes the locks and databases it needs, or a compile-time staging (the registry type changes when sealed) |
| G76-5 | `Authorize` for "a signed-in person holding the permission", and for "the service principal only" | `usecase.Require` answers `PERMISSION_REQUIRED`; Java's service answers `FORBIDDEN` and `PRINCIPAL_REQUIRED`, so the service wraps it. A check that is neither `Public()` nor `Require` (service-only) can only return `usecase.Public()`, which reads as "deliberately open to every caller" and is greppable as such, so a reviewer looking for open use cases finds `flag-sla-breach` | medium | `usecase.Allow()` (or `Granted(reason string)`) for a hand-written check, kept apart from `Public`; `Require` with an option for the code |
| G76-6 | A body with an enum field (`priority`, `action`) | The decoder accepts any string; unless the service adds `validate.OneOf(...)` listing the values a second time, `"SOON"` is stored. The type already says `EnumValues()`. Forgetting the rule is silent. (Query parameters of an enum type are checked automatically: `must be one of: ...`.) | **high** | The validator (or the decoder) rejects a string-typed `EnumValuer` field outside its set by default, with the same `NOT_ONE_OF` error, and `OneOf` stays for non-enum sets |
| G76-7 | `fwvet` on the service | `mustliteral` does not know `secrets.MustParseRef`, so a package-level `MustParseRef("notify-key")` fails vet; the service wraps `NewRef`. And `MustErrorCode` must be a literal, so a code that travels in data (`SlaMinutes.Check` returns `Problem{Code}`) needs `ParseErrorCode` and an error path that cannot happen | low | Add `MustParseRef` to `cmd/fwvet/mustliteral/checks.go`; document the second as intended |
| G76-8 | Check the SQL | The service copies `sqlc.yaml` (28 lines), `tools/fwsql/main.go`, the generated `.fw/schema` and `internal/dbq`. Each typed id column needs its own override (`tickets.id`, `ticket_comments.ticket_id`); a nullable `timestamptz` needs a second override; an optional filter is `sqlc.narg('x')::text`; a column that Java declares as an enum comes back as `string`. `make sql-check` was hard-coded to `orders-demo`. It works (the check passed first time), but it is a recipe, not a template | medium | `go run github.com/.../go/cmd/fwsql init` writing `sqlc.yaml` and `main.go` for a module list; a `sql-check` target that finds every `tools/fwsql` |
| G76-9 | The browser launcher | `ticketsweb/e2e.go` is 330 lines, of which 250 are the same as `ordersweb/e2e.go` (arguments, ports, `--dist`, provider, embedded PostgreSQL, the control server, `person`). It imports `internal/pgembed`, so it can only live inside the framework tree. This is Java's F22 | medium | `fwtest/e2e` (or `internal`-free `e2e`): `e2e.Run(ctx, args, e2e.Options{Provider, Database, Controls...})` with the kit's standard controls |
| G76-10 | Serve the SPA | `go:embed` cannot reach `ui/`, so the binary embeds a placeholder `web/dist` unless someone copies the build in; the e2e target works around it with `--dist`. A deployment build has no step for it (the Java pom copies `web/dist` into the jar) | low | A `make` target or `go generate` that copies the build into `web/dist`; a start-up warning when the embedded `index.html` is the placeholder |
| G76-11 | API tests with cookies | The Java tests use an in-memory `TestClient` over the routes and the resolver. In Go the session middleware lives in `httpfw.NewServer`, so the tests start a real server on a reserved port (about 0.9 s a test). `httpfw.Compile` gives a `Dispatcher`, but wiring the auth resolver into it by hand is as much code as the server | low | `fwtest.Handler(run)` returning the started application's `http.Handler` for `httptest.NewRecorder` |
| G76-12 | Test the repository | `usecasetest.Repository` (the contract tests) exists and found nothing; the Java service has no equivalent. Worth saying: it ran in 0.1 s and is the one test that would catch a repository that ignores the version | info | Mention it in the Java guide as a gap, or port it |
| G76-13 | Absent values | A Go domain has `""`, `nil` times and `*string` at the edge; `nullable()`/`deref()` appear in the repository, the queries and the views (Go 1.26's `new(expr)` removed the other helper) | low | None: this is Go. A generic `ptr`-or-nil helper in the framework would be used by every service |
| G76-14 | Run the use case from a receiver | `fw.EachClient` and `RunJoined` need no help; the sweep's `ForClient` contexts, error recording and cancellation worked as the contract says. Java's F3 and F15 are fixed in Go as well | none | |
| G76-15 | Go's `config` with `DeclareCore` | A core-only application (`DeclareCore`/`BuildCore`) reads the full `application.yaml` without complaint, including the `flowcatalyst:` keys of a component it does not declare. Java's `TicketsCore` behaves the same. (I expected a start-up error and wrote a temporary file; the code is simpler without it.) | none | |

What worked at once, since a trial records both: the generated OpenAPI document (equal on the first run, which
exercises enum query and body fields, a generic `Page[T]`, `PUT`, optional pointers, error documentation, the
auth and admin operations), the typed `Path`/`Query`, `httpfw.With` for the per-person limiter, the
`usecase` runner's failure table (`PERSIST`, `STALE_AGGREGATE`, cross-client refusal) against the same assertions as
Java, the retry policy (the two gated-repository tests, written with a channel instead of a latch, passed
under `-race`), `clientsettings` (version 1 then 2, a second client unaffected, the Java JSON), `auth.Testing`
cookies and the real round trip in the browser, the strict CSP with the build's `csp-hashes.json`, and the
overlap lock (`lock-held`) and the signature check without any service code.

## Not verified

- **A real platform.** Like the Java trial: the sync, the token and the job callbacks are answered by
  `fakeplatform`; the outbox is never delivered (the tests read the rows and play the delivery). That is G7.7.
- **A second replica.** The overlap lock and the gauge gate are tested against one process.
- **Other browsers**, screenshots.
- **Java and Go on one database.** The migrations are the Java files byte for byte and the settings JSON is the
  Java record's, but no test starts the Go service on a database the Java service wrote (the orders demo has
  `java_db_test.go` for that; the tickets service does not).
- **Throughput.** Nothing was measured (G7.8).
- **The Java launcher against the Go suite order.** The Go suite ran alone on ports 4394 and 4395; the Java and
  Go suites of one application must not run together.

## Owner decisions

1. **G76-3 (a silently skipped record check)** is the one finding I would not leave open: change the loaded
   use case's definition so the record-level check is part of the type, or accept the compile-time-assertion
   convention and write it into the guide.
2. **G76-6 (enum bodies)**: reject unknown enum values in the decoder by default? It changes the document-driven
   rules of `httpfw/validate` (no wire change for a valid request).
3. **G76-9 (the launcher)**: build `fwtest/e2e` now (two launchers are copies) or after the third application.
4. **Whether the tickets trial replaces the Java one as the parity gate in `STATUS.md`**: it passes the same 24
   tests and writes the same document; the remaining gate is G7.7 (a real platform).
