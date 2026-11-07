# Framework fixes from the fulfil-go port (2026-11-07)

Owner approved 2026-11-07: fix these in the Go framework (primary) **and** the Java framework.
Source: fulfil-go `apps/fulfil-go/docs/port-plan.md` section 7 (issue numbers in brackets) and the
owner's summary. fulfil-go works around each today; it drops its workarounds after this lands (its own
session does that, not this work).

Every change: a test that fails without it (prove red first), a mutation check, docs in the same change
(`docs/guide/...`, `docs/contracts/...`), Go and Java names parallel as below. No wire change to the
platform: everything here uses fields the platform already accepts.

## A. Core (Go: `usecase`, `fw`, `auth`, `flowcatalyst`; Java: framework-usecase, framework-auth, framework-flowcatalyst)

### A1. Dispatch jobs carry their client, idempotency key and connection [16]
- **Today:** `usecase.DispatchJobPayload` (Go) / the Java `OutboxWriter` dispatch-job item write no
  `clientCode`, `idempotencyKey` or `connectionId`. A job created in a client's context is delivered with
  no client, so the receiver acts for the platform client.
- **Change:**
  - Write the Context's client as `clientCode` when the context has a real client (not the platform
    client) and no explicit platform client id is set.
  - New job options: `IdempotencyKey(key)` and `ConnectionID(id)` (Go); `idempotencyKey(..)` /
    `connectionId(..)` on the Java builder. Explicit only: never derived from ExternalID (a shared
    external id must not silently merge two jobs). The platform scopes keys by application and client,
    keeps them 7 days, and answers a repeat with the first job's id.
  - The receiver: a delivery that names a client (the platform sends `X-FlowCatalyst-Client` / the
    envelope's client) runs in that client's context. Prove end to end with the fake platform: a job
    created in client C's context is delivered, and the receiving use case's context is C.
  - Document which account signs a job sent without `connectionId`: the platform falls back to the oldest
    active service account of the application named by the job code's first segment (flowcatalyst-go
    `internal/server/delivery_creds.go`; verify there, read-only). With `connectionId`, the connection's
    account signs it.

### A2. `usecase.Append`: an append-only row in the plan's transaction [8]
- **Today:** a plan holds only aggregate Insert/Update/Delete steps. fulfil-go fakes append-only rows
  (activity log, live-update log, projections) as insert-only "aggregates", placed after the real aggregate
  because the audit row names the first step.
