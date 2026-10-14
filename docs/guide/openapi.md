<!-- types: Operation, OperationHandler, OperationRequest, Outcome, PathParam, QueryParam, NoBody, TypeRef, Constraint, Rule, Validator, Routes, Json, OpenApi, OpenApiDocument, OpenApiTesting, Schema, SecurityScheme, DeclarationCommand, Declarations, AppBuilder, JettyHttpModule, AuthModule -->
# Typed operations and the OpenAPI document

A **typed operation** is a JSON endpoint described as a value: method, path, typed parameters, the
body's record and validator, the response record, the permission and the expected failures. The
router serves it like any route; the framework writes an **OpenAPI 3.1 document** from the same
values, and the single-page application's TypeScript client and Zod schemas are generated from that
document. One source, three consumers: the server, the document, the browser.

The rules are in [contracts/openapi.md](../contracts/openapi.md), shared with the Go framework; this
page is how to use them in Java. Plain routes (`routes.get(...)`) keep working and are not in the
document: receivers, health, metrics, the SPA's files, login redirects and Datastar streams stay
plain routes.

## Declaring operations

Operations are values, written once. These come from `examples/orders-demo` (`OrdersApi`):

```java
public static final PathParam<String> ORDER_ID = PathParam.string("orderId");
public static final QueryParam<@Nullable OrderStatus> STATUS = QueryParam.enumOf("status", OrderStatus.class).optional();
public static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);

public static final Operation<NoBody, Page<OrderSummaryView>> LIST =
    Operation.get("/api/orders", new TypeRef<Page<OrderSummaryView>>() {})
        .id("listOrders")
        .summary("List the client's orders")
        .tag("orders")
        .query(STATUS, LIMIT, AFTER)
        .permission(READ)
        .build();

public static final Operation<AddLineBody, OrderView> ADD_LINE =
    Operation.post("/api/orders/{orderId}/lines", AddLineBody.V, OrderView.class)
        .id("addOrderLine")
        .summary("Add a line to an open order")
        .tag("orders")
        .path(ORDER_ID)
        .permission(MANAGE)
        .error(404, "ORDER_NOT_FOUND", "there is no such order")
        .error(409, "ORDER_NOT_OPEN", "the order is cancelled and cannot change")
        .error(409, "STALE_AGGREGATE", "the order was changed by someone else; reload it and try again")
        .build();
```

| Part | What to know |
|---|---|
| factories | `Operation.get` and `delete` take the response; `post`, `put` and `patch` take the body's `Validator` and the response; `Operation.post(pattern, Response.class)` is a body-less `POST` (a body sent anyway is not read). A generic response goes through `new TypeRef<Page<X>>() {}` |
| `id` | required, `[a-z][A-Za-z0-9]*`, unique: it becomes the TypeScript function name |
| `path(...)` | every `{name}` of the pattern needs a `PathParam`: `string`, `integer`, `longInt`, `enumOf(name, Enum.class)` |
| `query(...)` | `QueryParam.string`, `integer`, `longInt`, `bool`, `enumOf`; required unless `.optional()` (reads `null` when absent), `.defaultValue(v)` or `.list()` (repeated, `?status=A&status=B`, empty when absent) |
| `status` | `200` by default, `201`, `202`; a `NoBody` response is always `204` |
| `requiresContext()` / `permission(p)` | the operation needs a `Context`; `permission` implies it and answers `403 FORBIDDEN` when `Context.hasPermission(p)` is false (wildcards in the held permission, as `Auth.require`) |
| `error(status, code, message)` | an expected failure, listed in the document; repeatable |

`build()` never throws for a mistake in the descriptor (a missing id, a path parameter that is not in
the pattern, `204` with a response body...). `operation.problems()` lists them, and they fail start-up
and the `openapi` command with every problem named, so a `static final` never breaks class loading.

## Declare in `register`, bind in `boot`

Declaring in `register` is what lets the document be written with no configuration and no database;
binding needs the services, so it happens in `boot` (`OrdersApiModule`):

```java
@Override
public void register(AppBuilder app) {
  OpenApi.declare(app, OrdersApi.LIST, OrdersApi.GET, OrdersApi.PLACE, OrdersApi.ADD_LINE, OrdersApi.CANCEL);
  OpenApi.info(app, "orders-demo", "1");
}
```

```java
routes.bind(OrdersApi.PLACE, r -> {
  Context context = r.context();
  Outcome<OrderView> out =
      then(
          useCases.run(orders.placeOrder(), context, new PlaceOrder(r.body().customer())),
          placed -> view(repository, db, context, placed.orderId()));
  return out instanceof Outcome.Ok<OrderView> ok ? ok.header("Location", "/api/orders/" + ok.value().id()) : out;
});
```

where a use case's failure passes straight through, because a `UseCaseError` is a `ServiceError`:

