<!-- types: Receivers, DispatchHandler, DispatchResult, DispatchDelivery, OnceResult, OnceWork, ScheduledJobHandler, JobResult, ScheduledJobFiring, FlowCatalystModule, PlatformClient, Context, Locks, LockStore -->
# Receivers: dispatch jobs and scheduled jobs

The platform delivers work **to** your application over HTTP: dispatch jobs (an event, fanned out by
a subscription) and scheduled-job firings (a cron tick). `framework-flowcatalyst` mounts the two
endpoints for you, checks the delivery signature, and calls the handlers you register.

| Route | Carries | Answers |
|---|---|---|
| `POST /fc/dispatch` | one dispatch job | `200` ack or defer, `422` reject, `503` retry |
| `POST /fc/scheduled-jobs` | one scheduled-job firing | `202` at once; the job runs in the background |

The prefix is `flowcatalyst.receivers.path` (default `/fc`). The routes need an HTTP module that
provides `Routes` (for example `JettyHttpModule`); they have no session and no `Context`
requirement, because the signature is the authentication. A disabled module mounts nothing. Set the
dispatch connection's or the job's target URL on the platform to these routes (the framework does
not set it for you).

Read this page with [The FlowCatalyst module](flowcatalyst-module.md) (settings, definitions) and
[Locks](locks.md) (the overlap lock). The specification is section 4 and 4a of
[contracts/flowcatalyst.md](../contracts/flowcatalyst.md).

## Registering handlers

`Receivers` is a provided service. Register in `boot`; registration closes when the module's
`started` hook runs. A duplicate or malformed registration throws at once, which fails start-up.

```java
Receiving.handlers(
    r -> {
      r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
      r.onScheduledJob("nightly", (ctx, f) -> JobResult.success());
    });
```

(`Receiving.handlers` is a small test module that runs the lambda in `boot`; in your module the
body is `app.get(Receivers.class)` then the same calls.)

A dispatch pattern is an exact event type (`orders:billing:invoice:created`), a family ending in
`:*` on a segment boundary (`orders:billing:*`), or `*`. The most specific wins: exact, then the
longest family, then `*`. `orders:*` does not match `orders` itself. Segments are
`[A-Za-z0-9._-]+`. A scheduled-job code is `[A-Za-z0-9._:-]{1,186}`.

Every handler receives a `Context`: the client is the code from the delivery's
`X-FlowCatalyst-Client` header, or `Receivers.PLATFORM_CLIENT` (`_platform`, which no real client
identifier can equal) for a platform-scoped dispatch job and for every scheduled job; the
application is `flowcatalyst.application`; the correlation id is the envelope's when it is
printable ASCII without spaces and at most 200 characters, else generated; the execution id is the
dispatch job id, or the scheduled-job instance id.

## Settings

