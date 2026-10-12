<!-- types: UseCase, UseCases, UseCaseName, Plan, Fresh, Loaded, Write, Aggregate, Repository, Version, VersionCheck, Valid, Authorized, Grant, Committed, ConflictPolicy, UseCaseError, ErrorCode, ErrorKind, DomainEvent, EventType, EventMetadata, Events, AuditData, AuditEntry, OutboxWriter, OutboxModule, Sink, DispatchJob, DispatchJobBuilder -->
# Rust: the write path

`fw-usecase` is the Rust counterpart of `framework-usecase` ([contract](../../contracts/write-path.md),
Java guide [The write path](../write-path.md)): use cases, the runner that applies a plan in one
transaction with optimistic locking and client scoping, and the outbox rows (events, audit rows,
dispatch jobs) in the FlowCatalyst SDK's shape. The full example is `rust/examples/orders-demo`,
the Java demo on the Rust framework ([the design's walk-through](../../rust/orders-showcase.md)).

## Aggregates: `Fresh` and `Loaded`

The aggregate holds what its rules need; the client and version are the framework's. A new
aggregate is `Fresh::new(ctx, value)` (the context's client, inserted with version 1); one a
repository found is `Loaded` (the row's client and version). `Plan::save` inserts a `Fresh` and
updates a `Loaded`: the type decides, not `version == 0`. The orders demo models the states as
types, so lines can only be added to an open order:

```rust
let (order, line_count) = order.try_map(|o| match o {
    Order::Open(open) => {
        let open = open.with_line(line.clone());
        let count = open.lines().len();
        Ok((Order::Open(open), count))
    }
    Order::Cancelled(_) => Err(not_open()),        // the match must handle it
})?;
```

## Use cases

A use case is a trait implementation. `authorize` has no default, so a use case without an
authorisation step does not compile; `execute` receives an `Authorized` command, which only the
runner can make; a success is a `Committed` event, which only the runner can make.

```rust
impl UseCase for PlaceOrderUseCase {
    const NAME: UseCaseName = UseCaseName::of("place-order");     // checked when the crate compiles
    type Command = PlaceOrder;
    type Event = OrderPlaced;

    async fn authorize(&self, ctx: &Context, _cmd: Valid<'_, PlaceOrder>) -> Result<Grant, UseCaseError> {
        principal_required(ctx)
    }

    async fn execute<'a>(&'a self, ctx: &'a Context, cmd: Authorized<'a, PlaceOrder>, _db: &'a Db) -> Result<Plan<'a, OrderPlaced>, UseCaseError> {
        let id: OrderId = self.ids.next();
        let order = Fresh::new(ctx, Order::Open(OpenOrder::place(id.clone(), cmd.customer.clone())));
        let event = OrderPlaced { meta: self.events.metadata::<OrderPlaced>(ctx, id.as_str()), order_id: id, customer: cmd.customer.clone() };
        Ok(Plan::save(order, &self.repo, event))
    }

    fn audit_data(&self, cmd: &PlaceOrder) -> Option<AuditData> {
        Some(AuditData::new().with("customer", cmd.customer.as_str()))
    }
}
```

Run it with `use_cases.run(&uc, &ctx, command).await`: `Ok(Committed<Event>)` or
`Err(UseCaseError)`. `UseCases::build(boot, &databases)` builds the runner on the default database
instance; `OutboxModule` contributes the outbox migrations.

Commands hold domain values parsed at the edge (`PlaceOrder::parse`, `AddLine::parse` in the demo),
so `validate` (optional) has little left to check. Plans: `Plan::save`, `Plan::delete`,
`Plan::emit`, then `and_save`, `and_delete`, `and_emit` (each with its event) and `and_dispatch`.

## What the runner does

One attempt: validate, authorize, execute, the client check (a planned write for another client's
aggregate is `INTERNAL`/`CROSS_CLIENT_WRITE`), then in one `READ COMMITTED` transaction on the default
instance: the writes in plan order, the events in step order, the dispatch jobs, one audit row,
commit. The failure table is the contract's:

| Failure | Result |
|---|---|
| a phase's own rejection | that error |
| row count 0 from `update`/`delete`, SQLSTATE `40001` or `40P01` | conflict: the policy (`Fail` gives `CONFLICT`/`STALE_AGGREGATE`; `ConflictPolicy::retry(n, backoff)` runs again from the start, 2 to 10 attempts, waiting `backoff` times 0.5 to 1.5) |
| `23505` | `CONFLICT`/`UNIQUE_VIOLATION`, details `constraint`, not retried |
| anything else, or a panic | `INTERNAL` with the step's code: `VALIDATE`, `AUTHORIZE`, `EXECUTE`, `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`; the cause is logged, never put in the message |

A phase meeting an unexpected failure returns `UseCaseError::failure(e)` (or uses `?` on a
`sqlx::Error`); the runner turns it into the row above. Every failed attempt rolls back. The
runner records `fw_usecase_runs_total{usecase,outcome}` and `fw_usecase_duration_seconds{usecase}`.

## Events and the outbox

An event is a struct with `#[derive(Serialize)]`, its metadata `#[serde(skip)]`, and a
const-checked type:

```rust
impl DomainEvent for OrderPlaced {
    const TYPE: EventType = EventType::of("orders:sales:order:placed");
    fn metadata(&self) -> &EventMetadata { &self.meta }
}
```

`Events::metadata::<E>(ctx, subject_id)` fills the id, time, source, subject, message group and the
context's ids. The rows are the Java writer's **byte for byte**: the vectors in
`conformance/outbox/` are written by the Java `OutboxWriter` and the Rust tests compare every
column, the payload text and its size. Null fields are left out of the event data (the Java
`NON_NULL` inclusion), so an `Option` field needs no attribute.

`DispatchJob::builder(code, target_url)` builds a dispatch job with the Java builder's rules and
messages (also in the vectors); `build()` returns `Err` for a job the platform would refuse.

## Where Rust differs from Java

- `Fresh`/`Loaded` instead of `client()` and `version()` on the aggregate.
- `INTERRUPTED` does not occur: a caller that drops the `run` future cancels the attempt and the
  transaction rolls back.
- The audit entity type is the aggregate type's name without its module path (Java: the simple class
  name); `Aggregate::entity_type` can override it.
- Load-once is not checked (the design's debug-build count is an open ruling).
