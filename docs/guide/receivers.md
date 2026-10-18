<!-- types: Receivers, DispatchHandler, DispatchResult, DispatchDelivery, OnceResult, OnceWork, ScheduledJobHandler, JobOptions, JobResult, ScheduledJobFiring, Database, Databases, FlowCatalystModule, PlatformClient, PlatformException, Context, PrincipalKind, Locks, LockStore, ReceiverTesting -->
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
requirement, because the signature is the authentication. Set the dispatch connection's or the
job's target URL on the platform to these routes (the framework does not set it for you).

**Only a receiver that has a handler answers.** An application that registers no dispatch handler
answers `404 NOT_FOUND` on `/fc/dispatch` without looking at the signature, and the same for
`/fc/scheduled-jobs` without a scheduled-job handler. `flowcatalyst.receivers.enabled: false`
removes both routes even when handlers exist. See [Applications that only want the
token](#applications-that-only-want-the-token). A disabled module (`flowcatalyst.enabled: false`)
mounts nothing either.

Read this page with [The FlowCatalyst module](flowcatalyst-module.md) (settings, definitions) and
[Locks](locks.md) (the overlap lock). The specification is sections 4, 4a and 4b of
[contracts/flowcatalyst.md](../contracts/flowcatalyst.md).

## Registering handlers

`Receivers` is a service the FlowCatalyst module provides. Register in your module's `boot`;
registration closes when the module's `started` hook runs. A duplicate or malformed registration
throws at once, which fails start-up. This is the production form (the types are `Receivers`,
`DispatchHandler`, `DispatchDelivery`, `OnceResult` and `Database` from the database module):

```java
public final class BillingModule implements Module {
  @Override
  public String id() {
    return "billing";
  }

  @Override
  public void boot(App app) {
    Receivers receivers = app.get(Receivers.class);
    Database db = app.get(Databases.class).defaultInstance();

    receivers.onDispatch(
        "orders:billing:invoice:created",
        (Context ctx, DispatchDelivery d) -> {
          OnceResult<Integer> sent = d.once(db, "send-invoice", tx -> send(tx, d.jobId()));
          return DispatchResult.ack();
        });
    receivers.onScheduledJob("nightly-export", (ctx, firing) -> JobResult.success());
  }
}
```

`d` is the `DispatchDelivery` (what the platform sent), and `db` is a `Database`, the default
instance of the database module; `send(tx, ...)` is your own method taking the open
`java.sql.Connection`. List `FlowCatalystModule` in the application (the order against your module
does not matter, because the handlers are looked up per request). The framework's tests use a
small helper, `Receiving.handlers(...)`, which wraps exactly this `boot` body in a test module.

A dispatch pattern is an exact event type (`orders:billing:invoice:created`), a family ending in
`:*` on a segment boundary (`orders:billing:*`), or `*`. The most specific wins: exact, then the
longest family, then `*`. `orders:*` does not match `orders` itself. Segments are
`[A-Za-z0-9._-]+`. A scheduled-job code is `[A-Za-z0-9._:-]{1,186}`.

Every handler receives a `Context`: the client is the **client code** (the platform's client
identifier such as `acme`, never the `clt_...` id) from the `{id}:{code}` of the delivery's
`X-FlowCatalyst-Client` header, held in a `ClientId` (see [Context](context.md#what-goes-in-a-clientid)),
or `Receivers.PLATFORM_CLIENT` (`_platform`, which no real client
identifier can equal) for a platform-scoped dispatch job and for every scheduled job; the
application is `flowcatalyst.application`; the correlation id is the envelope's when it is
printable ASCII without spaces and at most 200 characters, else generated; the execution id is the
dispatch job id, or the scheduled-job instance id. The principal is your application's service
account, and the delivered message is the cause: next section.

## Who runs the handler

Every handler runs as the **application's service principal**, kind `PrincipalKind.SERVICE`, and the
delivered message is the **cause** (owner ruling 2026-10-12). So a use case a handler runs writes
events and an audit row that say who did it and why:

| `Context` field | Dispatch job | Scheduled-job firing |
|---|---|---|
| `principal()` | the service principal, for example `prn_0HZXEQ5Y8JY5Z` | the same |
| `principalKind()` | `SERVICE` | `SERVICE` |
| `causationId()` | the job id (`X-Dispatch-Job-Id`) | the instance id |
| `executionId()` | the job id | the instance id |
| `permissions()` | empty | empty |

In the outbox that becomes each event's `causationId` and its `contextData` entries `principalId`
and `executionId`, and the audit row's `principalId`. The platform refuses an audit entry without a
principal, so before this rule such audit rows were lost. The platform's audit entry has no
causation field: the job id reaches the events, not the audit row.

**Where the id comes from.** The `sub` of the client-credentials token the module already fetches
with `flowcatalyst.client-id` and `client-secret`: the platform puts the service account's principal
id there and `type: SERVICE` beside it. `PlatformClient.servicePrincipal()` returns it; it is read
once per token (no extra request) and picked up again when the token is renewed. The token came
straight from the platform's token endpoint, so its signature is not checked.

**No principal, no handler.** When the token cannot be fetched, or does not name a service principal
(its `type` is `USER` because the configured credentials are a person's developer credentials, it
has no `sub`, or it is not a JWT), the receivers run **nothing**:

- a dispatch job is answered `503 UNAVAILABLE` (`the application's service principal is not
  available`), and the platform retries it;
- a scheduled-job firing is answered the same `503` instead of `202`, so the platform delivers it
  again; no completion is reported;
- the reason is logged at ERROR with the job id (never the token); at start-up the module logs the
  principal it will use at INFO, or the reason it has none at WARN;
- the `flowcatalyst` health check is `Down` with the reason while a receiver has handlers
  (informational unless you list it in `app.health.critical`). An application without receiver
  handlers is not affected.

A use case can admit receiver runs by the kind, as the framework's end-to-end test does:

```java
UseCase.named("send-invoice", SendInvoice.class, InvoiceSent.class)
    .authorize(
        (ctx, cmd) ->
            ctx.principalKind() == PrincipalKind.SERVICE
                ? Optional.empty()
                : Optional.of(UseCaseError.authorization("NOT_A_SERVICE", "only the service sends invoices")))
```

## Settings

All under `flowcatalyst.receivers.`; durations are `30s`, `10m` and so on. Secrets come from the
environment, for example `FLOWCATALYST_RECEIVERS_SIGNING_SECRET`; see [Config](config.md#secrets).

| Key | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | `false` mounts neither route, even when handlers are registered (start-up warns); see [Applications that only want the token](#applications-that-only-want-the-token) |
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
| `once-retention` | duration | `30d` | how long `DispatchDelivery.once` records are kept before the purge deletes them; keep it longer than the platform can redeliver a job |
| `once-purge-interval` | duration | `1h` | how often the background purge runs |
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
and answers `404 NOT_FOUND`.

## Dispatch jobs

A dispatch job is not only an event. The platform has two kinds, `EVENT` (made from an event by a
subscription) and `TASK` (created directly, for example through its batch endpoint), and for
**both** it sets `X-Event-Type` to the job's `code`. So a handler registered with
`onDispatch("<code>")` receives a TASK job whose code is `<code>` exactly as it receives an event of
that type; patterns (`orders:billing:*`, `*`) work the same. The code of a job created through the
platform API must start with the application code, `{application}:...`. How to create such a job
is on [the FlowCatalyst module](flowcatalyst-module.md#creating-a-dispatch-job).

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
| The service principal cannot be resolved ([who runs the handler](#who-runs-the-handler)) | `503 UNAVAILABLE`, no handler runs | retried. Signature, validation and the no-handler reject come first and need no principal |

A handler that wants a terminal failure returns `reject` explicitly; throwing is how it says "try
again". On a timeout the handler's virtual thread is interrupted, but the request does not wait for
it to stop, so write handlers that notice interruption.

### At-least-once: the rule

**A handler can run more than once for one job** (a timeout after a commit, a lost response, a
retry after a 503). Make it idempotent. The delivery's job id is the idempotency key, and
`delivery.once(db, handlerName, work)` does the bookkeeping:

With `d` the `DispatchDelivery` and `db` the `Database` from the registration example above:

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

**With a use case.** The work can run a use case in the same transaction:
`useCases.run(useCase, context, command, Tx.of(connection))` ([write path](write-path.md#inside-another-transaction)),
so the record, the use case's writes and its outbox rows commit or roll back together. What does not
compose: an HTTP call, an email or a message send inside the work is in no transaction (a rollback does not
unsend it), and a repository `find` through the `Database` does not see the work's uncommitted writes. Send
the job id as the idempotency key on such calls.

**Retention.** Rows older than `once-retention` (30 days by default, measured with the application's
clock against `processed_at`) are deleted by a background task the module owns: one virtual thread
that purges at start-up and then every `once-purge-interval`, in batches of 5 000 rows and at most
200 batches per run, and stops at shutdown. With several replicas, a run takes the service lock
`fw-dispatch-once-purge` from the default lock instance without waiting and skips when another
replica has it; with no lock instance configured it just runs, because the `DELETE` is idempotent.
The task exists only when the database module is listed and the table is there. If a job is
redelivered after its record was purged, the work runs again: keep the retention longer than the
platform's retry horizon.

## Scheduled jobs

The platform's HTTP timeout for a firing is 10 s, so the receiver answers `202 {"ok":true}`
**immediately** and runs the handler in the background on a virtual thread. An unknown job code is
`404 NOT_FOUND`; a malformed envelope is `400 VALIDATION` (types and patterns are checked, stricter
than the TypeScript runner); `503` while starting or stopping, and `503` when the service principal
cannot be resolved ([who runs the handler](#who-runs-the-handler)).

A `ScheduledJobHandler` receives the `Context` and a `ScheduledJobFiring` (`jobId()`, `jobCode()`,
`instanceId()`, `scheduledFor()`, `firedAt()`, `triggerKind()`, `correlationId()`, `payload()`,
`tracksCompletion()`, `timeout()`, `concurrent()`, `fencingToken()`, `log(...)`) and returns a
`JobResult`: `JobResult.success()`, `JobResult.of(value)` (the value is sent as JSON) or
`JobResult.failure(message)`. A throw, or `null`, is a failure.

**Overlap protection is yours, not the platform's.** Unless the envelope says `concurrent` (absent
means false), the run takes the lock `scheduled-job:{jobCode}` (service scope) from the **default**
lock instance, with a lease of `job-lock-lease` refreshed while the handler runs. Pass
`firing.fencingToken()` on to what you write if a stale run must be rejected there. So start-up
fails when such a job handler is registered and no lock store is given: list
`LocksModule.builder()` with a default store (`DatabaseLockStores` or `RedisLockStores` across
replicas; `LockStores.memory()` only for one process).

### Opting out of the lock: `JobOptions.alwaysConcurrent()`

A handler that is safe to run in overlap with itself (or that protects itself, say with the fencing
token or a database constraint) can be registered without the lock:

```java
r.onScheduledJob(
    CODE,
    (ctx, f) -> {
      runs.incrementAndGet();
      return JobResult.success();
    },
    JobOptions.alwaysConcurrent());
```

Such handlers do not count when start-up checks for a lock instance, so an application with only
these needs no locks module; the start-up error for the others names just the exclusive jobs. The
job must be **defined on the platform with `concurrent` true**. If a firing arrives with
`concurrent` false (or absent, which means false), the handler is **not run**: the application was
asked for an exclusive run and has no lock to give one, and running it unprotected would silently
break what the definition says. The firing is logged at ERROR, and when it tracks completion it is
reported `FAILURE {skipped:true, reason:"not-registered-for-exclusive-run"}`, so the mismatch shows
on every tick until the definition or the registration is fixed.

When the job `tracksCompletion`, the outcome is reported to the platform; when it does not, nothing
is reported. Outcomes:

| What happened | Reported |
|---|---|
| handler returned a result | `SUCCESS` with the value as JSON (omitted when null; over 10 000 characters it becomes `{truncated:true, preview}`) |
| `JobResult.failure(m)` or a throw | `FAILURE {error: m}` (the exception message, or its class name when blank) |
| always-concurrent handler, firing says `concurrent=false` | handler not run; `FAILURE {skipped:true, reason:"not-registered-for-exclusive-run"}` |
| lock held by another run | handler not run; `FAILURE {skipped:true, reason:"lock-held"}` |
| lock store unavailable | handler not run (no exclusion means no run); `FAILURE` |
| lock lost while running | handler interrupted; `FAILURE` |
| timeout (`timeoutSeconds`, else `job-timeout`) | handler interrupted; `FAILURE {error:"timed out after 10s"}` at once, while the lock stays held until the handler really returns |
| application stopping | handler interrupted; `FAILURE`; the module waits up to `app.shutdown.drain` |

**The completion call** (`PlatformClient.completeJobInstance`) is retried by the runner itself: 3
retries after the first attempt, so at most **4 calls**, waiting `completion-retry-delay` (1 s by
default), then twice that, then four times that; after the last failure it is logged at ERROR and
dropped (the platform overwrites an earlier report for the same instance, so a late success is
safe). The SDK client retries 408, 429, 502, 503 and 504 inside every call and cannot be told not to
per call, so reports go through a second SDK client built with no retries (`retryAttempts(0)`, its
own token cache). Without it the two layers multiply to up to 16 calls. Log lines
(`logJobInstance`) use the same client and are a single best-effort call each.

`firing.log(level, message)` posts a line to the instance's log on the platform, best effort, and
never throws.

## Applications that only want the token

An application that uses `FlowCatalystModule` only for its service-account token, definitions sync
or health check (an ingestor, say) registers no handlers. It does not expose receivers:

- A receiver with no handler answers `404 NOT_FOUND`, the same body as an unknown route, before
  the signature is looked at. Nothing about it depends on a secret, so no secret is needed.
- To remove the two routes entirely, set `flowcatalyst.receivers.enabled: false` (environment
  variable `FLOWCATALYST_RECEIVERS_ENABLED`). Then **nothing is mounted** and every method on
  `/fc/...` is `404`, even if a module registers handlers; start-up logs a WARN naming those
  handlers, because the platform can then never deliver to them. None of the start-up checks for
  receivers (secret, lock instance, `Routes`) apply while receivers are disabled.

A difference from a route that was never registered: with `enabled` true and no handler, the path
is registered and answers `404` to a `POST`, but a `GET` gets `405` with `Allow: POST` (the
handlers are only known after every module has booted, which can be after the route table is
built). With `enabled: false` nothing is registered and every method is `404`. This reverses the
earlier decision that the routes stay and refuse everything with `401` (see
[contracts/flowcatalyst.md](../contracts/flowcatalyst.md) section 4a, follow-up 5): fewer reachable
endpoints for an application that never wanted them.

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
calls a scheduled job makes (`instanceCalls`, `awaitInstanceCalls`, `failInstanceCalls`; see
[Testing](testing.md#the-fake-platform)). `TestClient` (in `framework-http`'s main jar, no test-jar needed) posts to the
routes without a socket, and `ReceiverTesting` (the same test-jar) signs what the platform signs: the
hex HMAC-SHA256 of the timestamp header's text followed by the body, keyed with the secret, with the
timestamp taken from the clock you gave the application. A dispatch delivery and a firing:

```java
TestResponse r =
    ReceiverTesting.dispatch(client, SECRET, now, "dj_1", TYPE, "clt_1:acme", envelope); // client header null: platform-scoped
TestResponse fired = ReceiverTesting.scheduledJob(client, SECRET, now, firingJson);      // 202 when accepted
```

`ReceiverTesting.signature(secret, timestamp, body)` and `timestamp(now)` are there when a test
sends the request some other way (a real socket). Wrong secrets, missing signatures and the other
refusals are the framework's own tests; yours only need the happy path and your handler's results.

The application is built with the module's settings in its environment (`FLOWCATALYST_BASE_URL`
pointing at `platform.baseUrl()`, and `FLOWCATALYST_RECEIVERS_SIGNING_SECRET`), an injected
`InstantSource` so the timestamp is inside the tolerance, and a module that registers handlers in
`boot`. The framework's own tests, `DispatchReceiverTest`, `ScheduledJobReceiverTest`,
`ReceiverSecretsTest`, `DispatchOnceTest` and `OncePurgeTest`, are the worked examples (the last two
need Docker for PostgreSQL).
