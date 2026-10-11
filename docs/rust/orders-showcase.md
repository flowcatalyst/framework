# Rust framework: the orders example, end to end (draft 1, 2026-10-11)

Companion to [`design.md`](design.md) and [`capabilities.md`](capabilities.md). The Java
`examples/orders-demo` written for the Rust design: the same table, the same three use cases
(`place-order`, `add-line`, `cancel-order`), the same events (`orders:sales:order:placed`,
`line-added`, `cancelled`) and so the same outbox rows. It adds what the Java demo leaves to a later
milestone: a permission, an HTTP handler and the wiring. The point is section 6: what the compiler
now guarantees that Java checks at run time, if at all.

The code is a sketch of the intended API, not compiled. It uses the Java demo's migration
(`V0001__create_orders.sql`) unchanged: a Rust build of this service could run against the Java
demo's database (design decision 8).

## 1. Values and ids

```rust
use fw_core::{ClientId, Id, IdKind, IdPrefix};

impl IdKind for Order { const PREFIX: IdPrefix = IdPrefix::of("ord"); }
impl IdKind for OrderLine { const PREFIX: IdPrefix = IdPrefix::of("lin"); }
pub type OrderId = Id<Order>;          // "ord_" + 13 Crockford characters; Id<Order> is not Id<OrderLine>
pub type LineId = Id<OrderLine>;

/// Trimmed, not blank, at most 100 characters. The only constructor is `parse`.
#[derive(Clone, Debug, Serialize, sqlx::Type)]
#[sqlx(transparent)]
pub struct CustomerName(String);

impl CustomerName {
    pub fn parse(raw: &str) -> Result<Self, FieldError> {
        let s = raw.trim();
        if s.is_empty() {
            return Err(FieldError::new(CUSTOMER_REQUIRED, "A customer is required."));
        }
        if s.chars().count() > 100 {
            return Err(FieldError::new(CUSTOMER_TOO_LONG, "At most 100 characters."));
        }
        Ok(Self(s.to_owned()))
    }
}

/// 1 to 20 characters (the table's CHECK is the last line of defence, as in Java).
pub struct Sku(String);                 // parse(..) as above
/// At least 1.
pub struct Quantity(NonZeroU32);        // TryFrom<i64>
/// Not negative, in cents.
pub struct Cents(u64);                  // TryFrom<i64>
pub struct CancelReason(String);        // parse: not blank

pub const CUSTOMER_REQUIRED: ErrorCode = ErrorCode::of("CUSTOMER_REQUIRED");   // const-checked UPPER_SNAKE
pub const CUSTOMER_TOO_LONG: ErrorCode = ErrorCode::of("CUSTOMER_TOO_LONG");
pub const ORDER_NOT_FOUND: ErrorCode = ErrorCode::of("ORDER_NOT_FOUND");
pub const ORDER_NOT_OPEN: ErrorCode = ErrorCode::of("ORDER_NOT_OPEN");

pub const ORDERS_WRITE: Permission = Permission::of("orders:orders:write");    // const-checked, app segment first
```

A misspelt code such as `ErrorCode::of("order-not-found")` in a `const` is a compile error, because
the `const fn` panics during constant evaluation.

## 2. The aggregate: one type per state

Java's `Order` is one record with a `status` field and an `isOpen()` method each use case must
remember to call. Here the states are separate types and the aggregate is an enum over them, so the
operations that only make sense on an open order exist only on `OpenOrder`.

```rust
pub enum Order {
    Open(OpenOrder),
    Cancelled(CancelledOrder),
}

pub struct OpenOrder { id: OrderId, customer: CustomerName, lines: Vec<OrderLine> }
pub struct CancelledOrder { id: OrderId, customer: CustomerName, lines: Vec<OrderLine> }
pub struct OrderLine { id: LineId, sku: Sku, quantity: Quantity, unit_price: Cents }

impl Aggregate for Order {
    type Id = OrderId;
    fn id(&self) -> &OrderId {
        match self {
            Order::Open(o) => &o.id,
            Order::Cancelled(c) => &c.id,
        }
    }
}

impl OpenOrder {
    pub fn place(id: OrderId, customer: CustomerName) -> Self {
        Self { id, customer, lines: Vec::new() }
    }
    pub fn with_line(mut self, line: OrderLine) -> Self {
        self.lines.push(line);
        self
    }
    pub fn cancel(self) -> CancelledOrder {
        CancelledOrder { id: self.id, customer: self.customer, lines: self.lines }
    }
    pub fn lines(&self) -> &[OrderLine] { &self.lines }
}
// CancelledOrder has no with_line and no cancel.
```

