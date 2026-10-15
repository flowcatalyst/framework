# Go framework: gap analysis against `pkg/fcsdk` and the Go platform (2026-10-13)

Companion to [`design.md`](design.md). For every capability of the Java framework (the guide, the
contracts and [`../guide/STATUS.md`](../guide/STATUS.md)), what already exists in Go and where:

- **fcsdk**: the Go SDK, `flowcatalyst-go/pkg/fcsdk` (packages `auth`, `cache`, `client`, `lock`,
  `outboxpgx`, `outboxsql`, `scheduledjobs`, `sync`, `tsid`, `usecase`, `usecaseop`, `usecasepgx`,
  `webhook`; read 2026-10-13 at `flowcatalyst-go` commit `4f1ecbb`);
- **platform**: the Go platform's own code, chiefly `internal/ui` (the templ port of the UI kit) and
  `internal/platform/shared`, plus its tooling (sqlc, `internal/testpg`, `tools/analyzer`); since
  the Nuxt UI ruling (2026-10-13) also its Vue SPA (`frontend/`) and its OpenAPI pipeline
  (`apiroute`, `tools/dump-spec`), read for rows 47 to 50.

Status words: **covered** (usable as is, possibly behind a thin wrapper), **partly** (some of the
contract, or the right idea with different bytes or rules), **missing**, **n/a** (Go does not need
it, or it is a tool rather than a library).

One fact shapes the whole table: **everything under the platform's `internal/` cannot be imported
from another Go module** (the `internal` rule). The platform column therefore means "a working
pattern to copy or a package to extract", never "a dependency". Only `pkg/fcsdk` can be imported.

## 1. Headline

