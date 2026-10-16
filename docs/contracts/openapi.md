# Contract: typed operations and the OpenAPI document

Status: written 2026-10-14, before any code, for the UI direction decided that day (Nuxt UI SPA as
the primary UI for the Java and Go frameworks; [`../handover.md`](../handover.md), "UI direction").
*Built in Java with MO (2026-10-14); amendments found by the build are marked (MO). Built in Go with G3 (2026-10-15, `httpfw/openapi`, `httpfw/validate`): the three vectors of section 10 pass byte for byte; Go's differences are listed in [`../go/milestones.md`](../go/milestones.md) under "G3 as built" (a pointer field needs `omitzero`; `int`, unsigned and `float32` are refused; the declaration is `Declared.Operations` and the command a `fw` declaration-phase command).* The **document rules** (sections 4 to 9) are language-neutral: the Java
emitter (`framework-http`) and the Go emitter (`httpfw`, [`../go/capabilities.md`](../go/capabilities.md)
section 5, decision 18) produce the same document for the same API, checked by a shared conformance
vector (section 10). The **declaration API** (sections 2 and 3) is described in its Java shape; Go's
shape is in the Go capabilities page and follows the same rules.

This contract amends http contract section 11, which listed OpenAPI generation as out of scope, and
core contract section 5 (a declaration-phase command, section 9 below).

## 1. Shape

```
operations declared in code (descriptors: method, pattern, id, parameters, body, response, errors, security)
   -> bound to handlers when the module boots; the router serves them like any route
   -> the emitter writes an OpenAPI 3.1 document from the descriptors and the Java records they name
      (the `openapi` command, registration phase only: no configuration, no database, no network)
   -> the application commits it (web/openapi.json); a test fails the Maven build when it is stale
   -> @hey-api/openapi-ts generates the TypeScript types, one SDK function per operation and Zod schemas
   -> the SPA calls the SDK; Nuxt UI's UForm validates with the Zod schema of the request body
   -> the server decodes and validates again (http contract section 7) and stays the authority
```

**Declared in code** (owner ruling 2026-10-13: behaviour in code, no YAML; house rule: no annotation
scanning for wiring). Operations are values the application writes; the document is a by-product
and is never edited by hand. There is no spec-first generation and no runtime validation of
responses against the document.

**Typed operations are an addition**, not a replacement: plain `Routes.get(...)` routes keep working
and are not in the document (receivers, health, metrics, the SPA's files, login redirects, Datastar
streams, application routes that are not JSON).

## 2. Operations (Java)

```java
// Values, written once (names are a sketch; final at build).
static final PathParam<String> ID = PathParam.string("id");
static final QueryParam<@Nullable String> CLIENT = QueryParam.string("clientId").optional();

static final Operation<NoBody, IngestionList> LIST =
    Operation.get("/api/v1/ingestions", IngestionList.class)
        .id("listIngestions").summary("List ingestions").tag("ingestions")
        .query(CLIENT).requiresContext().permission(MANAGE)
        .build();

static final Operation<IngestionBody, IngestionView> CREATE =
    Operation.post("/api/v1/ingestions", IngestionBody.V, IngestionView.class)   // body type from the Validator
        .id("createIngestion").summary("Create an ingestion").tag("ingestions")
        .status(201).requiresContext().permission(MANAGE)
        .error(409, "CODE_TAKEN", "the client already has an ingestion with that code")
        .build();
```

| Part | Rule |
|---|---|
| method and pattern | as http contract section 2 (`GET PUT POST PATCH DELETE`; `{name}` segments). `HEAD` and `OPTIONS` are not operations (the router answers them) |
| `id` | required; `[a-z][A-Za-z0-9]*` (it becomes the TypeScript function name); unique in the application |
| `summary`, `description`, `tag`s | optional text; tags `[a-z][a-z0-9-]*` |
| path parameters | every `{name}` in the pattern is declared with a type (`PathParam.string`, `.integer`, `.longInt`, `.enumOf(Class)`); a declared name missing from the pattern, or a pattern name not declared, is a start-up problem |
| query parameters | declared with a type; required unless `.optional()` (then `@Nullable`) or `.defaultValue(v)`; `.list()` for a repeated parameter (`?status=A&status=B`) |
| request body | given by a `Validator<B>` (http contract section 7), so decoding, validation and the schema have one source; `GET` and `DELETE` operations have none (`NoBody`) |
| response | a record class, `NoBody` (then the status is `204`), or a generic record through a type token (`new TypeRef<CursorPage<IngestionView>>() {}`); never a `Map`, a bare list or a primitive at the top level |
| `status` | the success status, one per operation: `200` by default, `201`, `202` or `204` (`204` if and only if the response is `NoBody`) |
| `error(status, code, message)` | an expected failure; documents the status (and the code in its description). Repeatable |
| `requiresContext()` | as `RouteOptions.requiresContext()`; documents the security schemes (section 7) and the statuses section 6 implies |
| `permission(p)` | the permission the `Context` must hold (core contract section 3 matching); enforced by `framework-http` with `Context.hasPermission` (`403 FORBIDDEN`, the same answer as `Auth.require`); implies `requiresContext()`; emitted as `x-fw-permission` |

