# TypeScript framework: the orders example, end to end (draft 1, 2026-10-13)

Companion to [`design.md`](design.md) and [`capabilities.md`](capabilities.md). The Java
`examples/orders-demo` written for the TypeScript design: the same tables (the Java demo's
`V0001__create_orders.sql`, unchanged), the same three use cases (`place-order`, `add-line`,
`cancel-order`), the same events (`orders:sales:order:placed`, `line-added`, `cancelled`) and so the
same outbox rows. It adds what the Java demo leaves to later: a permission, routes, a page, a
receiver and the wiring. The point is section 7: what the type checker, the lint rules and scriptc
guarantee, and what stays a run-time check.

The code is a sketch of the intended API, not compiled. The folders follow the schema split:

```
examples/orders-demo/
  server/src/
    domain/        ids, value objects, the order states, decide functions, event data   (no TypeBox, no I/O, no throw)
    app/           use cases (async shells), repository, receivers
    api/           TypeBox wire schemas, edge parse functions, routes                    (importable by web/)
    main.ts        declare, launch, build
  server/migrations/orders/V0001__create_orders.sql   the Java demo's file
  web/src/         Vite + Vue + Nuxt UI + @fw/ui; imports api/ schemas as types and for forms
```

## 1. Values and ids (domain)

```ts
import type { NoThrow } from "@scriptc/types";
import { type Id, idParser, type Result, ok, err, fieldError, type FieldError } from "@fw/core";

export type OrderId = Id<"ord">;                   // "ord_" + 13 Crockford characters
export type LineId = Id<"lin">;                    // Id<"lin"> is not assignable to Id<"ord">
export const OrderId = { parse: idParser("ord") }; // (raw: unknown) => Result<OrderId, FieldError>
export const LineId = { parse: idParser("lin") };

declare const customerName: unique symbol;
export type CustomerName = string & { readonly [customerName]: true };

export const CustomerName = {
  /** Trimmed, not blank. The only constructor. */
  parse(raw: string): NoThrow<Result<CustomerName, FieldError>> {
    const s = raw.trim();
    if (s.length === 0) return err(fieldError("CUSTOMER_REQUIRED", "A customer is required."));
    return ok(brand<CustomerName>(s));      // the framework's one sanctioned cast, inside `brand`
  },
};
// Sku (1 to 20 characters), Quantity (an Int32 of at least 1), Cents (a non-negative safe integer),
// CancelReason (not blank): the same shape.

export const ORDERS_WRITE = permission("orders:orders:write");   // checked at module load
```

`brand` is `@fw/core`'s `unsafeBrand` re-exported for value objects; it is the only place lint allows
`as` (design section 1.5). A value object's `parse` is `NoThrow`: no throw, no recursion, no cast
outside `brand`.

## 2. The aggregate: one type per state (domain)

```ts
import type { Acyclic, Int32, NoThrow } from "@scriptc/types";

export type OrderLine = Acyclic & {
  readonly id: LineId; readonly sku: Sku; readonly quantity: Quantity; readonly unitPrice: Cents;
};
export type OpenOrder = Acyclic & {
  readonly status: "open"; readonly id: OrderId; readonly customer: CustomerName; readonly lines: readonly OrderLine[];
};
export type CancelledOrder = Acyclic & {
  readonly status: "cancelled"; readonly id: OrderId; readonly customer: CustomerName; readonly lines: readonly OrderLine[];
};
export type Order = OpenOrder | CancelledOrder;
```

(Whether `Acyclic` is written as an intersection on a type alias or only on interfaces is checked
in the spike; scriptc documents it on classes, interfaces and object type aliases.)

The aggregate has no `client` and no `version`: those are the framework's (`Fresh`, `Loaded`). Java's
`Order` is one record with a `status` field and an `isOpen()` each use case must remember to call;
here a `switch` on `status` is exhaustive by lint, and the functions that only make sense on an open
order take `OpenOrder`.

## 3. Decide functions: the pure core (domain)

Everything a decision needs is an argument: the loaded state, the parsed command, the new line's id
and the time are values, never a generator or a clock.

