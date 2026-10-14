# Contract: the FlowCatalyst module, client side and receivers (M4a, M4b)

Status: M4a and M4b built (2026-10-09; section 4a records where the build differs; section 4b,
the receiver principal, added and built 2026-10-14). Facts come from `docs/research/m4-platform-facts.md` (each with a
file reference); where this contract and that sheet disagree, the sheet's source wins and this
file is wrong. Sessions and login are M4c and have their own contract.

Owner rulings used: handler failure or timeout in a receiver is a transient error (2026-10-09);
each application delivers its own outbox with its own credentials (2026-10-08).

## 1. Module, settings, client

Module `flowcatalyst` (artifact `framework-flowcatalyst`, depends on the Java SDK
`io.flowcatalyst:flowcatalyst-sdk` and on `framework-http`, `framework-locks`,
`framework-database`). The framework does not depend on FlowCatalyst (plumbing 9a): this module
is the only place that imports the SDK.

| Key | Type | Default |
|---|---|---|
| `flowcatalyst.enabled` | boolean | `true` when the module is listed |
| `flowcatalyst.base-url` | URI | required |
| `flowcatalyst.application` | string (application code) | required |
| `flowcatalyst.client-id` | string | required |
| `flowcatalyst.client-secret` | secret | required |
| `flowcatalyst.timeout` | duration | `30s` |

Provides `PlatformClient`: a thin wrapper over the SDK's `FlowCatalystClient` built with
client-credentials (the application's service account). Application code uses the wrapper's
methods; the SDK types do not appear in framework contracts. Adds a health check `flowcatalyst`
(token fetch succeeds within `app.health.timeout`).

Failure of the platform at start-up does not stop the application (the platform is not on the
request path); it is logged and the health check reports it.

*Amended by MA item 7 (2026-10-10):* the `flowcatalyst` check and the `outbox` check (section 3)
are added as **informational** (core contract section 1): they are reported by `/health/ready` but
do not make it answer `503`. Before, a platform outage that outlasted the cached token marked every
replica not ready. `app.health.critical: [flowcatalyst, outbox]` makes either decide readiness
again.

## 2. Definition sync (deploy time)

Modules contribute definitions during `register` through `Definitions`:

`eventType(code, name, description)`, `permission(code, description)`,
`role(name, permissions…)`, `subscription(...)`, `connection(...)`, `dispatchPool(...)`,
`scheduledJob(...)`, `process(...)`, `docs(slug, content)`. Codes are validated against the
platform's patterns at `register` (event type `{app}:{domain}:{aggregate}:{action}`; the app
segment must equal `flowcatalyst.application`); a bad one fails start-up.

- `DefinitionSync.run(mode)` pushes the set with the SDK's `DefinitionSynchronizer`
  (`/api/applications/{app}/…/sync`). Modes: `APPLY` and `DRY_RUN` (reports what would change,
  writes nothing). It returns a per-category result and throws `SyncException` on any category
  failure after attempting all of them.
- It is **not** run on boot by default (`flowcatalyst.sync.on-boot`, boolean, default `false`).
  The deploy runs it: `java -jar app.jar sync [--dry-run]` through a small command entry point
  the module provides (the application's `main` delegates to `App.command(args)`; unknown
  commands fall through to normal start).
- The framework's own modules contribute their permissions and roles the same way (the admin
  module's `admin:*` set, M4d).
- **Docs sync:** the platform has `POST /api/applications/{appCode}/docs/sync` but the Java SDK
  has no docs category. Platform/SDK work: add it to the SDK; until then `docs(...)` definitions
  are accepted and skipped with a warning.
- Sync is idempotent. Removing a definition from code does not delete it on the platform unless
  the SDK's sync set marks the category as authoritative; the framework does not set that flag
  (deletions are an operator action).

## 3. Outbox delivery (ruling 2026-10-09: the platform delivers, the framework does not)

The framework writes outbox rows (write-path contract section 7) and does **not** deliver them.
Delivery is the platform's outbox processor (Go `internal/outbox`, run as `fcdev outbox` or inside
`fc-server`), pointed at the application's database and configured with the application's own
service-account credentials (which is why the `application-service` role carries the batch-ingest
permissions and the batch endpoints confine it to its own application, 2026-10-09).

- An application that wants events, dispatch jobs, subscriptions or scheduled jobs needs a
  platform. Without one the framework provides none of them: no local fan-out, no local
  subscriptions, no local job dispatch. (An earlier draft of this section specified an in-process
  processor; the owner ruled it out.)