```java
private static <E extends DomainEvent> Outcome<OrderView> then(UseCaseResult<E> result, Step<E> next) throws Exception {
  return switch (result) {
    case UseCaseResult.Committed<E> c -> next.apply(c.event());
    case UseCaseResult.Failed<E> f -> Outcome.failed(f.error());
  };
}
```

`OpenApi.info(app, title, version)` is called once per application. Start-up fails for an operation
declared but not bound, bound but not declared, bound twice, two operations with one id, or a
descriptor problem. (A table you build by hand in a test, `new Routes()`, knows no declarations
unless you call `routes.declare(...)`; the HTTP module's table always does.) `routes.bind(op,
handler, RouteOptions.none().with(middleware))` adds route middleware such as a rate limit.

The handler gets an `OperationRequest`: `body()` (decoded and validated; `NoBody.VALUE` for none),
`path(PARAM)`, `query(PARAM)`, `context()` and `raw()` (the `Request`, for headers). It returns an
`Outcome`:

| Outcome | Answer |
|---|---|
| `Outcome.ok(value)` | the operation's status, the value as JSON; `.header(name, value)` appends a header (two `Set-Cookie`s are fine) |
| `Outcome.noContent()` | `204` for a `NoBody` operation |
| `Outcome.failed(serviceError)`, `Outcome.failed(status, code, message[, details])` | the error envelope with that status |

Throwing `HttpException` still works. A failure whose status the document does not list is answered
as given and logged once at WARN ("status 418 is not in the document for ...").

## What a request goes through

1. The context is resolved (`401` without one), then the **permission** (`403 FORBIDDEN`), before
   anything about the request is looked at.
2. Every path and query parameter, all errors in one `400 VALIDATION` (`"limit": "INVALID: must be an
   integer"`, `"q": "REQUIRED: is required"`).
3. The body: `415` unless `application/json`, `400 INVALID_JSON` for malformed JSON or an unknown
   property, `400 VALIDATION` with every failed field (http guide, "Typed request validation").
4. The handler.

Responses are written with `Json.operationMapper()`, which differs from `Json.mapper()` (plain routes
and `Response.json`) in three ways, by owner ruling: a `@Nullable` record component that is `null`
is **left out** (plain routes keep writing `null`), `BigDecimal` is written as a **JSON string**
(`"9.990"`; read from a string or a number), and the context ids (`ClientId`, `PrincipalId`,
`ApplicationCode`, `CorrelationId`) are plain strings. Durations are ISO-8601 (`"PT5M"`), instants
ISO-8601 UTC.

## Schemas from your types

The emitter walks the records your operations name, once, the way Jackson sees them:

| Java | Schema |
|---|---|
| `String`, `boolean`, `int`, `long`, `double` (and boxes) | `string`, `boolean`, `integer` `int32`, `integer` `int64`, `number` `double` |
| `BigDecimal`, `Instant`, `LocalDate`, `Duration`, `UUID`, `byte[]` | `string` with `decimal`, `date-time`, `date`, `duration`, `uuid`, base64 |
| an enum | `{"type": "string", "enum": [...]}` in declaration order |
| a record | a component (`$ref`), `additionalProperties: false` |
| `List`, `Collection`, `Set` | `array` (`uniqueItems` for a set) |
| `Map<String, V>` | `object` with `additionalProperties` |
| `JsonNode` | `{}` (anything) |
| a sealed interface with `@JsonTypeInfo` / `@JsonSubTypes` | a `oneOf` component with a `discriminator` |
| a value type you register | your schema |

- **Required unless `@Nullable`.** `@Nullable` means "may be absent", never "may be null": the
  property is not required and its schema is not nullable (TypeScript `x?: T`).
- **Names.** A component is named after the type; a record nested in a record or interface is
  prefixed by it (`IngestionView.Route` is `IngestionViewRoute`), while a holder class adds nothing
  (`OrdersApi.OrderView` is `OrderView`); a generic record is followed by its arguments
  (`Page<OrderSummaryView>` is `PageOrderSummaryView`). Two types with one name fail the dump; rename
  one or `OpenApi.name(app, Type.class, "OtherName")`.
- **Sealed interfaces** need `@JsonTypeInfo(use = NAME, include = PROPERTY, property = "kind")` and a
  `@JsonSubTypes` naming exactly the permitted records, each with a `name`. Each variant becomes
  `<Interface><Variant>` with the discriminator as its first property.
- **A value type of your own** (`Money`) needs `OpenApi.schema(app, Money.class,
  Schema.string().pattern(...))`; it is written inline. `OpenApi.property(app, Record.class,
  "component", schema)` replaces one property's schema (a fixed value: `Schema.bool().constant(true)`).
- **Refused**, naming the path from the operation (`getX.response.x: Optional is refused ...`):
  `Optional`, `Object`, raw and wildcard generics, a `Map` with a non-`String` key, arrays other than
  `byte[]`, `float`, plain classes, local records, `@Nullable` inside a type argument, and a top-level
  response that is a list, a map or a primitive.