```ts
export type AddLine = { readonly order: OrderId; readonly sku: Sku; readonly quantity: Quantity; readonly unitPrice: Cents };

export type LineAddedData = {                 // the event's data, in the Java record's component order
  readonly orderId: OrderId; readonly lineId: LineId; readonly sku: Sku;
  readonly quantity: Quantity; readonly unitPriceCents: Cents; readonly lineCount: Int32;
};

export function addLine(order: Order, cmd: AddLine, lineId: LineId):
    NoThrow<Result<{ next: OpenOrder; data: LineAddedData }, DomainRejection>> {
  switch (order.status) {
    case "cancelled":
      return err(rejection("ORDER_NOT_OPEN", "The order is not open."));
    case "open": {
      const line: OrderLine = { id: lineId, sku: cmd.sku, quantity: cmd.quantity, unitPrice: cmd.unitPrice };
      const next: OpenOrder = { ...order, lines: [...order.lines, line] };
      return ok({
        next,
        data: { orderId: order.id, lineId, sku: cmd.sku, quantity: cmd.quantity,
                unitPriceCents: cmd.unitPrice, lineCount: next.lines.length | 0 },
      });
    }
  }
}

export function cancel(order: Order, reason: CancelReason):
    NoThrow<Result<{ next: CancelledOrder; data: CancelledData }, DomainRejection>>;   // same shape
export function place(id: OrderId, customer: CustomerName): NoThrow<{ next: OpenOrder; data: PlacedData }>;
```

`DomainRejection` is `{ kind: "business-rule" | "not-found"; code; message }`, a domain type; the
application maps it to `UseCaseError`. `lineCount: next.lines.length | 0` is how a length becomes a
provable `Int32`. The domain imports nothing from the framework but `Result`, ids and the scriptc
contract types.

## 4. The wire: schemas and the edge parse (api)

```ts
import { Obj, Str, Int } from "@fw/schema";      // TypeBox builders; Obj closes the object (additionalProperties: false)

export const AddLineBody = Obj({ sku: Str(), quantity: Int(), unitPriceCents: Int() });
export const LineAddedView = Obj({ orderId: Str(), lineId: Str(), lineCount: Int() });
export type AddLineBody = Static<typeof AddLineBody>;

/** Every field error at once: 400 VALIDATION with details "<code>: <message>" per field. */
export function toAddLine(body: AddLineBody, rawOrder: string): Result<AddLine, Violations> {
  return all({
    order: OrderId.parse(rawOrder),
    sku: Sku.parse(body.sku),
    quantity: Quantity.parse(body.quantity),
    unitPrice: Cents.parse(body.unitPriceCents),
  });
}
```

The schema says the body is an object with an integer `quantity`; the domain says a quantity is at
least 1. The first is checked by `Value.Check` before the handler runs (wrong type, missing field,
unknown field: `INVALID_JSON`/`VALIDATION` with the JSON path, the value never echoed); the second by
`Quantity.parse`. The same `AddLineBody` is imported by `web/` for the form.

## 5. Repository, use case, routes, receiver (app and api)

### Repository: explicit SQL, rows parsed

```ts
export const orderRepository: Repository<Order, OrderId> = {
  async find(db, client, id) {
    const head = await db.query(
      "SELECT client_id, version, customer, status FROM orders WHERE id = $1 AND client_id = $2", [id, client]);
    if (!head.ok) return head;
    const row = head.value.rows.at(0);
    if (row === undefined) return ok(undefined);
    const lines = await db.query(
      "SELECT line_id, sku, quantity, unit_price_cents FROM order_lines WHERE order_id = $1 ORDER BY position", [id]);
    if (!lines.ok) return lines;
    return parseOrder(id, row, lines.value.rows);   // Result<Loaded<Order>, DbError>: each column through its type's parse
  },
  // insert, update (UPDATE ... SET version = $next WHERE id AND client_id AND version = $expected, then
  // DELETE + one INSERT ... SELECT FROM unnest(...) for the lines) and delete likewise
};
```

`parseOrder` reads every column as `unknown` and parses it (`ClientId.parse`, `Version.parse`,
`CustomerName.parse`, the status literal), so a corrupt row is a `DbError` naming the column, not a
crash, and no `as` is needed. `client_id` comes back from the row so the runner can confirm the
repository filtered by client. T7's checked-SQL generator replaces these hand-written parsers.