- The module provides what the operator needs to run the processor correctly: a health check
  `outbox` that is `Down` when the oldest pending row in `outbox_messages` is older than
  `flowcatalyst.outbox.stale-after` (default `5m`), and a `docs` page generated into the
  project describing the processor's settings (table name, database, credentials). It reads the
  table only; it never claims or updates rows.
- Status semantics the processor applies (positional results, terminal `BAD_REQUEST` and
  `FORBIDDEN`, retried gateway and server errors, three attempts) are documented in the platform
  and the fact sheet (section 1.3); the framework does not re-implement them.

## 4. Receivers

Mounted by the module under a configurable prefix (`flowcatalyst.receivers.path`, default
`/fc`). They are ordinary routes registered through `Routes`, with no `Context` requirement and
no session; authentication is the signature.

### Signature check (both receivers)

Port `WebhookSignature` (Java SDK, same rules as the Go/TS verifiers): hex HMAC-SHA256 over
`timestamp + rawBody` with the signing secret, headers `X-FlowCatalyst-Signature` and
`X-FlowCatalyst-Timestamp`, tolerance 300 s past and 60 s future, constant-time compare, empty
secret fails closed, optional bearer gate (`Authorization`) AND-ed. Settings:

| Key | Meaning |
|---|---|
| `flowcatalyst.receivers.signing-secret` (secret) | the shared secret the platform signs with (per-receiver overrides: section 4a, follow-up 1) |
| `flowcatalyst.receivers.bearer-token` (secret, optional) | when set, `Authorization: Bearer` must match |
| `flowcatalyst.receivers.require-signature` (boolean, default `true`) | `false` accepts unsigned deliveries; start-up logs a warning |

A failed check is `401 UNAUTHENTICATED` with the standard envelope, before the body is read into
a handler. The platform treats 401/403 as terminal for a dispatch job (fail fast), so a wrong
secret surfaces immediately rather than as retries. Note (fact sheet 2, 3): when the platform
cannot resolve credentials it sends **unsigned**; with `require-signature` true those are
rejected, which is the intended outcome.

### Dispatch jobs: `POST {prefix}/dispatch`

The URL is what the connection/subscription target points at. Body: the platform's envelope
`{id, type, attemptNumber, source?, subject?, correlationId?, messageGroup?, clientId?,
clientCode?, data}` or, for `dataOnly`, the raw payload; headers `X-Dispatch-Job-Id`,
`X-Event-Type`, `X-FlowCatalyst-Client: {id}:{code}` (optional).

Handlers are registered by event type (exact or `*` suffix on a segment boundary, most specific
wins; duplicates fail start-up):

```java
receivers.onDispatch("orders:billing:invoice:created", (ctx, delivery) -> DispatchResult.ack());
```

- `delivery` carries job id, type, attempt number, subject, message group, parsed `data`
  (`JsonNode`, or raw bytes when `dataOnly`), raw body, and the client from
  `X-FlowCatalyst-Client`. `ctx` is a `Context` built from that client header, the configured
  application, and the job's correlation id (generated when absent). *Amended by section 4b:* it
  also carries the application's service principal (kind `SERVICE`) and the job id as causation.
- Results (sealed): `Ack` → `200 {"ack":true}`. `Defer(delay)` → `200 {"ack":false,
  "delaySeconds":n}` (the platform reschedules without spending retry budget; default 30 s).
  `Reject(reason)` → `422` with header `FlowCatalyst-Outcome: reject` (terminal; the reason is
  returned in the envelope message and never contains handler internals).
- **A handler that throws, or exceeds the job's timeout, is a transient error** (owner ruling):
  the response is `503 UNAVAILABLE` and the exception is logged with the job id and correlation
  id. The platform retries with its own backoff up to the job's max retries. A handler that
  wants a terminal failure returns `Reject` explicitly.
- No handler for the type: `Reject("no handler for <type>")` (422 reject). A job that can never
  succeed must not retry.
- Handler timeout: the job's timeout is not in the request; the module's own
  `flowcatalyst.receivers.dispatch-timeout` (default `25s`, less than the platform's 30 s
  default) bounds the call. On expiry the handler's virtual thread is interrupted and the result
  is 503.
- **At-least-once delivery.** A handler can run twice for one job (a timeout after a commit, a
  lost response). The delivery's job id is the idempotency key; an optional helper
  `delivery.once(db, handlerName, work)` records `(handler, jobId)` in a framework table in the
  same transaction as the work and skips repeats (migration contributed by the module).

### Scheduled-job firings: `POST {prefix}/scheduled-jobs`

