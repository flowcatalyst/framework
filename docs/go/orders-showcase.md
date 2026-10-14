# Go framework: the orders example, end to end (draft 1, 2026-10-13)

Companion to [`design.md`](design.md) and [`capabilities.md`](capabilities.md). The Java
`examples/orders-demo` written for the Go design: the same tables (the Java demo's
`V0001__create_orders.sql`, unchanged), the same three use cases (`place-order`, `add-line`,
`cancel-order`), the same events (`orders:sales:order:placed`, `line-added`, `cancelled`) and so the
same outbox rows. Like the Rust showcase it adds a permission, HTTP handlers and the wiring; it also
adds a dynamic list query written both ways (go-jet and `Filter`), a dispatch receiver that runs
a use case with the service principal, and two Nuxt UI screens on a client generated from the
service's OpenAPI document (section 7a). Section 8 is the point: what Go checks, and where.

The code is a sketch of the intended API, not compiled. A Go build of this service runs against the
Java demo's database (design decision 10).

## 1. Values and ids

```go
package domain

// Typed ids: one generic type, a kind per aggregate. id.ID[OrderKind] is not id.ID[LineKind].
type OrderKind struct{}
func (OrderKind) Prefix() string { return "ord" }
type LineKind struct{}
func (LineKind) Prefix() string { return "lin" }

type OrderID = id.ID[OrderKind]   // "ord_" + 13 Crockford characters; the alias is what sqlc.yaml names
type LineID = id.ID[LineKind]

// CustomerName: trimmed, not blank, at most 100 characters. The only constructor is ParseCustomerName,
// so a CustomerName that exists is valid. Its zero value is not: IsZero reports it, and the
// repository refuses to store one.
type CustomerName struct{ v string }

func ParseCustomerName(raw string) (CustomerName, error) {
	s := strings.TrimSpace(raw)
	switch {
	case s == "":
		return CustomerName{}, validate.Problem(CustomerRequired, "A customer is required.")
	case utf8.RuneCountInString(s) > 100:
		return CustomerName{}, validate.Problem(CustomerTooLong, "At most 100 characters.")
	}
	return CustomerName{v: s}, nil
}
func (c CustomerName) String() string { return c.v }
func (c CustomerName) IsZero() bool  { return c.v == "" }

// SKU (1 to 20 characters), Quantity (at least 1), Cents (not negative), CancelReason (not blank)
// are built the same way. The table's CHECKs stay the last line of defence, as in Java.

var (
	CustomerRequired = usecase.MustErrorCode("CUSTOMER_REQUIRED") // UPPER_SNAKE: checked by fwvet, and at init
	CustomerTooLong  = usecase.MustErrorCode("CUSTOMER_TOO_LONG")
	OrderNotFound    = usecase.MustErrorCode("ORDER_NOT_FOUND")
	OrderNotOpen     = usecase.MustErrorCode("ORDER_NOT_OPEN")

	OrdersWrite = fw.MustPermission("orders:orders:write")       // application segment first
)
```

A misspelt literal such as `usecase.MustErrorCode("order-not-found")` is reported by `fwvet` in the
editor and in CI, and panics when the package initialises, so every test of the package fails. It
is not a compile error (Rust makes it one).

## 2. The aggregate: one type per state, as a sealed interface

Java's `Order` is one record with a status that each use case must remember to check. Go has no sum
types; the plumbing rule 3a form is an interface with an unexported marker method, and
`gochecksumtype` makes every type switch over it exhaustive.