### Use case: the async shell

```ts
export function addLineUseCase(deps: { ids: Ids; events: Events }): UseCase<AddLine, DomainEvent> {
  return {
    name: useCaseName("add-line"),
    conflict: retry(3, 25),                                   // as the Java demo
    authorize: async (ctx) => requirePermission(ctx, ORDERS_WRITE),
    async execute(ctx, authorized, db) {
      const cmd = command(authorized);
      const found = await orderRepository.find(db, ctx.client, cmd.order);   // load once
      if (!found.ok) return found;                                           // the runner maps DbError (step EXECUTE)
      if (found.value === undefined) return err(notFound("ORDER_NOT_FOUND", "No such order."));
      const decided = addLine(found.value.value, cmd, deps.ids.next("lin"));
      if (!decided.ok) return err(fromRejection(decided.error));
      const event = deps.events.of(ctx, "orders:sales:order:line-added", cmd.order, decided.value.data);
      return ok(save(mapLoaded(found.value, () => decided.value.next), orderRepository, event));   // an UPDATE ... AND version = $n
    },
  };
}
```

`deps.ids.next("lin")` is the only impure call before the decision, and its value goes in as an
argument. `place-order` saves a `Fresh` (`freshFor(ctx, order)`, an `INSERT` with version 1) and has
`auditData` returning `{ customer }`; `cancel-order` uses `cancel` and the `fail` conflict policy, as
in Java.

### Routes: plain arrays, composed with `scope`

```ts
export function orderRoutes(deps: OrderDeps): readonly Route[] {
  return [
    route({
      method: "POST", path: "/orders/:id/lines", module: "orders",
      params: Obj({ id: Str() }), body: AddLineBody, response: { 200: LineAddedView },
      auth: { context: true },
      async handler(_req, c) {
        const cmd = toAddLine(c.body, c.params.id);
        if (!cmd.ok) return err(validation(cmd.error));                      // 400 VALIDATION, every field
        const done = await deps.useCases.run(deps.addLine, c.ctx, cmd.value);
        if (!done.ok) return done;                                           // envelope by ErrorKind
        return ok(json(200, lineAddedView(committed(done.value))));
      },
    }),
    // POST /orders, POST /orders/:id/cancel, GET /orders (a read model query), GET /orders/:id
  ];
}

// main.ts, inside build:
const all = [
  ...healthRoutes(boot),
  ...scope({ prefix: "/api", auth: { context: true }, middleware: [csrf(cfg)] }, orderRoutes(deps)),
  ...fc.receiverRoutes(receivers),                 // signature-authenticated, no session
  ...spaRoutes({ dir: "web/dist", prefix: "/" }),  // trailing wildcard, index.html fallback for navigations
];
```

A second `POST /api/orders/:id/lines` anywhere in `all`, or `/api/orders/:orderId/lines`, fails the
build naming both routes.

### Receiver: a dispatch job runs a use case

```ts
receivers.onDispatch("billing:payments:payment:captured", PaymentCaptured, async (ctx, delivery, signal) => {
  // ctx.principal is the application's service principal; ctx.causation is the dispatch job id (receiver ruling)
  const cmd = toMarkPaid(delivery.data);
  if (!cmd.ok) return reject("payload does not describe a payment");
  const done = await useCases.run(markPaid, ctx, cmd.value);
  return done.ok || done.error.code === "ORDER_NOT_FOUND" ? ack() : retryLater(done.error);   // an INTERNAL error answers 503
});
```

## 6. The page (web)

`web/src/pages/OrderLines.vue` uses `@fw/ui`'s list section for the lines and a form dialog for
"Add line". Its script part:

```ts
import { standardSchema, useApi, useDirtyForm } from "@fw/ui";
import { AddLineBody, type LineAddedView } from "../../server/src/api/orders";

const schema = standardSchema(AddLineBody);     // UForm validates shape with the API's own schema
const api = useApi();
async function submit(orderId: string, body: AddLineBody) {
  const r = await api.post<LineAddedView>(`/api/orders/${orderId}/lines`, body);
  if (!r.ok && r.error.kind === "validation") form.setErrors(r.error.fields);   // the server's domain errors on the fields
}
```

