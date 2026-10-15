<!-- types: Platform, PlatformClient, PlatformError, Declared, Definitions, Catalog, EventType, Permission, Role, Subscription, Connection, DispatchPool, ScheduledJob, Process, Doc, SyncMode, SyncReport, CategoryResult, SyncError, Receivers, DispatchResult, Ack, Defer, Reject, JobResult, Success, Failure, JobOptions, Delivery, DeliveryMeta, DeliveryClient, Firing, OnceResult, BuildOption, Category, Outcome, Completion, LogLevel -->
# Go: the FlowCatalyst module

Package `flowcatalyst` (its own Go module, `github.com/flowcatalyst/framework/go/flowcatalyst`, the
only importer of the platform SDK) implements the [FlowCatalyst contract](../../contracts/flowcatalyst.md):
the platform client as the application's service account, the definitions and their sync, the
`flowcatalyst` and `outbox` health checks, and the two receivers, which run handlers as the
application's service principal. The Java pages are [The FlowCatalyst module](../flowcatalyst-module.md)
and [Receivers](../receivers.md); behaviour, settings and defaults are the same unless a section
says otherwise. fcsdk types never appear in this package's signatures.

## Declaring and building

```go
d := flowcatalyst.Declare(app)                                  // in main: settings, migration set, component claim
d.Definitions("orders").
	EventTypeOf(orders.Placed, "Order placed", "A customer placed an order.").
	Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read"))

fc, err := flowcatalyst.Build(ctx, b, d,                        // in the build function
	flowcatalyst.WithDatabases(dbs), flowcatalyst.WithLocks(all))
err = flowcatalyst.OnDispatch(fc.Receivers(), "billing:invoices:invoice:paid", handler)
routes, err := fc.Routes()                                      // to httpfw.NewServer
```

Every setting is configuration (`flowcatalyst.*`, config-and-code contract section 3.5); the keys,
defaults, ranges and environment variables are the Java page's table. The module claims the
`flowcatalyst` component, so a misspelt key fails start-up listing the real ones. While enabled,
`base-url`, `application`, `client-id` and `client-secret` are required and a missing one is a
start-up problem naming it, all reported at once; `flowcatalyst.enabled: false` needs none of them,
leaves the catalog empty and makes every method of the client fail with `ErrDisabled`.
`Declare` also contributes the migration set that creates `fw_dispatch_once` (module `flowcatalyst`,
the Java file byte for byte): run `database.Migrate` before `Build` so the purge and the checks find
their tables. `WithDatabases` and `WithLocks` give the module what it uses when the application has
it; both are optional.

## The platform client

`Platform.Client()` returns a `*PlatformClient`, a thin wrapper over the SDK client with client
credentials: `AccessToken(ctx)` (a `config.Secret`, cached; use it as the credential of an
`httpclient` for platform APIs the wrapper does not cover), `ClearToken()`, `VerifyCredentials`,
`ServicePrincipal(ctx)`, `CompleteJobInstance` and `LogJobInstance`. It holds two SDK clients over
one token source, because the SDK retries inside every call and the completion calls have their own
bounded retry. Errors are `*PlatformError` values whose message carries the status, never the
platform's answer or a credential (they appear on the unauthenticated health endpoint).

The platform is not on the request path: if it is down at start-up the application starts, the
failure is logged and the `flowcatalyst` check (informational) reports it. Once receivers have
handlers the check also resolves the service principal, and start-up logs which principal handlers
run as.

## Definitions and `sync`