*Amended by the build (MO):*

- Factories, as built: `Operation.get(pattern, Class|TypeRef)`, `delete(...)` the same, `post`,
  `put`, `patch(pattern, Validator, Class|TypeRef)`, and a body-less `post(pattern, Class|TypeRef)`
  (logout): a body sent to a body-less operation is not read. `id`, `summary`, `description`,
  `tag(...)`, `path(...)`, `query(...)`, `status`, `requiresContext()`, `permission`, `error`, `build()`.
- `build()` never throws for a descriptor mistake (a missing or malformed id, a bad tag, a malformed
  pattern, a path parameter missing from the pattern or a pattern name not declared, a parameter
  declared twice, a success status other than 200, 201, 202 and 204, `204` without `NoBody` or
  `NoBody` with another status, an error status outside 400 to 599, an error code not
  `[A-Z][A-Z0-9_]*`, an error declared twice or without a message, a blank permission). They are
  listed by `Operation.problems()` and reported as start-up problems when the route table is
  compiled and as dump problems by the `openapi` command, so a static field never fails class
  initialisation.
- Parameters: `PathParam.string|integer|longInt(name)`, `PathParam.enumOf(name, Class)`;
  `QueryParam.string|integer|longInt|bool(name)`, `QueryParam.enumOf(name, Class)`, then at most one
  of `.optional()` (`QueryParam<@Nullable T>`), `.defaultValue(v)` (documented as the schema's
  `default`) or `.list()` (`QueryParam<List<T>>`, empty when absent). Query names match
  `[A-Za-z][A-Za-z0-9_.-]*`. A missing required value is `REQUIRED: is required`, a bad one `INVALID:
  ...`, all parameter errors in one `400 VALIDATION`.
- The response may also be a sealed interface (a union), not only a record.

## 3. Declaring and binding (Java)

Operations are **declared in `register`** (so the document needs no service, configuration or
database) and **bound in `boot`** (where the services exist):

```java
// register(AppBuilder app)
OpenApi.declare(app, LIST, CREATE, READ, CHANGE, REMOVE);
OpenApi.info(app, "ingest-control-plane", "1");      // once per application: title and version

// boot(App app)
routes.bind(CREATE, request -> {
  String id = ids.generate("ing");
  return switch (store.create(id, request.body())) {
    case TAKEN -> Outcome.failed(409, "CODE_TAKEN", "the client already has an ingestion with that code");
    case DONE -> Outcome.ok(store.find(id).orElseThrow());
  };
});
```

*Amended by the build (MO):* binding is `Routes.bind(operation, handler)` or `bind(operation,
handler, RouteOptions)` for route middleware (options that require a context on an operation that
does not are a start-up problem: the document would call it public). The HTTP module's `Routes` is
given every declared operation (`Routes.declare`, from `OpenApi.operations(lookup)`); a table built by
hand in a test (`new Routes()`) that is never given declarations does not check that bound
operations were declared. Order of the checks on a request: the permission (`403`), then every
parameter (one `400`), then the body (`415`, `400`), then the handler, so a caller without the
permission learns nothing about the body rules.

- `OperationHandler<B, R>` is `Outcome<R> handle(OperationRequest<B> request) throws Exception`.
  `OperationRequest<B>` gives `body()` (decoded and validated), `path(PathParam<T>)`,
  `query(QueryParam<T>)`, `context()`, and `raw()` (the `Request`, for headers).
- `Outcome<R>` is a **sealed result** (house rule): `Outcome.Ok<R>` (the value, optional extra
  headers such as `Location` or `Set-Cookie`) or `Outcome.Failed<R>` (a `ServiceError`, so a
  `UseCaseError` passes straight through; `Outcome.failed(status, code, message)` builds one). Throwing `HttpException` still works and maps as before.
  *As built (MO):* `Outcome.ok(value)` returns `Outcome.Ok`, whose `header(name, value)` appends (two
  `Set-Cookie`s are fine); `Outcome.noContent()` for a `NoBody` response; `Outcome.failed(error)` and
  `failed(status, code, message[, details])` (status 400 to 599).
- Start-up problems, collected as usual: a declared operation that is not bound; a bound operation
  that was not declared; an operation bound twice; two operations with one id; the route rules of
  http contract section 2 (a bound operation is a route).
