<!-- types: Operation, Builder, In, Binding, OperationHandler, PathParam, QueryParam, OptionalQuery, ListQuery, Params, NoBody, Validator, FieldSpec, Rule, FieldError, Input, Schema, EnumValuer, SchemaProvider, Unique, SecurityScheme, Info, Described -->
# Go: typed operations and OpenAPI

Typed operations are the Go form of the [OpenAPI contract](../../contracts/openapi.md): an operation
is a value written once, declared at declaration time, bound to a handler when the application is
built, and the OpenAPI 3.1 document is written from the declarations by the framework's own emitter
(`httpfw/openapi`). The document is byte for byte the Java framework's for the same API: the
fixture, the two session operations and the orders demo are the vectors
`conformance/openapi/fixture.json`, `auth.json` and `orders-demo.json`. Snippets come from
`go/examples/orders-demo/orders/api.go`.

## Declaring an operation

```go
ListOrdersOp = httpfw.Get[Page[OrderSummaryView]]("/api/orders").
	ID("listOrders").Summary("List the client's orders").Tag("orders").
	Query(status, limit, after).Permission(Read).Build()

PlaceOrderOp = httpfw.Post[PlaceOrderBody, OrderView]("/api/orders", placeOrderRules).
	ID("placeOrder").Summary("Place an order").Tag("orders").
	Status(http.StatusCreated).Permission(Manage).Build()
```

`Get`, `Delete` and `PostNoBody` take the response type; `Post`, `Put` and `Patch` take the body
type, its validator and the response type. A response of `httpfw.NoBody` is `204`. The id is
`[a-z][A-Za-z0-9]*`, unique (it becomes the TypeScript function name); tags `[a-z][a-z0-9-]*`;
`Status` is 200, 201, 202 or 204; `Error(status, code, message)` documents an expected failure;
`RequiresContext()` resolves the `fw.Context` and documents the security schemes and the 401 and
403; `Permission(p)` implies it, is enforced (`403 FORBIDDEN`) before anything about the request is
read, and is written as `x-fw-permission`. `Build` never panics: a mistake (a path parameter missing
from the pattern, a 204 with a body type) is listed with the operation and reported as a start-up
problem and by the `openapi` command, so a package-level variable never fails initialisation.

Parameters are typed values: `httpfw.Path[int64]("id")`, `httpfw.Query[OrderStatus]("status")`,
then `.Optional()` (value and presence), `.Default(v)` or `.List()`. The type is a string, `int32`,
`int64`, `bool` or a string type with `EnumValues()`. The handler reads them with
`status.Value(in.Params)`; a missing or malformed parameter is one `400 VALIDATION` naming every bad
one (`REQUIRED`, `INVALID`).

## Declaring for the document, binding in build

```go
func DeclareAPI(web *httpfw.Declared) {
	web.Info("orders-demo", "1")
	web.Operations(ListOrdersOp, GetOrderOp, PlaceOrderOp, AddOrderLineOp, CancelOrderOp)
}
```

```go
rt.Bind(httpfw.Handle(ListOrdersOp, a.list))
```

A handler is `func(ctx context.Context, cx fw.Context, in httpfw.In[Body]) (Resp, error)`. `in.Body`
is decoded and validated, `in.Params` holds the parsed parameters, `in.Request` is the request and
`in.Header` collects headers for the success response (`Location`, two `Set-Cookie`s). A returned
`ServiceError` (a `*usecase.Error` passes straight through) is answered with its envelope; a status
the operation does not document is logged once per operation and status. The order of checks is the
permission, then every parameter (one `400`), then the body (`415`, then `400` with every field
error), then the handler. An optional value is a pointer field with `omitzero`, and a nil one is left
out of the JSON (the emitter refuses a pointer field without it, so the document and the wire
agree). Declared and bound must match: an operation declared and never bound, bound and never
declared, bound twice, or with two operations of one id, fails start-up.

## Validation as data

`httpfw/validate` is the one source of decoding rules and schema constraints:

```go
placeOrderRules = validate.Of(
	validate.Field("customer", func(b PlaceOrderBody) string { return b.Customer }, validate.NotBlank(), validate.MaxLength(200)))
addLineRules = validate.Of(
	validate.Field("sku", func(b AddLineBody) string { return b.SKU }, validate.NotBlank(), validate.MaxLength(64), validate.Pattern("[A-Z0-9][A-Z0-9-]*")),
	validate.Field("quantity", func(b AddLineBody) int32 { return b.Quantity }, validate.Range[int32](1, 10_000)))
```

The rules are `NotBlank`, `NotNull`, `MinLength`, `MaxLength`, `Pattern` (whole string), `Range`,
`Size`, `OneOf`, `Each`, `Nested`, `Custom` (documents nothing) and `Ptr` (a nil pointer passes). All
errors are collected, not the first; the response is `400 VALIDATION` with `details` mapping each
field path (`lines[1].sku`) to `"<code>: <message>"`. The same rules become `minLength`, `pattern`,
`minimum`, `minItems`, `enum` and the rest in the document; where rules overlap the tighter bound
wins, an explicit pattern replaces `notBlank`'s, and a pattern RE2 accepts but ECMA-262 does not
(inline flags, `\pL`, `\A`, POSIX classes, named groups) is a problem: use `Custom`.

## The emitter and the type table

The emitter walks the types the operations name once, with reflection at the JSON edge. `string`,
`bool`, `int32`, `int64` and `float64` are the contract's table rows; `time.Time` is `date-time`,
`[]byte` base64, `jsontext.Value` any JSON; slices, `map[string]V`; a struct is one component named
after its type (`Page[OrderSummaryView]` is `PageOrderSummaryView`) with `additionalProperties:
false`, a field required unless it is a pointer or `omitzero`/`omitempty`. `int`, unsigned, `float32`,
arrays, maps with other keys, pointer elements, embedded structs, unknown `json` options and types
that write their own JSON are refused with the path from the operation. A string type with
`EnumValues() []string` is an enum; a type with `JSONSchema() openapi.Schema` writes its own schema;
a slice type with `UniqueItems()` is a set. Registrations (`Declared.Schema`, `Name`, `Property`) cover
the rest, including `openapi.Union` (a discriminated union over struct variants) and `openapi.OneOf`.
Security schemes are contributed by the module that resolves the Context (`Declared.Security`);
`httpfw/sessionapi` contributes the session cookie and bearer scheme and the two session operations
(`getAuthSession`, `logout`) with their fixed components. Their handlers are the sessions milestone
(G5b); `sessionapi.Unavailable` binds both to `501 NOT_IMPLEMENTED` until then.

The output is deterministic (paths and components sorted, fixed key order, two-space JSON as
`JSON.stringify(doc, null, 2)` plus a newline) and no input order changes a byte. Problems are
collected: a name collision, a constraint on the wrong kind of value, a missing `Info`, an operation
that requires a context when no scheme was contributed.

## The `openapi` command

```
orders-demo openapi                      # the document on standard output
orders-demo openapi --out web/openapi.json
```

It is a *declaration-phase command* (`Declarer.DeclarationCommand`): `Launch` runs it right after
the `Declare` functions, so it needs no configuration file, environment, database or network and
never calls the build function. Problems go to standard error with exit code 1; any other argument
prints the usage and exits 2. The orders demo commits the file as `web/openapi.json`, and a test
fails when it is stale (the drift check of the contract's section 9); the TypeScript client is
generated from it by the shared UI package.