```go
//sumtype:decl
type Order interface {
	ID() OrderID
	isOrder()
}

type OpenOrder struct {
	id       OrderID
	customer CustomerName
	lines    []OrderLine
}
type CancelledOrder struct {
	id       OrderID
	customer CustomerName
	lines    []OrderLine
	reason   CancelReason
}

func (o OpenOrder) ID() OrderID      { return o.id }
func (OpenOrder) isOrder()            {}
func (o CancelledOrder) ID() OrderID { return o.id }
func (CancelledOrder) isOrder()       {}

func PlaceOrder(id OrderID, customer CustomerName) OpenOrder {
	return OpenOrder{id: id, customer: customer}
}

// WithLine returns a new value; it exists only on OpenOrder.
func (o OpenOrder) WithLine(l OrderLine) OpenOrder {
	o.lines = append(slices.Clip(o.lines), l)   // never shares the backing array with the old value
	return o
}
func (o OpenOrder) Cancel(reason CancelReason) CancelledOrder {
	return CancelledOrder{id: o.id, customer: o.customer, lines: o.lines, reason: reason}
}
func (o OpenOrder) Lines() []OrderLine { return slices.Clone(o.lines) }
```

The aggregate has no client and no version; those are `usecase.Fresh`/`usecase.Loaded`. Values are
structs with unexported fields, so other packages build them only through these functions.

## 3. Events

```go
var (
	Placed    = event.MustType("orders:sales:order:placed")      // {app}:{domain}:{aggregate}:{action}
	LineAdded = event.MustType("orders:sales:order:line-added")
	Cancelled = event.MustType("orders:sales:order:cancelled")
)

type OrderLineAdded struct {
	Meta           event.Metadata `json:"-"`
	OrderID        OrderID        `json:"orderId"`
	LineID         LineID         `json:"lineId"`
	SKU            SKU            `json:"sku"`
	Quantity       Quantity       `json:"quantity"`
	UnitPriceCents Cents          `json:"unitPriceCents"`
	LineCount      int            `json:"lineCount"`
}

func (e OrderLineAdded) Metadata() event.Metadata { return e.Meta }
// OrderPlaced{orderId, customer}, OrderCancelled{orderId, reason, lineCount} likewise.
```

The JSON keys are the Java records' component names, so the `CreateEventDto` payload in the outbox
row is the same; the conformance test compares it byte for byte with `conformance/outbox/outbox.json`.
The value types marshal as their text (`MarshalText`), which `encoding/json/v2` uses.

## 4. Commands, parsed at the edge

```go
type AddLine struct {
	Order     OrderID
	SKU       SKU
	Quantity  Quantity
	UnitPrice Cents
}

// The HTTP body (a wire DTO: it becomes a schema in the OpenAPI document). Unknown members, duplicate names and wrong-case names are refused by httpfw.DecodeJSON.
type AddLineBody struct {
	SKU            string `json:"sku"`
	Quantity       int64  `json:"quantity"`
	UnitPriceCents int64  `json:"unitPriceCents"`
}

// Every field error at once (400 VALIDATION with one detail per field), as Java's Validator collects them.
func (b AddLineBody) command(order OrderID) (AddLine, error) {
	var v validate.Violations
	cmd := AddLine{
		Order:     order,
		SKU:       validate.Field(&v, "sku", func() (SKU, error) { return ParseSKU(b.SKU) }),
		Quantity:  validate.Field(&v, "quantity", func() (Quantity, error) { return NewQuantity(b.Quantity) }),
		UnitPrice: validate.Field(&v, "unitPriceCents", func() (Cents, error) { return NewCents(b.UnitPriceCents) }),
	}
	if err := v.Err(); err != nil {
		return AddLine{}, err   // never a half-built command
	}
	return cmd, nil
}
```

The use case's `Validate` is then mostly empty: a command that exists is already valid.

## 5. Repository, use cases, handler, wiring

### Queries (sqlc)

```sql
-- sql/orders.sql
-- name: OrderFind :one
SELECT client_id, version, customer, status FROM orders WHERE id = @id AND client_id = @client_id;

-- name: OrderLines :many
SELECT line_id, sku, quantity, unit_price_cents FROM order_lines WHERE order_id = @order_id ORDER BY position;

-- name: OrderInsert :exec
INSERT INTO orders (id, client_id, version, customer, status) VALUES (@id, @client_id, 1, @customer, @status);

-- name: OrderUpdate :execrows
UPDATE orders SET status = @status, version = @next_version
 WHERE id = @id AND client_id = @client_id AND version = @expected_version;

-- name: OrderLinesDelete :exec
DELETE FROM order_lines WHERE order_id = @order_id;

-- name: OrderLinesInsert :copyfrom
INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents) VALUES ($1, $2, $3, $4, $5, $6);
```

