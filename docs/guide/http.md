<!-- types: Routes, Handler, Method, RouteOptions, Request, Response, StreamBody, Middleware, Next, Pipeline, Validator, Rules, Rule, Violations, FieldError, ValidationException, HttpException, HttpErrors, ContextResolver, TestClient, TestResponse, Json, Datastar, ServerSentEventGenerator, PatchElementsOptions, PatchSignalsOptions, ElementPatchMode, Namespace, ClientDisconnectedException, JettyHttpModule, HttpServer, Dispatcher -->
# HTTP

Two artifacts:

- `framework-http`: the types you code against (`Routes`, `Request`, `Response`, validation,
  errors, Datastar) and the Jetty-free pieces (router, pipeline, `TestClient`). Nothing in it
  mentions Jetty.
- `framework-http-jetty`: `JettyHttpModule`, the only code that imports `org.eclipse.jetty.*`.

Application code depends on `framework-http` types only. There is no Servlet API, no sessions, no
static files, no TLS (terminate in front), no HTTP/2, no CORS, no compression, no rate limiting, and
no OpenAPI generation.

## Wiring it up

List `JettyHttpModule` among your modules. **Order is irrelevant**: it compiles the route table and
starts listening in `started`, after every module has booted.

```java
App.builder()
    .module(new DatabaseModule())
    .module(new OutboxModule())
    .module(new UseCaseModule())
    .module(new OrdersModule())
    .module(new OrdersHttpModule())
    .module(new JettyHttpModule())
    .shutdownHook(true)
    .start();
```

The module provides `Routes` and `HttpServer` (`port()` returns the bound port, which matters when
`http.port` is `0`). Your module registers routes in **`boot`**, never `register`:

```java
@Override
public void boot(App app) {
  Routes routes = app.get(Routes.class).forModule(id());
  routes.post("/orders", this::create, RouteOptions.requiresContext());
}
```

`forModule(id)` attributes registrations to your module in start-up errors. Registration is
allowed until the server starts listening; afterwards `IllegalStateException`.

## Routes

```java
routes.get("/orders/{id}", orders::show);
routes.post("/orders", orders::create);
routes.add(Method.PUT, "/orders/{id}", handler, RouteOptions.requiresContext());
```

There are `get`, `post`, `put`, `patch`, `delete` and `add(Method, ...)`, each with and without
`RouteOptions`. A `Handler` is `Response handle(Request request) throws Exception`.

- Methods: `GET HEAD POST PUT PATCH DELETE OPTIONS`. `HEAD` is answered from `GET` with the body
  dropped unless a `HEAD` route exists.
- Pattern: segments separated by `/`; a segment is a literal or `{name}` (name is
  `[a-z][A-Za-z0-9]*`) matching one non-empty segment. No wildcards, regex or optional segments. A
  trailing slash is a different path; there is no redirect.
- Literals beat parameters at the same position, so `/orders/new` beats `/orders/{id}`.
  Percent-decoding happens per segment after splitting.
- **Duplicate routes fail start-up** (same method and shape; parameter names ignored). The error
  names both registrants.
- No route: `404 NOT_FOUND`. Path matches but not the method: `405 METHOD_NOT_ALLOWED` with `Allow`.
  `OPTIONS` to a known path without an `OPTIONS` route is `204` with `Allow`.
- Route patterns are logged at INFO at start-up, one line each.

## Request and response

`Request` is an immutable view:

| Method | Returns |
|---|---|
| `method()`, `path()` | |
| `pathParam(name)`, `pathInt(name)`, `pathLong(name)` | a path parameter; an undeclared name is `IllegalArgumentException`, a bad number a `ValidationException` |
| `query(name)`, `queries(name)` | first value or `null`; all values |
| `queryRequired(name)`, `queryInt(name, default)`, `queryLong`, `queryBool`, `queryEnum(name, Class, default)` | typed query parameters; a bad value is a `ValidationException` naming the parameter |
| `header(name)`, `headers(name)` | case-insensitive |
| `body()`, `bodyBytes()`, `bodyText()` | the raw body (stream once, or bytes cached); limited by `http.max-body-bytes` |
| `json(Validator<T>)` | decode and validate a JSON body (below) |
| `context()` | the resolved `Context`; `IllegalStateException` on a route without `requiresContext` |
| `correlationId()` | |
| `attribute(Key)` / `attribute(Key, value)` | typed values for middleware to pass on; make keys with `Request.Key.of("user", String.class)` |

`Response` is a value built with static factories and fluent headers:

```java
Response.ok(Map.of("a", 1));            // 200 JSON
Response.json(201, Map.of("orderId", id));
Response.text(200, "pong");
Response.html(200, "<p>hi</p>");
Response.noContent();
Response.bytes(200, "application/pdf", bytes);
Response.stream(200, "text/event-stream", out -> { ... });   // a StreamBody
response.header("X-Frame-Options", "DENY");   // replaces; addHeader appends
```