Body (camelCase): `{jobId, jobCode, instanceId, scheduledFor?, firedAt, triggerKind, correlationId?,
payload?, tracksCompletion, timeoutSeconds?, concurrent}`; validation as the TS runner
(string fields present, `triggerKind` in `CRON|MANUAL`); invalid: `400 VALIDATION`. Signed with
the application's service account; the platform's HTTP timeout is 10 s, so the receiver
**responds `202 {"ok":true}` immediately** and runs the handler in the background on a virtual
thread.

```java
receivers.onScheduledJob("nightly-export", (ctx, firing) -> Result.of(...));
```

- Unknown `jobCode`: `404 NOT_FOUND`. Duplicate registration fails start-up.
- **Overlap protection is ours** (the platform does not enforce `concurrent=false`, fact sheet 3):
  unless `concurrent` is true the runner takes lock `scheduled-job:{jobCode}` (service scope,
  lease 10 min, refreshed while the handler runs) from the default lock instance. If held, the
  handler is not invoked; when `tracksCompletion` the instance is completed with `FAILURE`
  `{skipped:true, reason:"lock-held"}`. With no lock instance configured, start-up fails if any
  registered job could need it (no silent no-op provider; the TS SDK's silent fallback is not
  ported).
- Completion (only when `tracksCompletion`): `POST /api/scheduled-jobs/instances/{instanceId}/complete`
  through the platform client, body `{status:"SUCCESS"|"FAILURE", result?}`; `result` JSON is
  truncated to 10 000 characters; a thrown exception or a timeout is `FAILURE {error: message}`.
  A failed completion call is retried 3 times with backoff and then logged at ERROR.
- Timeout: `timeoutSeconds` from the envelope, else `flowcatalyst.receivers.job-timeout`
  (default `10m`).
- A per-instance log hook (`firing.log(level, message)`) posts to
  `POST /api/scheduled-jobs/instances/{id}/log` best-effort.

## 4a. As built (2026-10-09), with rulings

Where the build differs from sections 1 to 4 above, the build is right and these notes are the
contract. Slices 1 and 2 are commits `e07493c` and `29771c5`; slices 3 to 5 (outbox health check,
signature check and dispatch receiver, scheduled-job receiver) are `b52c323`, `b6f584a` and
`b1642a5`.

- Settings are optional in the declaration and validated when `flowcatalyst.enabled` is true: one
  `StartupException` naming every missing or malformed key. Disabled means no validation and an
  empty catalog; `PlatformClient` is still provided and every method but `isEnabled()` throws.
- Definition validation runs at start-up step 3 (settings are unreadable in `register`) and
  reports one problem per issue naming the contributing module. Rules **stricter than the
  platform's** (kept, owner may relax): lowercase `[a-z0-9][a-z0-9_-]*` segments; event type,
  permission and process codes start with the application code; role names carry no `app:`
  prefix; cron has 6 fields; subscriptions name at least one event type; codes unique across
  modules.
- New setting `flowcatalyst.subscription-target-base-url` (the SDK fails a subscription whose
  target is a path with no base URL).
- `DRY_RUN` never contacts the platform (neither SDK nor platform has a preview): it validates
  and reports `WOULD_SYNC` with the codes. The `sync --dry-run` command makes zero requests.
- Each category is synced by its own SDK call so one failure does not stop the others. Order:
  roles, event types, connections, dispatch pools, subscriptions, processes, scheduled jobs,
  docs (pools before subscriptions, unlike the SDK, so a first deploy works). Permissions travel
  only inside roles; a declared permission no role grants is a WARN.
- `sync` exit codes: 0 success, 1 a category failed, 2 bad argument or module disabled. The
  command runs after start-up step 3 and before any `boot`. Core gained `Command`,
  `AppBuilder.command(Command)` and `AppBuilder.launch(String...)` (`Launch.Started` or
  `Launch.Ran`).
- Sync on boot (off by default) runs synchronously in `started` and never stops start-up. The
  start-up token check runs on a virtual thread in `boot`. The `flowcatalyst` health check uses
  the SDK's cached token (so it can stay Up briefly after the platform goes down): ruled
  acceptable, to avoid minting a token per probe.
- Not exposed yet: per-client sync (`--client`), principals and OpenAPI sync.

As built in slice 3 (the `outbox` health check, 2026-10-09):

- The check is added at `boot` only when the module is enabled, a `Databases` service exists with
  at least one instance, and the **default** instance has an `outbox_messages` table (migrations
  have run by then). Otherwise no check is added (INFO log): an application without the database
  module or without an outbox has no backlog to watch. If the table lookup itself fails, the check
  is added so it reports the database error.