All under `flowcatalyst.receivers.`; durations are `30s`, `10m` and so on. Secrets come from the
environment, for example `FLOWCATALYST_RECEIVERS_SIGNING_SECRET`; see [Config](config.md#secrets).

| Key | Type | Default | Meaning |
|---|---|---|---|
| `path` | string | `/fc` | prefix of the routes; literal segments, no trailing `/` |
| `signing-secret` | secret | none | the **shared** secret the platform signs deliveries with; used by each receiver that has no secret of its own |
| `bearer-token` | secret | none | the **shared** optional bearer token; when effective, `Authorization: Bearer` must match as well as the signature |
| `dispatch.signing-secret` | secret | shared one | the secret for the dispatch receiver only |
| `dispatch.bearer-token` | secret | shared one | the bearer token for the dispatch receiver only |
| `scheduled-jobs.signing-secret` | secret | shared one | the secret for the scheduled-job receiver only |
| `scheduled-jobs.bearer-token` | secret | shared one | the bearer token for the scheduled-job receiver only |
| `require-signature` | boolean | `true` | `false` accepts unsigned deliveries (start-up logs a warning) |
| `dispatch-timeout` | duration | `25s` | how long a dispatch handler may run; keep it below the job's timeout (platform default 30 s) |
| `job-timeout` | duration | `10m` | how long a scheduled job may run when its firing carries no `timeoutSeconds` |
| `job-lock-lease` | duration | `10m` | lease of the scheduled-job overlap lock, 1 s to 24 h; refreshed every third of it |
| `completion-retry-delay` | duration | `1s` | wait before the first retry of a failed completion call, 0 to 1 m; doubles |

### Why there can be two secrets

Dispatch jobs are signed by the service account of the subscription, or of its connection. Scheduled
jobs are signed by the application's service account. They may be different accounts with different
secrets. Set the shared `signing-secret` when one account does both; set the per-receiver secrets
when they differ (the shared one is then optional). A receiver uses its own secret when it is set
and not blank, else the shared one. The bearer tokens fall back the same way, each independently of
the secret. A blank value counts as not set.

At start-up, **a receiver that has handlers needs a non-blank effective secret** when
`require-signature` is true, or `StartupException` names the receiver's key
(`flowcatalyst.receivers.dispatch.signing-secret` or
`flowcatalyst.receivers.scheduled-jobs.signing-secret`). A receiver with no handlers needs no secret
and refuses every delivery (`401 MISSING_SECRET` when it has none).

## Dispatch jobs

The body is the platform's envelope, or the raw payload for a `dataOnly` subscription. The job id
and type always come from the headers `X-Dispatch-Job-Id` and `X-Event-Type`, which are required;
`X-FlowCatalyst-Client: {id}:{code}` is optional. A malformed header or envelope is `400
VALIDATION` and no handler runs.

A `DispatchHandler` receives the `Context` and a `DispatchDelivery`: `jobId()`, `eventType()`,
`attemptNumber()` (empty for data-only), `source()`, `subject()`, `messageGroup()`,
`correlationId()`, `client()`, `isDataOnly()`, `data()` (parsed JSON), `rawBody()`, and `once(...)`.
It returns a `DispatchResult`:

| Result | Response | Meaning to the platform |
|---|---|---|
| `DispatchResult.ack()` | `200 {"ack":true}` | done |
| `DispatchResult.defer()` or `defer(Duration)` | `200 {"ack":false,"delaySeconds":n}` | not now; reschedule without spending retry budget (default delay 30 s; the delay is rounded up to whole seconds) |
| `DispatchResult.reject(reason)` | `422` with `FlowCatalyst-Outcome: reject` | terminal; never retried. The reason is returned in the message after cleaning, so never put internals in it |

### What the platform sees

| Situation | Response | Platform behaviour |
|---|---|---|
| Handler returned `ack` / `defer` / `reject` | `200` / `200` / `422` | as above |
| Handler **threw**, exceeded `dispatch-timeout`, or returned `null` | `503 UNAVAILABLE`, fixed message | **transient**: retried with the platform's backoff up to the job's max retries. The exception is logged at ERROR with the job id and correlation id and never returned |
| No handler for the type | `422` reject `no handler for <type>` | terminal: a job that can never succeed must not retry |
| Bad or missing signature, or bearer | `401 UNAUTHENTICATED` with `details.reason` | the platform treats 401 and 403 as terminal, so a wrong secret shows at once |
| Malformed headers or envelope | `400 VALIDATION` | |
| Receivers not yet ready, or stopping | `503 UNAVAILABLE` | retried |

A handler that wants a terminal failure returns `reject` explicitly; throwing is how it says "try
again". On a timeout the handler's virtual thread is interrupted, but the request does not wait for
it to stop, so write handlers that notice interruption.

### At-least-once: the rule

**A handler can run more than once for one job** (a timeout after a commit, a lost response, a
retry after a 503). Make it idempotent. The delivery's job id is the idempotency key, and
`delivery.once(db, handlerName, work)` does the bookkeeping:

```java
OnceResult<Integer> first = d.once(db, "send-invoice", tx -> send(tx, "dj_1"));
OnceResult<Integer> second = d.once(db, "send-invoice", tx -> send(tx, "dj_1"));
// first is Ran(1), second is AlreadyDone
```

`work` receives the open `java.sql.Connection` (READ COMMITTED, not auto-commit; it must not
commit). A record of `(handlerName, jobId)` is inserted in the same transaction as the work, so
either both commit or neither does. A repeat skips the work and returns `OnceResult.AlreadyDone`;
a concurrent repeat waits for the first and then skips (or runs, if the first rolled back). If the
work throws, everything rolls back and the exception propagates, so the platform retries. The
handler name is `[A-Za-z0-9._:-]{1,200}`; one job can be handled once per name. The record lives in
table `fw_dispatch_once`, created by the module's migration set `flowcatalyst` on the default
database whenever the database module is listed.

## Scheduled jobs

The platform's HTTP timeout for a firing is 10 s, so the receiver answers `202 {"ok":true}`
**immediately** and runs the handler in the background on a virtual thread. An unknown job code is
`404 NOT_FOUND`; a malformed envelope is `400 VALIDATION` (types and patterns are checked, stricter
than the TypeScript runner); `503` while starting or stopping.

A `ScheduledJobHandler` receives the `Context` and a `ScheduledJobFiring` (`jobId()`, `jobCode()`,
`instanceId()`, `scheduledFor()`, `firedAt()`, `triggerKind()`, `correlationId()`, `payload()`,
`tracksCompletion()`, `timeout()`, `concurrent()`, `fencingToken()`, `log(...)`) and returns a
`JobResult`: `JobResult.success()`, `JobResult.of(value)` (the value is sent as JSON) or
`JobResult.failure(message)`. A throw, or `null`, is a failure.

**Overlap protection is yours, not the platform's.** Unless the envelope says `concurrent` (absent
means false), the run takes the lock `scheduled-job:{jobCode}` (service scope) from the **default**
lock instance, with a lease of `job-lock-lease` refreshed while the handler runs. Pass
`firing.fencingToken()` on to what you write if a stale run must be rejected there. So start-up
fails when a job handler is registered and no lock instance is configured: list the locks module
with a default instance (driver `database` or `redis` across replicas; `memory` only for one process).