The aggregate has no `client` and no `version`: those are the framework's (`Fresh<Order>`,
`Loaded<Order>`), so the domain type knows nothing about storage. Methods consume `self` and return
the new value, the Rust form of Java's "methods return a new value".

## 3. Events

```rust
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderLineAdded {
    #[serde(skip)]
    meta: EventMetadata,
    order_id: OrderId,
    line_id: LineId,
    sku: Sku,
    quantity: Quantity,
    unit_price_cents: Cents,
    line_count: u32,
}

impl DomainEvent for OrderLineAdded {
    const TYPE: EventType = EventType::of("orders:sales:order:line-added");   // const-checked shape
    fn metadata(&self) -> &EventMetadata { &self.meta }
}
// OrderPlaced { order_id, customer }, OrderCancelled { order_id, reason, line_count } likewise.
```

The JSON data is the struct's fields, the same keys as the Java records' components, so the
`CreateEventDto` payload in the outbox row is the same (checked by the conformance vectors).

## 4. Commands, parsed at the edge

A command holds domain values, so a command that exists is already valid. Java's `validate` phase
re-checks strings in each use case; here it is mostly empty, and the parsing happens once, where the
input arrives.

```rust
pub struct PlaceOrder { pub customer: CustomerName }
pub struct AddLine { pub order: OrderId, pub sku: Sku, pub quantity: Quantity, pub unit_price: Cents }
pub struct CancelOrder { pub order: OrderId, pub reason: CancelReason }

/// The HTTP body. Unknown fields are refused by the framework's Json extractor.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddLineBody { sku: String, quantity: i64, unit_price_cents: i64 }

impl AddLineBody {
    /// Every field error at once, as Java's Validator collects them: 400 VALIDATION with details.
    pub fn into_command(self, order: OrderId) -> Result<AddLine, Violations> {
        let (sku, quantity, unit_price) = fields((
            ("sku", Sku::parse(&self.sku)),
            ("quantity", Quantity::try_from(self.quantity)),
            ("unitPriceCents", Cents::try_from(self.unit_price_cents)),
        ))?;
        Ok(AddLine { order, sku, quantity, unit_price })
    }
}
```

`fields` is a framework function over tuples: it returns every value when every field parsed, and
otherwise a `Violations` with one entry per failed field (`"<code>: <message>"`).

## 5. Repository, use cases, handler, wiring

### Repository (checked SQL)

```rust
pub struct OrderRepository;

impl Repository<Order> for OrderRepository {
    async fn find(&self, db: &Db, client: &ClientId, id: &OrderId) -> Result<Option<Loaded<Order>>, DbError> {
        let Some(row) = sqlx::query!(
            r#"SELECT client_id AS "client_id: ClientId", version, customer AS "customer: CustomerName",
                      status AS "status: OrderStatus"
                 FROM orders
                WHERE id = $1 AND client_id = $2"#,
            id as &OrderId,
            client as &ClientId,
        )
        .fetch_optional(db.pool())
        .await?
        else {
            return Ok(None);
        };
        let lines = sqlx::query_as!(
            OrderLine,
            r#"SELECT line_id AS "id: LineId", sku AS "sku: Sku", quantity AS "quantity: Quantity",
                      unit_price_cents AS "unit_price: Cents"
                 FROM order_lines WHERE order_id = $1 ORDER BY position"#,
            id as &OrderId,
        )
        .fetch_all(db.pool())
        .await?;
        let order = match row.status {
            OrderStatus::Open => Order::Open(OpenOrder { id: id.clone(), customer: row.customer, lines }),
            OrderStatus::Cancelled => Order::Cancelled(CancelledOrder { id: id.clone(), customer: row.customer, lines }),
        };
        Ok(Some(Loaded::new(row.client_id, Version::stored(row.version)?, order)))
    }

    async fn update(&self, tx: &mut Tx<'_>, client: &ClientId, order: &Order, v: VersionCheck) -> Result<u64, DbError> {
        let changed = sqlx::query!(
            "UPDATE orders SET status = $1, version = $2
              WHERE id = $3 AND client_id = $4 AND version = $5",
            OrderStatus::of(order) as OrderStatus,
            v.next.get(),
            order.id() as &OrderId,
            client as &ClientId,
            v.expected.get(),
        )
        .execute(tx.conn())
        .await?
        .rows_affected();
        if changed == 1 {
            rewrite_lines(tx, order).await?;     // DELETE, then one INSERT ... SELECT FROM UNNEST(...)
        }
        Ok(changed)
    }
    // insert and delete likewise
}
```

Every query is checked against the migrated schema when the crate compiles (from `.sqlx/`, no
database needed). A renamed column, a wrong type or a nullable column read into a non-`Option` field
fails the build. `client_id` comes back from the row so the runner can confirm the repository really
filtered by client.

### Use cases