- "Undelivered" is status `0` (PENDING) or `9` (IN_PROGRESS); terminal failure statuses are not a
  backlog (the processor will not retry them). The age is measured with the application's
  `InstantSource`, the clock the outbox writer stamps `created_at` with. `Down` only when the age
  is strictly greater than `stale-after`. The query is two `min(created_at)` sub-queries that use
  the table's partial indexes; nothing is written.
- `flowcatalyst.outbox.stale-after` must be positive (start-up fails otherwise).
- The generated `docs` page describing the processor's settings is not built (docs work).

As built in slice 4 (signature check and the dispatch receiver, 2026-10-09):

- **Registration.** `Receivers` is a provided service; handlers are registered in `boot`
  (`receivers.onDispatch(pattern, handler)`), a duplicate or malformed pattern throws at once (so
  `boot` fails start-up), and registration closes in the module's `started`. The routes are
  mounted in `boot` when the module is enabled and an HTTP module provides `Routes`; deliveries
  are answered `503 UNAVAILABLE` until `started` has checked the registrations (the server may
  already be listening). Start-up fails when handlers are registered and no `Routes` exists, or
  when handlers are registered, `require-signature` is true and `signing-secret` is absent or
  blank. (Reversed by follow-up 5: a receiver without handlers now answers 404.) A disabled module
  mounts nothing.
- New settings: `flowcatalyst.receivers.path` must be literal segments (`/fc`, no trailing `/`);
  `flowcatalyst.receivers.dispatch-timeout` must be positive.
- **Signature.** Ported (not the SDK class): the SDK reads the system clock and echoes the
  timestamp header in its messages. Same rules, compared in whole seconds like every SDK
  (`ts < now-300` expired, `ts > now+60` future, so exactly 300 s and 60 s are accepted); time
  from the application's `InstantSource`. Stricter: Unix seconds must be ASCII digits, a
  timestamp over 64 characters is invalid, a signature that is not 64 characters is invalid
  before hashing, the bearer token is compared as SHA-256 digests (constant time, length not
  revealed), and a signature, timestamp or `Authorization` header sent twice is refused
  (`DUPLICATE_HEADER`). The headers are checked before the body is read; the body is read once
  (`Request.bodyBytes`, cached) and those same bytes are verified, parsed and handed to the
  handler. A refusal is `401 {"error":"UNAUTHENTICATED","message":"the delivery could not be
  authenticated","details":{"reason":"<CODE>"}}` and a WARN naming the code and the framework's
  correlation id (never a header value or a secret).
- `require-signature: false`: an unsigned delivery is accepted, and so is every delivery when no
  secret is configured; a signed delivery is still verified when a secret is configured. The
  bearer gate applies whenever `bearer-token` is set, signed or not.
- **Dispatch headers.** `X-Dispatch-Job-Id` (`[A-Za-z0-9._:-]{1,128}`) and `X-Event-Type`
  (`[A-Za-z0-9._:-]{1,255}`) are required, once each; `X-FlowCatalyst-Client` is optional and must
  be `{id}:{code}` with each part `[A-Za-z0-9._-]{1,100}`. Otherwise `400 VALIDATION`.
- **Envelope or data-only.** The job id and type come from the headers. The body is the envelope
  when it is a JSON object whose `id` and `type` equal them; then `attemptNumber` must be a
  positive integer, `source`, `subject`, `correlationId`, `messageGroup`, `clientId` and
  `clientCode` strings when present, and keys unique, else `400 VALIDATION` and no handler runs.
  Any other body is data-only: `data()` is the body parsed as JSON (empty when it is not JSON),
  `attemptNumber()` is empty.
- **Context.** Client: the code from `X-FlowCatalyst-Client`, or `Receivers.PLATFORM_CLIENT`
  (`_platform`, which no platform client identifier can equal) for a platform-scoped job.
  Application: `flowcatalyst.application`. Correlation id: the envelope's when it is printable
  ASCII without spaces, at most 200 characters, else generated. Execution id: the job id.
  *Amended by section 4b:* principal = the service principal, kind `SERVICE`, causation = the job
  id. (As first built, principal and causation were empty.)
- **Results.** `Ack` → `200 {"ack":true}`. `Defer(delay)` → `200
  {"ack":false,"delaySeconds":n}` with `n` the delay rounded up to whole seconds, 0 to 2^32-1.
  `Reject(reason)` → `422 {"error":"REJECTED","message":reason}` with `FlowCatalyst-Outcome:
  reject`; the reason must be non-blank and is cleaned (control and format characters become
  spaces, cut to 500 characters). A throw, a timeout (the handler's virtual thread is
  interrupted; the request does not wait for it to stop) or a `null` result is `503
  {"error":"UNAVAILABLE"}` with a fixed message; the exception is logged at ERROR in the
  application's log with the job id and correlation id, never returned. No handler: `422`
  reject `no handler for <type>`, logged at WARN.