`sqlc.yaml` maps `orders.id` and `order_lines.order_id` to `domain.OrderID` and
`order_lines.line_id` to `domain.LineID` (capabilities section 4). `make sql` regenerates
`internal/dbq` without a database; `fw sql check` prepares every query against an embedded
PostgreSQL migrated by the framework's runner (outbox sets included), and fails CI when the
generated code is stale.

### Repository

```go
type OrderRepository struct{}

func (OrderRepository) Find(ctx context.Context, db *database.DB, client fw.ClientID, id OrderID) (usecase.Loaded[Order], bool, error) {
	q := dbq.New(db.Pool())
	row, err := q.OrderFind(ctx, dbq.OrderFindParams{ID: id, ClientID: client.String()})
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[Order]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[Order]{}, false, fmt.Errorf("order find: %w", err)
	}
	lines, err := q.OrderLines(ctx, id)
	if err != nil {
		return usecase.Loaded[Order]{}, false, fmt.Errorf("order lines: %w", err)
	}
	order, err := orderFromRows(id, row, lines)   // status text -> OpenOrder or CancelledOrder; a bad row is an error
	if err != nil {
		return usecase.Loaded[Order]{}, false, err
	}
	rowClient, err := fw.ParseClientID(row.ClientID)
	if err != nil {
		return usecase.Loaded[Order]{}, false, err
	}
	return usecase.LoadedFrom[Order](rowClient, row.Version, order), true, nil
}

func (OrderRepository) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, o Order, v usecase.VersionCheck) (int64, error) {
	q := dbq.New(tx.Conn())
	n, err := q.OrderUpdate(ctx, dbq.OrderUpdateParams{
		Status: statusOf(o), NextVersion: v.Next, ID: o.ID(), ClientID: client.String(), ExpectedVersion: v.Expected,
	})
	if err != nil || n != 1 {
		return n, err                  // 0: stale, the runner applies the policy; above 1: INTERNAL
	}
	return n, rewriteLines(ctx, q, o)  // delete, then one COPY
}
// Insert and Delete likewise.

func statusOf(o Order) string {
	switch o.(type) {                  // gochecksumtype: a new state must be handled here
	case OpenOrder:
		return "OPEN"
	case CancelledOrder:
		return "CANCELLED"
	}
	panic("unreachable")               // the linter guarantees the switch is complete
}
```

`client_id` comes back from the row so the runner can confirm the repository really filtered by
client (`CROSS_CLIENT_WRITE`).

### Use cases