The template is Nuxt UI's `UForm`, `UFormField` and `UInput` inside `@fw/ui`'s `FormDialog`, and the
list is `@fw/ui`'s `ListSection` over Nuxt UI's `UTable`. (Template markup is not shown inline: the
documentation site would read its interpolation syntax as Liquid.) The browser never sees a domain
type; it sees the wire schema, and a domain rule it cannot know (a blank customer after trimming)
comes back as `400 VALIDATION` mapped onto the field.

## 7. What is guaranteed, and by what

| Guarantee | Java today | TypeScript design | Enforced by |
|---|---|---|---|
| every use case has an authorisation step | exception when defined | `authorize` is a required member | type checker |
| business logic never runs on an unauthorised command | convention | `execute` takes `Authorized<C>`, built only by the runner | type checker + lint (no `as`) |
| a success comes only from a commit | `Committed` record anyone can build | `Committed<E>` sealed by a module-private symbol | type checker + lint |
| lines are only added to an open order | each use case calls `isOpen()` | `addLine` switches on `status`; the switch is exhaustive | lint (`switch-exhaustiveness-check`) |
| a new aggregate is inserted, a loaded one updated | `version() == 0` | `Fresh` versus `Loaded` | type checker |
| a new aggregate belongs to the caller's client | runner check | `freshFor(ctx, a)` is the only constructor | type checker |
| an order id is not a line id or a string | `OrderId` record; line ids are strings | `Id<"ord">`, `Id<"lin">`, `ClientId` are distinct brands | type checker |
| a quantity is positive, a customer not blank | `validate` in each use case | the command's field types cannot hold a bad value | type checker + smart constructors |
| the route's params, query and body are what the handler reads | `request.pathInt` at run time | `c.params`, `c.query`, `c.body` typed from the route's schemas and path | type checker; schema check at run time |
| two routes for one method and path | start-up failure | build failure naming both | start-up |
| a route needing a `Context` has a resolver | start-up failure | start-up failure (arrays hide it from the type checker) | start-up |
| the domain core never throws | not checked | `NoThrow` on every decide and parse function | scriptc (non-gating job); until then lint (no `throw`, `try`, recursion, `as`) |
| domain values are trees | not checked | `Acyclic` on aggregate and event types | scriptc (non-gating) |
| no value from outside is trusted unparsed | Jackson binding plus `Validator` | `JSON.parse` and rows are `unknown`; parse functions narrow | type checker + lint |
| a secret is not logged or serialised | redacting `toString` | redacting `toString`/`toJSON`, lint on template literals | run time + lint (weaker than Rust) |
| the outbox row matches Java's | conformance vector | the same vector, byte for byte | test |
| event types, codes, permissions are well formed | checked when constructed | shape by template-literal types, characters at module load | type checker (partly) + start-up |

## 8. What stays a run-time check

- Optimistic conflicts (`STALE_AGGREGATE`), unique violations, serialisation failures, deadlocks.
- A repository that loads another client's row (`CROSS_CLIENT_WRITE` on `Loaded`), or whose `update`
  ignores the version: caught by the repository contract tests, not by types.
- Load once: a rule, not a type, as in Java and Rust.
- The application segment of an event type equals `flowcatalyst.application`: start-up.
- Configuration values: start-up, every problem at once.
- Anything a cast could defeat: the seals and brands hold only while lint forbids `as`.

## 9. Considered and not chosen

- **Classes for aggregates with methods** (`order.addLine(...)`). Plain data plus functions keeps
  the domain free of `this`, prototype tricks and the scriptc class limits, and serialises trivially.
- **TypeBox schemas as domain types.** The schema split (design section 1.6): a domain type built
  from a schema inherits the schema library's limits and the wire's looseness (strings for ids).
- **Exceptions for rejections inside the domain**, caught by the runner. Simpler to write, but no
  `NoThrow`, and a rejection is an expected outcome (plumbing rule: errors are values).
- **A generic `Order<S extends Status>`.** The loaded state is known only at run time; the union
  with a discriminant is simpler, as the Rust design found.
