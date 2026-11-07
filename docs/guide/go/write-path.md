<!-- types: UseCase, Defined, Runner, Plan, Step, Appender, Fresh, Loaded, Repository, VersionCheck, Aggregate, Entity, Valid, Authorized, Grant, Committed, ConflictPolicy, Error, Kind, Name, ErrorCode, DispatchJob, JobOption, AuditData, AuditField, AuditEntry, OutboxWriter, Declared, Type, Metadata, Event, Factory, ID, Harness -->
# Go: the write path

Packages `usecase`, `event` and `id` implement the [write-path contract](../../contracts/write-path.md):
use cases with validate, authorize and execute phases, one runner that applies each plan in one
transaction with optimistic locking and client scoping, and the outbox writer whose rows are byte
for byte the Java framework's. Snippets come from `go/examples/orders-demo`.

## A use case is an interface

```go
// AddLineUseCase adds a line to an open order.
type AddLineUseCase struct {
	Orders Store
	Ids    *fw.Ids
	Events *event.Factory
}
```

It has four methods: `Name`, `Validate`, `Authorize` and `Execute`. A use case without `Authorize`
**does not compile** where it is defined (`usecase.Define` takes a `usecase.UseCase[C, E]`; a test in
`usecase` proves it with a type check of `testdata/noauth`). `Authorize` returns a `usecase.Grant`:
`usecase.Public()` for a deliberately open operation, `usecase.Require(cx, permission)` with the
Context's permissions, or an error. `Execute` receives a `usecase.Authorized[C]`, which only the
runner makes:

```go
// Execute loads the order once; only an open order takes a line.
func (u AddLineUseCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[AddLine], db *database.DB) (*usecase.Plan[OrderLineAdded], error) {
	cmd := a.Command()
	loaded, found, err := u.Orders.Find(ctx, db, cx.Client(), cmd.Order)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, usecase.NotFound(OrderNotFound, "There is no such order.")
	}
	var open domain.OpenOrder
	switch o := loaded.Get().(type) {
	case domain.OpenOrder:
		open = o
	case domain.CancelledOrder:
		return nil, usecase.BusinessRule(OrderNotOpen, "The order is cancelled and cannot change.")
	}
```

```go
	return usecase.NewPlan(ev, usecase.Update(loaded.With(open), u.Orders)), nil
```

`usecase.RequireService(cx)` grants only a service principal (kind `fw.PrincipalService`: the
receivers' principal, a scheduled job, a provider webhook's `fw.ServiceContextFor`); anyone else,
whatever their permissions, gets `AUTHORIZATION` / `SERVICE_REQUIRED`. A service principal carries no
permissions, so `Require` cannot say this. Return it from `Authorize`:

```go
	if g, err := usecase.RequireService(fw.ServiceContextFor(acme, fw.MustPrincipalID("prn_svc"))); err != nil || g == (usecase.Grant{}) {
```

(from `usecase/require_service_test.go`, which also refuses a user, a field worker, a principal of no
kind and a user holding every permission).

`Define` registers it with a conflict policy; `Run(ctx, cx, cmd)` returns `(Committed[E], error)`,
the error always a `*usecase.Error` (kind, code, message, details; the kinds map to 400, 403, 404,
409, 409 and 500).

### The conflict policy: Fail unless you opt in

**The default is `usecase.Fail()`**: the first conflict (a stale aggregate, a serialization failure,
a deadlock) ends the run with `CONFLICT` / `STALE_AGGREGATE`. That is right for a person's request:
they saw a record, someone changed it, they should look again. It is usually wrong for work no person
is waiting on: a **receiver** (a dispatch job, an event delivered to a subscription), a **scheduled
job**, a **process step** that reacts to another use case. Two deliveries racing on one aggregate then
fail one of them, which the platform retries much later (or never, for a `Reject`). Give those use cases
`usecase.Retry(n, backoff)` (2 to 10 attempts in total, each wait randomised between half and one and a
half times `backoff`); every attempt reloads, so the retry sees the other writer's change:

```go
	retry, err := usecase.Retry(3, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
```

```go
	}}, usecase.WithConflictPolicy(retry))
```

(from `usecase/append_test.go`, where the second attempt commits).

A test that runs two of them at once (two drivers claiming one order) is what finds a missing opt-in.

## A use case that works on one record: the load phase

`usecase.LoadedUseCase[C, E, L]` (write-path contract 3a) is a second interface, registered with
`usecase.DefineLoaded`; plain use cases are unchanged. It has `Name`, `Validate` and `Authorize` as
before (so one without `Authorize` still does not compile), then `Load(ctx, cx, cmd, db) (L, found, err)`,
`WhenMissing() *usecase.Error`, `AuthorizeLoaded(ctx, cx, cmd, record L) error` (the record-level check)
and `Execute(ctx, cx, cmd, record L, db)`. The record-level check is part of the type, so a use case that
forgets it, or declares it with a drifted signature (another command type, a pointer receiver), does not
compile; the runner never skips it. A use case with no record-level rule says so by embedding
`usecase.NoRecordCheck[C, L]`, which permits every record. (In Java `authorizeLoaded` is an optional
builder call; the Go type makes the same choice explicit.)