- **Matching.** Exact type, then the longest `prefix:*` family, then `*`. `orders:*` does not
  match `orders` itself.
- **`delivery.once(db, handlerName, work)`** returns `OnceResult.Ran(value)` or
  `OnceResult.AlreadyDone`. `work` receives the open `java.sql.Connection` (READ COMMITTED, not
  auto-commit; it must not commit). The record row is inserted first (`ON CONFLICT DO NOTHING`), so
  a concurrent repeat waits for the first and then skips (or runs, if the first rolled back).
  Table `fw_dispatch_once (handler, job_id, processed_at)`, migration set `flowcatalyst`
  (`V0001__create_dispatch_once.sql`), applied to the default database whenever the database
  module is listed. Handler names `[A-Za-z0-9._:-]{1,200}`. Old rows are purged (follow-up 2).

As built in slice 5 (the scheduled-job receiver, 2026-10-09):

- **Registration.** `receivers.onScheduledJob(jobCode, (ctx, firing) -> JobResult...)` in `boot`;
  codes are `[A-Za-z0-9._:-]{1,186}` (so `scheduled-job:{code}` is a valid lock name); a duplicate
  or malformed code fails start-up. The section 4 example's `Result.of(...)` is
  `JobResult.of(value)`; also `JobResult.success()` and `JobResult.failure(message)` (a failure
  without throwing). A `null` result is a failure.
- **Lock check at start-up.** Any registered job handler requires a `Locks` service with a default
  instance (start-up fails otherwise, naming the job codes): which firings need the lock is only
  known from the envelope, so every job counts as possibly needing it. Exception: a handler
  registered `JobOptions.alwaysConcurrent()` (follow-up 3).
- **Validation** (`400 VALIDATION`, nothing runs): one JSON object with unique keys; `jobId` and
  `instanceId` strings matching `[A-Za-z0-9._:-]{1,128}` (the instance id goes into a platform URL
  and logs), `jobCode` as above, `firedAt` an RFC 3339 time, `triggerKind` `CRON` or `MANUAL`,
  `tracksCompletion` a boolean; when present: `concurrent` a boolean (absent means false, so the
  lock is taken, as the TS runner), `timeoutSeconds` a positive integer, `scheduledFor` an RFC 3339
  time, `correlationId` a string (used when printable ASCII without spaces, at most 200
  characters, else generated). The validation is stricter than the TS runner (types and patterns
  of the optional fields), never looser. Unknown `jobCode`: `404 NOT_FOUND`. Accepted: `202
  {"ok":true}`; `503 UNAVAILABLE` while starting or stopping.
