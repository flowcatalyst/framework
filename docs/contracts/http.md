# Contract: HTTP (server, routing, middleware, validation, errors, Datastar)

Status: M3. Owner ruling 2026-10-08: Jetty 12 (12.1.x, core handlers, no Servlet API).
Java names are given where the shape matters; the rules are language-neutral.

## 1. Modules and layering

| Module | Contents |
|---|---|
| `framework-http` | the contracts below (`Routes`, `Request`, `Response`, `Middleware`, `Validator`, `HttpErrors`, `ContextResolver`, `Datastar`) and the Jetty-free pieces: router, pipeline, validation, error mapping, the in-memory test client |
| `framework-http-jetty` | `JettyHttpModule`: the only code that imports `org.eclipse.jetty.*` |

Application code and other framework modules depend on `framework-http` only. The server is
swappable because nothing in `framework-http` mentions Jetty types. The router, pipeline and
error mapping are tested without a socket through `TestClient` (section 9); the Jetty module is
tested with real requests.

**Dependencies (MA item 8, 2026-10-10).** `framework-http` depends on `framework-core` and Jackson
only; `framework-http-jetty` adds Jetty. Neither may have `framework-usecase`, `framework-database`,
HikariCP or the PostgreSQL driver on its classpath in any scope; a Maven Enforcer
`bannedDependencies` execution (`http-only-classpath`, parent POM) fails the build otherwise.
Ruling taken (the plan offered two ways): instead of moving `UseCaseError` and `ErrorKind` to a new
module (a split package, or a package rename that breaks every application) or moving
`HttpErrors.of` to the use-case side (a use-case to HTTP dependency), core gained the interface
`ServiceError` (`httpStatus()`, `code()`, `message()`, `details()`), `UseCaseError` implements it,
and `HttpErrors.of` takes it. Source compatible: no package or class moved and
`HttpErrors.of(useCaseError)` compiles unchanged; binary incompatible for code compiled against the
old signature (pre-release, recompile). Build-level change for applications: one that relied on
`framework-http` bringing `framework-usecase` transitively must now declare it. The per-`ErrorKind`
mapping test moved to `framework-usecase` (`UseCaseErrorHttpTest`, `framework-http` in test scope).

## 2. Routes

A module declares routes in `boot` (never `register`) through a provided service `Routes`:

```java
routes.get("/orders/{id}", orders::show);
routes.post("/orders", orders::create);
routes.add(Method.PUT, "/orders/{id}", handler, RouteOptions.requiresContext());
```

- A `Handler` is `Response handle(Request request) throws Exception`.
- Methods: `GET HEAD POST PUT PATCH DELETE OPTIONS`. `HEAD` is answered from `GET` with the body
  dropped unless a `HEAD` route exists.
- Pattern: segments separated by `/`; a segment is a literal or `{name}` matching one non-empty
  segment (`name` is `[a-z][A-Za-z0-9]*`). No wildcards, no regex, no optional segments. A
  trailing slash is a different path from none (no normalisation); the router never redirects.
- Matching: literal segments beat parameters at the same position; the first difference decides
  (so `/orders/new` beats `/orders/{id}`). Percent-decoding happens per segment after splitting.
- Registration is allowed until the server starts listening; afterwards `IllegalStateException`.
- **Duplicate routes fail start-up**: same method and same shape (parameter names ignored, so
  `/a/{x}` and `/a/{y}` collide). The error names both registrants (module id and pattern).
  Reported as `StartupException` problems, collected, like core failures.
- No route for the path: `404 NOT_FOUND`. Path matches but not the method: `405
  METHOD_NOT_ALLOWED` with an `Allow` header listing the methods (sorted, `HEAD` included when
  `GET` is, `OPTIONS` always). An `OPTIONS` request to a known path with no `OPTIONS` route is
  answered `204` with `Allow`. There is no CORS handling (platform has none either).
- Route patterns are listed at start-up in the log at INFO, one line per route.

## 3. Request and response