Order inside one attempt: validate, authorize, **load**, **authorizeLoaded**, execute. A caller
refused by `Authorize` causes no read; `found=false` ends the run with `WhenMissing`'s error and the
rest does not run (`Find` filters by client, so another client's record is missing); a refusal from
`AuthorizeLoaded` is the result (return `WhenMissing()`'s error to hide that the record exists); a
conflict re-runs all of it, so the retry loads and checks again. A load that returns an error is
`INTERNAL` / `LOAD`, a failing `AuthorizeLoaded` `INTERNAL` / `AUTHORIZE`; the cause is logged and
never in the message. Go differs from Java only in shape: the check is a method rather than a
builder call, and "no check" is written (`NoRecordCheck`) rather than left out.

## Append-only rows in the plan

Some writes are not aggregates: an activity entry, a live-update log line a stream tails, a projection
row. They are only ever inserted, never loaded, changed or version-checked, and they must commit with
the change they describe. `usecase.Append(row, appender)` puts one in the plan; an `Appender[R]` is the
application's INSERT (`Append(ctx, tx, row) error`, on the attempt's `*database.Tx`):

```go
// journal appends entries; with fail set it fails.
type journal struct{ fail error }

func (j journal) Append(ctx context.Context, tx *database.Tx, e entry) error {
```

```go
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "before"}, journal{}), // first in the plan, yet not the audit subject
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "after"}, journal{}),
		), nil
```

(from `usecase/append_test.go`). An append runs **in plan order** among the aggregate steps, in the
same transaction: a later failure or a conflict rolls it back, and a retried attempt writes it again
(once per committed run). It is never version-checked and never the audit row's subject: the audit
row names the plan's first **aggregate** step, wherever the appends are, so there is no need to put the
real aggregate first. The row carries everything it stores, its client included (take it from the
Context when the plan is built: the runner's client check covers aggregates only). A plan of appends
and no aggregate step is refused as `INTERNAL` / `APPEND_ONLY_PLAN` (logged as a bug): the appends
belong to an aggregate change. **Aggregate or Append?** Anything that is loaded, changed or deleted
later, or whose concurrent change must be detected, is an aggregate; anything only inserted (and read
by queries, a tailer or a projection) is an append.

## Running inside the caller's transaction

`Defined.RunJoined(ctx, cx, cmd, tx)` (write-path contract 4a) runs the same attempts on a
transaction the caller owns, so a use case composes with `flowcatalyst.Once`, whose work gets the open
`*database.Tx`: the delivery's idempotency record, the use case's writes, its events and its audit row
are one transaction. Each attempt runs under a savepoint (pgx nests a `Begin` on a transaction); a
stale aggregate, a serialization failure, a deadlock, a unique violation or any failed step rolls back
**to the savepoint**, so the caller's earlier work survives and the transaction stays usable; a retry
runs the attempt again on the same transaction. The runner never commits or rolls back the caller's
transaction: `Committed` means "applied in your transaction". Reads through a repository's `Find`
use the pool, so they do not see the caller's uncommitted writes; an HTTP call or a message send is
not made atomic by this (make it idempotent: the dispatch job id is the key).

Differences from Java: it is a method of the defined use case, not of the runner (Go has no generic
methods); the handle is the `*database.Tx` only the framework (`Once`, the runner) makes, so
Java's "connection in auto-commit" argument error cannot occur; the savepoint-release step code is
`SAVEPOINT`, as in Java. `usecasetest.Begin` plays the caller in tests.

## Fresh, Loaded and the repository

The aggregate knows nothing of storage. `usecase.NewFresh(cx, a)` is a new aggregate for the
Context's client, written by `usecase.Insert` at version 1; `usecase.LoadedFrom(client, version, a)`
is what a repository's `Find` returns, written by `usecase.Update` or `usecase.Delete` where id,
client and version still match. A loaded aggregate cannot be inserted nor a new one updated: they
are different types. The aggregate names itself for the audit row (`AuditEntity()`). A
`usecase.Repository[A, I]` is the application's (the demo's runs sqlc's generated queries);
`usecasetest.Repository` checks one keeps the contract (stale writes affect no row, `Find` filters
by client).

## The runner

`usecase.NewRunner(b, outbox, db)` with the outbox module's declaration (`usecase.Declare(app)`,
which contributes the `outbox` migration set: the SDK's table byte for byte, then the client-id
widening). One attempt: validate, authorize, execute, the client check, then one READ COMMITTED
transaction: the plan's writes (aggregate steps and appends) in order, the events, the dispatch jobs,
one audit row, commit. The
failure table of the contract, every row tested:

| Failure | Result |
|---|---|
| stale aggregate (row count 0), SQLSTATE `40001` or `40P01` | conflict: the policy, then `CONFLICT` / `STALE_AGGREGATE` |
| `23505` | `CONFLICT` / `UNIQUE_VIOLATION` with the constraint, not retried |
| a `Loaded` of another client | `INTERNAL` / `CROSS_CLIENT_WRITE`, not retried |
| appends and no aggregate step | `INTERNAL` / `APPEND_ONLY_PLAN`, not retried |
| any other error or a panic in a phase | `INTERNAL` with the step: `VALIDATE`, `AUTHORIZE`, `LOAD`, `EXECUTE`, `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`; the cause logged, never in the message |
| the context ends while waiting between attempts | `INTERNAL` / `INTERRUPTED` |

Metrics: `fw_usecase_runs_total{usecase,outcome}` and `fw_usecase_duration_seconds{usecase}`.

## Events and the outbox

An event is a struct with its data as json-tagged fields (the Java record's component names) and
its `event.Metadata` in a field tagged `json:"-"` (marshalling metadata fails, so a missing tag is
found at once):

```go
// OrderLineAdded is the result of add-line.
type OrderLineAdded struct {
	Meta           event.Metadata `json:"-"`
	OrderID        domain.OrderID `json:"orderId"`
	LineID         domain.LineID  `json:"lineId"`
	SKU            string         `json:"sku"`
	Quantity       int32          `json:"quantity"`
	UnitPriceCents int64          `json:"unitPriceCents"`
	LineCount      int            `json:"lineCount"`
}
```

An `event.Factory` fills the metadata (TSID, type, source, subject, message group, the Context's
ids, the clock's time). The outbox rows (`EVENT`, `DISPATCH_JOB`, `AUDIT_LOG`) are written with the
shared `conformance/sql/outbox/insert.sql` and equal `conformance/outbox/outbox.json` **byte for
byte**, payload text and size included. JSON is `encoding/json/v2` with options that make it write
what Jackson writes: `time.Time` as Java's `Instant.toString` (fraction in groups of 3 digits),
floats as Java's `Double.toString` (`1.0`, `1.0E10`), `\u` escapes in upper case; strings, integers,
field order and `<`, `>`, `&`, U+2028 already match. Use pointers with `omitzero` for optional data
(Jackson's `NON_NULL`); a nil slice is written `[]` (Java would omit a null list).

`usecase.AuditData` on a command gives the audit row's `operationData` as ordered fields
(`usecase.Field("customer", ...)`): Java's `LinkedHashMap` order, which a Go map cannot keep.

## Dispatch jobs

```go
job, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", usecase.JobID("job-1"))
```

`NewDispatchJob(code, targetURL, options...)` checks every rule of contract section 7a with the
Java builder's messages (checked against the vector's refusals); `Plan.Dispatch(job)` writes it
after the events and before the audit row, in the platform's batch-item shape (`kind` default
`TASK`, `metadata` as an array, the fields the endpoint ignores never written).

**A job keeps its client.** A job written in a client's context names that client: the item carries
`clientCode` (the Context's client), which the platform resolves, so it delivers the job with
`X-FlowCatalyst-Client` and the receiving handler runs in that client's context
([FlowCatalyst module](flowcatalyst.md#who-runs-the-handler)). A job written in `fw.PlatformClient`'s
context (a sweep's base context) names none and is delivered for `_platform`. `PlatformClientID(id)`
names the client by its platform id instead and wins (no `clientCode` is written). The client must
exist on the platform and be one the application's service account may reach: an unknown code fails
the whole batch.

Two more options, explicit only:

- `IdempotencyKey(key)` (not blank, at most 100 bytes): the platform creates the first job naming the
  key within the job's application (the code's first segment) and client, and answers a repeat with
  that job's id, for 7 days. It is never taken from `ExternalID`: two jobs that share an external
  reference must not merge silently.
- `ConnectionID(id)`: the connection the job is delivered through. Pausing the connection holds the
  job, and its service account signs the delivery. **Without it** a job with no subscription is signed
  by the **oldest active service account of the application named by the code's first segment** (the
  platform's `internal/server/delivery_creds.go`), so `orders:...` jobs are signed by the `orders`
  application's account; name a connection when the receiver checks another account's secret.

```go
	named := jobPayload(t, cx, usecase.ExternalID("ord_1"), usecase.IdempotencyKey("create-pick:ord_1"), usecase.ConnectionID("con_0ABCDEFGHJKMN"))
```

(from `usecase/dispatch_client_test.go`). The item writes them after `descriptor`, in the platform's
order: `connectionId`, then `idempotencyKey`.

## Typed ids

```go
// OrderID is an order's id, ord_ and a TSID. sqlc.yaml names this alias as the type of the
// orders.id and order_lines.order_id columns.
type OrderID = id.ID[OrderKind]
```

`id.New[K](ids)`, `id.Parse[K]`; `id.ID[OrderKind]` and `id.ID[LineKind]` are different types. IDs
marshal as text, bind and scan through `driver.Valuer` and `sql.Scanner`, and the zero id is never
stored.