- **Context.** Client `Receivers.PLATFORM_CLIENT` (the envelope names no client), application,
  correlation id as above, execution id = instance id. *Amended by section 4b:* principal = the
  service principal, kind `SERVICE`, causation = the instance id. The firing exposes every envelope field,
  `fencingToken()` (the overlap lock's, empty for a concurrent job) and `log(level, message)`.
- **Run.** A virtual thread per firing takes the lock with `LockStore.withLock(key, lease, 0)` from
  the default lock instance and runs the handler on a second virtual thread bounded by the
  timeout. New settings: `flowcatalyst.receivers.job-lock-lease` (default `10m`, 1 s to 24 h;
  refreshed every third) and `flowcatalyst.receivers.completion-retry-delay` (default `1s`, 0 to
  1 m, doubling). Outcomes, reported only when `tracksCompletion`:
  - lock held: handler not run, `FAILURE {skipped:true, reason:"lock-held"}` (INFO log);
  - lock store unavailable: handler not run (no exclusion, no run), `FAILURE {error:"the overlap
    lock could not be taken; the job did not run"}`;
  - lock lost while running (refresh `Lost`, or failing until the local deadline): the handler's
    thread is interrupted, `FAILURE {error:"the overlap lock was lost while the job ran"}`, ERROR log;
  - timeout: the handler's thread is interrupted and `FAILURE {error:"timed out after 10s"}` is
    reported at once, but the lock stays held and refreshed until the handler really returns, so
    a handler that ignores the interrupt never overlaps the next firing;
  - throw: `FAILURE {error: <exception message, or its class name when blank>}`, the exception
    logged at ERROR with job code, instance id and correlation id;
  - shutdown: the module's `shutdown` interrupts running firings (`FAILURE {error:"interrupted: the
    application is stopping"}`) and waits up to `app.shutdown.drain`;
  - success: `SUCCESS` with `result` = the value as JSON, omitted when null; over 10 000 characters
    of JSON it is `{truncated:true, preview:<first 10 000>}`, unwritable (a throwing getter, a
    cycle) `{unserialisable:true}`. The same cut applies to failure results.
- **Completion** goes through new `PlatformClient.completeJobInstance(instanceId, Completion,
  JsonNode)` (SDK `ScheduledJobsResource.completeInstance`); the log hook through
  `PlatformClient.logJobInstance(instanceId, LogLevel, message)` (`logForInstance`), best effort,
  lines cut to 10 000 characters. A failed completion is retried 3 times (4 attempts) after
  1 s, 2 s, 4 s by default, then logged at ERROR. (The SDK transport also retries 408, 429, 502,
  503 and 504 inside each call; follow-up 4 removes that multiplication.)
- One shared `signing-secret` served both receivers in the first build. Scheduled jobs are signed
  by the application's service account; dispatch jobs by the subscription's or its connection's
  account (fact sheet 2), which may be a different account with a different secret. Resolved by
  follow-up 1.

### Follow-up rulings (2026-10-09, after slice 5)

1. **Per-receiver secrets.** `flowcatalyst.receivers.dispatch.signing-secret` and
   `flowcatalyst.receivers.scheduled-jobs.signing-secret`, and
   `flowcatalyst.receivers.dispatch.bearer-token` / `flowcatalyst.receivers.scheduled-jobs.bearer-token`,
   each optional and each falling back, independently, to the shared `signing-secret` /
   `bearer-token`; a blank value counts as unset. Start-up validation is per receiver: a receiver
   with handlers needs a non-blank effective secret while `require-signature` is true, and the
   `StartupException` problem carries that receiver's own key (not the shared one), so both
   receivers can be reported at once. A receiver without handlers needs no secret.
2. **`fw_dispatch_once` retention.** `flowcatalyst.receivers.once-retention` (duration, default
   `30d`, must be positive) and `flowcatalyst.receivers.once-purge-interval` (default `1h`, must be
   positive). One module-owned virtual thread purges at start-up and then every interval: rows
   whose `processed_at` is before `now - retention` (the application's `InstantSource`, not the
   database clock), in batches of 5 000, at most 200 batches per run. It stops in the module's
   `shutdown` within `app.shutdown.drain`. Several replicas: when a default lock instance exists a
   run takes service lock `fw-dispatch-once-purge` without waiting and skips if it is held;
   without one, the idempotent `DELETE` just runs. The task exists only when the database module
   is listed and the table exists. Retention must exceed the platform's retry horizon, or a late
   redelivery runs the work again.
3. **Lock opt-out.** `Receivers.onScheduledJob(code, handler, JobOptions.alwaysConcurrent())`
   (`JobOptions.exclusive()` is the default). Such handlers do not count when start-up checks for a
   lock instance. If a firing for one says `concurrent=false` (or omits it, which means false), the
   **handler is not run**: the definition asked for exclusion that this registration cannot give,
   and running it unprotected would silently break that. The firing is logged at ERROR and, when it
   tracks completion, reported `FAILURE {skipped:true, reason:"not-registered-for-exclusive-run"}`
   (the shape of `lock-held`). It is refused on every tick until the job is defined with
   `concurrent` true or the handler is registered exclusive: failing loudly was preferred to a
   silent run without exclusion.
4. **Completion retries.** The runner's own retry (3 retries after the first attempt, so 4 calls,
   delay doubling from `completion-retry-delay`) is the only retry. The SDK's transport retry
   (`retryAttempts`, default 3 on 408/429/502/503/504) is a builder setting with no per-call
   switch, so `PlatformClient` keeps a second SDK client built with `retryAttempts(0)` (and its own
   token cache) for `completeJobInstance` and `logJobInstance`. Tests count exactly 4 completion
   calls and 1 log call for each of those statuses. The SDK's single token refresh on a `401` is
   not a retry of this kind and remains.
