<!-- types: UseCase, Defined, Runner, Plan, Step, Fresh, Loaded, Repository, VersionCheck, Aggregate, Entity, Valid, Authorized, Grant, Committed, ConflictPolicy, Error, Kind, Name, ErrorCode, DispatchJob, JobOption, AuditData, AuditField, AuditEntry, OutboxWriter, Declared, Type, Metadata, Event, Factory, ID, Harness -->
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

`Define` registers it with a conflict policy (`usecase.Fail()`, the default, or
`usecase.Retry(n, backoff)`, 2 to 10 attempts with jittered waits); `Run(ctx, cx, cmd)` returns
`(Committed[E], error)`, the error always a `*usecase.Error` (kind, code, message, details; the
kinds map to 400, 403, 404, 409, 409 and 500).

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
transaction: the plan's writes in order, the events, the dispatch jobs, one audit row, commit. The
failure table of the contract, every row tested:

| Failure | Result |
|---|---|
| stale aggregate (row count 0), SQLSTATE `40001` or `40P01` | conflict: the policy, then `CONFLICT` / `STALE_AGGREGATE` |
| `23505` | `CONFLICT` / `UNIQUE_VIOLATION` with the constraint, not retried |
| a `Loaded` of another client | `INTERNAL` / `CROSS_CLIENT_WRITE`, not retried |
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

## Typed ids

```go
// OrderID is an order's id, ord_ and a TSID. sqlc.yaml names this alias as the type of the
// orders.id and order_lines.order_id columns.
type OrderID = id.ID[OrderKind]
```

`id.New[K](ids)`, `id.Parse[K]`; `id.ID[OrderKind]` and `id.ID[LineKind]` are different types. IDs
marshal as text, bind and scan through `driver.Valuer` and `sql.Scanner`, and the zero id is never
stored.