- A `Failed` outcome whose status is neither declared nor implied (section 6) is answered as given
  and logged once per operation and status at WARN ("status 409 is not in the document for
  createIngestion"); *as built (MO):* once per process.
- **Responses of typed operations omit absent values**: a `@Nullable` record component that is
  `null` is left out of the JSON (Jackson `NON_NULL` on the typed-operation writer only; plain
  routes and `Response.json` keep writing `null`, unchanged). This matches the Go emitter
  (`omitzero`), the session JSON (sessions contract section 6.1) and the document (section 5).
  *As built (MO):* the typed-operation mapper is `Json.operationMapper()`: `Json.mapper()`'s
  strictness, `NON_NULL` for record components, `BigDecimal` written as a string (read from a string
  or a number), `Duration` as ISO-8601, and the context id types (`ClientId`, `PrincipalId`,
  `ApplicationCode`, `CorrelationId`) as plain strings (Jackson would otherwise write `{"value":
  ...}`, which the document does not describe).

## 4. The document

OpenAPI **3.1.0**, JSON. Top level, in this order: `openapi` (`"3.1.0"`), `info` (`title`,
`version` from `OpenApi.info`; a missing `info` is a dump problem), `paths`, `components`
(`schemas`, `responses`, `securitySchemes`, in that order), `tags` (only when an operation has a tag: `{"name": ...}` sorted by
name). No `servers` (the SPA calls its own origin), no `jsonSchemaDialect`, no `webhooks`.

An operation object, in this order: `operationId`, `summary`, `description`, `tags`, `parameters`,
`requestBody`, `responses`, `security`, `x-fw-permission`; absent parts are left out.

- `parameters`: path parameters in pattern order, then query parameters in declaration order; each
  `{"name", "in", "required", "schema"}` (path parameters always `required: true`); a list query
  parameter has `"style": "form", "explode": true` and an array schema.
- `requestBody`: `{"required": true, "content": {"application/json": {"schema": {"$ref": ...}}}}`.
- `responses`: the success status with `{"description": "OK"|"Created"|"Accepted"|"No Content",
  "content": {"application/json": {"schema": ...}}}` (no `content` for `204`), then each error
  status ascending (section 6), then `default`.

Example (one operation, abridged):

```json
"/api/v1/ingestions/{id}": {
  "get": {
    "operationId": "getIngestion",
    "summary": "Read an ingestion",
    "tags": ["ingestions"],
    "parameters": [
      {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}}
    ],
    "responses": {
      "200": {"description": "OK", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/IngestionView"}}}},
      "400": {"$ref": "#/components/responses/Error"},
      "401": {"$ref": "#/components/responses/Error"},
      "403": {"$ref": "#/components/responses/Error"},
      "404": {"$ref": "#/components/responses/Error"},
      "default": {"$ref": "#/components/responses/Error"}
    },
    "security": [{"session": []}, {"bearer": []}],
    "x-fw-permission": "ingestor:admin:ingestion:manage"
  }
}
```

### 4.1 Deterministic output

The same declarations produce the same bytes, on every machine and in both implementations:

- UTF-8, `\n` line ends, a final newline; two-space indentation; `"key": value` with one space after
  the colon (the output of JavaScript's `JSON.stringify(document, null, 2)` plus `\n`); non-ASCII
  characters written as they are; `<`, `>`, `&` and `/` not escaped.
- Fixed key order for every OpenAPI object (as listed in this section); `paths` sorted by the path
  string (UTF-8 byte order); in a path item, methods in the order `get, put, post, delete, patch`;
  `components.schemas` sorted by name; schema keywords in the order of section 5.4; `properties` in
  declaration order (record component order in Java, field order in Go); `required` in the same order
  as `properties`; `enum` values in declaration order; `discriminator.mapping` sorted by key;
  `security` with `session`, then `bearer`, then other schemes sorted by name.
- Nothing from the build or the clock (no timestamps, no git hashes, no host names).

## 5. Schemas from types

The emitter walks the types reachable from the declared operations **once, at the JSON edge**.
Reading record components and the generic types of their accessors is reflection, allowed there
for the same reason Jackson uses it; it is not wiring and never scans the classpath.

### 5.1 Type table

| Java | JSON Schema | TypeScript (hey-api) | Go (for comparison) |
|---|---|---|---|
| `String` | `{"type": "string"}` | `string` | `string` |
| `boolean` / `Boolean` | `{"type": "boolean"}` | `boolean` | `bool` |
| `int` / `Integer` | `{"type": "integer", "format": "int32"}` | `number` | `int32` |
| `long` / `Long` | `{"type": "integer", "format": "int64"}` | `number` (exact only below 2^53: ids are strings) | `int64` |
| `double` / `Double` | `{"type": "number", "format": "double"}` | `number` | `float64` |
| `BigDecimal` | `{"type": "string", "format": "decimal"}`; the typed-operation writer writes it as a JSON string and reads a string or a number | `string` | a decimal type with a `SchemaProvider` |
| `Instant` | `{"type": "string", "format": "date-time"}` (ISO-8601 UTC, as Jackson writes it with `WRITE_DATES_AS_TIMESTAMPS` off) | `string` | `fw.Instant` |
| `LocalDate` | `{"type": "string", "format": "date"}` | `string` | `civil.Date`-like value type |
| `Duration` | `{"type": "string", "format": "duration"}` (ISO-8601 `PT5M`: Jackson 3.2's default, `WRITE_DURATIONS_AS_TIMESTAMPS` off) | `string` | a duration value type |
| `UUID` | `{"type": "string", "format": "uuid"}` | `string` | `uuid.UUID` |
| an `enum` | `{"type": "string", "enum": [names in declaration order]}` | a literal union (`enums: "javascript"` gives an `as const` object) | a string type with `EnumValues()` |
| a record | `{"$ref": "#/components/schemas/<Name>"}` (5.2) | an object type | a struct |
| a sealed interface | `{"$ref": ...}` to a `oneOf` component (5.3) | a union | an interface with registered variants |
| `List<T>`, `Collection<T>` | `{"type": "array", "items": T}` | `Array<T>` | `[]T` |
| `Set<T>` | `{"type": "array", "items": T, "uniqueItems": true}` | `Array<T>` | `[]T` with a uniqueness rule |
| `Map<String, V>` | `{"type": "object", "additionalProperties": V}` | `Record<string, V>` (with `noPropertyAccessFromIndexSignature`) | `map[string]V` |
| `JsonNode` | `{}` | `unknown` | `jsontext.Value` |
| a framework value type (ids, `ClientId`) | its built-in schema (a `string`, with a `pattern` where the type has one) | `string` | its `SchemaProvider` |
| an application value type | a schema registered in code: `OpenApi.schema(app, Money.class, Schema.string().pattern("^-?[0-9]+\\.[0-9]{2}$"))` | per the schema | `SchemaProvider` |

*Amended by the build (MO):* the framework value types are `ClientId` (`{"type": "string",
"pattern": "^[A-Za-z0-9._-]{1,100}$"}`), `ApplicationCode` (`"pattern": "^[a-z][a-z0-9-]*$"`),
`PrincipalId` and `CorrelationId` (`"pattern": "\\S", "minLength": 1`, the non-blank rule). A
registered schema that is a union (`Schema.oneOf(...)`) or an object is a component under the type's
name; any other registered schema is written inline wherever the type appears. Jackson annotations
on a registered type are not checked (the registration is the application's word for what Jackson
writes; a `Money` record with `@JsonValue` is the example). `float`, `short`, `byte` and `char` are not
in the table and are refused.

**Refused** (the dump fails, naming the type and the path from the operation to it, for example
`createIngestion.body.source.Sftp.every`): `Optional` (house rule: never a field), `Object`, raw or
wildcard generic types, a `Map` whose key is not `String`, arrays of primitives other than
`byte[]` (and `byte[]`, which Jackson writes as base64, is `{"type": "string", "contentEncoding":
"base64"}`), classes that are not records, enums or registered value types, and any type with a
Jackson annotation the emitter does not understand (5.5).

### 5.2 Records

- One component per record type. **Name:** the simple name; a nested record is prefixed by its
  enclosing types (`IngestionView.Route` is `IngestionViewRoute`). *Amended (MO):* only enclosing
  records, interfaces and enums count; an enclosing class that is none of those (a holder of API
  types such as `OrdersApi`) adds nothing, so `OrdersApi.OrderView` is `OrderView`, the name a Go
  package-level type would have. The name `Error` is the framework's envelope; a type named so is a
  name collision. A recursive record (`Node(List<Node> children)`) is one component; a generic record is followed by
  its type arguments' names (`CursorPage<IngestionView>` is `CursorPageIngestionView`). Two types
  that produce one name is a dump problem (rename one, or register a name in code:
  `OpenApi.name(app, Type.class, "OtherName")`).
- `{"type": "object", "properties": {...}, "required": [...], "additionalProperties": false}`:
  property names are the component names (Jackson's default for records); a component is
  **required** unless it is `@Nullable` (JSpecify, runtime-retained); primitives are always
  required.
- **`@Nullable` means "may be absent"**, never "may be `null`": the property is not required and its
  schema is not nullable (TypeScript `x?: T`; with `exactOptionalPropertyTypes` absent and
  `undefined` are distinct). Typed operations omit it when null (section 3); on input Jackson still
  accepts an explicit `null` for it, which the document does not advertise. A three-state value
  (absent, null, a value) is **not supported** in this version; Go does not emit `Nullable[T]`
  either.
- `additionalProperties: false` on every record schema, request and response alike: the framework's
  decoder refuses unknown properties (http contract section 7), so a request schema without it would
  be wrong, and the SPA and its document ship together, so a closed response type costs nothing.
  (The platform's huma output had the same closed request schemas, and its SPA sent supersets that
  were refused; the generated, fully typed client of section 11 is what prevents that here.)
- `@JsonIgnore`-free public methods named like getters (`getX()`, `isX()`) on a record are a dump
  problem: Jackson would serialise them as properties the walk does not see.
- *Added (MO):* `OpenApi.property(app, Record.class, "component", schema)` replaces one property's
  schema (whether it is required still follows `@Nullable`), for a property with a fixed value
  (`Schema.bool().constant(true)`), used by the session components (sessions contract 4.5). An
  override naming no component is a dump problem. `@JsonIgnore` on a record component is a dump
  problem (Jackson would drop the property).

### 5.3 Sealed interfaces

A sealed interface reachable from an operation must carry `@JsonTypeInfo(use = NAME, include =
PROPERTY, property = "<name>")` and `@JsonSubTypes` naming **exactly** its permitted subtypes (the
form the first application's `Source`, `Split` and `DispatchTarget` already use); anything else is
a dump problem, because Jackson's wire form and the document would disagree.

```json
"Source": {
  "oneOf": [{"$ref": "#/components/schemas/SourceDatabasePoll"}, {"$ref": "#/components/schemas/SourceHttp"}],
  "discriminator": {"propertyName": "kind", "mapping": {"database-poll": "#/components/schemas/SourceDatabasePoll", "http": "#/components/schemas/SourceHttp"}}
}
```

- `oneOf` in the order of `discriminator.mapping`'s sorted keys; each variant is a record component
  named `<Interface><Variant>` whose **first** property is the discriminator,
  `{"type": "string", "const": "<name>"}`, required.
- The interface's own `@JsonIgnore` default methods (for example `Source.singleOwner()`) are not
  properties and are ignored.
- *Amended by the build (MO):* every `@JsonSubTypes.Type` needs an explicit `name` (Jackson's default
  for a nested class is not portable); `names` aliases, `defaultImpl` and `visible = true` are dump
  problems; every permitted subtype must be a record; a variant component with the discriminator's
  name is a dump problem. A variant record used on its own (as a response) is still the variant
  component `<Interface><Variant>` with the discriminator first, because Jackson inherits the type
  id from the interface. A sealed interface with a registered `Schema.oneOf(...)` needs no Jackson
  annotations and is documented without a `discriminator` (the session answer).

### 5.4 Constraints from the Validator

The request body's `Validator` (http contract section 7) is the only source of constraints; they are
merged into the body type's component and its nested types (`nested(...)`). `Rule` gains a way to
describe itself (Java: a default method returning a constraint, empty for `custom`); the standard
rules map as follows:

| Rule | Schema |
|---|---|
| `notNull` | none (required-ness comes from the type: a `@Nullable` component the validator requires is documented as optional; the server refuses it with `400`) |
| `notBlank` | `"minLength": 1, "pattern": "\\S"` |
| `minLength(n)`, `maxLength(n)` | `minLength`, `maxLength` |
| `pattern(regex)` | `"pattern": "^(?:regex)$"` (Java's `matches` is whole-string; JSON Schema patterns are not anchored). A regex using constructs ECMA-262 lacks (inline flags `(?i)`, possessive quantifiers, `\A`, `\Z`, `\z`, `\h`, `\p{Java...}`) is a dump problem: use `custom` instead, which documents nothing |
| `range(min, max)` | `minimum`, `maximum` |
| `size(min, max)` | `minItems`, `maxItems` |
| `oneOf(values)` | `enum` |
| `each(rule)` | the rule's constraint on `items` |
| `custom(...)` | none |

*Amended by the build (MO):* when rules overlap, the tighter bound wins (`minLength`, `minimum`,
`minItems` take the largest, the maxima the smallest); an explicit `pattern` replaces `notBlank`'s
`\S` (its `minLength: 1` stays; a pattern that accepts only whitespace is then under-documented); two
explicit patterns, or `oneOf` with a value outside an enum's constants, are dump problems; `oneOf` on an
enum narrows its `enum`; a rule that does not fit the value (`oneOf` on a list) is a dump problem.
The ECMA refusal list, as built: inline flags `(?i)`, atomic groups `(?>`, possessive quantifiers,
`\A \Z \z \G \h \H \v \V \R \X \Q \E \e \a \N`, every `\p{...}`/`\P{...}` (ECMA reads them only with the
`u` flag, which a browser validator may not set), `\x{...}`, a nested class or `&&` in a class, a
class starting with `]`, and a pattern compiled with flags. Validator fields that are not record
components are dump problems. Constraints are compared as documented: `custom` and `notNull` rules do
not make two validators differ.

One component per type: two operations whose validators give the same type different constraints
is a dump problem (give each its own body type, or share the validator). A type used both as a
request body and in a response carries the body's constraints in both places (data the server
produces is valid data).

**Schema keyword order** (for section 4.1): `$ref`, `type`, `const`, `enum`, `format`,
`contentEncoding`, `pattern`, `minLength`, `maxLength`, `minimum`, `maximum`, `items`, `minItems`,
`maxItems`, `uniqueItems`, `properties`, `required`, `additionalProperties`, `oneOf`,
`discriminator`, `description`. *Amended (MO):* `default` (a query parameter's default value) comes
before `description`. A record with no required component has no `required` keyword.

### 5.5 Jackson annotations

The emitter must describe what Jackson does, so it accepts only: `@JsonTypeInfo` and
`@JsonSubTypes` on sealed interfaces (5.3) and `@JsonIgnore` on non-component methods. Any other
Jackson annotation on a reachable type, record component or accessor (`@JsonProperty`,
`@JsonInclude`, `@JsonFormat`, `@JsonUnwrapped`, `@JsonValue`, `@JsonCreator`, `@JsonAlias`, ...) is
a dump problem naming it; the fix is to remove it or to register a schema for the type in code. A
test in the emitter suite serialises a sample of every row of the 5.1 table with the
typed-operation writer and validates the JSON against the emitted schema, so the table cannot drift
from Jackson.

## 6. Errors

One shared component, the http contract's envelope:

```json
"Error": {
  "type": "object",
  "properties": {
    "error": {"type": "string"},
    "message": {"type": "string"},
    "details": {"type": "object", "additionalProperties": {"type": "string"}}
  },
  "required": ["error", "message"],
  "additionalProperties": false
}
```

and one shared response, `components.responses.Error`: `{"description": "Error", "content":
{"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}}`. Every operation lists,
each pointing at it:

| Status | Listed when |
|---|---|
| `400` | the operation has a body or any parameter (`VALIDATION`, `INVALID_JSON`) |
| `401` | `requiresContext()` (`UNAUTHENTICATED`) |
| `403` | `requiresContext()` (`FORBIDDEN`, `CLIENT_NOT_PERMITTED`, `CROSS_ORIGIN_REQUEST`) |
| `413`, `415` | the operation has a body |
| each `error(status, ...)` | declared; the codes are listed in the response's `description` (`"CODE_TAKEN: the client already has an ingestion with that code"`), one line per code, sorted |
| `default` | always (everything else: `429`, `500`, `503`) |

*As built (MO):* a declared `error(status, ...)` on a status the shape already implies (for example
`403`) replaces that status's `$ref` with the described response. A response object for a declared
status is `{"description": "<CODE>: <message>" lines joined with "\n", "content": {...Error...}}`.

`error` stays a `string`, not an enum: use-case codes are an open set. hey-api types the SDK's
`error` result as `Error`, so the SPA never handles `unknown`.

## 7. Security schemes

The schemes are **contributed by the module that resolves the `Context`**, because only it knows how
callers authenticate:

- `framework-auth` contributes `"session": {"type": "apiKey", "in": "cookie", "name":
  "__Host-fw_session"}` (the production name always, whatever the environment) and, when bearer
  tokens are accepted, `"bearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}`;
- an application's own resolver contributes its own (Java: `OpenApi.securityScheme(app, "basic",
  SecurityScheme.httpBasic())`), for example the first application's fixed token and basic login.

*As built (MO):* `SecurityScheme.cookie(name)`, `header(name)`, `bearer(format)` and `httpBasic()`;
scheme names match `[A-Za-z][A-Za-z0-9_-]*`, and one name contributed twice is a dump problem.
`components.securitySchemes` is left out when nothing was contributed.

An operation that `requiresContext()` gets `security` listing every contributed scheme (each as its
own alternative); a public operation gets none. An operation that requires a context when no
scheme was contributed is a dump problem ("say how callers authenticate").

## 8. The framework's own operations

- `framework-auth` declares **`GET /auth/session`** (`getAuthSession`) and **`POST /auth/logout`**
  (`logout`) as operations, with the fixed component names and shapes in the sessions contract
  section 4.5, so `@flowcatalyst/api-runtime` calls them through a generated client (the platform's `/auth/*`
  surface, hand-typed and outside its spec, is the counter-example). Both are public in the
  document (no `security`): they answer without a session.
- `/auth/login` and `/auth/callback` are browser navigations, never fetched: not in the document.
- Health, metrics, receivers, the SPA's files and Datastar streams are not operations.
- *Built (MO):* with summaries `Read the browser session` and `Sign out of the application` and the
  tag `auth`; `logout` declares `403 CROSS_ORIGIN_REQUEST` and `415 UNSUPPORTED_MEDIA_TYPE`.

## 9. Dumping the document

- **Core amendment (core contract section 5):** a module may claim a **declaration-phase command**
  (Java: `app.command(DeclarationCommand)`, a name and `int run(Declarations, List<String> args,
  PrintStream out)`). `AppBuilder.launch(args)` runs it **after step 1 (register) only**: no
  configuration is loaded or validated, no service is built, nothing connects anywhere.
  `Declarations` exposes the registered contributions and declared settings, read-only. A
  `Provider` or `boot` is never called.
- `framework-http` claims **`openapi`**: `java -jar app.jar openapi` writes the document to standard
  output; `openapi --out web/openapi.json` writes the file. Every dump problem is collected and
  printed, exit code 1. *Amended by the build (MO):* `framework-http` has no module, so it provides
  the command (`OpenApi.command()`) and the HTTP server module (`JettyHttpModule`, id `http`) claims
  it: every application that serves HTTP has it. Any other argument prints the usage, exit code 2.
  Tools and tests get the declarations without a command through core's `AppBuilder.declarations()`
  (register only); `OpenApi.document(declarations)` returns `Written(json)` or `Refused(problems)`. Go: the same command name and output (`<app> openapi`), run in Go's declare
  phase.
- **Drift in the Maven build (Java):** the framework's test helper
  `OpenApiTesting.assertCommitted(appBuilder, Path.of("web/openapi.json"))` regenerates the document
  in a unit test and fails with a diff when the committed file differs, so `./mvnw verify` catches a
  stale document without Node. *As built (MO):* the same test run with `-Dopenapi.update=true`
  writes the file; a missing file fails with the command to write it. The TypeScript side has its own drift check (section 11).

## 10. One document for Java and Go

The two emitters must produce comparable documents, so a SPA written against one backend's document
runs against the other's when the operations are the same:

- the rules of sections 4 to 8 are shared, including the output formatting;
- **conformance vector** `conformance/openapi/`: a fixture API (a handful of operations covering
  every row of the 5.1 table, a sealed interface, a generic page, each rule of 5.4, each implied
  status, both security cases) described in its `README.md`, and `fixture.json`, the expected
  document. The Java tests write it (`-Dconformance.update=true`); Go's emitter must reproduce it
  **byte for byte**. `conformance/openapi/auth.json` holds the two operations of section 8 and their
  components, which `@flowcatalyst/api-runtime` is generated from; *added (MO):*
  `conformance/openapi/orders-demo.json`, a sample application (the Java `examples/orders-demo`
  document, byte-equal to its committed `web/openapi.json`);
- known modelling differences are absorbed by the rules: Java writes `@Nullable` absent, Go omits
  `omitzero` values, and both document "may be absent" (5.2); neither emits nullable types.

**Out of scope:** a test that a running API (this framework's, or the FlowCatalyst platform's Java
or Go implementation) agrees with a document it did not emit; validating requests or responses
against the document at run time; consuming a foreign document to generate server code.

## 11. The TypeScript client

Generated from the committed document by **`@hey-api/openapi-ts`** (pinned exactly: it is 0.x and
its output changes between minors), as the platform SPA does, but generating the **whole client**,
not types only:

```ts
// web/openapi-ts.config.ts
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";
export default fwOpenApiConfig({ input: "openapi.json", output: "src/api" });
```

`fwOpenApiConfig` (`ui/packages/api-runtime/src/openapi-ts.ts`) holds the settings, so every
application generates the same way. Its plugin list:

```ts
plugins: [
  { name: "@hey-api/typescript", enums: "javascript" },  // literal unions; no TypeScript enums (erasableSyntaxOnly)
  { name: "@hey-api/client-fetch", baseUrl: false },     // no generated base URL (below)
  { name: "@hey-api/sdk", validator: false },            // one typed function per operation
  { name: "zod", compatibilityVersion: 4,                // schemas for UForm (Standard Schema)
    "~resolvers": { number: int64AsNumber } },           // int64 stays a number (below)
],
```

Two settings the build found necessary (MU U2, [`../ui/u2.md`](../ui/u2.md); hey-api 0.99.0):

- **`@hey-api/client-fetch` with `baseUrl: false`.** The document has no `servers` (section 4: the
  SPA calls its own origin). Without the setting, hey-api reads a bare input such as `openapi.json`
  as a host and writes it as the client's base URL (`createConfig({ baseUrl: 'openapi.json' })`), so
  every call would go to `openapi.json/api/...` relative to the page (an input path with a slash in
  it does not trigger this). `@flowcatalyst/api-runtime` also sets `baseUrl: ""` when it configures
  the client at run time.
- **`int64` through the Zod plugin's number resolver.** For `format: int64` (and `uint64`) hey-api
  writes `z.coerce.bigint()` while the TypeScript type it writes is `number` (section 5's type
  table). The schema's output, which `UForm` hands to the submit handler, is then a BigInt, and
  `JSON.stringify` refuses it ("Do not know how to serialize a BigInt"). The resolver
  (`~resolvers.number`, hey-api's documented hook; `int64AsNumber` in the same file) writes
  `z.int()` with the declared bounds as plain numbers: `z.int().gte(0).lte(100000000)` instead of
  `z.coerce.bigint().gte(BigInt(0)).lte(BigInt(100000000))`. `z.int()` accepts safe integers only,
  which is section 5's rule (exact only below 2^53; ids are strings). Every other number keeps
  hey-api's default.

- **Committed:** `web/openapi.json` and `web/src/api/` (generated). The SPA imports only the
  generated SDK for API calls; `@flowcatalyst/api-runtime` configures its `client-fetch` instance (same
  origin, `credentials: "same-origin"`, the `Error` envelope as the typed error, `401` to sign-in,
  validation details onto form fields). An ESLint rule forbids other `fetch` wrappers.
- **Zod:** the plugin's schemas (zod 4) are passed to Nuxt UI's `UForm`; they carry the section 5.4
  constraints, so the browser checks what the server will check. The SDK's run-time response
  validation stays off, except in the end-to-end tests, where a response that does not match the
  document fails the test. Zod's output marks an optional property `?: T | undefined` while the generated
  type says `?: T`, so under `exactOptionalPropertyTypes` a parsed form value is not assignable to a request
  type: `exactOutput(...)` from `@flowcatalyst/api-runtime` (2026-10-16) removes the undefined-valued properties
  and types the result exactly, in place of an application cast.
- **Drift check in CI:** after the Java (or Go) build has checked `openapi.json` (section 9), the web
  build runs `openapi-ts` and runs the drift check (`fc-api-drift`, a bin of `@flowcatalyst/api-runtime`: `git status` on the
  folder, so added and deleted files count, or, without git, `fc-api-drift --run "openapi-ts" src/api`, which compares the folder's file hashes before
  and after the generator); then `vite build` and
  `vue-tsc --build`, so a renamed field, a removed operation or a changed enum is a type error in
  the pages that use it.
- The toolchain, lint and tsconfig rules are those of [`../go/ui.md`](../go/ui.md) sections 2, 3
  and 6, which now apply to the shared UI package and to every application's `web/`.

## 12. Tests (Java)

- descriptors: every start-up problem of sections 2 and 3; permission enforcement (`403 FORBIDDEN`);
  `Outcome` mapping (`Ok` with headers, `Failed` with a `UseCaseError`); omission of null components;
  the WARN for an undocumented status;
- the emitter: every row of 5.1 (and every refusal), naming and collisions, `@Nullable`, sealed
  interfaces (and the refusals for missing or mismatched `@JsonSubTypes`), generics, each rule of 5.4
  (and an ECMA-incompatible pattern), the Jackson agreement test of 5.5, implied statuses, security
  schemes, the deterministic output (two runs and a shuffled declaration order give the same
  bytes);
- the declaration-phase command: runs with no configuration file, no environment and no database
  reachable; exit code 1 with every problem listed;
- `OpenApiTesting.assertCommitted`: passes, and fails with a diff after a change;
- the conformance vectors of section 10;
- an end-to-end check in the example application: the generated client compiles under the UI's
  `tsconfig` and a Playwright run with response validation on passes against the real server.
  *Not built with MO (it needs the UI package, MU U3).* Checked by hand on 2026-10-14 instead:
  `@hey-api/openapi-ts` 0.93.1 (the platform SPA's pin) generated types, SDK and Zod 4 schemas from
  `fixture.json`; `tsc` 5.8 under `strict` and `exactOptionalPropertyTypes` accepts the generated
  types, SDK and Zod files and a discriminated-union switch over `Shape`; hey-api's own runtime files
  (`client/`, `core/`) do not compile under `exactOptionalPropertyTypes` (the UI package must exclude
  them from that flag), and its Zod plugin writes `int64` as `z.coerce.bigint()` while the type is
  `number` (to configure in the UI package). U0 pinned 0.99.0 and checked a hand-written document
  to these rules ([`../ui/u0.md`](../ui/u0.md)) with the same findings for the runtime files; the
  emitted documents have not been run through 0.99.0 yet (U3 does, from
  `examples/orders-demo/web/openapi.json`).
- *As built (MO):* the documents are validated in the Maven build against the OpenAPI Initiative's
  3.1 schema (`schema/2022-10-07`, Apache-2.0, vendored as a test resource) and each component against
  the JSON Schema 2020-12 meta-schema, with `com.networknt:json-schema-validator` as a test dependency;
  the Jackson agreement test validates the typed writer's JSON with the same library.

## 13. Rulings

Rulings 4, 5 and 6 were confirmed by the owner on 2026-10-14; the others were taken here and the
owner may reverse them.

1. **The framework's own emitter**, deriving schemas from records, enums and sealed interfaces, not a
   library (swagger-core's model resolver or a JSON Schema generator): the house rules (values are
   records, no annotations for wiring) keep the type walk small, and a library would bring its own
   annotation vocabulary and defaults. The Go design took the same decision (Go decision 18).
2. **Declared in `register`, bound in `boot`**, and the declaration-phase command in core, so the
   document is dumped without configuration or a database.
3. **`Outcome<R>` as the handler's result** (a sealed type) instead of returning `Response`.
4. **`@Nullable` means "may be absent"**; typed operations omit null values; no three-state values.
   *Owner, 2026-10-14: keep the split; plain routes and `Response.json` keep writing Jackson's
   `null`s (no wire change for existing routes).*
5. **Closed schemas** (`additionalProperties: false`) for request and response records. *Owner,
   2026-10-14: confirmed.*
6. **`BigDecimal` as a string** on the wire of typed operations. *Owner, 2026-10-14: confirmed.*
7. **Constraints only from the Validator**; a custom rule documents nothing.
8. **Security schemes contributed by the resolving module**; a context operation without one fails
   the dump.
9. **Byte-identical documents across Java and Go** for the fixture API, rather than "equivalent
   after normalisation".