```go
type AddLineUseCase struct {
	Orders OrderRepository
	Ids    *fw.Ids
	Events *event.Factory              // metadata from the context and the application clock
}

func (AddLineUseCase) Name() usecase.Name { return usecase.MustName("add-line") }

func (AddLineUseCase) Validate(context.Context, fw.Context, AddLine) error { return nil }  // parsed at the edge

func (AddLineUseCase) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[AddLine]) (usecase.Grant, error) {
	return cx.Require(OrdersWrite)     // AUTHORIZATION when the principal lacks it (M4c)
}

func (u AddLineUseCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[AddLine], db *database.DB) (*usecase.Plan[OrderLineAdded], error) {
	cmd := a.Command()
	loaded, found, err := u.Orders.Find(ctx, db, cx.Client(), cmd.Order)   // load once
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, usecase.NotFound(OrderNotFound, "There is no such order.")
	}
	var open OpenOrder
	switch o := loaded.Get().(type) {
	case OpenOrder:
		open = o
	case CancelledOrder:
		return nil, usecase.BusinessRule(OrderNotOpen, "The order is cancelled and cannot change.")
	}
	line := NewOrderLine(id.New[LineKind](u.Ids), cmd.SKU, cmd.Quantity, cmd.UnitPrice)
	open = open.WithLine(line)
	ev := OrderLineAdded{
		Meta:    u.Events.Metadata(cx, LineAdded, cmd.Order.String()),
		OrderID: cmd.Order, LineID: line.ID(), SKU: line.SKU(), Quantity: line.Quantity(),
		UnitPriceCents: line.UnitPrice(), LineCount: len(open.Lines()),
	}
	return usecase.NewPlan(ev,
		usecase.Update(loaded.With(open), u.Orders, usecase.Entity{Type: "Order", ID: cmd.Order.String()}),
	), nil                              // Loaded: an UPDATE ... AND version = $n
}

// PlaceOrderUseCase.Execute ends with
//     usecase.NewPlan(placed, usecase.Insert(usecase.NewFresh[Order](cx, PlaceOrder(id, cmd.Customer)), u.Orders, entity))
// (Fresh: an INSERT at version 1, under the context's client), and has AuditData on its command:
//     func (c PlaceOrderCmd) AuditData() map[string]string { return map[string]string{"customer": c.Customer.String()} }
// CancelOrderUseCase switches the same way: OpenOrder -> o.Cancel(reason); CancelledOrder -> ORDER_NOT_OPEN.
```

Definition, with the Java demo's policies (`add-line` retries 3 times with 25 ms; the others fail):

```go
retry, err := usecase.Retry(3, 25*time.Millisecond)
if err != nil {
	return nil, err
}
addLine, err := usecase.Define(runner, AddLineUseCase{Orders: repo, Ids: b.Ids(), Events: events},
	usecase.WithConflictPolicy(retry))
```

### Operations, handler and routes

The routes are typed operations (capabilities section 5, decision 18), so the same declarations
produce the OpenAPI document the SPA's client is generated from (section 7a).

```go
// api.go: immutable descriptors, declared by orders.Declare; `orders-demo openapi` needs nothing else
var (
	PlaceOrderOp  = httpfw.Operation[PlaceOrderBody, OrderView]{ID: "placeOrder", Pattern: "POST /orders",
		Status: http.StatusCreated, Tags: tags, Errors: writeErrors}
	AddLineOp     = httpfw.Operation[AddLineBody, LineAddedView]{ID: "addLine", Pattern: "POST /orders/{id}/lines",
		Status: http.StatusOK, Tags: tags, Path: orderPath, Errors: writeErrors}
	CancelOrderOp = httpfw.Operation[httpfw.NoBody, OrderView]{ID: "cancelOrder", Pattern: "POST /orders/{id}/cancel",
		Status: http.StatusOK, Tags: tags, Path: orderPath, Errors: writeErrors}
	ListOrdersOp  = httpfw.Operation[httpfw.NoBody, OrderList]{ID: "listOrders", Pattern: "GET /orders",
		Status: http.StatusOK, Tags: tags, Errors: readErrors,            // section 6's query; OrderStatus lists its EnumValues
		Query: httpfw.Params{httpfw.QueryParam[OrderStatus]("status"), httpfw.QueryParam[string]("search"), httpfw.QueryParam[int]("page")}}

	tags        = []string{"orders"}
	orderPath   = httpfw.Params{httpfw.PathParam[OrderID]("id")}
	writeErrors = []int{400, 401, 403, 404, 409}
	readErrors  = []int{400, 401, 403}
)

func (s *Service) Routes() *httpfw.Routes {
	rt := httpfw.NewRoutes("orders")
	rt.Bind(httpfw.Handle(PlaceOrderOp, s.placeOrder))
	rt.Bind(httpfw.Handle(AddLineOp, s.addLine))     // start-up fails if a declared operation is unbound
	rt.Bind(httpfw.Handle(CancelOrderOp, s.cancelOrder))
	rt.Bind(httpfw.Handle(ListOrdersOp, s.listOrders))
	return rt
}

func (s *Service) addLine(ctx context.Context, cx fw.Context, in httpfw.In[AddLineBody]) (LineAddedView, error) {
	order := httpfw.PathValue[OrderID](in, "id")      // parsed before the handler: malformed is 400 VALIDATION
	cmd, err := in.Body.command(order)                // 400 VALIDATION, every field (decision 17)
	if err != nil {
		return LineAddedView{}, err
	}
	done, err := s.addLineUC.Run(ctx, cx, cmd)        // *usecase.Error -> the envelope
	if err != nil {
		return LineAddedView{}, err
	}
	return lineAddedView(done.Event()), nil            // written with AddLineOp.Status
}
```

