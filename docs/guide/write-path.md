<!-- types: Aggregate, AggregateId, Repository, Tx, UseCase, UseCases, UseCaseResult, UseCaseError, ErrorKind, Check, Authorize, Execute, Decision, Plan, Rejected, ConflictPolicy, DomainEvent, EventMetadata, EventType, Events, AuditData, AuditEntry, Sink, OutboxWriter, OutboxModule, UseCaseModule, Jdbc, Row, RowMapper, Ids, Context, Database, Databases, MigrationSet -->
# The write path

All behaviour that changes data goes through a **use case**. A use case validates, authorizes,
loads, decides, and returns a *plan*; one runner then applies the plan in a single transaction,
together with the domain events and an audit row. Application code never begins, commits or rolls
back a transaction.

The running example is `examples/orders-demo`: an `Order` with `OrderLine`s and three use cases,
`placeOrder`, `addLine` and `cancelOrder`.
[Source](https://github.com/flowcatalyst/framework/tree/main/examples/orders-demo/src).

## The modules

```java
App.builder()
    .module(new DatabaseModule())
    .module(new OutboxModule())     // the outbox table and OutboxWriter
    .module(new UseCaseModule())    // provides Events and UseCases
    .module(new OrdersModule());    // yours: migrations and use cases
```

`UseCaseModule` provides `UseCases` (the runner, on the **default** database instance) and `Events`
(makes event metadata). `OutboxModule` provides `OutboxWriter` and contributes the `outbox_messages`
migration under module id `outbox`. Your module contributes its own migrations and provides its use
cases (see `OrdersModule`):

```java
@Override
public void register(AppBuilder app) {
  MigrationSet.contribute(app, OrdersModule.class, "db/migration/orders");
  app.provide(
      OrderUseCases.class,
      lookup ->
          new OrderUseCases(
              new OrderRepository(),
              lookup.get(Databases.class).defaultInstance(),
              lookup.get(Events.class),
              lookup.get(Ids.class),
              OrderUseCases.Policies.defaults()));
}
```

Use cases run against the default database instance only.

## 1. Aggregates

An aggregate is a record implementing `Aggregate`: `id()` (a typed id), `client()` (the `ClientId`
it belongs to) and `version()` (a `long`; `0` for an aggregate not yet stored). It holds what its
rules need to decide. Methods that change it **return a new value** and write nothing. Child
collections belong to the aggregate: they are saved with it and covered by its version.

```java
public record OrderId(String value) implements AggregateId {}

public record Order(
    OrderId id, ClientId client, long version, String customer,
    OrderStatus status, List<OrderLine> lines)
    implements Aggregate {

  public static Order place(OrderId id, ClientId client, String customer) {
    return new Order(id, client, 0, customer, OrderStatus.OPEN, List.of());
  }

  public Order withLine(OrderLine line) { ... }   // returns a new Order
  public Order cancel() { ... }
}
```

Ids are typed records, usually a prefix and a TSID: `new OrderId(ids.generate("ord"))` gives
`ord_` plus 13 time-ordered characters, monotonic within the application.

## 2. Repositories

You write the repository, with explicit SQL (or [checked SQL](checked-sql.md)). It implements
`Repository<I, A>`:

| Method | Runs in | Contract |
|---|---|---|
| `Optional<A> find(Database db, ClientId client, I id)` | no transaction | filters by `client_id`; returns the aggregate with the stored version |
| `void insert(Tx tx, A a)` | the attempt's transaction | stores `a` with version `1` |
| `int update(Tx tx, A a)` | the attempt's transaction | `UPDATE ... SET version = <a.version() + 1> ... WHERE id = ? AND client_id = ? AND version = <a.version()>`, then writes children; **returns the root update's row count** |
| `int delete(Tx tx, A a)` | the attempt's transaction | `DELETE ... WHERE id = ? AND client_id = ? AND version = ?`; returns the row count |

`tx.connection()` gives the `java.sql.Connection` to write on. The runner calls `insert` when
`version() == 0` and `update` otherwise. This is the whole of the repository from the example:

```java
@Override
public int update(Tx tx, Order order) throws SQLException {
  Connection c = tx.connection();
  int rows =
      OrderSql.updateOrder(
          c, order.version() + 1, order.customer(), order.status(),
          order.id(), order.client(), order.version());
  if (rows == 1) {
    OrderSql.deleteLines(c, order.id());
    writeLines(c, order);
  }
  return rows;
}
```

### Optimistic locking

A row count of `0` from `update` or `delete` means the aggregate is **stale**: someone else
committed a change since it was loaded. The version is on the root row; because the lines are
rewritten together with it, adding a line bumps the order's version, so a concurrent edit of
the header conflicts (this is exercised in `OrdersDemoTest`). A count above `1` is a bug and fails
the attempt as `INTERNAL`.

You need a `version BIGINT NOT NULL` column and the `WHERE ... AND version = ?` guard in every
update; the framework cannot add them for you.

## 3. Use cases

A use case is defined once, with a builder, and run many times (abridged from `OrderUseCases`). `UseCase.named(name, CommandType.class, EventClass.class)`
starts it; the name matches `[a-z][a-z0-9-]*` and appears in logs and the audit row.

```java
UseCase.named("add-line", AddLine.class, OrderLineAdded.class)
    .validate(
        (context, command) ->
            command.quantity() <= 0
                ? Optional.of(UseCaseError.validation("QUANTITY_INVALID", "The quantity must be positive."))
                : Optional.empty())
    .authorize(PRINCIPAL_REQUIRED)
    .conflict(ConflictPolicy.retry(3, Duration.ofMillis(25)))
    .execute(
        (context, command) -> {
          Optional<Order> found = repository.find(database, context.client(), new OrderId(command.orderId()));
          if (found.isEmpty()) {
            return Decision.reject(UseCaseError.notFound("ORDER_NOT_FOUND", "There is no such order."));
          }
          Order updated = found.get().withLine(newLine);
          return Plan.save(updated, repository, new OrderLineAdded(...));
        });
```

| Part | Required | Contract |
|---|---|---|
| name | yes | `[a-z][a-z0-9-]*` |
| `validate(Check)` | no | checks the command's shape without loading data; returns `Optional<UseCaseError>` |
| `authorize(Check)` | **yes** | resource-level access check, same shape. `Authorize.PUBLIC` declares an operation deliberately open. Calling `execute(...)` without one throws `IllegalStateException` when the use case is *defined* |
| `conflict(ConflictPolicy)` | no | `ConflictPolicy.fail()` (the default) or `ConflictPolicy.retry(maxAttempts, backoff)` |
| `execute(Execute)` | yes | loads what it needs (once), applies the rules, returns a `Decision` |

A `Check<C>` is `Optional<UseCaseError> check(Context context, C command) throws Exception`. Each
phase receives the `Context` and the command. **Load once**: `execute` finds each aggregate once;
if it needs fresher state the attempt has failed and runs again from the start.

The authorize step in the example, a `Check<Object>` shared by all three use cases:

```java
private static final Check<Object> PRINCIPAL_REQUIRED =
    (context, command) ->
        context.principal() == null
            ? Optional.of(UseCaseError.authorization("PRINCIPAL_REQUIRED", "Sign in to change orders."))
            : Optional.empty();
```

There is **no built-in permission model** (roles and permissions arrive with login and sessions;
see [STATUS](STATUS.md)). Authorize is your own check of the `Context`.

### Plans

`execute` returns a `Decision`: either `Decision.reject(UseCaseError)` (a `Rejected`, nothing is
written) or a `Plan`:

- `Plan.save(aggregate, repository, event)`: insert (version 0) or update.
- `Plan.delete(aggregate, repository, event)`.
- `Plan.emit(event)`: an event with no aggregate write.
- `.andSave(...)`, `.andDelete(...)`, `.andEmit(event)` add further steps.

Every plan carries at least one event; the **first is the use case's result**. Events are written
in step order. `Plan.save` is generic in the aggregate type, so the repository must match it.

## 4. Running a use case

```java
UseCaseResult<OrderEvents.OrderPlaced> placed =
    useCases.run(orders.placeOrder(), context, new PlaceOrder("Ada Lovelace"));
```

`UseCaseResult<E>` is sealed: `Committed(E event)` or `Failed(UseCaseError error)`. It never throws
for an expected outcome.

```java
return switch (result) {
  case UseCaseResult.Committed<?> ok -> "committed " + ok.event().metadata().type();
  case UseCaseResult.Failed<?> failed -> failed.error().kind() + " " + failed.error().code();
};
```

One attempt:

1. `validate`, then `authorize`; the first error is the result.
2. `execute`; a rejection is the result.
3. Every aggregate in the plan must belong to `context.client()`. A mismatch fails as `INTERNAL` /
   `CROSS_CLIENT_WRITE`, is logged as a bug, and writes nothing.
4. Begin a transaction (READ COMMITTED) on the default database.
5. Apply the plan's writes in order, then write each event to the outbox, then one audit row.
6. Commit.

| Failure | Result |
|---|---|
| stale aggregate (row count 0) | conflict: the use case's policy applies |
| SQLSTATE `40001` or `40P01` (serialization failure, deadlock) | conflict: the policy applies |
| SQLSTATE `23505` (unique violation) | `CONFLICT` / `UNIQUE_VIOLATION`, constraint name in the details, not retried |
| any other exception from a phase, repository or the database | `INTERNAL`, code names the step (`VALIDATE`, `AUTHORIZE`, `EXECUTE`, `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`); the cause is logged, never put in the message |
| interrupted while waiting between attempts | `INTERNAL` / `INTERRUPTED`; the interrupt flag is restored |

Every failed attempt rolls back fully: a failing line insert rolls back the order header and the
outbox rows too (tested in `OrdersDemoTest`).

### Conflict policy

`fail()` returns `CONFLICT` / `STALE_AGGREGATE`. `retry(n, backoff)` runs the **whole use case
again from step 1**, up to `n` attempts in total (2 to 10), waiting `backoff` randomised between
half and one and a half times its value; when attempts run out the result is `CONFLICT` /
`STALE_AGGREGATE`. Choose per use case: adding a line is safe to repeat on fresh state, so it
retries; a cancellation made on a stale view is for the caller to decide again, so it fails.

### Errors

`UseCaseError` is a record: `kind`, `code` (`UPPER_SNAKE`), `message` (safe to show a caller), and
`details` (a string map). Build them with `UseCaseError.validation`, `authorization`, `notFound`,
`conflict`, `businessRule` (each takes a code and a message) and `internal(code)`.

| `ErrorKind` | HTTP status (`httpStatus()`) |
|---|---|
| `VALIDATION` | 400 |
| `AUTHORIZATION` | 403 |
| `NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `BUSINESS_RULE` | 409 |
| `INTERNAL` | 500 |

`HttpErrors.of(UseCaseError)` turns one into the error response ([HTTP](http.md#errors)).

## 5. Events

A domain event is a record implementing `DomainEvent`, with `EventMetadata metadata()` first and
its own data fields. The type is an `EventType(application, domain, aggregate, action)`, each
segment `[a-z][a-z0-9-]*`:

```java
public static final EventType PLACED = new EventType("orders", "sales", "order", "placed");

public record OrderPlaced(EventMetadata metadata, String orderId, String customer)
    implements DomainEvent {}
```

`events.metadata(context, OrderEvents.PLACED, order.id())` (an `Events`, provided by
`UseCaseModule`) fills in:

| Field | Value |
|---|---|
| event id | a TSID |
| spec version | `1.0` |
| type | `orders:sales:order:placed` (`application:domain:aggregate:action`) |
| source | `orders:sales` |
| subject | `sales.order.<id>` (`domain.aggregate.id`) |
| time | from the injected `InstantSource` |
| correlation id, principal id | from the `Context` |
| causation id, execution id | from the `Context` when present |
| message group | `sales:order:<id>` (`domain:aggregate:id`), so one aggregate's events stay in order. `metadata.withMessageGroup(group)` changes it |

The event's **data payload is the record's own fields** as JSON (Jackson 3; records only), without
`metadata`. Keep event records plain: strings, numbers, nested records, lists.

The event type must also exist on the platform for it to be delivered. The framework's
[definition sync](flowcatalyst-module.md#declaring-definitions) can declare it.

## 6. The outbox

Events and audit rows are written to `outbox_messages` in the **same transaction** as the
aggregate. The table and the payloads are those of the FlowCatalyst Java SDK, so the platform's
outbox processor reads them unchanged:

- an event row has type `EVENT`, the event's message group, the SDK's `CreateEventDto` JSON, status
  `0`, and `client_id` set. `deduplicationId` is the event id; `clientCode` is the `ClientId`
  value, which must therefore be the platform's **client code** (the identifier such as `acme`),
  not the `clt_...` id ([Context](context.md#what-goes-in-a-clientid)); the principal and execution id travel as `contextData` entries;
- the audit row has type `AUDIT_LOG` and the SDK's `CreateAuditLogDto` JSON: entity type and id
  from the first aggregate (or the event subject), `operation` = the use case name, the principal,
  time, correlation id and client;
- a command's data goes into the audit row **only if the command implements `AuditData`**
  (`Map<String, Object> auditData()`). Otherwise just the fact of the operation is recorded. Take
  care not to put secrets in `auditData()`.

```java
public record PlaceOrder(String customer) implements AuditData {
  @Override
  public Map<String, Object> auditData() {
    return Map.of("customer", customer);
  }
}
```

> **The framework writes the outbox; it does not deliver it.** Delivery is the platform's outbox
> processor, pointed at your database. Without a platform nothing consumes the rows. And the writer
> handles **events and audit rows only**: there are no dispatch-job rows yet (plan item MA-4), and
> no direct framework call that creates a dispatch job either; see
> [Creating a dispatch job](flowcatalyst-module.md#creating-a-dispatch-job). The platform delivers
> the rows: see [How the outbox gets delivered](flowcatalyst-module.md#how-the-outbox-gets-delivered),
> [The FlowCatalyst module](flowcatalyst-module.md) and [STATUS](STATUS.md).

An application that already ran the SDK's outbox migration by other means has the table and must
not also list `OutboxModule`'s migration; the first migration file's content (and checksum) is the
SDK's, and a second migration widens `client_id` to `VARCHAR(100)`.

## 7. Reading

Screens and lists do not load aggregates. They use plain typed queries on the database:

```java
public static List<Summary> summaries(Database db, ClientId client) throws SQLException {
  try (Connection c = db.dataSource().getConnection()) {
    return OrderSql.summaries(c, client).stream().map(r -> new Summary(...)).toList();
  }
}
```

Write those queries as [checked SQL](checked-sql.md). For dynamic queries (optional filters, a
variable `IN` list) there is a small JDBC helper in `io.flowcatalyst.framework.usecase.jdbc`:
`Jdbc.query(dataSource, sql, mapper, params...)`, `Jdbc.queryOne(...)`, `Jdbc.update(...)`, a `Row`
with typed column getters (`string`, `integer`, `longValue`, `instant`, `optionalString`, ...) and
`Jdbc.nullOf(sqlType)` for a null parameter. Always filter by `client_id`.

**Parameter types `Jdbc` can bind** (anything else throws `IllegalArgumentException` at run time
naming the parameter position and the class, for example `parameter 2 has unsupported type
java.sql.Timestamp`):

| You pass | Bound as |
|---|---|
| `String` | text |
| `Integer`, `Long`, `Short`, `Double`, `Boolean` | the matching SQL type |
| `BigDecimal` | numeric |
| `Instant` | `timestamptz`, as a UTC `OffsetDateTime` |
| `UUID` | `uuid` |
| `byte[]` | `bytea` |
| any `Enum` | its `name()` as text |
| `AggregateId`, `ClientId`, `PrincipalId`, `CorrelationId` | their `value()` as text |
| `Jdbc.nullOf(Types.X)` | a typed SQL `NULL` |

**Rejected:** a bare `null` (a `NullPointerException`; write `Jdbc.nullOf(Types.VARCHAR)`), `float`/`Float`,
`byte`/`Byte`, `char`, and every date and time type except `Instant`: `java.sql.Timestamp`,
`java.sql.Date`, `java.util.Date`, `LocalDateTime`, `LocalDate`, `OffsetDateTime`, `ZonedDateTime`.
The helper has exactly one way to say "a moment in time" (`Instant`, bound at UTC); the other
types either carry no zone (`LocalDateTime`, `LocalDate`) or the JVM's default one
(`java.sql.Timestamp`), so the helper does not guess. Convert instead: `timestamp.toInstant()`, `localDateTime.toInstant(ZoneOffset.UTC)` (choose the
zone yourself), `offsetDateTime.toInstant()`, `zonedDateTime.toInstant()`; a `LocalDate` is
passed as its ISO text with an explicit cast in the SQL (`localDate.toString()` bound to `?::date`),
or the query is written as [checked SQL](checked-sql.md). Reading is the matching set on `Row`: `instant`, `optionalInstant`,
`uuid`, `bytes`, `decimal` and the string and number getters.

## 8. What to watch for

- **Client scoping is yours to write into the SQL.** The runner refuses a cross-client plan, but a
  query you write that forgets `client_id` leaks. The repository contract requires it.
- **Use cases and migrations use the default database instance.** You may configure several named
  instances (`Databases.get(name)`, each with its own pool) and query another one yourself with
  `Jdbc`; only the runner, the outbox and the migrations are tied to the default.
- **Idempotence of retries.** A retried use case re-runs `validate`, `authorize` and `execute`
  from scratch; do not do side effects (HTTP calls, emails) inside them.
- **Tests** run use cases against a real PostgreSQL; see [Testing](testing.md).

Specification: [contracts/write-path.md](../contracts/write-path.md).
