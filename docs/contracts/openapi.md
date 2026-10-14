# Contract: typed operations and the OpenAPI document

Status: written 2026-10-14, before any code, for the UI direction decided that day (Nuxt UI SPA as
the primary UI for the Java and Go frameworks; [`../handover.md`](../handover.md), "UI direction").
Nothing here is built. The **document rules** (sections 4 to 9) are language-neutral: the Java
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

- `OperationHandler<B, R>` is `Outcome<R> handle(OperationRequest<B> request) throws Exception`.
  `OperationRequest<B>` gives `body()` (decoded and validated), `path(PathParam<T>)`,
  `query(QueryParam<T>)`, `context()`, and `raw()` (the `Request`, for headers).
- `Outcome<R>` is a **sealed result** (house rule): `Outcome.Ok<R>` (the value, optional extra
  headers such as `Location` or `Set-Cookie`) or `Outcome.Failed<R>` (a `ServiceError`, so a
  `UseCaseError` passes straight through; `Outcome.failed(status, code, message)` builds one). Throwing `HttpException` still works and maps as before.
- Start-up problems, collected as usual: a declared operation that is not bound; a bound operation
  that was not declared; an operation bound twice; two operations with one id; the route rules of
  http contract section 2 (a bound operation is a route).
- A `Failed` outcome whose status is neither declared nor implied (section 6) is answered as given
  and logged once per operation and status at WARN ("status 409 is not in the document for
  createIngestion").
- **Responses of typed operations omit absent values**: a `@Nullable` record component that is
  `null` is left out of the JSON (Jackson `NON_NULL` on the typed-operation writer only; plain
  routes and `Response.json` keep writing `null`, unchanged). This matches the Go emitter
  (`omitzero`), the session JSON (sessions contract section 6.1) and the document (section 5).

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

**Refused** (the dump fails, naming the type and the path from the operation to it, for example
`createIngestion.body.source.Sftp.every`): `Optional` (house rule: never a field), `Object`, raw or
wildcard generic types, a `Map` whose key is not `String`, arrays of primitives other than
`byte[]` (and `byte[]`, which Jackson writes as base64, is `{"type": "string", "contentEncoding":
"base64"}`), classes that are not records, enums or registered value types, and any type with a
Jackson annotation the emitter does not understand (5.5).

### 5.2 Records

- One component per record type. **Name:** the simple name; a nested record is prefixed by its
  enclosing types (`IngestionView.Route` is `IngestionViewRoute`); a generic record is followed by
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

One component per type: two operations whose validators give the same type different constraints
is a dump problem (give each its own body type, or share the validator). A type used both as a
request body and in a response carries the body's constraints in both places (data the server
produces is valid data).

**Schema keyword order** (for section 4.1): `$ref`, `type`, `const`, `enum`, `format`,
`contentEncoding`, `pattern`, `minLength`, `maxLength`, `minimum`, `maximum`, `items`, `minItems`,
`maxItems`, `uniqueItems`, `properties`, `required`, `additionalProperties`, `oneOf`,
`discriminator`, `description`.

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

An operation that `requiresContext()` gets `security` listing every contributed scheme (each as its
own alternative); a public operation gets none. An operation that requires a context when no
scheme was contributed is a dump problem ("say how callers authenticate").

## 8. The framework's own operations

- `framework-auth` declares **`GET /auth/session`** (`getAuthSession`) and **`POST /auth/logout`**
  (`logout`) as operations, with the fixed component names and shapes in the sessions contract
  section 4.5, so `@fw/api-runtime` calls them through a generated client (the platform's `/auth/*`
  surface, hand-typed and outside its spec, is the counter-example). Both are public in the
  document (no `security`): they answer without a session.
- `/auth/login` and `/auth/callback` are browser navigations, never fetched: not in the document.
- Health, metrics, receivers, the SPA's files and Datastar streams are not operations.

## 9. Dumping the document