How a typed path value is read back (`httpfw.PathValue` above) is not settled: Go cannot give `In`
a field per declared parameter, so the sketch looks it up by name and type; a generated accessor or
a params struct per operation are the alternatives (G3).

The wiring is the `main` of [`design.md`](design.md) section 1.2, with `orders.Declare` contributing
the migration set `orders` (the Java file, embedded), the event types, the operations above and the
`orders:orders:write` permission for definition sync.

## 6. The dynamic list query, both ways

`GET /orders?status=OPEN&search=acme&page=2`: optional status, optional customer search, paging,
always scoped by client. sqlc cannot generate optional predicates without the `(@x IS NULL OR ...)`
pattern (the platform's `docs/sqlc.md`, option A); this is where ruling c's builder applies.

**go-jet** (tables generated by `fw sql jet` from an embedded, framework-migrated PostgreSQL):

```go
func (r ReadModel) ListOrders(ctx context.Context, cx fw.Context, q ListQuery) ([]OrderRow, error) {
	cond := Orders.ClientID.EQ(String(cx.Client().String()))
	if q.Status != nil {
		cond = cond.AND(Orders.Status.EQ(String(q.Status.String())))
	}
	if q.Search != "" {
		cond = cond.AND(Orders.Customer.LIKE(String("%" + escapeLike(q.Search) + "%")))
	}
	stmt := SELECT(Orders.ID, Orders.Customer, Orders.Status).
		FROM(Orders).WHERE(cond).ORDER_BY(Orders.ID.DESC()).
		LIMIT(int64(q.Page.Size)).OFFSET(int64(q.Page.Offset()))
	return sqlfw.Query(ctx, r.db, stmt, pgx.RowToStructByName[OrderRow])   // jet builds, pgx runs and scans
}
```

A renamed column (`Orders.Customer`) or a string compared with an integer column fails to
**compile**. The cost: a second generator for the same tables, and a live database to run it.

**`Filter`** (no dependency):

```go
func (r ReadModel) ListOrders(ctx context.Context, cx fw.Context, q ListQuery) ([]OrderRow, error) {
	var f database.Filter
	f.Eq("client_id", cx.Client().String())
	if q.Status != nil {
		f.Eq("status", q.Status.String())
	}
	if q.Search != "" {
		f.Clause("customer LIKE $%d", "%"+escapeLike(q.Search)+"%")
	}
	limit, offset := f.Arg(q.Page.Size), f.Arg(q.Page.Offset())
	sql := "SELECT id, customer, status FROM orders" + f.Where() +
		fmt.Sprintf(" ORDER BY id DESC LIMIT $%d OFFSET $%d", limit, offset)
	rows, err := r.db.Pool().Query(ctx, sql, f.Args()...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[OrderRow])
}
```

A renamed column fails at **run time**, in the read model's test (which every list query has: it
runs each filter combination against the embedded database). Values are always bound in both.

## 7. A dispatch receiver running a use case

A subscription delivers `billing:invoices:invoice:paid` to `/fc/dispatch`; the orders service marks
the order paid through a use case.