```rust
pub struct AddLineUseCase { repo: OrderRepository, ids: Ids, events: Events, policy: ConflictPolicy }

impl UseCase for AddLineUseCase {
    const NAME: UseCaseName = UseCaseName::of("add-line");
    type Command = AddLine;
    type Event = OrderLineAdded;

    fn conflict_policy(&self) -> ConflictPolicy {
        self.policy                                   // default for add-line: retry 3 times, 25 ms
    }

    async fn authorize(&self, ctx: &Context, _cmd: &Valid<AddLine>) -> Result<Grant, UseCaseError> {
        ctx.require(&ORDERS_WRITE)                    // AUTHORIZATION when the principal lacks it
    }

    async fn execute<'a>(&'a self, ctx: &'a Context, cmd: Authorized<'a, AddLine>, db: &'a Db)
        -> Result<Plan<'a, OrderLineAdded>, UseCaseError>
    {
        let order = self
            .repo
            .find(db, ctx.client(), &cmd.order)       // load once
            .await?
            .ok_or_else(|| UseCaseError::not_found(ORDER_NOT_FOUND, "No such order."))?;
        let line = OrderLine::new(self.ids.next::<OrderLine>(), cmd.sku.clone(), cmd.quantity, cmd.unit_price);
        let (order, line_count) = order.try_map(|o| match o {
            Order::Open(open) => {
                let open = open.with_line(line.clone());
                let count = open.lines().len();
                Ok((Order::Open(open), count))
            }
            Order::Cancelled(_) => Err(UseCaseError::business_rule(ORDER_NOT_OPEN, "The order is not open.")),
        })?;
        let event = OrderLineAdded {
            meta: self.events.metadata::<OrderLineAdded>(ctx, &cmd.order),
            order_id: cmd.order.clone(),
            line_id: line.id().clone(),
            sku: line.sku().clone(),
            quantity: line.quantity(),
            unit_price_cents: line.unit_price(),
            line_count: u32::try_from(line_count).map_err(UseCaseError::internal)?,
        };
        Ok(Plan::save(order, &self.repo, event))      // Loaded<Order>: an UPDATE ... AND version = $n
    }
}

pub struct PlaceOrderUseCase { repo: OrderRepository, ids: Ids, events: Events }

impl UseCase for PlaceOrderUseCase {
    const NAME: UseCaseName = UseCaseName::of("place-order");
    type Command = PlaceOrder;
    type Event = OrderPlaced;

    async fn authorize(&self, ctx: &Context, _cmd: &Valid<PlaceOrder>) -> Result<Grant, UseCaseError> {
        ctx.require(&ORDERS_WRITE)
    }

    async fn execute<'a>(&'a self, ctx: &'a Context, cmd: Authorized<'a, PlaceOrder>, _db: &'a Db)
        -> Result<Plan<'a, OrderPlaced>, UseCaseError>
    {
        let id = self.ids.next::<Order>();
        let order = ctx.fresh(Order::Open(OpenOrder::place(id.clone(), cmd.customer.clone())));  // the context's client
        let event = OrderPlaced { meta: self.events.metadata::<OrderPlaced>(ctx, &id), order_id: id, customer: cmd.customer.clone() };
        Ok(Plan::save(order, &self.repo, event))      // Fresh<Order>: an INSERT, version 1
    }

    fn audit_data(&self, cmd: &PlaceOrder) -> Option<BTreeMap<String, String>> {
        Some(BTreeMap::from([("customer".into(), cmd.customer.to_string())]))
    }
}
```

`cancel-order` matches the same way: `Order::Open(open) => Order::Cancelled(open.cancel())`, and
`Order::Cancelled(_)` is the `ORDER_NOT_OPEN` rejection. Its conflict policy is `Fail`, as in Java.

### Handler and wiring

```rust
const ORDER_LINES: RoutePath = RoutePath::of("/orders/{id}/lines");

async fn add_line(
    State(app): State<AppState>,
    Ctx(ctx): Ctx,                         // compiles only because AppState provides a ContextResolver
    Path(order): Path<OrderId>,            // a malformed id is 400 VALIDATION, never reaches the use case
    Json(body): Json<AddLineBody>,         // 415, INVALID_JSON with a path, unknown fields refused
) -> Result<Json<LineAddedView>, HttpError> {
    let cmd = body.into_command(order)?;                                   // Violations -> 400
    let done = app.use_cases.run(&app.orders.add_line, &ctx, cmd).await?;  // UseCaseError -> envelope
    Ok(Json(LineAddedView::from(done.into_inner())))
}

#[derive(Clone, FromRef)]
struct AppState {
    use_cases: Arc<UseCases>,
    orders: Arc<OrderUseCaseSet>,
    resolver: Arc<dyn ContextResolver>,
}
```