- **Core amendment (core contract section 5):** a module may claim a **declaration-phase command**
  (Java: `app.command(DeclarationCommand)`, a name and `int run(Declarations, List<String> args,
  PrintStream out)`). `AppBuilder.launch(args)` runs it **after step 1 (register) only**: no
  configuration is loaded or validated, no service is built, nothing connects anywhere.
  `Declarations` exposes the registered contributions and declared settings, read-only. A
  `Provider` or `boot` is never called.
- `framework-http` claims **`openapi`**: `java -jar app.jar openapi` writes the document to standard
  output; `openapi --out web/openapi.json` writes the file. Every dump problem is collected and
  printed, exit code 1. Go: the same command name and output (`<app> openapi`), run in Go's declare
  phase.
- **Drift in the Maven build (Java):** the framework's test helper
  `OpenApiTesting.assertCommitted(appBuilder, Path.of("web/openapi.json"))` regenerates the document
  in a unit test and fails with a diff when the committed file differs, so `./mvnw verify` catches a
  stale document without Node. The TypeScript side has its own drift check (section 11).

## 10. One document for Java and Go

The two emitters must produce comparable documents, so a SPA written against one backend's document
runs against the other's when the operations are the same:

- the rules of sections 4 to 8 are shared, including the output formatting;
- **conformance vector** `conformance/openapi/`: a fixture API (a handful of operations covering
  every row of the 5.1 table, a sealed interface, a generic page, each rule of 5.4, each implied
  status, both security cases) described in its `README.md`, and `fixture.json`, the expected
  document. The Java tests write it (`-Dconformance.update=true`); Go's emitter must reproduce it
  **byte for byte**. `conformance/openapi/auth.json` holds the two operations of section 8 and their
  components, which `@fw/api-runtime` is generated from;
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
import { defineConfig } from "@hey-api/openapi-ts";
export default defineConfig({
  input: "openapi.json",
  output: { path: "src/api" },
  plugins: [
    { name: "@hey-api/typescript", enums: "javascript" },  // literal unions; no TypeScript enums (erasableSyntaxOnly)
    "@hey-api/client-fetch",
    { name: "@hey-api/sdk", validator: false },            // one typed function per operation
    { name: "zod", compatibilityVersion: 4 },              // schemas for UForm (Standard Schema)
  ],
});
```

- **Committed:** `web/openapi.json` and `web/src/api/` (generated). The SPA imports only the
  generated SDK for API calls; `@fw/api-runtime` configures its `client-fetch` instance (same
  origin, `credentials: "same-origin"`, the `Error` envelope as the typed error, `401` to sign-in,
  validation details onto form fields). An ESLint rule forbids other `fetch` wrappers.
- **Zod:** the plugin's schemas (zod 4) are passed to Nuxt UI's `UForm`; they carry the section 5.4
  constraints, so the browser checks what the server will check. The SDK's run-time response
  validation stays off, except in the end-to-end tests, where a response that does not match the
  document fails the test.
- **Drift check in CI:** after the Java (or Go) build has checked `openapi.json` (section 9), the web
  build runs `openapi-ts` and fails on `git diff --exit-code web/src/api`; then `vite build` and
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

## 13. Rulings taken here (owner may reverse)

1. **The framework's own emitter**, deriving schemas from records, enums and sealed interfaces, not a
   library (swagger-core's model resolver or a JSON Schema generator): the house rules (values are
   records, no annotations for wiring) keep the type walk small, and a library would bring its own
   annotation vocabulary and defaults. The Go design took the same decision (Go decision 18).
2. **Declared in `register`, bound in `boot`**, and the declaration-phase command in core, so the
   document is dumped without configuration or a database.
3. **`Outcome<R>` as the handler's result** (a sealed type) instead of returning `Response`.
4. **`@Nullable` means "may be absent"**; typed operations omit null values; no three-state values.
5. **Closed schemas** (`additionalProperties: false`) for request and response records.
6. **`BigDecimal` as a string** on the wire of typed operations.
7. **Constraints only from the Validator**; a custom rule documents nothing.
8. **Security schemes contributed by the resolving module**; a context operation without one fails
   the dump.
9. **Byte-identical documents across Java and Go** for the fixture API, rather than "equivalent
   after normalisation".