```go
flowcatalyst.OnDispatch(rcv, func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[InvoicePaid]) (flowcatalyst.DispatchResult, error) {
	// cx: the application's service principal (kind Service), the client from X-FlowCatalyst-Client,
	// causation = the dispatch job id, so the outbox rows this use case writes point at the job.
	res, err := flowcatalyst.Once(ctx, d.Meta(), db, "mark-paid", func(ctx context.Context, tx *database.Tx) (struct{}, error) {
		_, err := s.markPaid.RunInTx(ctx, cx, tx, MarkPaid{Order: d.Data().OrderID})  // the record and the work commit together
		return struct{}{}, err
	})
	if err != nil {
		return nil, err                      // 503: transient, the platform retries
	}
	switch res.(type) {
	case flowcatalyst.Ran[struct{}], flowcatalyst.AlreadyDone:
		return flowcatalyst.Ack{}, nil
	}
	return nil, errors.New("unreachable")
})
```

`RunInTx` (a use case inside the `once` transaction, so the record and the work are atomic) is an
open point: the Java `once` hands the work a connection, and the use-case runner owns its own
transaction. Both implementations need the same answer (section 10).

## 7a. The orders screens in Nuxt UI

`go/examples/orders-demo/web/` is the example SPA ([`ui.md`](ui.md)): Vue, Nuxt UI and `@flowcatalyst/ui`,
built into `web/dist` and embedded by `web/embed.go`. `make ui-gen` runs `orders-demo openapi`
(declare phase only) into `web/openapi.json` and generates `web/src/api/` from it: `types.gen.ts`
(`AddLineBody`, `LineAddedView`, `OrderList`, `OrderStatus` as a literal union),
`sdk.gen.ts` (`placeOrder`, `addLine`, `cancelOrder`, `listOrders`, plus the framework's session
operations) and `zod.gen.ts` (`zAddLineBody`, ...).

Two screens:

- **Orders** (`/orders`): `FwListPage` with `FwTableToolbar` (search, a status filter whose options
  come from the generated `OrderStatus` values), `UTable`, `FwPaginator`; the filters live in the
  URL (`useListState`) and are passed to `listOrders({ query })`. A row opens `/orders/:id` as a
  child route in `FwRecordDrawer` (Back closes it, the list keeps its filters).
- **Add line**: `FwFormDialog` over the drawer, `UForm` with `zAddLineBody`; submit calls
  `addLine({ path: { id }, body })`; a `400 VALIDATION` envelope lands on the fields
  (`applyServiceError`), a `409` (the order is no longer open) as a toast; on success the drawer
  refreshes.

```ts
// web/src/pages/orders/useOrders.ts (sketch)
import { listOrders } from "@/api/sdk.gen";
import type { OrderList, OrderStatus } from "@/api/types.gen";
import { useListState } from "@flowcatalyst/ui";

export function useOrders() {
  const state = useListState({
    filters: {
      search: { type: "string", key: "search" },
      status: { type: "string", key: "status" },
    },
  });
  async function load(): Promise<OrderList | undefined> {
    const status: OrderStatus | undefined = parseStatus(state.filters.status.value);
    const { data } = await listOrders({
      query: { search: state.filters.search.value, page: state.page.value, ...(status ? { status } : {}) },
    });
    return data;
  }
  return { state, load };
}
```

The spread for `status` is what `exactOptionalPropertyTypes` asks for: an absent filter is left
out, not sent as `undefined`. Renaming `quantity` in `AddLineBody`, adding a required field or
changing a status value breaks `vue-tsc` in this folder after `make ui-gen`; CI's drift check makes
sure `make ui-gen` ran.

## 8. What Go checks, and where