`Declared.Definitions(module)` collects event types (`EventType`, `EventTypeOf` for a typed
`event.Type`), permissions, roles (`NewRole`), connections, dispatch pools, subscriptions
(`NewSubscription(...).ViaConnection(...).InPool(...)`), scheduled jobs
(`NewScheduledJob(...).Tracking(true).Targeting(url).WithTimeout(n)`; a job's target is an absolute URL), processes and docs. They
are validated in `Build` against `flowcatalyst.application` with the Java rules (stricter than the
platform's): four-part lowercase codes starting with the application, role names without a prefix,
six-field crons, subscriptions with at least one event type, a path target only with
`flowcatalyst.subscription-target-base-url`, codes unique across modules. Every problem is reported
at once naming the contributing module. Optional numbers (`MaxRetries`, `RateLimit`, ...) are 0 for
"the platform's default" and negative is a problem (Java's nullable `Integer` rejects 0 too).

`Platform.Sync(ctx, mode)` sends one SDK call per category in the order roles, event types,
connections, dispatch pools, subscriptions, processes, scheduled jobs, docs, never stopping at a
failed category, and returns a `SyncReport` (a `*SyncError` carrying it when any failed). A
`SyncDryRun` makes no request at all. Permissions travel only inside roles; a declared permission no
role grants is a warning. Removing a definition from code never deletes it on the platform (the
remove-unlisted flag is never set). The `sync [--dry-run]` command runs `Sync` after `Build` and
before anything starts (exit 0, 1 when a category failed, 2 for a bad argument or a disabled
module); `flowcatalyst.sync.on-boot` also syncs in the started phase, logging and continuing on
failure.

## Receivers

`Platform.Routes()` seals the registry and returns the routes `POST {path}/dispatch` and
`POST {path}/scheduled-jobs` (`flowcatalyst.receivers.path`, default `/fc`). Only a receiver that has
handlers is routed, so the other answers 404 to every method before any signature work (Java answers
`GET` with 405); with `flowcatalyst.receivers.enabled: false` nothing is mounted and start-up warns
which handlers can never be called. Deliveries are answered 503 until start-up has checked the
registrations: a receiver with handlers needs an effective signing secret while
`require-signature` is true (its own, else the shared one), exclusive scheduled jobs need a default
lock store, and the routes must have been taken from `Routes()`. Per-receiver secrets and bearer
tokens fall back, independently, to the shared ones; a blank value counts as unset.

The signature rules are the Java module's (ported, not fcsdk's `webhook`): hex HMAC-SHA256 over the
raw timestamp then the raw body, ISO or Unix-seconds timestamps compared in whole seconds (300 s past,
60 s future), constant-time comparison, a refusal as `401 UNAUTHENTICATED` with `details.reason`
(`MISSING_SECRET`, `MISSING_SIGNATURE`, `MISSING_TIMESTAMP`, `INVALID_TIMESTAMP`,
`TIMESTAMP_EXPIRED`, `TIMESTAMP_IN_FUTURE`, `INVALID_SIGNATURE`, `MISSING_BEARER`, `INVALID_BEARER`,
`DUPLICATE_HEADER`), the body not read until the headers pass, the bearer gate AND-ed.

### Who runs the handler

A handler always runs as the application's service principal: the `sub` of the client-credentials
token (a JWT with `type` `SERVICE`; anything else is `ErrNoServicePrincipal` and the delivery is
answered `503 UNAVAILABLE`, the dispatch retried or the firing redelivered, with no handler run). The
`fw.Context` has that principal (kind Service), the client from `X-FlowCatalyst-Client` (or
`_platform`, which is also every firing's client), the application, the envelope's correlation id
(generated when it is not printable ASCII of at most 200 characters), **causation and execution = the
dispatch job id**, or for a firing **the instance id**, and no permissions. The vector
`conformance/receivers/context.json`, written by the Java test, passes in `conformance_test.go`.

### Dispatch jobs

```go
flowcatalyst.OnDispatch(fc.Receivers(), "billing:invoices:invoice:paid",
	func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[InvoicePaid]) (flowcatalyst.DispatchResult, error) {
		res, err := flowcatalyst.Once(ctx, d.Meta, db, "mark-paid", func(ctx context.Context, tx *database.Tx) (struct{}, error) {
			return struct{}{}, markPaid(ctx, tx.Conn(), d.Data.OrderID)
		})
		...
		return flowcatalyst.Ack{}, err
	})
```

Patterns are an exact type, a family ending in `:*` on a segment boundary, or `*`; the most specific
wins; a duplicate or malformed one is an error (and so is registering after `Routes`). A typed
handler gets its data decoded into a `T` before it runs; data that does not decode answers `Reject`
(decision 23); `OnDispatchRaw` and `Delivery[jsontext.Value]` give the raw JSON. `Delivery.Meta` has
the job id, event type, attempt number, source, subject, message group, correlation id, client,
whether the body is data-only, and the raw bytes that were verified. Results: `Ack{}` is
`200 {"ack":true}`; `Defer{Delay}` is `200 {"ack":false,"delaySeconds":n}` rounded up (0 to
2^32-1; `DefaultDefer` is 30 s); `Reject{Reason}` is `422 REJECTED` with `FlowCatalyst-Outcome:
reject`, the reason non-blank, cleaned and cut to 500 characters. A returned error, a panic, a nil
result, an invalid result or a handler past `dispatch-timeout` is `503 UNAVAILABLE` with a fixed
message (the cause is logged with the job and correlation ids). On a timeout the handler's context is
cancelled and the goroutine is left to finish, tracked, and shutdown waits for it within the drain.
No handler for the type is a terminal `422` reject.

### Scheduled jobs

`Receivers.OnScheduledJob(code, JobOptions{}, handler)` returns a `JobResult` (`Success{Value}` or
`Failure{Message}`) or an error. The receiver validates the firing (`400 VALIDATION`), answers
`404` for an unknown code, and otherwise `202 {"ok":true}` at once and runs the handler in the
background. Unless the firing is `concurrent` it takes the service lock `scheduled-job:{code}` from
the default lock store (lease `job-lock-lease`, refreshed while it runs); held means the handler is
not run and a tracked instance is completed `FAILURE {skipped:true, reason:"lock-held"}`; a lost lock
cancels the handler and reports the failure; a timeout (`timeoutSeconds` or `job-timeout`) reports
at once but keeps the lock until the handler returns. `JobOptions{AlwaysConcurrent: true}` never
takes the lock and refuses an exclusive firing. Completion is reported only when the job tracks it,
the result cut to 10 000 characters, retried three times after the first call with a doubling delay
(`completion-retry-delay`) and the SDK transport retry off. `Firing.Log(level, message)` appends to
the instance's log, best effort.

## `Once` and its purge

`flowcatalyst.Once(ctx, meta, db, handlerName, work)` runs `work` at most once per (handler, job id)
inside one transaction with the record in `fw_dispatch_once`; the work gets the framework's
`*database.Tx` and uses `tx.Conn()`. An error or a panic rolls both back, so the redelivery runs it
again; a concurrent repeat waits for the first. Old rows are purged by a background loop
(`flowcatalyst-once-purge`, every `once-purge-interval`, rows older than `once-retention` by the
application's clock, in batches of 5 000, one replica at a time when there is a lock store). Running a
use case inside the same transaction is an open point shared with Java.

## Differences from Java

- Docs: fcsdk has the docs category, so `Sync` sends documentation pages (Java skips them with a
  warning) and a dry run lists them.
- fcsdk retries 408, 425, 429 and 5xx in its transport (Java's SDK: 408, 429, 502, 503, 504), 3
  attempts, for the main client; the sync wire has no explicit `removeUnlisted=false`.
- No 405 for an unused receiver (above); handlers receive a `context.Context` cancelled on timeout
  where Java interrupts a virtual thread.
- Registration is by function (`OnDispatch[T]`) with an explicit pattern, because a Go type cannot
  carry its event type; optional integers are 0 for unset.
- `Once` hands the work a `*database.Tx`, not a JDBC `Connection`.

## Using it without the platform checkout

fcsdk has no release yet (`pkg/fcsdk/VERSION` 0.0.0, no `pkg/fcsdk/v*` tag), so
`go/flowcatalyst/go.mod` requires `github.com/flowcatalyst/flowcatalyst/pkg/fcsdk v0.0.0` without a
replace, and the framework's own checks resolve it from a sibling checkout of the platform repository:
`make -C go fc-modfile` writes `go/flowcatalyst/go.local.work` (not committed) with a `replace` to
`../flowcatalyst-go/pkg/fcsdk` (or `FCSDK=path`). The module's `replace` of the core module by `../`
is applied only inside this repository. A consumer needs both modules released with tags.