The `main` function and the `build` closure are the ones in [`design.md`](design.md) section 1.1,
with `OrdersModule` declaring the migration set `orders` and the event types for definition sync.

## 6. What the compiler guarantees that Java checks at run time

| Guarantee | Java today | Rust design |
|---|---|---|
| every use case has an authorisation step | `IllegalStateException` when the use case is defined (start-up) | `authorize` has no default: missing it does not compile |
| business logic never runs on an unvalidated or unauthorised command | convention: callers use `UseCases.run` | `execute` takes `Authorized<C>`, which only the runner can make |
| a success can only come from a commit | `Committed` record, constructible by anyone | `Committed<E>` is sealed (as the Rust platform's is) |
| lines are only added to an open order | each use case calls `isOpen()` | `with_line` exists only on `OpenOrder`; the `match` on `Order` must handle `Cancelled` (and the lint forbids `_ =>`) |
| a new aggregate is inserted, a loaded one updated | runner tests `version() == 0` | `Fresh` versus `Loaded` |
| a new aggregate belongs to the caller's client | runner check `CROSS_CLIENT_WRITE` | `ctx.fresh` takes the context's client; there is no other constructor |
| an order id is not a line id, a client id or a string | `OrderId` record yes; line ids are `String` | `Id<Order>`, `Id<OrderLine>`, `ClientId` are distinct types |
| a quantity is positive, a SKU is at most 20 characters, a customer is not blank | `validate` in each use case, at run time | the command's field types cannot hold a bad value |
| event types, error codes, permission codes, use-case names, route patterns are well formed | checked when constructed (run time or start-up) | checked in `const` evaluation: compile errors |
| a route that needs a `Context` has a resolver | start-up failure | does not compile |
| the SQL matches the schema | the `check` goal in `mvn verify` | `cargo check` (and the editor), from `.sqlx/` |
| a secret is not logged or serialised | redacting `toString`, not serialisable (fails at run time in Jackson) | no `Display`, no `Serialize`: does not compile |
| no data race on shared state | review | `Send` and `Sync` |
| an outcome added to a result type is handled everywhere | sealed types and exhaustive `switch` | exhaustive enums and the `wildcard_enum_match_arm` lint: parity, not a gain |
| nullness | NullAway at build time | `Option`: parity |

## 7. What stays a run-time check

- **Optimistic conflicts** (`STALE_AGGREGATE`), unique violations, serialisation failures: facts of
  the database at commit time.
- **A repository that loads another client's row.** `Loaded::new` takes the row's `client_id`, and
  the runner compares it with the context (`CROSS_CLIENT_WRITE`). A repository whose SQL forgot
  `client_id = $2` is caught only when it happens; the repository contract tests (one call with a
  row of another client) catch it earlier.
- **A repository whose `update` ignores the version.** Caught by the repository contract tests
  (`fw_usecase::testing`), not by types.
- **Load once.** A convention, checked in debug and test builds by counting `find` calls
  (capabilities section 3).
- **The application segment of an event type equals `flowcatalyst.application`.** Configuration,
  checked at start-up.
- **Configuration values.** Checked at start-up, all problems at once.

## 8. Considered and not chosen

- **Brand lifetimes for client scoping.** Tying every loaded aggregate to its context with an
  invariant lifetime (`Scope<'brand>`, the "generativity" pattern) would make the cross-client check
  a compile error for loaded aggregates too. It does not survive contact with `async` trait methods,
  `Send` futures and spawned tasks without heavy annotation, and the error messages are poor. The
  runtime check on `Loaded` plus `Fresh` by construction covers the same risk.
- **A generic `Order<S>` with a state type parameter** (`Order<Open>`, `Order<Cancelled>`). Equivalent
  to the enum for operations, but a loaded order's state is only known at run time, so the
  repository would still return an enum; the enum alone is simpler.
- **Load-once in the type system** (a loader consumed by each find). It would forbid legitimate code
  that loads two different aggregates, or needs the same handle twice in one branch, for little
  gain over the debug-build count.
- **Validation as a typestate the use case writes** (`fn validate(cmd) -> Valid<Cmd>`). If the use
  case can build `Valid`, nothing is proven. Parsing commands into domain types at the edge gives the
  guarantee; the runner's `Valid` only orders the phases.

## 9. Open points this example exposes

- `ctx.require(&ORDERS_WRITE)` needs the principal's permissions in `Context`, which the Java
  contract does not have yet (M4c). Until then the example checks only that a principal is present,
  as the Java demo does.
- A use case run by a dispatch receiver has no principal (the context is built from the client
  header). How such a call is authorised (a system grant tied to the receiver, or a permission of
  the application's service account) is a ruling for both implementations.
