<!-- types: MetricsEndpoint, ServiceError, Routes, Handler, Method, RouteOptions, Request, Response, StreamBody, Middleware, Next, Pipeline, Validator, Rules, Rule, Violations, FieldError, ValidationException, HttpException, HttpErrors, ContextResolver, TestClient, TestResponse, Json, Datastar, ServerSentEventGenerator, PatchElementsOptions, PatchSignalsOptions, ElementPatchMode, Namespace, ClientDisconnectedException, JettyHttpModule, HttpServer, Dispatcher, EventStreams, SseEvent, CorsPolicy, Idempotency, IdempotencyStore, MemoryIdempotencyStore, IdempotencyScope, IdempotencyRecord, StoredAnswer, IdempotencyDatabaseModule, DatabaseIdempotencyStore -->
# HTTP

Two artifacts:

- `framework-http`: the types you code against (`Routes`, `Request`, `Response`, validation,
  errors, Datastar) and the Jetty-free pieces (router, pipeline, `TestClient`). Nothing in it
  mentions Jetty.
- `framework-http-jetty`: `JettyHttpModule`, the only code that imports `org.eclipse.jetty.*`.

Every request is counted in `fw_http_server_requests_total` and timed in
`fw_http_server_request_duration_seconds`, labelled by the route pattern
([metrics](background-and-metrics.md#built-in-metrics)).

Rate limiting is a middleware in `framework-http` itself: see [Rate limiting](rate-limiting.md).
`request.remoteAddress()` is the connection's peer address (never a header).

Application code depends on `framework-http` types only. There is no Servlet API, no TLS (terminate
in front), no HTTP/2 and no compression. CORS is off unless `http.cors.allowed-origins` is set
([CORS](#cors)); sessions, the SPA and OpenAPI are their own pages.

## What `framework-http` pulls in

`framework-core` and Jackson, nothing else. An application that only wants HTTP (an ingestor with
no database) does not get the write path, the database module, HikariCP or the PostgreSQL driver.
The build enforces it: `framework-http` and `framework-http-jetty` run a Maven Enforcer
`bannedDependencies` rule (`http-only-classpath`, declared in the parent POM) that fails if any of
those appears in any scope, directly or transitively.

`HttpErrors.of(...)` takes a `ServiceError`, a small interface in `framework-core` (`httpStatus()`,
`code()`, `message()`, `details()`). The write path's `UseCaseError` implements it, so
`HttpErrors.of(failed.error())` compiles unchanged; any other module's error value can implement it
too.

**Migrating from before MA item 8 (2026-10-10).** `framework-http` used to depend on
`framework-usecase` and so brought `framework-database`, HikariCP and the driver transitively. An
application that used the write path but declared only `framework-http` / `framework-http-jetty`
must now declare `framework-usecase` (and `framework-database` if it uses its types directly)
itself. No package or class name changed. Code compiled against the old
`HttpErrors.of(UseCaseError)` signature must be recompiled (the method now takes `ServiceError`);
the source is the same.

## Wiring it up

List `JettyHttpModule` among your modules. **Order is irrelevant for registering routes**: every
module registers its routes in `boot`, and no module's `started` runs until every `boot` has
finished. But the module compiles the route table and **opens the port in its own `started` hook**,
and `started` hooks run in the order the modules are listed. A module listed *after* Jetty therefore
runs its `started` work with the port already open, and a request can arrive before that work is
done (the FlowCatalyst receivers answer `503` until their `started` hook has run; a module that
registers routes in `started` fails with `IllegalStateException`, because the table is already
compiled). **List `JettyHttpModule` last**, as every example here does, so the port opens only once
every other module has fully started.

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
| `headers()` | every header by lower-case name (unmodifiable), for code that passes headers on |
| `body()`, `bodyBytes()`, `bodyText()` | the raw body (stream once, or bytes cached); limited by `http.max-body-bytes`, or by the route's own limit ([below](#a-routes-own-body-limit)) |
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

Each standard rule also says what it checks (`Rule.describe()`, `Validator.fields()`), which is how a
typed operation's validator becomes the constraints of its OpenAPI document; see [Typed operations and
OpenAPI](openapi.md) for `Operation`, `Routes.bind` and the `openapi` command.

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
return HttpErrors.of(failed.error());                      // any ServiceError, e.g. a UseCaseError: status by ErrorKind
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

## A route's own body limit

`http.max-body-bytes` (5 MiB) is the limit of every route unless the route sets its own, larger or
smaller, with `RouteOptions.maxBodyBytes(bytes)` (fulfil-go issue 6: proof photos were held to the
global 1 MiB). From `RouteBodyLimitTest`:

```java
routes.post("/photo", length, RouteOptions.none().maxBodyBytes(100));
routes.bind(NOTE, r -> Outcome.ok(new Echo(r.body().text().length())), RouteOptions.none().maxBodyBytes(200));
```

- It applies once the route is matched: a declared `Content-Length` over it is `413
  PAYLOAD_TOO_LARGE` before the handler runs, and reading past it (`bodyBytes()`, `body()`) is
  `413`. Paths with no route, and `405`/`OPTIONS` answers, keep the global limit.
- Global middleware runs before routing, so a global middleware that reads the body reads under the
  global limit.
- `with(...)`, `acceptingForms()` and `Routes.bind` keep it; a negative limit is
  `IllegalArgumentException`.

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

The framework's resolver is in `framework-auth` (M4c): list `AuthModule` and every context route
is authenticated by the session cookie or a platform bearer token, with the CSRF rules for cookie
requests and a `302` to `/auth/login` for HTML navigations. See
[Sessions and login](sessions-and-login.md). A placeholder resolver of your own (as in
[Context](context.md#building-one)) is still fine in tests.

*Added by M4c:* `request.rawQuery()` (the query as sent), `request.contentLength()` (the declared
body length, `-1` when none) and `Response.redirect(location)` (a `302` with no body).

*Added by GU3:* `Response.seeOther(location)` (a `303` with no body, where a browser goes after a form post) and
`RouteOptions.acceptForms()` / `.acceptingForms()`, which declare a route that server-rendered pages post to:
the sessions CSRF guard accepts a form body on it (see [sessions and login](sessions-and-login.md#csrf)).
`Request.acceptsForms()` tells whether the matched route was declared so. A typed operation cannot be one
(`Routes.bind` throws `IllegalArgumentException`).

## Server-sent event streams

`EventStreams` (package `io.flowcatalyst.framework.http.sse`; the HTTP module provides one) serves
`text/event-stream` routes with what fulfil-go built for its live updates (issue 6): heartbeats,
`Last-Event-ID` replay from the application's own log, and an end on shutdown before the drain.

```java
EventStreams streams = app.get(EventStreams.class);
routes.get(
    "/api/stores/{store}/events",
    r -> streams.serve(r, e -> e.type().startsWith("pick-"), (after, limit) -> log.after(r.pathParam("store"), after, limit)),
    RouteOptions.requiresContext());
// where the application learns of a committed change, for example its tail of the log table:
streams.publish(new SseEvent(row.id(), row.type(), row.payloadJson()));
```

- An `SseEvent(id, type, data)` has an id of at least 1, increasing in publish order (a log table's
  sequence), a one-line type and text data (usually JSON). On the wire: `id:`, `event:`, one
  `data:` line per line of data.
- A stream sends `retry: 3000` first. When the client sends `Last-Event-ID` (or the `lastEventId`
  query parameter, for clients that cannot set the header; a value that is not a number of 0 or more
  counts as none), it then sends what `Replay.after(lastId, 1000)` returns, then the published events
  its filter admits, each once and in id order: it subscribes before it replays, and skips any id at
  or below the last one sent. `serve(request, filter)` without a replay starts live.
- Idle for `http.sse.heartbeat` (25 s): a `: ping` comment, which keeps proxies from closing it and
  finds a client that went away. Headers: `Cache-Control: no-cache`, `X-Accel-Buffering: no`.
- A stream ends when the client goes, when it falls more than 256 events behind (`BUFFER`; it is
  sent what it held, the client reconnects with its last id), when the replay throws (logged at
  WARN), and on `closeAll()`, which the HTTP module registers with `HttpServer.beforeDrain`, so open
  streams end when shutdown begins instead of holding the drain for its grace. A stream opened after
  `closeAll` ends at once.
- `publish` is in-process and never blocks. Each instance publishes what its own tail of the shared
  log reads (fulfil-go wakes its tail with PostgreSQL `NOTIFY` and polls as a safety net), so any
  instance serves any stream.

Datastar streams (below) are not `EventStreams` and are not ended before the drain.

## Idempotency-Key

`Idempotency.middleware(store, clock)` (package `io.flowcatalyst.framework.http.idempotency`) makes
a write sent twice run once: a device's offline queue replays what it could not confirm (fulfil-go
issue 6). It is route middleware on a route that requires a context, and behaves as the Go
framework's `idempotency` package, table included (HTTP contract section 13.6). From
`IdempotencyTest`:

```java
RouteOptions once = RouteOptions.requiresContext().with(Idempotency.middleware(store, clock));
routes.post("/claims", handler, once);
```

| Request | Answer |
|---|---|
| no `Idempotency-Key` header | runs as it is |
| a key that is not 1 to 200 printable ASCII characters (blank included), or the header twice | `400 IDEMPOTENCY_KEY_INVALID` |
| a key on a request whose context has no principal | `400 IDEMPOTENCY_KEY_UNSUPPORTED` (it cannot be scoped to a caller) |
| the first with a key (per client, principal and key) | runs; a `2xx` with a body held in memory is kept (status, `Content-Type`, body) |
| the same method, path, query and body again | the kept answer, with `Idempotency-Replayed: true`; other headers of the first answer (`Location`) are not kept |
| again while the first still runs | waits for it (10 s) and gets its answer; `409 IDEMPOTENCY_IN_PROGRESS` when the wait runs out |
| another request under the same key | `422 IDEMPOTENCY_KEY_REUSED` |
| the first failed (not `2xx`, an exception, or a streamed body) | its key is freed, so a retry does the work |
| the key is older than the retention (24 h) | taken over: the request runs again |
| the first never finished and took the key over 5 minutes ago (its process may have stopped) | taken over: the request runs; the stuck one can no longer complete or free the key |

The request is identified by SHA-256 of `METHOD SP path "?" query LF body` (the path and query as
sent); the body is read within the route's limit and cached, so the handler reads the same bytes.
Spaces around the key are not part of it. Each request that tries to take a key makes a random
token, and only the request holding the key's token completes or frees it.

The timings are `Idempotency.Options` (`retention`, `maxWait`, `inFlightTimeout`; `defaults()` is
24 h, 10 s, 5 min), passed as `Idempotency.middleware(store, clock, options)`.

Stores (both pass `IdempotencyStoreContract`): `MemoryIdempotencyStore` (one process; tests) and,
in the module `framework-idempotency-database`, `IdempotencyDatabaseModule` (module id
`http-idempotency`, the Go framework's migration set: table `fw_idempotency_keys`), which migrates the
default database, provides `IdempotencyStore` as a `DatabaseIdempotencyStore` and `Idempotency.Options`
from its settings, and runs the loop `http-idempotency-prune` every hour, deleting keys older than
the retention 1000 at a time.

| Setting | Default | |
|---|---|---|
| `idempotency.retention` | `24h` | how long a key and its answer are kept |
| `idempotency.wait` | `10s` | how long a repeat waits for the first request's answer |
| `idempotency.in-flight-timeout` | `5m` | how long an unfinished reservation holds its key |

```java
.module(new DatabaseModule())
.module(new IdempotencyDatabaseModule())
// in a module's boot:
Middleware<Request, Response> once =
    Idempotency.middleware(
        app.get(IdempotencyStore.class), app.get(InstantSource.class), app.get(Idempotency.Options.class));
```

## CORS

Off by default. Setting `http.cors.allowed-origins` turns it on for every response, with
`CorsPolicy` (`Routes.cors(policy)` installs one in code; one per application):

- The middleware runs just inside the correlation id, outside the error mapping and every other
  middleware, so error answers, `404`s and refusals by middleware carry the headers too.
- A request with an allowed `Origin` gets `Access-Control-Allow-Origin` (the origin, or `*` for the
  wildcard), `Access-Control-Expose-Headers` and `Vary: Origin`; another origin gets only `Vary:
  Origin` (the browser withholds the answer). A request without `Origin` is untouched.
- A preflight (`OPTIONS` with `Access-Control-Request-Method`) from an allowed origin asking for an
  allowed method and allowed headers is `204` with `Access-Control-Allow-Methods`, `-Headers` and
  `-Max-Age`; any other preflight is `403 CORS_REFUSED`. A plain `OPTIONS` keeps the router's
  `204` with `Allow`.
- **Never credentials** (`Access-Control-Allow-Credentials` is never sent): the session cookie is
  never usable across origins, as the [sessions contract](../contracts/sessions-and-login.md)
  section 7 relies on. A page on an allowed origin calls with a bearer token it holds
  (`Authorization` is an allowed header).
- Origins are `scheme://host[:port]` with no path, or the single entry `*`; a bad one fails
  start-up naming `http.cors`.

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
  `datastar` query parameter for `GET`, `HEAD` and `DELETE` (Datastar 1.0.5 sends a body for no other method
  than those), otherwise the JSON body. A body above 1 MiB (`Datastar.MAX_SIGNAL_BYTES`) is `413
  PAYLOAD_TOO_LARGE`. `Datastar.signals(request, SomeRecord.class)` decodes to a record. Absent or invalid is a
  `ValidationException`.
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

The server's settings are configuration (they are service settings,
[config-and-code contract](../contracts/config-and-code.md) section 3.4):

| Key | Type | Default |
|---|---|---|
| `http.host` | string | `0.0.0.0` |
| `http.port` | integer | `8080` (`0` = any free port) |
| `http.max-body-bytes` | long | `5242880` (5 MiB; not negative) |
| `http.request-timeout` | duration | `30s` (idle timeout of reads and writes) |
| `http.shutdown-grace` | duration | falls back to `app.shutdown.drain` |
| `http.metrics.enabled` | boolean | `false`; mounts the [Prometheus endpoint](background-and-metrics.md#the-prometheus-endpoint) |
| `http.metrics.path` | string | `/metrics` |
| `http.metrics.bearer-token` | secret | none; when set, scrapes need it |
| `http.sse.heartbeat` | duration | `25s`; an idle [event stream](#server-sent-event-streams) sends a comment |
| `http.cors.allowed-origins` | string list | empty: [CORS](#cors) off |
| `http.cors.allowed-methods` | string list | `GET, HEAD, POST, PUT, PATCH, DELETE` |
| `http.cors.allowed-headers` | string list | `Content-Type, Authorization, DPoP, X-Correlation-Id, Idempotency-Key, Last-Event-ID` |
| `http.cors.exposed-headers` | string list | `X-Correlation-Id, Idempotency-Replayed` |
| `http.cors.max-age` | duration | `10m`; how long a browser caches a preflight's answer |

The module claims the `http` component: any other key under `http` in a file (a typo such as
`http.prot`) fails start-up as unknown, listing the keys above.

A body over the limit (by `Content-Length` or while reading) is `413` and the connection is closed
after the response. `started` fails with a `StartupException` if the port cannot be bound.

### Health endpoints

Registered by the module, with no authentication and no context:

- `GET /health/live`: always `200 {"status":"UP"}`.
- `GET /health/ready`: runs `App.health().check()` and returns `200
  {"status":"UP","checks":{name:{"status":"UP"}}}`, or `503` with `DOWN` / `TIMED_OUT` (and the
  reason for `Down`). It returns 503 from the moment shutdown begins.
- Only **critical** checks decide the status code
  ([criticality](modules-and-lifecycle.md#critical-and-informational-checks)). When only
  informational checks are not up the answer is `200` with top-level `"status":"DEGRADED"`; each
  informational check's entry carries `"informational": true`:

```json
{"status":"DEGRADED","checks":{"platform":{"status":"DOWN","reason":"IllegalStateException: platform unreachable","informational":true},"good":{"status":"UP"}}}
```

Add your own checks with `app.health().add(name, check)` (see
[Modules and lifecycle](modules-and-lifecycle.md#health-checks)).

### Graceful shutdown

On `shutdown` the module first marks the server draining (new requests on existing connections get
`503 UNAVAILABLE` with `Connection: close`, `/health/ready` is down), then runs the
**before-drain actions**, then stops accepting (closes connectors), lets in-flight requests finish
for up to `http.shutdown-grace`, and interrupts the rest.

`HttpServer.beforeDrain(Runnable)` adds an action that runs when shutdown begins, before the drain
(fulfil-go issue 17: a long-lived response held the drain until its grace ran out). Use it to end
long-lived responses; `EventStreams.closeAll` is registered there by the module. A module's own
`shutdown` hook is too late for that: the HTTP module is listed last, so its `shutdown` (the drain)
runs first. Actions run once, in the order added, on the stopping thread; one that throws is logged
at WARN and the rest still run. From `GracefulShutdownTest`:

```java
server.beforeDrain(
    () -> {
      drainingInHook.set(server.isDraining());
      listeningInHook.set(listening(port));
      stop.set(true);
    });
```

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