`Response.json` and `ok` take **records, maps, collections or a `JsonNode`**, not a bare `String`
(use `text`). JSON is Jackson 3 and strict (see below). Header names and values containing CR or LF
fail with `IllegalArgumentException`.

## Typed request validation

No annotations and no reflection over fields. A body is a record; its validator is an explicit
function you write next to it.

```java
record PlaceOrderBody(String customer) {
  static final Validator<PlaceOrderBody> V =
      Validator.of(PlaceOrderBody.class)
          .field("customer", PlaceOrderBody::customer, Rules.notBlank(), Rules.maxLength(100));
}

routes.post("/orders", request -> {
  PlaceOrderBody body = request.json(PlaceOrderBody.V);
  ...
});
```

- `request.json(validator)` requires `Content-Type: application/json` (else 415), decodes, validates,
  and returns the record or throws `ValidationException`.
- The decoder is strict: **unknown properties are an error**, `null` for a primitive is an error,
  trailing tokens and duplicate keys are errors, dates are ISO. A decode failure is one
  `INVALID_JSON` error whose message never echoes the offending value.
- `Validator` collects **all** field errors. Nested records: `.nested("address", Order::address, Address.V)`
  gives paths like `address.street`.
- `Rules`: `notNull`, `notBlank`, `minLength`, `maxLength`, `pattern`, `range` (numbers), `size`
  (collections), `oneOf`, `each(rule)` (elements; path `tags[2]`), `valid(validator)` and
  `custom(code, message, predicate)`. A rule other than `notNull`/`notBlank` on a null value
  passes: absence is `notNull`'s job.
- Codes: `NOT_NULL`, `NOT_BLANK`, `TOO_SHORT`, `TOO_LONG`, `PATTERN`, `OUT_OF_RANGE`, `SIZE`,
  `NOT_ONE_OF`, `INVALID_JSON`, and your own from `custom`.

A failed validation responds `400`:

```json
{"error": "VALIDATION", "message": "request is invalid",
 "details": {"customer": "NOT_BLANK: must not be blank"}}
```

`details` holds the first error per field path, as `"<CODE>: <message>"`.

## Errors

Every non-2xx response the framework produces has one envelope, content type `application/json`:

```json
{"error": "<CODE>", "message": "...", "details": {"k": "v"}}
```

`details` is omitted when empty; its values are strings.

| Situation | Status | `error` |
|---|---|---|
| malformed JSON, failed validation, bad parameter | 400 | `VALIDATION` |
| no or bad credentials (from a `ContextResolver`) | 401 | `UNAUTHENTICATED` |
| not allowed | 403 | the use case's code |
| no route | 404 | `NOT_FOUND` |
| method not allowed | 405 | `METHOD_NOT_ALLOWED` |
| body over the limit | 413 | `PAYLOAD_TOO_LARGE` |
| unsupported content type | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| server draining on shutdown | 503 | `UNAVAILABLE` |
| uncaught exception | 500 | `INTERNAL` (message `internal error`; the cause is logged with the correlation id, never returned) |

In a handler you either return an error `Response` or throw:

```java
return HttpErrors.of(failed.error());                      // from a UseCaseError: status by ErrorKind
return HttpErrors.error(409, "TAKEN", "That name is taken.");
throw new HttpException(404, "NO_SUCH_ORDER", "There is no such order.");
```

`HttpErrors` also has `validation`, `unauthenticated`, `forbidden` and `notFound` helpers.
`ValidationException` is an `HttpException` with status 400. The error-mapping middleware turns a
thrown `HttpException` into the envelope.

## Middleware

One abstraction, `Middleware<I, O>` in `io.flowcatalyst.framework.pipeline`, not HTTP-specific:

```java
interface Middleware<I, O> { O handle(I input, Next<I, O> next) throws Exception; }
```

Register HTTP middleware with `Routes.use(middleware)` (global, in registration order) or
`RouteOptions.with(middleware)` (per route, inside the global ones):

```java
routes.use(
    (request, next) -> {
      Response response = next.handle(request);
      return response.header("X-Frame-Options", "DENY");
    });
```

A middleware may short-circuit by returning without calling `next`. The framework installs, outermost
first and not removable: **(1)** correlation id, **(2)** error mapping, **(3)** access log (method,
path template, status, duration, correlation id; never query strings or bodies). Yours run inside
those. Context resolution runs after the global middleware and before route middleware.

## Correlation id