- **Jackson annotations**: only `@JsonTypeInfo` and `@JsonSubTypes` on sealed interfaces and
  `@JsonIgnore` on methods that are not components are understood; any other (`@JsonProperty`,
  `@JsonInclude`, `@JsonFormat`, `@JsonValue`, `@JsonNaming`...) fails the dump, as does a public
  `getX()` / `isX()` method on a record without `@JsonIgnore` (Jackson would write it). Give the API
  its own records rather than exposing an aggregate: `Order.isOpen()` is why `OrdersApi` has
  `OrderView`.

## Constraints come from the validator

The body's `Validator` is the only source of constraints; each standard rule describes itself
(`Rule.describe()` returns a `Constraint`) and `Validator.fields()` lists them per field:

| Rule | Schema |
|---|---|
| `notBlank` | `minLength: 1`, `pattern: "\\S"` |
| `minLength`, `maxLength`, `range`, `size` | `minLength`, `maxLength`, `minimum`/`maximum`, `minItems`/`maxItems` |
| `pattern(regex)` | `^(?:regex)$` |
| `oneOf(values)` | `enum` |
| `each(rule)`, `nested`/`valid` | the rule on `items`; the nested validator on the nested record |
| `notNull`, `custom` | nothing |

`AddLineBody`'s validator gives `"sku": {"type": "string", "pattern": "^(?:[A-Z0-9][A-Z0-9-]*)$",
"minLength": 1, "maxLength": 64}`. A pattern the browser cannot read the same way (inline flags,
possessive quantifiers, `\A`, `\z`, `\p{...}`...) fails the dump: use `Rules.custom`. Two operations
whose validators give one record different constraints fail the dump; share the validator or give
each its own body record.

## Errors, statuses and security

Every operation lists `default` and, by its shape: `400` with a body or any parameter, `401` and
`403` with a context, `413` and `415` with a body, plus each declared `error` (its codes in the
description). All of them point at one shared `Error` component, the http envelope.

The module that resolves the context contributes the security schemes: `AuthModule` adds `session`
(the `__Host-fw_session` cookie) and, when bearer tokens are accepted, `bearer`; an application with
its own resolver calls `OpenApi.securityScheme(app, "basic", SecurityScheme.httpBasic())`. Operations
that need a context list every scheme; public ones none. A context operation with no scheme fails
the dump.

`AuthModule` also declares its two endpoints as operations, `getAuthSession` (`GET /auth/session`)
and `logout` (`POST /auth/logout`), so they are in every document of an application that uses it
(see [Sessions and login](sessions-and-login.md#the-session-endpoint)).

## Writing the document

The HTTP module (`JettyHttpModule`) claims the `openapi` command. It runs after `register` only: no
configuration file, no environment, no database, nothing listens.

```
java -jar app.jar openapi                          # to standard output
java -jar app.jar openapi --out web/openapi.json   # to the file
```

Every problem is printed, one per line, exit code 1. The output is deterministic (sorted paths and
components, fixed key order, formatted like `JSON.stringify(doc, null, 2)`), so the same operations
give the same bytes on every machine, in Java and in Go. Your `main` passes the arguments to
`launch` (`OrdersWeb.main` does). Tools and tests get the same without a command:
`OpenApi.document(builder.declarations())` returns `OpenApiDocument.Written(json)` or
`Refused(problems)`.

## Keeping it current

Commit the document (`web/openapi.json`) and let a unit test fail the Maven build when it is stale
(`OrdersOpenApiTest`):

```java
OpenApiTesting.assertCommitted(OrdersWeb.builder(), DOCUMENT);
```

It regenerates the document from the builder's modules and fails with a line diff when the file
differs. Run that test with `-Dopenapi.update=true` to rewrite the file, review the diff and commit.
`examples/orders-demo/web/openapi.json` is the committed example.

## The generated TypeScript client

The SPA generates its client from the committed file with `@hey-api/openapi-ts` (contract section
11; the shared UI package pins the version). Checked on 2026-10-14 with this repository's documents:
`const` becomes a literal type and `z.literal`, a sealed interface a discriminated union, "may be
absent" `x?: T`. Two things to know: hey-api's bundled fetch runtime (`client/`, `core/`) does not
compile under `exactOptionalPropertyTypes` (the UI package gives it its own project without that
flag), and its Zod plugin turns `int64` into `z.coerce.bigint()` while the TypeScript type is
`number`.

## Testing

- `TestClient` serves bound operations like any route; hand-built tables call
  `routes.declare(OpenApi.operations(lookup))` to get the declared-but-not-bound checks
  (`OrdersApiTest` does).
- `OpenApiTesting.assertCommitted` in the build, as above.
- The framework's own tests validate every emitted document against the OpenAPI 3.1 schema and the
  JSON the typed writer produces against the emitted schemas.

## Not done yet

- The end-to-end run of the contract's section 12 (the generated client compiled in the UI's
  workspace and a Playwright run with response validation against the server) belongs to the UI
  package's U3 step.
- No YAML output, no `servers`, no request or response validation against the document at run time
  (by design).