5. **Receivers off, or without handlers (ruling 2026-10-09, reverses "the routes still exist and
   refuse everything").** An application that wants only the token (an ingestor, say) got
   `/fc/dispatch` and `/fc/scheduled-jobs` answering `401`, which advertises two endpoints that
   do nothing for it. Now: (a) a receiver with **no registered handler** answers `404 NOT_FOUND`
   (the router's own envelope) **before any signature work**, once the registrations are sealed
   (until then `503`, as before); (b) new `flowcatalyst.receivers.enabled` (boolean, default
   `true`): when `false`, **neither route is mounted at all**, even if handlers are registered, and
   start-up logs a WARN naming the handlers that can then never be called. A receiver that has
   handlers keeps every start-up validation (secret, lock instance, `Routes`); when receivers are
   disabled those validations do not apply. Smaller attack surface: nothing to probe, no signature
   code reachable. *As built, a difference from "not mounted" for (a):* handlers are registered
   during any module's `boot` and are known only when the module's `started` hook runs, by which
   time the route table may already be compiled (Jetty listed first), so the two routes are
   registered in `boot` and answer 404 themselves. Observable difference from a missing route:
   a `GET` to them is `405` with `Allow: POST`, not `404`. `enabled=false` is known in `boot`, so
   there nothing is registered and every method is `404`.
6. **Docs.** The guide has a Receivers page (`docs/guide/receivers.md`) documenting all of the
   above; STATUS rows M4a and M4b are Built.

## 4b. The receiver principal (owner ruling 2026-10-12, re-confirmed 2026-10-14)

A use case run by a receiver always has a principal: the **application's service principal**, kind
`SERVICE` (core contract section 3), the principal the module's own credentials
(`flowcatalyst.client-id` / `client-secret`) identify. The delivered message is the **cause**. No
handler ever runs without that principal.

**Where the id comes from (decided 2026-10-14).** The `sub` claim of the client-credentials access
token `PlatformClient` already fetches. Evidence (platform Go source, read 2026-10-14):

- The `client_credentials` grant loads the OAuth client's linked principal and refuses a client
  without one (`internal/platform/auth/oauthapi/token.go`, `handleClientCredentialsGrant`, lines
  496-585) and mints the token for that principal (`mintClientCredentialsToken`, 643-), whose
  `sub` is the principal's id and `type` its type (`internal/platform/auth/authservice/authservice.go`
  `mintAccessToken`, 531 and 537; `subjectOf`, 482). A service account's principal has type
  `SERVICE` (`internal/platform/principal/entity.go`, 30) and an id with the `prn` prefix
  (`internal/tsid/tsid.go`, 102), such as `prn_0HZXEQ5Y8JY5Z`.
- The platform itself treats `sub` as the acting principal: its API middleware sets the caller's
  principal id from it (`internal/platform/shared/middleware/middleware.go`, 175 and 213).
- `GET /api/me` answers `principalId` from the same token (`internal/platform/shared/me/me.go`,
  67), so it would give the same id for one more request per token and a dependency on an
  endpoint the SDK does not wrap. Not used.

**How it is read.** The token is the one the module received **directly from the platform's token
endpoint** (the configured `flowcatalyst.base-url`) in answer to its own credentials; that
connection is what makes it trustworthy, as for every other answer from the platform (OpenID
Connect Core 3.1.3.7, item 6, allows the same for an id_token from the token endpoint). The
signature is not checked: this module has no JWKS client and does not depend on `framework-auth`.
The token must be a JWT: three `.`-separated segments, the second base64url (padding optional)
decoding to one JSON object with unique keys, with `sub` a string matching
`[A-Za-z0-9._:-]{1,128}` and `type` exactly `SERVICE`. Anything else does not identify a service
principal.

**Cached with the token.** `PlatformClient.servicePrincipal()` returns the id for the current
token (the SDK's cache, refreshed 60 s before expiry or after `clearToken()`). The id is kept with
the token it came from and decoded again only when the token changes, so a delivery makes no extra
request and a new token (rotated credentials, a different account) is picked up with it.

**Fail closed.** When the token cannot be fetched, or does not identify a service principal (not a
JWT, no or malformed `sub`, `type` not `SERVICE`: for example a developer's own credentials, whose
token is `type` `USER`), **no handler runs**:

- dispatch: `503 {"error":"UNAVAILABLE","message":"the application's service principal is not
  available"}`; transient, the platform retries the job with its backoff;
- scheduled job: the same `503` **instead of** `202`, so the platform redelivers the firing (until
  its delivery attempts run out) and no completion is reported;
- an ERROR log naming the job id (or job code and instance id) and the reason, never the token.

The principal is resolved after the signature check, the validation and the handler lookup (so
`401`, `400`, the no-handler `422` and the unknown-job `404` never contact the platform), and
before the handler runs; for scheduled jobs before the `202`.

**Health signal.** Once the receivers are open with at least one handler, the `flowcatalyst` health
check also resolves the service principal and is `Down` with the reason when it cannot (it stays
informational by default, section 1). At that point the module also resolves it once in the
background and logs the id at INFO, or the reason at WARN. Applications without receiver handlers
(token-only users such as an ingestor) see no change.

**The contexts.**

| Field | Dispatch job | Scheduled-job firing |
|---|---|---|
| client | the code from `X-FlowCatalyst-Client`, else `_platform` | `_platform` |
| principal | the service principal id | the service principal id |
| principal kind | `SERVICE` | `SERVICE` |
| application | `flowcatalyst.application` | `flowcatalyst.application` |
| correlation id | the envelope's when well formed, else generated (section 4a) | the envelope's when well formed, else generated |
| causation id | the job id (`X-Dispatch-Job-Id`) | the **instance id** |
| execution id | the job id | the instance id |
| permissions | empty | empty |

Rulings taken here: the scheduled-job cause is the **instance id**, because the instance is the
message that was delivered, while the job id names the definition, which fires many times and
would make every run look like one cause. Permissions stay empty: the service account's platform
permissions (`scope`) are the platform's (`platform:application-service:...`), not the
application's; a use case that must admit receiver runs checks the principal kind.

**What reaches the outbox** (write-path contract sections 6 and 7): each event's `causationId` is
the job (or instance) id and its `contextData` holds `principalId` = the service principal and
`executionId`; the audit row's `principalId` is the service principal. Before this change those
audit rows had no `principalId`, and the platform refuses such an item terminally
(`internal/platform/shared/sdk/audit_batch.go`, 36-38 and 181-183: `BAD_REQUEST "principalId is
required"`), so every audit row written by a receiver-run use case was lost. The platform's audit
item has **no causation (or correlation) field** (`AuditBatchItem`, same file, 31-42; unknown keys
are ignored), so the audit row cannot carry the job id; the events do. Platform work: section 5,
item 6.

Conformance: `conformance/receivers/context.json` (written by the Java test
`ReceiverContextConformanceTest`): for given deliveries and token claims, the `Context` a receiver
hands its handler, or the refusal when the service principal cannot be resolved.

## 5. Platform work found by the survey (not framework code)

1. **Scoped reads.** `GET /api/events` and `GET /api/dispatch-jobs` scope by client only; the
   `application-service` role has neither view permission, and nothing confines an
   application-scoped caller to its own applications. Needed for M4e (Go, Rust, Java).
2. **Default login tokens cannot call `/api/*`** (`token_use=identity`). The admin UI (M4d/M4e)
   calls the platform as the application's service account, enforcing the signed-in user's
   permissions in the framework, or uses an `apiAccess` OAuth client. Decision in the M4c/M4d
   contract.
3. **Public login client:** the platform rejects a non-empty secret from a public client; the
   ported OIDC flow must omit it (M4c).
4. **Missing platform capabilities for admin:** no endpoint to list, resend or revoke pending
   invitations; the `application-service` role lacks `user:view`, `user:create` and config
   permissions (M4e).
5. Java SDK: docs sync; read methods for events and dispatch jobs.
6. **Audit causation** (found 2026-10-14, section 4b): `POST /api/audit-logs/batch` items have no
   `causationId` or `correlationId`, so an audit entry cannot be linked to the dispatch job or the
   request that caused it; the framework's audit payload already sends `correlationId` and the
   platform drops it.

## 6. Tests

Unit: definition validation and the sync result handling against a fake platform (a small
in-process HTTP server in the test support); signature vectors (the one shared by the Java SDK,
TypeScript and Laravel verifiers, and the Go router test's input checked with `openssl`: the
`tests/golden/webhook/` directory the Go wire contract names does not exist in the Go repo), the
exact 300 s / 60 s window edges and every refusal code; each receiver response (ack, defer,
reject, throw, timeout, null, no handler, bad signature, unsigned with `require-signature` on and
off, bearer gate, malformed headers and envelopes, not ready); overlap lock held / free / lost /
store down, ten concurrent firings; timeouts; completion body, truncation and retries; the log
hook; per-receiver secret fallbacks; the always-concurrent opt-out; exact completion call counts
against the SDK's retryable statuses; start-up failures. Integration (Testcontainers PostgreSQL, a real platform is not required):
the `outbox` health check on the real `outbox_messages` migration; `delivery.once` alone,
concurrently and through the receiver; the retention purge (cutoff edges, batches, lock held,
shutdown); the lock-gated job end to end on the database lock driver
with two replicas and a lease taken by another process. (The outbox delivery tests an earlier
draft listed are the platform's, since the platform delivers.)