The `X-Correlation-Id` request header is used when valid (`[A-Za-z0-9._-]{1,100}`), otherwise one is
generated; an invalid supplied value is replaced, not rejected. It is echoed in the response header
on every response (including errors and 404s), available as `request.correlationId()`, and set in
the resolved `Context`.

## Context and authentication

A route declared `RouteOptions.requiresContext()` gets `request.context()` from a
`ContextResolver`: a service **you provide**, `Context resolve(Request request) throws Exception`,
which throws `HttpException(401, "UNAUTHENTICATED", ...)` when it cannot. **Start-up fails** if any
route requires a context and no resolver is provided, naming the route.

> **There is no authentication in the framework yet.** No login, sessions, token validation or
> default resolver exist (milestone M4c, see [STATUS](STATUS.md)). Until then an application writes
> its own `ContextResolver` and its own authorization checks, for example from a bearer token. The
> placeholder in [Context](context.md#building-one) accepts everyone.

## Datastar

For server-driven pages, `io.flowcatalyst.framework.http.datastar` follows the Datastar 1.0 SDK
wire format.

```java
routes.get(
    "/feed",
    request ->
        Datastar.stream(
            request,
            sse -> {
              sse.patchElements("<div id=\"feed\">hello</div>");
              sse.patchElements("<li>one</li>", PatchElementsOptions.defaults().selector("#list"));
              sse.patchSignals("{\"count\":1}");
            }));
```

- `Datastar.signals(request)` returns the client's signals as a `JsonNode`: the JSON in the
  `datastar` query parameter for `GET`/`HEAD`, otherwise the JSON body. `Datastar.signals(request,
  SomeRecord.class)` decodes to a record. Absent or invalid is a `ValidationException`.
- `Datastar.stream(request, events)` runs your `Datastar.Events` callback with a
  `ServerSentEventGenerator` and streams `text/event-stream`. The callback form is how it is
  built (the body is written after the handler returns). When the callback returns the stream ends.
- Generator methods: `patchElements(html)` and `patchElements(html, PatchElementsOptions)`
  (`selector`, `mode` as an `ElementPatchMode`, `useViewTransition`, `namespace`, `eventId`,
  `retry`), `patchSignals(json)` and with `PatchSignalsOptions` (`onlyIfMissing`, `eventId`,
  `retry`), `remove(selector)`, `close()`. `executeScript` is not provided: patch a `<script>`
  element instead.
- Writes are flushed per event. A client disconnect surfaces as the unchecked
  `ClientDisconnectedException` from the next write; let it propagate (it is logged at DEBUG).

There is no template engine or view helper yet: you produce the HTML strings yourself. That is the
planned `framework-ui` module (M-UI, see [STATUS](STATUS.md)).

## The Jetty module and its settings

`JettyHttpModule` (module id `http`) runs Jetty 12 core handlers with **virtual threads** for
request execution. The server header, version and stack traces are never sent.

| Key | Type | Default |
|---|---|---|
| `http.host` | string | `0.0.0.0` |
| `http.port` | integer | `8080` (`0` = any free port) |
| `http.max-body-bytes` | long | `1048576` |
| `http.request-timeout` | duration | `30s` (idle timeout of reads and writes) |
| `http.shutdown-grace` | duration | falls back to `app.shutdown.drain` |

A body over the limit (by `Content-Length` or while reading) is `413` and the connection is closed
after the response. `started` fails with a `StartupException` if the port cannot be bound.

### Health endpoints

Registered by the module, with no authentication and no context:

- `GET /health/live`: always `200 {"status":"UP"}`.
- `GET /health/ready`: runs `App.health().check()` and returns `200
  {"status":"UP","checks":{name:{"status":"UP"}}}`, or `503` with `DOWN` / `TIMED_OUT` (and the
  reason for `Down`). It returns 503 from the moment shutdown begins.

Add your own checks with `app.health().add(name, check)` (see
[Modules and lifecycle](modules-and-lifecycle.md#health-checks)).

### Graceful shutdown

On `shutdown` the module stops accepting (closes connectors), lets in-flight requests finish for
up to `http.shutdown-grace`, then interrupts the rest. New requests on existing connections get
`503 UNAVAILABLE` with `Connection: close`.

## Testing

`TestClient` runs the full pipeline in-process with no socket:

```java
Routes routes = new Routes();
routes.get("/ping", r -> Response.text(200, "pong"));
TestResponse response = TestClient.of(routes).get("/ping").header("X-Correlation-Id", "t-1").send();
```

`TestClient.of(routes, resolver)` supplies a `ContextResolver`. `TestResponse` has `status()`,
`header(name)`, `body()`, `json(Class)` and `tree()`. See [Testing](testing.md).

Specification: [contracts/http.md](../contracts/http.md). Where the contract and the code differ
(the Datastar callback form), the code is described here.