| Guarantee | Java | Rust design | Go design |
|---|---|---|---|
| every use case has an authorisation step | definition-time exception | compile error | **compile error** (interface method) |
| business logic never runs on an unvalidated or unauthorised command | convention (`UseCases.run`) | `Authorized<C>` only from the runner | `Authorized[C]` non-zero only from the runner; a zero one is constructible (lint can flag `usecase.Authorized[...]{}` literals) |
| a success comes only from a commit | `Committed` constructible by anyone | sealed | non-zero only from the runner |
| lines only on an open order | each use case calls `isOpen()` | enum and match | `WithLine` only on `OpenOrder`; **`gochecksumtype`** makes switches over `Order` exhaustive (lint, not compiler) |
| a new aggregate is inserted, a loaded one updated | `version() == 0` | `Fresh`/`Loaded` | **`Fresh`/`Loaded`**, by type |
| a new aggregate belongs to the caller's client | runner check | `ctx.fresh` | `NewFresh(cx, a)`, the only constructor |
| an order id is not a line id, a client id or a string | partly | distinct types | **`id.ID[OrderKind]` ≠ `id.ID[LineKind]` ≠ `fw.ClientID`**: compile errors |
| quantity positive, SKU short, customer not blank | `validate` at run time | field types | field types with unexported fields; the zero value exists, so constructors and `IsZero` checks matter (NilAway does not see this) |
| event types, error codes, permissions, use-case names, route patterns well formed | at construction | `const` evaluation | **`fwvet`** at lint time, and a panic at package init |
| a route that needs a `Context` has a resolver | start-up | does not compile | **start-up** |
| the SQL matches the schema | `check` goal | `cargo check` | sqlc at generation (static), `fw sql check` in CI (prepared against PostgreSQL); go-jet columns at compile time; `Filter` queries in tests |
| a secret is not logged or serialised | redacting `toString`, Jackson fails | does not compile | redacting `String`, `Format`, `LogValue`; `MarshalJSON` fails at run time |
| no data race | review | `Send`/`Sync` | `go test -race` (dynamic) and review |
| nil dereference | NullAway | `Option` | **NilAway** (lint), conventions |
| an added outcome is handled everywhere | sealed `switch` | exhaustive `match` | **`exhaustive`** and **`gochecksumtype`** (lint) |
| every typed route is declared, bound once and in the API document | n/a (no OpenAPI) | n/a | **start-up** (`Bind` against the declared operations); the document by the `openapi` command, drift by CI |
| the browser sends what the API accepts and reads what it returns | n/a (server-rendered) | n/a | **`vue-tsc`** over the generated client (paths, query, body, response, error envelope), after the drift check |
| a form checks the API's shape before submitting | server only | server only | the generated Zod schema in `UForm` (types, required fields, enums); the Go parse stays the authority |

## 9. What stays a run-time check

As for Java and Rust: optimistic conflicts, unique violations and serialisation failures; a
repository that loads another client's row (`CROSS_CLIENT_WRITE` on `Loaded`); a repository whose
`Update` ignores the version (the repository contract tests, `usecasetest.Repository`); load once (a
rule, as ruled for Java and Rust); the event type's application segment against
`flowcatalyst.application`; configuration values (start-up, all problems at once). Go adds: zero
values of domain types reaching a repository (the repository refuses them), and `Filter` column
names (tests).

## 10. Open points this example exposes

- `cx.Require(OrdersWrite)` needs permissions on `Context` (M4c); until then the example checks that
  a principal is present, as the Java demo does.
- A use case inside a receiver's `once` transaction (`RunInTx`): the contract says application code
  never begins or commits; `once` owns a transaction. A ruling for every implementation: either the
  runner joins an existing transaction (and `once` records inside it), or `once` becomes a plan step.
- `usecase.Entity` repeats the aggregate's type and id for the audit row; Java takes them from the
  first aggregate. A small `Audited` interface on the aggregate (`AuditEntity() (string, string)`)
  would remove the repetition; decide in G2.
- Reading a typed path parameter back in the handler (`httpfw.PathValue`): a lookup by name and
  type, a params struct per operation, or a generated accessor; decide in G3 (section 5).
- `OrderStatus` must list its values for the document (`EnumValues()`); nothing yet checks that the
  list matches the constants. An `fwvet` check comparing a type's constants with its `EnumValues`
  would; decide in G3.
- The status filter's options come from the generated union; their labels do not (the document has
  no display names). Labels stay in the SPA.