`Request` (immutable view, no Jetty types): `method()`, `path()`, `pathParam(name)` (throws
`IllegalArgumentException` for an undeclared name), `query(name)` (first value or null),
`queries(name)`, `header(name)` (case-insensitive, first), `headers(name)`, `body()`
(`InputStream`, read at most once), `bodyBytes()` (reads and caches; limit section 8), `context()`
(section 6; throws `IllegalStateException` on a route without `requiresContext`),
`correlationId()`, `attribute(key)` / `attribute(key, value)` (typed keys, for middleware to pass
values on).

*Added by MA item 9:* `remoteAddress()` (`@Nullable`), the IP literal of the connection's peer as
the server adapter saw it (a proxy's address behind one); never taken from a header.

`Response` is a value built with static factories and fluent `header(name, value)`:
`Response.ok(body)`, `Response.json(status, object)` (Jackson 3, records and maps only),
`Response.text(status, string)`, `Response.noContent()`, `Response.html(status, string), Response.bytes(status, contentType,
bytes)`, `Response.stream(status, contentType, StreamBody)` (a callback that writes to an
`OutputStream`; used by Datastar). Default JSON content type `application/json`. Response
header names/values containing CR or LF fail with `IllegalArgumentException` at construction.

## 4. Middleware

One abstraction, shared later with jobs and commands, so it is **not** HTTP-specific:

```java
interface Middleware<I, O> { O handle(I input, Next<I, O> next) throws Exception; }
interface Next<I, O>       { O handle(I input) throws Exception; }
```

`Pipeline<I, O>` composes a list of middleware around a terminal handler; the first in the list
is outermost. It lives in `framework-core` (package `io.flowcatalyst.framework.pipeline`) with
its own tests, and `framework-http` uses `Pipeline<Request, Response>`. A middleware may
short-circuit by returning without calling `next`.

HTTP middleware is registered through `Routes.use(middleware)` (global, in registration order)
and `RouteOptions.with(middleware)` (per route, inside the global ones). The framework installs,
outermost first and not removable: **(1)** correlation id, **(2)** error mapping, **(3)** access
log (method, path template, status, duration, correlation id; never query strings or bodies).
Application middleware runs inside those. Context resolution (section 6) runs after global
middleware, before route middleware.

## 5. Errors

Every non-2xx response the framework produces has the platform's envelope, content type
`application/json`:

```json
{"error": "<CODE>", "message": "...", "details": {"k": "v"}}
```

`details` is omitted when empty. Values are strings (as `UseCaseError.details`).

| Situation | Status | `error` |
|---|---|---|
| malformed JSON, failed validation, bad parameter | 400 | `VALIDATION` (or the validator's code) |
| no / bad credentials (from a `ContextResolver`) | 401 | `UNAUTHENTICATED` |
| not allowed | 403 | the use case code |
| no route / not found | 404 | `NOT_FOUND` |
| method not allowed | 405 | `METHOD_NOT_ALLOWED` |
| body over the limit | 413 | `PAYLOAD_TOO_LARGE` |
| unsupported content type | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| uncaught exception | 500 | `INTERNAL` (message `internal error`; cause logged with the correlation id, never returned) |

Use-case results map by `ErrorKind` (write-path contract section 5): status from
`UseCaseError.httpStatus()`, `error` = the error's `code`, `message` = its message, `details` =
its details. Helper `HttpErrors.of(UseCaseError)` returns the `Response`. (As built after MA item 8:
`HttpErrors.of(ServiceError)`, the core interface `UseCaseError` implements; section 1.)

Handlers signal errors by returning `HttpErrors.…` responses or by throwing `HttpException(status,
code, message, details)`; the error-mapping middleware turns the exception into the envelope.
`ValidationException` (section 7) is a `HttpException` with status 400.

## 6. Context

`ContextResolver` is a provided service (optional): `Context resolve(Request request) throws
Exception`, throwing `HttpException(401, UNAUTHENTICATED, ...)` when it cannot. A route declared
`requiresContext()` gets `request.context()` populated by it. The framework supplies no real
resolver in M3 (M4 brings login); a test resolver is used in tests.

**Start-up fails** if any route requires a context and no `ContextResolver` is provided; the
problem names the route.

*Amended by M4c (2026-10-13):* the real resolver is `framework-auth`'s
([sessions-and-login contract](sessions-and-login.md) section 7): bearer tokens and the session
cookie, the CSRF rules, and the rule that turns a `401` into a `302` to `/auth/login` for an HTML
navigation. Cookies are plain `Set-Cookie` headers added with `Response.addHeader`; the http layer
itself still has no cookie or session support.

**Correlation id:** the `X-Correlation-Id` request header is used when present and valid
(`[A-Za-z0-9._-]{1,100}`), else one is generated; an invalid supplied value is replaced, not
rejected. It is echoed in the response header on every response including errors and 404s, put
on the request (`correlationId()`), and the resolved `Context` must carry it: after the
resolver returns, the framework replaces the context's correlation id with the request's.

## 7. Typed request validation

No reflection over fields, no annotations. Bodies are records decoded with Jackson 3 (module's
`ObjectMapper` is configured: fail on unknown properties = **true**, fail on null for primitives,
ISO dates, no default typing). Validation is an explicit function the record's author writes:

```java
record CreateOrder(String customer, int quantity) {
  static final Validator<CreateOrder> V = Validator.of(CreateOrder.class)
      .field("customer", CreateOrder::customer, Rules.notBlank(), Rules.maxLength(100))
      .field("quantity", CreateOrder::quantity, Rules.range(1, 1000));
}
CreateOrder body = request.json(CreateOrder.V);
```

- `request.json(Validator<T>)` requires `Content-Type: application/json` (else 415), decodes,
  validates, and returns `T` or throws `ValidationException`.
- `Validator<T>` collects **all** field errors, not the first. Each is `(field, code, message)`.
  `Rules` provides: `notNull`, `notBlank`, `minLength`, `maxLength`, `pattern`, `range`
  (numbers), `size` (collections), `oneOf`, `each(rule)` (elements; field path `tags[2]`), and
  `custom(code, message, predicate)`. A rule on a null value other than `notNull`/`notBlank` passes
  (absence is `notNull`'s job). Nested records: `.nested("address", R::address, Address.V)`
  (path `address.street`).
- A JSON decode failure (syntax, wrong type, unknown property) is one error with field =
  the JSON path when Jackson reports it, code `INVALID_JSON`. The message never echoes the
  offending value.
- Failure response: 400, `error` `VALIDATION`, `message` `request is invalid`, `details` =
  map from field path to `"<code>: <message>"` for each failed field (first error per field).
- Path and query parameters: `request.pathInt(name)`, `pathLong`, `queryInt(name, default)`,
  `queryLong`, `queryBool`, `queryEnum(name, Class, default)`, and `queryRequired(name)`; a bad
  value throws `ValidationException` naming the parameter.

## 8. Server (Jetty)

`JettyHttpModule` (module id `http`) provides `Routes`, registers the health endpoints in `boot`, compiles the route
table and starts the server in `started` (after every module's `boot`), and stops it in
`shutdown`. **Module order is irrelevant**: the module may be listed before or after the modules
that register routes. `ContextResolver` is optional and found with `App.find`. Settings (declared by the module):

| Key | Type | Default |
|---|---|---|
| `http.host` | string | `0.0.0.0` |
| `http.port` | integer | `8080` (`0` = any free port; `HttpServer.port()` returns the bound one) |
| `http.max-body-bytes` | long | `1048576` |
| `http.request-timeout` | duration | `30s` (idle and total read) |
| `http.shutdown-grace` | duration | falls back to `app.shutdown.drain` |

- Jetty core `Handler.Abstract` as the only handler; **virtual threads** for request execution
  (a `QueuedThreadPool` with `setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor())`).
  No Servlet, no sessions, no static files.
- Server header, version and stack traces are never sent.
- Body over `http.max-body-bytes` (by `Content-Length` or while reading): 413, connection
  closed after the response.
- The module's `started` fails (`StartupException`) when the port cannot be bound.
- **Health endpoints**, registered by the module: `GET /health/live` always `200
  {"status":"UP"}`; `GET /health/ready` runs `App.health().check()` and returns `200
  {"status":"UP","checks":{name:{"status":"UP"}}}` or `503` with `DOWN` / `TIMED_OUT` and the
  reason for `Down`. Both without authentication; no context.
  *Amended by MA item 7 (core contract section 1, criticality):* the status code follows
  `HealthReport.isReady()`, so only **critical** checks can make it `503`. Top-level `status` is
  `UP` (every check up, 200), `DEGRADED` (only informational checks are not up, 200) or `DOWN` (a
  critical check is not up, 503). An informational check's entry carries `"informational": true`;
  a critical one has no such key. A probe that reads only the status code sees no change for
  critical checks; one that compares the body's `status` with `UP` must accept `DEGRADED`.
- **Graceful shutdown** (`shutdown` hook): stop accepting (close connectors), let in-flight
  requests finish up to `http.shutdown-grace`, then interrupt the rest. New requests arriving
  during the drain on existing connections get 503 `UNAVAILABLE` with `Connection: close`.
  `/health/ready` returns 503 from the moment shutdown begins.

## 9. Test support

`TestClient` (in `framework-http`, main source set, small): builds against `Routes`, runs the
full pipeline in-process, no socket. `client.get("/x").header(...).send()` returns a
`TestResponse` (status, headers, body text, `json(Class)`). Used by the router, pipeline,
error-mapping and validation tests, and by applications' own tests.

## 10. Datastar adapter

Package `io.flowcatalyst.framework.http.datastar`, in `framework-http`. Follows the Datastar 1.0
SDK specification (events and field names below are the ones to implement; check against the
published spec when implementing and flag any difference).

- `Datastar.signals(Request)` returns the client's signals as a `JsonNode`: for `GET` and
  `HEAD`, the JSON in the `datastar` query parameter; otherwise the JSON request body.
  `Datastar.signals(Request, Class<T>)` decodes to a record. Absent/invalid: `ValidationException`.
- `Datastar.stream(Request, Datastar.Events)` returns the streamed `Response`; the body is written
  after the handler returns, so the callback receives a `ServerSentEventGenerator` when the server
  sends the body (`new ServerSentEventGenerator(OutputStream)` can also be used directly). The
  response is (`text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive` only
  for HTTP/1.1). Methods:
  - `patchElements(String html)` and an options form: `selector`, `mode` (`outer`, `inner`,
    `replace`, `prepend`, `append`, `before`, `after`, `remove`), `useViewTransition`,
    `namespace` (`html`/`svg`/`mathml`), `eventId`, `retry`. Emits `event:
    datastar-patch-elements` with `data: selector …`, `data: mode …` (only when not the
    default `outer`), `data: useViewTransition true` (only when true), `data: elements …` per
    line of HTML.
  - `patchSignals(String json)` and options (`onlyIfMissing`): `event: datastar-patch-signals`,
    `data: onlyIfMissing true` (only when true), `data: signals …` per line.
  - `remove(selector)` = `patchElements` with mode `remove`.
  - `executeScript` is not provided (it is a patch of a `<script>` element in the 1.0 spec; the
    app can do that with `patchElements`).
  - Each event ends with a blank line; `eventId` → `id:`; `retry` → `retry:` only when it is not
    the default (1000 ms).
  - `close()` ends the stream. A client disconnect surfaces as `ClientDisconnectedException`
    (unchecked) from the next write; handlers may let it propagate: it is logged at DEBUG, not as
    an error, and produces no response body.
- Writes are flushed per event. The generator is not thread-safe except `close()`.

## 11. Out of scope for M3

TLS (terminate in front), HTTP/2 (Jetty default h1 only), WebSockets, multipart, cookies and
sessions (M4), OpenAPI generation, rate limiting (added by MA item 9, rate-limiting contract), compression, static assets, and any real
`ContextResolver` or authentication.

## 12. Test requirements

At minimum: router (precedence, `{param}` decoding, duplicate detection incl. different param
names, 404 vs 405 and `Allow`, `HEAD`/`OPTIONS`); pipeline (order, short-circuit, exception
propagation); error envelope for each row of the section 5 table; use-case error mapping for
every `ErrorKind`; validator (all errors collected, nested paths, `each`, JSON decode errors do
not echo values, unknown property rejected, 415); correlation id (generated, echoed, invalid
replaced, in `Context`); `requiresContext` start-up failure; Jetty: real request, virtual
thread asserted (`Thread.currentThread().isVirtual()`), body limit 413, port 0, health endpoints,
graceful shutdown with an in-flight request, bind failure; Datastar: exact wire bytes for each
event with each option, signals from query and body, disconnect handling.