Of the **40** capabilities the Java framework has built (M1 to MA, not counting the six rows that
are n/a in Go), `pkg/fcsdk` covers **2 (5%)**, covers **14 (35%) partly**, and has nothing for
**24 (60%)**. Weighted by work, fcsdk saves roughly **15 to 20%** of what the Java framework took,
almost all of it in the FlowCatalyst module (the platform client, client credentials, the sync
endpoints, the scheduled-job runner's shape) and in the use-case envelope's shape.

None of the **partly** rows passes the shared conformance vectors as it is. The outbox payload, the
audit row, the lock table and scripts, the outbox migration bytes and the use-case error codes all
differ from what the Java framework writes (section 4). The framework therefore owns its write path
and its locks, as the Java and Rust frameworks do, and uses fcsdk where the Java framework uses the
Java SDK: in the FlowCatalyst module only.

The platform contributes proven patterns rather than code: sqlc, the embedded-PostgreSQL test
fixture, the dynamic-query `Filter`, and for the UI its PrimeVue SPA's embedding, dev loop,
composables and OpenAPI-to-TypeScript pipeline. (The first draft counted on extracting the
platform's templ port of the UI kit; the owner's 2026-10-13 ruling makes the Go framework's UI a
Nuxt UI SPA instead, see [`ui.md`](ui.md), so that reuse is gone and the UI is new work.)

## 2. Capability table

### Core and lifecycle (Java M1, MA 2, 3, 7)

| # | Capability (contract) | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 1 | Modules, phases (declare, configure, build, start), commands and `launch` ([core](../contracts/core.md) 1, 5) | missing | missing: `cmd/` mains are wired by hand, not a library | new |
| 2 | Named instances and drivers (core 2) | missing | missing | new |
| 3 | `Context` and its id types (core 3) | **partly**: `usecase.ExecutionContext` has principal, correlation, causation and execution ids, but no client and no application; ids are UUIDs; time is `time.Now()` | `internal/ids`: one named type per id (pattern only) | new, `ClientID` and friends as small value types |
| 4 | Config: declarations, YAML layers, env names, collision check, durations, `secrets.local.yaml` (core 4) | missing | env-only (`envutil`, internal) | new |
| 5 | `Secret` (core 4) | missing | missing (`shared/encryption` is column encryption, unrelated) | new |
| 6 | Health checks with timeout and criticality (core 1) | missing | not reusable (internal) | new |
| 7 | Shutdown order and `app.shutdown.drain` (core 1) | missing | not reusable | new |
| 8 | Background loops and the lock gate ([background](../contracts/background-and-metrics.md) 1) | missing | not reusable | new |
| 9 | Metrics and the Prometheus endpoint (background 2, 3) | missing | `prometheus/client_golang` (its own output bytes, global registry by default) | new (exact bytes) |
| 10 | Test overrides (core 1, MA 2) | n/a | n/a | n/a: tests pass fakes to the application's build function |

### Database and Redis (M1)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 11 | Database instances: pools, start-up ping, health `database:<name>` ([database](../contracts/database-and-redis.md) 1) | missing: callers pass a `*pgxpool.Pool` | `shared/database.NewPool` (internal, pgxpool defaults) | new, small, on pgx v5 |
| 12 | Migration runner on `fw_schema_history` (database 2) | missing: `outboxpgx.InitSchema` runs `CREATE ... IF NOT EXISTS` | goose (a different history table) | new |
| 13 | Redis connections (database 3) | missing: callers pass a go-redis client | go-redis v9 in use | new, on go-redis v9 |

### Locks (M1)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 14 | Lock API: try / wait with backoff, refresh, release, fencing tokens, `withLock` ([locks](../contracts/locks.md) 1 to 4) | **partly**: `lock.Provider.Acquire(key, ttl)` and `Handle.Release`; no wait, refresh, fencing or `withLock` | none | new |
| 15 | Drivers `memory`, `database`, `redis` with the shared statements and scripts (locks 5) | **partly, not conformant**: `postgreslock` uses its own table (`key, holder, expires_at`, no fence); `redislock` uses `SET NX PX` and a check-and-delete script | none | new, loading `conformance/sql/locks/*` and `conformance/lua/locks/*` |
| 16 | The lock contract test suite (locks 6) | missing | none | new |

### Write path (M2a, MA 4)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 17 | Use-case definition: validate, authorize required, execute returning a plan ([write path](../contracts/write-path.md) 3) | **covered in shape**: `usecaseop.Operation` (struct of phase functions, `Authorize` required at run time and by the `uowseal` analyzer, `Plan` sealed by an unexported method) | every platform write uses it | the same envelope, framework-owned (needs `Context`, `Grant`, `Valid`/`Authorized`) |
| 18 | Runner and unit of work: one READ COMMITTED transaction, writes then events then dispatch jobs then one audit row, the failure table and step codes (write path 4) | **partly**: `usecasepgx.Commit*` run persist, event, audit in one transaction; codes are `TX_BEGIN`, `EVENT_WRITE`, `AUDIT_WRITE`, `TX_COMMIT` (contract: `BEGIN`, `OUTBOX`, `AUDIT`, `COMMIT`); one audit row **per event**; no panic recovery in `Commit` | uses fcsdk | new runner |
| 19 | Optimistic locking and the conflict policy with retry and jitter (write path 2, 4) | missing: `Persist` is an upsert with no version check or row count | none | new |
| 20 | Client scoping and `CROSS_CLIENT_WRITE` (write path 4) | missing | none | new |
| 21 | `UseCaseError`: kinds, codes, details, status mapping (write path 5) | **partly**: `usecase.Error` has the six kinds and the same statuses, but kinds are lowercase strings and details `map[string]any` | uses fcsdk | new type, contract names |
| 22 | Event metadata (write path 6) | **partly**: `EventMetadata` has every field; `NewEventMetadata` reads `time.Now()` and makes UUIDs | uses fcsdk | new, clock injected |
| 23 | Outbox writer: event and audit rows (write path 7) | **partly, not conformant**: `outboxpgx.Sink` writes a snake_case payload (`event_type`, `spec_version`, ...), `data` as an object, `deduplication_id` = type + id, an `aggregateType` context entry, no `clientCode`, `created_at` = `NOW()`; the vector has camelCase, `data` as a JSON string, `deduplicationId` = event id, `clientCode`, the application clock | `platformsink` writes `msg_events` directly (platform side) | new, the shared `INSERT` |
| 24 | Outbox migrations `V0001` (the Java SDK file byte for byte) and `V0002` (write path 7) | **partly**: same columns and indexes, but `IF NOT EXISTS` and no comments, so not byte-identical (checksum differs) | none | embed the Java files |
| 25 | Dispatch-job rows in the batch-item shape (write path 7a) | missing | `shared/sdk` `BatchItem` (the receiving side; the field list to match) | new |
| 26 | TSIDs: 13 Crockford characters, prefixes, monotonic, injected clock (write path 8) | **partly**: codec and a monotonic CAS generator; state is package-global and the clock is `time.Now()`; the encoder is reachable through `tsid.FromLong` | uses fcsdk | generator new (per-instance, clocked); codec reusable, vector-checked |
| 27 | Read helpers (write path 9) | n/a | `repocommon.One` (internal) | n/a in Go: sqlc-generated code and pgx |

### Checked SQL (M2b)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 28 | Queries in `.sql` files, typed generated code, committed, a check mode ([checked SQL](../contracts/checked-sql.md)) | n/a | **sqlc** with `make sqlc-verify` (regenerate and `git diff --exit-code`); schema = the migration files | adopt sqlc; add a check against a migrated database |
| 29 | Dynamic queries (checked SQL 5) | n/a | `repocommon.Filter` (internal, about 60 lines) | `Filter` (copied) and go-jet (owner ruling c) |

### HTTP (M3, MA 8, 9)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 30 | Server and routing rules: 404, 405 with `Allow`, `OPTIONS`, `HEAD`, duplicates as start-up problems ([http](../contracts/http.md) 2) | missing | chi and huma (not the stdlib mux) | new, on `net/http` `ServeMux` |
| 31 | Middleware order: correlation id, error mapping, access log, then the application's (http 4, 6) | missing | `shared/middleware` (chi, internal): correlation id and auth | new |
| 32 | Error envelope `{"error","message","details"}` and every status (http 5) | **partly**: `usecase.Error.HTTPStatus()` | `shared/httperror` has the same envelope (internal) | new, small |
| 33 | JSON strictness and validation that collects every error (http 7) | missing | `shared/validate` (code rules, internal) | new, on `encoding/json/v2` |
| 34 | Health endpoints, body limit 413, graceful drain and 503 (http 8) | missing | not reusable | new |
| 35 | Test client (http 9) | n/a | n/a | `net/http/httptest` |
| 36 | Datastar adapter: exact SSE bytes (http 10) | n/a | `internal/ui/ds` over `starfederation/datastar-go` v1.2.2 | **n/a**: not implemented in Go (Nuxt UI ruling 2026-10-13; design decision 27) |
| 37 | Rate limiting: GCRA, `memory` and `redis` with the shared Lua ([rate limiting](../contracts/rate-limiting.md)) | missing | `shared/ratelimit`: token bucket (`x/time/rate`) plus Redis and PostgreSQL stores, not GCRA | new |

### HTTP client and secrets (MA 5, 6)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 38 | HTTP client: base-URL and path rules, retries, auth strategies, client credentials ([http client](../contracts/http-client.md)) | **partly**: `auth.ClientCredentialsProvider` (cached token, single refresh; skew 60 s where the contract says `min(30s, expires_in/10)`); `client`'s retry loop is platform-specific | none | new on `net/http` |
| 39 | `SecretResolver` with `env` and `file` drivers ([secrets](../contracts/secrets.md)) | missing | none (`encryption.secretref` is the platform's own secret references) | new |

### FlowCatalyst module (M4a, M4b)

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 40 | Platform client as the application's service account ([flowcatalyst](../contracts/flowcatalyst.md) 1) | **covered**: `client.FlowCatalystClient` with `auth.ClientCredentialsProvider`; every resource the module calls (`ScheduledJobs().CompleteInstance`, logs, the sync endpoints). Retry is per client (`WithRetry`), so a second client with retry off is needed for completion calls, as in Java | n/a | wrap; fcsdk types stay out of framework signatures |
| 41 | Definitions, validation, the `sync` command with dry run (flowcatalyst 2, 4a) | **partly**: `sync.DefinitionSet` and `Synchronizer` cover roles, event types, subscriptions, dispatch pools, principals and processes; **no connections, scheduled jobs or docs categories**; no validation; no dry run | n/a | wrap; add the missing categories to fcsdk (platform-repository work) |
| 42 | `flowcatalyst` and `outbox` health checks, informational (flowcatalyst 1, 3) | missing | n/a | new |
| 43 | Signature check: HMAC-SHA256 over timestamp + body, ISO or Unix seconds, 300 s / 60 s, refusal codes, bearer gate, duplicate headers (flowcatalyst 4, 4a) | **partly**: two verifiers. `webhook.Verifier` takes ISO timestamps with a symmetric 5-minute window; `webhook.Validator` takes Unix seconds with 300 s / 60 s. Neither has refusal codes, the duplicate-header rule or the bearer gate; `Verifier` reads the wall clock | n/a | new port; the HMAC is ten lines |
| 44 | Dispatch receiver: headers, envelope or data-only, matching, `Ack`/`Defer`/`Reject`, 503 on error or timeout (flowcatalyst 4, 4a) | missing | n/a (the platform is the sender) | new |
| 45 | `DispatchDelivery.once` on `fw_dispatch_once` and its retention purge (flowcatalyst 4a, follow-up 2) | missing | n/a | new |
| 46 | Scheduled-job receiver: 202, validation, overlap lock with refresh, completion with retries, truncation, log hook (flowcatalyst 4a, follow-ups 3, 4) | **partly**: `scheduledjobs.Runner` validates the envelope, runs in the background, skips with `lock-held`, cuts results at 10 000, has the log hook; no `concurrent` flag, no lease refresh, no completion retries, no timeout, looser validation | n/a | new, borrowing its shape |

### Not yet built in Java (M-UI, M4c, M4d to M4f, M5)

These rows are not counted in the headline.

| # | Capability | fcsdk | Platform | Go framework |
|---|---|---|---|---|
| 47 | UI components (Java plan: JTE over `ui-kit/`; Go: Nuxt UI, [`ui.md`](ui.md)) | n/a | the templ kit (`internal/ui/kit`) is **not used** by the Go framework; the PrimeVue SPA's composables (`useListState`, `useDrawerRoute`, `useCursorPagination`, `useDirtyForm`) and partials are the model | new: `@flowcatalyst/ui` on Nuxt UI |
| 48 | A typed JSON API description (OpenAPI) and a generated browser client | n/a | huma (`apiroute`, `tools/dump-spec`, `api/openapi.lock.json`, `make api-diff`) and hey-api **types only** with a hand-written transport; no enums, `Time` patched, `/bff` and `/auth` outside the spec | new: the framework's own emitter (design decision 18), the full hey-api client (decision 19) |
| 49 | Serving the built SPA from the binary | n/a | **pattern**: `frontend/embed.go` and `handler.go` (a missing hashed asset and unknown paths answer `index.html`; `no-store` on `index.html`) | new, `httpfw/spa`, with those two points corrected |
| 50 | CSRF for cookie-authenticated requests | n/a | **pattern**: `internal/ui/guard.CSRF` (`http.CrossOriginProtection` plus refusing no-provenance requests), applied to `/ui` only, not to the `/api` routes the SPA calls | new, `httpfw.CSRF` |
| 51 | Sessions (`cookie`, `database`, `redis` drivers) | missing | the platform's own login sessions (internal, not read for this analysis) | new, after the M4c contract |
| 52 | OIDC relying party: PKCE, discovery, JWKS, id_token, refresh, logout | **partly**: `auth.OAuthClient` (PKCE, code exchange, refresh, revoke, introspect, userinfo, logout URL), `auth.TokenValidator` (RS256, JWKS via `lestrrat-go/jwx/v2`) | `coreos/go-oidc` in the platform | reuse fcsdk `auth` |
| 53 | Admin area | missing | the platform's own admin UI (not reusable) | later |
| 54 | Cache (M5; no contract yet) | **covered**: `cache` (memory) with `postgrescache` and `rediscache` | n/a | reuse when the contract exists |

## 3. Counting

Rows 1 to 46 are the built Java capabilities. Rows 10, 27, 28, 29 and 35 are n/a for fcsdk (a
design choice or a tool) and row 36 is n/a for Go since the Nuxt UI ruling, which leaves 40:

| fcsdk status | Rows | Count |
|---|---|---|
| covered | 17 (shape only), 40 | 2 |
| partly | 3, 14, 15, 18, 21, 22, 23, 24, 26, 32, 38, 41, 43, 46 | 14 |
| missing | 1, 2, 4, 5, 6, 7, 8, 9, 11, 12, 13, 16, 19, 20, 25, 30, 31, 33, 34, 37, 39, 42, 44, 45 | 24 |

The count treats every row as equal; they are not. Rows 1 to 4 and 18 to 23 are the largest pieces
of the Java build and fcsdk gives little there beyond the envelope's shape.

## 4. Findings about fcsdk (to report, not to fix here)

1. **The outbox payload differs from the Java SDK's and the framework's vector.** `outboxpgx`
   writes snake_case keys and `data` as an object (`buildEventPayload`); the Java SDK's
   `CreateEventDto`, which the framework writes and the vector pins, is camelCase with `data` as a
   JSON string and `clientCode` set. Whether the platform's batch ingest accepts both shapes was
   not checked. The TypeScript spike plan expected this kind of difference
   ([`../typescript/spike.md`](../typescript/spike.md) G5).
2. **The audit row is per event**, not one per use case, and its `operation` is the command's Go
   type name (by reflection), not the use-case name.
3. **Licence: was MPL-2.0, now Apache-2.0** (relicensed 2026-10-13, owner-approved, no third-party MPL
   code in it; `pkg/fcsdk/LICENSE` and `NOTICE`). When this analysis was written it was MPL-2.0, which
   the Rust framework's `deny.toml` refuses (Rust milestones, R0); no exception is needed any more.
   [Design](design.md) decision 3.
4. **No module of its own, no version.** fcsdk is a package tree inside the module
   `github.com/flowcatalyst/flowcatalyst-go`, whose `go.mod` requires the AWS SDK, huma, chi, the
   MySQL driver and more. There are no `go-sdk` tags (the platform's release tooling lists the Go
   SDK as deferred), so a consumer pins a pseudo-version. The Git remote is
   `github.com/flowcatalyst/flowcatalyst`, which does not match the module path; whether `go get`
   resolves it (a GitHub rename redirect, a private repository) was not tested.
5. **The README is stale**: it lists `usecasesql` and `examples/order-service`, which do not exist,
   and says the seal token is constructable by packages under `clients/go-sdk/` (it is
   `pkg/fcsdk/internal/sealed`).
6. **Process-global state**: `tsid` keeps its monotonic state in a package variable and reads
   `time.Now()`; `usecase.NewExecutionContext` and `NewEventMetadata` read the wall clock. The
   framework's rule is no global state and an injected clock.
7. The platform's `.golangci.yml` excludes `internal/db/gen # go-jet generated code`; there is no
   go-jet in `go.mod` and no such directory. A stale line.

## 5. What this means for the plan

- **Write path, locks, config, lifecycle, HTTP: build them**, as ports of the contracts, the way the
  Rust framework did. The fcsdk envelope (`usecaseop`) is the model for the use-case shape; it is
  not a dependency.
- **FlowCatalyst module: depend on fcsdk**, in one module only (`go/flowcatalyst`), as the Java
  framework depends on the Java SDK in `framework-flowcatalyst` alone. The missing sync categories
  (connections, scheduled jobs, docs) are added to fcsdk in the platform repository.
- **UI: build**, in TypeScript on Nuxt UI ([`ui.md`](ui.md)), taking the platform SPA's patterns;
  nothing of the platform's templ kit is imported. The HTTP layer gains typed operations with an
  OpenAPI emitter, static serving and CSRF (rows 48 to 50).
- **Tooling: copy** sqlc's configuration style, the `testpg` fixture, the `Filter` accumulator, the
  lint profile and the `uowseal`/`idconv` analyzer approach.