When the job `tracksCompletion`, the outcome is reported to the platform; when it does not, nothing
is reported. Outcomes:

| What happened | Reported |
|---|---|
| handler returned a result | `SUCCESS` with the value as JSON (omitted when null; over 10 000 characters it becomes `{truncated:true, preview}`) |
| `JobResult.failure(m)` or a throw | `FAILURE {error: m}` (the exception message, or its class name when blank) |
| lock held by another run | handler not run; `FAILURE {skipped:true, reason:"lock-held"}` |
| lock store unavailable | handler not run (no exclusion means no run); `FAILURE` |
| lock lost while running | handler interrupted; `FAILURE` |
| timeout (`timeoutSeconds`, else `job-timeout`) | handler interrupted; `FAILURE {error:"timed out after 10s"}` at once, while the lock stays held until the handler really returns |
| application stopping | handler interrupted; `FAILURE`; the module waits up to `app.shutdown.drain` |

`firing.log(level, message)` posts a line to the instance's log on the platform, best effort, and
never throws.

## Security properties of the signature check

The check is the platform's webhook signature: hex HMAC-SHA256 over `timestamp + rawBody` with the
secret, headers `X-FlowCatalyst-Signature` and `X-FlowCatalyst-Timestamp`. It runs before any
handler, and before the body is read into one.

- Fails closed: no secret configured means refusal (with `require-signature` true).
- The timestamp must be within 300 s in the past or 60 s in the future of the application's clock
  (an injected `InstantSource`); Unix seconds, ASCII digits only.
- The signature is compared in constant time; the bearer token is compared as SHA-256 digests.
- A signature, timestamp or `Authorization` header sent twice is refused (`DUPLICATE_HEADER`).
- The body is read once and those same bytes are verified, parsed and given to the handler.
- A refusal is `401` with `details.reason` set to a code, logged as a WARN naming the code and the
  framework's correlation id; **never a header value or a secret**.
- With `require-signature: false` an unsigned delivery is accepted, and a signed one is still
  verified when a secret is configured. The bearer gate applies whenever a token is configured.
  The platform sends **unsigned** deliveries when it cannot resolve credentials, so leave
  `require-signature` true unless the routes are reachable only from a trusted network.

## Testing receivers

`FakePlatform` (the module's test-jar) serves the token endpoint and records the completion and log
calls a scheduled job makes; `TestClient` (from the HTTP module's test-jar) posts to the routes
without a socket. The framework's own receiver tests, `DispatchReceiverTest`,
`ScheduledJobReceiverTest` and `ReceiverSecretsTest`, are the worked examples. See also
[Testing](testing.md#the-fake-platform).
