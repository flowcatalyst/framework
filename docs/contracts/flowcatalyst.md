# Contract: the FlowCatalyst module, client side and receivers (M4a, M4b)

Status: draft for M4a and M4b. Facts come from `docs/research/m4-platform-facts.md` (each with a
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
| `flowcatalyst.receivers.signing-secret` (secret) | the secret the platform signs with |
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
  application, and the job's correlation id (generated when absent).
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

## 4a. As built in slices 1 and 2 (2026-10-09), with rulings

Where the first two slices (commits `e07493c`, `29771c5`) differ from sections 1 and 2 above, the
build is right and these notes are the contract:

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

## 6. Tests

Unit: definition validation and the sync result handling against a fake platform (a small
in-process HTTP server in the test support); outbox status mapping table (each row of section 3);
group ordering; positional results; lease loss stops sending; signature vectors from
`tests/golden/webhook/` in the Go repo; each receiver response (ack, defer, reject, throw,
timeout, no handler, bad signature, unsigned with `require-signature` on and off); overlap lock
held / free; completion body and truncation; at-least-once helper. Integration (Testcontainers
PostgreSQL, a real platform is not required): outbox rows delivered to the fake platform end to
end, including a restart mid-batch.