- **Change:** `usecase.Append(row, appender)` (Go: generic over the row type, an `Appender[R]` with
  `Append(ctx, tx, R) error`; Java: `Plan.append(row, appender)` or the Java plan idiom). It writes in the
  same transaction, in plan order, is never the audit row's subject and never version-checked. A plan still
  needs at least one aggregate step (the audit row's subject); a plan of appends only is refused with a
  clear error. Docs: when to use Append versus an aggregate.

### A3. `RequireService` and a service context for webhooks [18]
- `usecase.RequireService(cx)` (Java: `Grants.requireService(cx)` or the Java grant idiom): granted only
  when the context's principal is a service.
- A constructor for the context of an inbound provider webhook that has no platform principal:
  `fw.ServiceContextFor(client, principalID)` (Java equivalent), replacing
  `fw.ContextFor(client).WithPrincipalOfKind(id, PrincipalService)` by hand.

### A4. The field worker's name on the context [20]
- `fw.Context` gains the subject's display name (`cx.SubjectName()`; Java `subjectName()`), set by the
  auth layer for field workers (the token's `name`) and for users where the token carries one. `Actor`
  stays the delegated operator (the `act` claim).

### A5. Field tokens are checked against the path's client [10]
- Field workers bypass the client selector, so a path naming client X accepts a field token of client Y.
  The resolver must refuse that (403, the same code a user of another client gets). fulfil-go checks it in
  every handler (`sameClient`) today.

### A6. `PlatformClient.BaseURL()` and an SDK client [11]
- `PlatformClient.BaseURL()` and `PlatformClient.SDK()` (Go: a `github.com/flowcatalyst/go-sdk` client
  using the platform client's token, clearing it and retrying once on a 401; Java: the Java SDK client
  likewise, if the Java framework has the SDK on its path, else `baseUrl()` only, say which).
- Go: `go/flowcatalyst/go.mod` requires go-sdk `v0.0.15` (it says v0.0.12).

### A7. Signed-delivery test helpers in the framework [19]
- `fakeplatform.Deliver(...)` and `fakeplatform.Fire(...)` (Java: the fake platform's equivalents) build a
  correctly signed dispatch delivery and a scheduled-job firing (signature, envelope, firing body) for any
  application's tests. The tickets example's copies move here and the example uses them.

### A8. Conflict default guidance [21]
- Docs only: the use-case guide says plainly that the default conflict policy is Fail, that receivers and
  process use cases usually want `Retry`, and shows the opt-in.

## B. HTTP, storage, secrets (Go: `httpfw`, `fwtest`, `objectstore`, `secrets`; Java: framework-http(-jetty), objectstore, client-settings/secrets)

### B1. A pre-drain hook; streams close before the drain [17]
- Shutdown actions run after the HTTP server drains, so a long-lived response (SSE) holds the drain until
  its grace runs out. Add a hook that runs when shutdown begins, before the drain (Go:
  `Server.BeforeDrain(func(ctx))` or an option; Java equivalent). B3's SSE helper registers itself there.

### B2. OpenAPI problems at start-up [14]
- Problems (a plain `int` field, an embedded struct, a nullable pointer without omitzero) show only when the
  document is written. Build and check the document in `httpfw.NewServer` (fail start-up with the
  problem), and add `fwtest` helper(s) that assert it builds. Java: the same check if Java has the same
  late failure; say if it does not.

### B3. httpfw gaps fulfil-go built itself [6]
Lift fulfil-go's versions where they are sound (read them in
`/Users/andrewgraaff/Developer/inhance/mono-repo-fulfil-go/apps/fulfil-go/server/internal/`: streams,
idempotency, app):
- an SSE writer: heartbeats, `Last-Event-ID` replay through an application-supplied source, closes on B1;
- inbound `Idempotency-Key` middleware (storage via the framework's database, a framework migration set);
- a per-route request body limit (today only the global one);
- CORS (config-driven, off by default);
- objectstore: store and return the content type (S3 `ContentType`; the folder store keeps it beside the
  object; memory keeps it);
- a Go secrets driver matching what the Java framework has (check the Java one; fulfil-go's notes say
  "a Go secrets driver").
For each: Java gets the same where it lacks it; report what Java already has.

### B4. `fwsql generate` without compiling the application [15]
- The migration sets are exported by the packages that use the generated queries, so the first generate of
  a new package needs a stub. Make generate read migration directories by path (or move migration sets to
  leaf packages; pick one and say why). Java: check whether framework-sql-maven-plugin has the same
  problem; report.

## C. Docs that disagree with the code [docs 14–17]
- `docs/handover.md:301` says the Go framework is parked: the owner ruled 2026-11-06 it is unparked and the
  default.
- `docs/guide/go/flowcatalyst.md:192`, `handover.md:302`, `docs/contracts/go-build.md:17`: the SDK is
  `github.com/flowcatalyst/go-sdk`, not `pkg/fcsdk`.
- `docs/guide/go/flowcatalyst.md:155`: `RunJoined` is built, not an open point.

## Status

Built in four lanes (`fix/fulfil-g-core`, `fix/fulfil-j-core`, `fix/fulfil-g-http`, `fix/fulfil-j-http`)
and integrated 2026-11-07 on `fix/fulfil-integration`, where the orchestrator's rulings below were
applied in both languages. Nothing is published.

| Item | Go | Java |
|---|---|---|
| A1 | **Built.** `clientCode` from the context (not `fw.PlatformClient`, not with `PlatformClientID`), `usecase.IdempotencyKey`, `usecase.ConnectionID`; received in the job's client, end to end through `fakeplatform` | **Built.** Same rules (`ClientId.PLATFORM`, `platformClientId`), `DispatchJob.Builder.idempotencyKey`/`connectionId`; end to end in `ReceiverOutboxTest`. Rust's outbox writer follows (vectors pass) |
| A1, ruling d | **Built.** A delivery whose `X-FlowCatalyst-Client` names another client than the signed envelope's `clientCode` is `400 CLIENT_MISMATCH`, no handler run | **Built**, same; the vector `receivers/dispatch.json` has the case |
| A2 | **Built.** `usecase.Append(row, appender)` | **Built.** `Plan.andAppend(row, Appender)` |
| A3 | **Built.** `usecase.RequireService`, `fw.ServiceContextFor` | **Built.** `Authorize.requireService`, `Context.ofService` |
| A4 | **Built.** `fw.Context.SubjectName()` | **Built.** `Context.subjectName()` |
| A5 | **Built.** `403 CLIENT_NOT_PERMITTED` for a field token of another client | **Built**, same |
| A6 | **Built.** `PlatformClient.BaseURL()`, `SDK()` (go-sdk v0.0.15, retry once on 401) | **Built.** `PlatformClient.baseUrl()`, `sdk()` (the Java SDK is on the path) |
| A7 | **Built.** `fakeplatform.Deliver`, `Fire`, `JobFromOutbox`, `JobFromEvent` | **Built.** `ReceiverTesting.Delivery` (`ofJob`, `ofEvent`), `deliver`, `fire` |
| A8 | **Done** (guide) | **Done** (guide) |
| B1 | **Built.** `Server.BeforeDrain`, `httpfw.Draining`; `sse.Serve` ends on it | **Built.** `HttpServer.beforeDrain`; `EventStreams` closed there. `Datastar.stream` streams are not (a static helper without a list of open streams); noted in the HTTP contract 13.1 |
| B2 | **Built.** `NewServer` checks the document; `fwtest.OpenAPI` | **Built.** `JettyHttpModule` checks it before opening the port; `OpenApiTesting.assertBuilds` |
| B3 SSE (ruling e) | **Built.** `sse.Serve` (channel based) | **Built.** `EventStreams` hub; shapes stay idiomatic |
| B3 Idempotency-Key (ruling b) | **Built**, the reference behaviour and table `fw_idempotency_keys` (module `http-idempotency`); a streamed (flushed) answer frees its key, as in Java (2026-11-07) | **Built**, aligned at the integration: Go's table byte for byte, module id `http-idempotency`, waits for the first answer, takeover of expired and abandoned keys, tokens, printable-ASCII keys (max 200, both), the query in the hash, `400 IDEMPOTENCY_KEY_UNSUPPORTED` without a principal; memory and database stores pass one store contract |
| B3 body limit | **Built.** `httpfw.MaxBody` | **Built.** `RouteOptions.maxBodyBytes` |
| B3 CORS (ruling a) | **Built**; `http.cors.allow-credentials` removed at the integration: credentials never allowed | **Built**, never with credentials |
| B3 content type (ruling c) | **Built.** `PutObject`/`GetObject`, `CheckContentType` (`mime.ParseMediaType`), `""` the default | **Built**, aligned at the integration: the same media-type rule (a port of `mime.ParseMediaType`, Go's verdicts in the tests), `null`/`""` the default, max 255 |
| B3 secrets | **Built.** `flowcatalyst.PlatformSecrets`, cache alias keys, single flight, stale-on-error | Already had per-client `env`, `file`, `platform` drivers |
| B4 | **Built.** `sqlfw.MigrationDir` (migrations read by path) | Not affected: `framework-sql-maven-plugin` already reads migrations by path |
| C | **Done** (handover, Go guide, go-build contract) | n/a |

Rust (ruling f): the outbox vector changed with A1, and CI builds and tests Rust (`.github/workflows/rust.yml`),
so the Rust outbox writer now writes `clientCode` (not for `PLATFORM_CLIENT`, `_platform`, or with a
`platform_client_id`), `connection_id` and `idempotency_key` in the Java field order;
`rust/conformance/tests/outbox.rs` passes.
