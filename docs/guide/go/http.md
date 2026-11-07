<!-- types: CSRFGuard, Routes, Dispatcher, Server, Declared, Error, Detail, ServiceError, HandlerFunc, ContextHandlerFunc, Middleware, ContextResolver, RouteOption, Option, SPA, Options, CSP, Handler, Event, Replay, Keys -->
# Go: HTTP and the SPA

Package `httpfw` is the Go counterpart of `framework-http` and `framework-http-jetty`: the
[HTTP contract](../../contracts/http.md) on `net/http` and `encoding/json/v2`, and the
[static assets and SPA contract](../../contracts/static-assets-and-spa.md) in package `httpfw/spa`.
Typed operations and the OpenAPI document are the [next page](openapi.md). Snippets come from
`go/examples/orders-demo` and the package tests; the conformance vector `conformance/http/spa.json`
(86 requests) passes byte for byte.

## Declaring, building, serving

`httpfw.Declare` declares the module: the `http.*` settings (service settings, so configuration:
`http.host`, `http.port` (0 picks a free port), `http.max-body-bytes`, `http.request-timeout`,
`http.shutdown-grace` (default `app.shutdown.drain`), `http.metrics.enabled`, `http.metrics.path`,
`http.metrics.bearer-token`, the `http.cors.*` settings of [CORS](#cors)), a claim on the `http` component (a misspelt key under it fails
start-up listing the real ones) and the `openapi` command. `httpfw.NewServer`, in the build
function, checks every module's route table together, adds the health endpoints, registers the start
action that binds the address and returns the application's `fw.Serve`:

```go
rt := httpfw.NewRoutes("orders-api")
(&orders.API{Service: svc, DB: db, Store: orders.Repository{}}).Bind(rt)
sessionapi.Unavailable(rt)
site, err := spa.New(web.Dist(), spa.Options{})
if err != nil {
	return nil, err
}
rt.SPA(site)
return httpfw.NewServer(b, d.web, []*httpfw.Routes{rt}, httpfw.WithResolver(tokenResolver{token: token, client: client}))
```

Every problem found while compiling the tables is reported at once as a `*fw.StartupError`: a
malformed or duplicate route (same method and shape, parameter names ignored; the problem names both
modules), a route that requires a context when no resolver was given, an operation bound twice,
undeclared or declared but not bound, a `MaxBody` below 1, and the SPA's own checks. `NewServer` also
builds the OpenAPI document of the declared operations and fails with every problem it has (a plain
`int` field, an embedded struct, a pointer without `omitzero`; see [typed operations](openapi.md#the-document-at-start-up)).
Registering after the server was built panics.

## Routes

```go
rt := httpfw.NewRoutes("orders")
rt.Handle("GET /orders/{id}", show)                   // func(w, r) error
rt.HandleContext("POST /orders", create)              // func(w, r, cx fw.Context) error
rt.Handle("GET /export", export, httpfw.With(rateLimit))
rt.Use(accessAudit)                                   // global middleware
```

A handler writes its response and returns `nil`, or returns an error. The rules are the contract's,
as a client sees them: segments are literals or `{name}` (`[a-z][A-Za-z0-9]*`); a literal beats a
parameter at the first difference (`/orders/new` before `/orders/{id}`); the path is split first and
each segment percent-decoded after, so `%2F` stays inside its segment; a trailing slash is another
path; nothing redirects or cleans a path; no route is `404 NOT_FOUND`, a path with another method
`405 METHOD_NOT_ALLOWED` with a sorted `Allow` (`HEAD` with `GET`, `OPTIONS` always), `OPTIONS` on a
known path `204` with `Allow`; `HEAD` is answered from `GET` with the length the body would have
had and no body. A malformed escape is `400 VALIDATION`. The path parameters are read with
`r.PathValue(name)`.

The fixed middleware, outermost first, cannot be removed: correlation id, error mapping, access
log (`slog`: method, route pattern, status, duration, correlation id; never a query or a body).
Application middleware runs inside them; on a route that requires a context it runs after the
`ContextResolver`. Every response carries `X-Correlation-Id` (the caller's when it matches
`[A-Za-z0-9._-]{1,100}`, else a fresh one; the resolved `fw.Context` carries it too) and
`X-Content-Type-Options: nosniff`. The HTTP metrics are `fw_http_server_requests_total` and
`fw_http_server_request_duration_seconds`, labelled with the route pattern, `(spa)` or
`<unmatched>`, never the path.

## Errors

Every non-2xx answer the framework produces is `{"error": CODE, "message": "...", "details": {...}}`
(`details` left out when empty). A handler returns an `*httpfw.Error` (`NewError`, `Validation`,
`Unauthenticated`, `Forbidden`, `NotFound`) or any `ServiceError`: a `*usecase.Error` is one, so a
use case failure passes straight through with its status, code, message and details. Any other
error is `500 INTERNAL` with the message `internal error`; the cause and a panic are logged with the
correlation id and never returned. A request the client abandoned is logged at debug and recorded
as 499.

`httpfw.DecodeJSON[T](r)` reads a body strictly: `415` unless `application/json`, `413` over
the body limit (by `Content-Length` or while reading, `Connection: close`), unknown members,
duplicate names and wrong-case names refused. A decode failure is one `INVALID_JSON` detail at the
member's path (`lines[1].sku`); the message never echoes the value.

The body limit is `http.max-body-bytes`, or the route's own with the route option `httpfw.MaxBody(n)`,
smaller for a route that takes little or larger for one that takes uploads; every other route keeps
the global limit. The route's limit applies to `Content-Length` before the handler runs and while the
body is read (`DecodeJSON`, `ReadBody`, typed operations). From `httpfw/bodylimit_test.go`:

```go
rt.Handle("POST /small", echo, httpfw.MaxBody(20))
rt.Handle("POST /large", echo, httpfw.MaxBody(100)) // above the global limit: photos
rt.Bind(httpfw.Handle(uploadOp, upload, httpfw.MaxBody(20)))
```

## Context

A route registered with `HandleContext` (or `RequiresContext()`) gets its `fw.Context` from the
`ContextResolver` given to `NewServer`, which returns a `401` `Unauthenticated` error when it
cannot; the framework then replaces the Context's correlation id with the request's. The real
resolver (sessions and bearer tokens) is package `auth` ([sessions and login](sessions-and-login.md));
the orders demo still uses a fixed-token resolver of its own and says so.

## CSRF and security headers

`httpfw.SessionsGuard(publicBaseURL)` is the platform's F16 rule for an unsafe request (anything but
`GET`, `HEAD`, `OPTIONS`) that a session cookie authenticates ([contract](../../contracts/sessions-and-login.md)
section 7.2): `Sec-Fetch-Site` must be `same-origin` or `none`; without it `Origin` must equal the
origin of the public base URL (scheme, host, port, defaults dropped); with neither header the
request is refused. A refusal is `403 CROSS_ORIGIN_REQUEST`, logged at INFO with the method, path and
the two header values (cut to 100 characters, control characters replaced). Then a body (a length
above zero, or a `Transfer-Encoding`) must be `application/json` or `application/*+json`: `415
UNSUPPORTED_MEDIA_TYPE`. There is no synchronizer token, and CORS is off unless configured ([CORS](#cors)). `PublicFormGuard` is
the variant for an application's own public `POST` routes (a sign-up form): a request with neither
header passes, and the body must still be JSON. Both are `*CSRFGuard`; use `Check(r)` in a handler or
`Middleware()` on a route:

```go
rt.Handle("POST /signup", signup, httpfw.With(httpfw.PublicFormGuard(base).Middleware()))
```

**Form routes.** A server-rendered page ([Go: server-rendered pages](ui-templ.md)) posts native forms, whose
bodies are `application/x-www-form-urlencoded` or `multipart/form-data`. A route registered with the route option
`httpfw.AcceptForms()` takes those bodies from a cookie-authenticated request, after the provenance rules above,
which are unchanged and still run first (a form post without `Sec-Fetch-Site: same-origin`/`none` or a matching
`Origin` is `403`). Only `SessionsGuard` honours it, and only on the routes registered that way: every other route
keeps the JSON-only `415`, `PublicFormGuard` stays JSON-only for every caller, `text/plain` or a missing content
type is still `415` on a form route, and bearer requests are unaffected. The router records the flag on the matched
route before the context resolver and the middleware run; the guard reads it from the request's state.

```go
rt.HandleContext("POST /orders", createOrder, httpfw.AcceptForms())
```

The session module applies `SessionsGuard` itself (see [sessions and login](../sessions-and-login.md)).
`httpfw.SecurityHeaders()` is middleware that adds `Referrer-Policy: same-origin`, `X-Frame-Options:
DENY` and the baseline `Content-Security-Policy` to responses that lack them (pass an `spa.CSP` to
use another policy); `X-Content-Type-Options: nosniff` is already on every response, and the SPA's own
answers carry these headers. HSTS is the TLS terminator's job. Not verified: the guard is tested
with synthetic requests and, in the sessions module, over the real server; no browser was used.

## The server

`NewServer` returns a `*httpfw.Server`; `Port()` is valid once the application has started. It is
`net/http`'s server behind two guards: a request that arrives during the drain gets `503
UNAVAILABLE` with `Connection: close`, and a method the framework does not route `405`. The server
never sends a `Server` header or a stack trace. `http.request-timeout` is the read-header, read and
idle timeout. On shutdown `/health/ready` answers `503` from the moment it begins, the listener
closes, in-flight requests get `http.shutdown-grace` and the rest are closed; the application waits
for the server that long (`ShutdownGrace`) rather than `app.shutdown.drain`.

`net/http` does not cancel a request's context when it drains, so a long-lived response (a
[stream](#server-sent-events)) would hold the drain until the grace ran out. Two things end such a
response when shutdown begins, after the server has started refusing new requests and before it
waits for the ones in flight: the channel `httpfw.Draining(r)`, closed for every request (a
dispatcher that no `Server` runs never closes it), and the hooks registered with
`Server.BeforeDrain(func(ctx))`, which run in registration order with a context that ends with the
grace. From `httpfw/drain_test.go`:

```go
select {
case <-httpfw.Draining(r):
	_, _ = io.WriteString(w, "draining\n")
case <-r.Context().Done():
}
```

```go
srv.BeforeDrain(func(context.Context) {
	close(stop)
})
```

Health: `GET /health/live` is always `{"status":"UP"}`. `GET /health/ready` runs the checks and
answers `UP` (200), `DEGRADED` (200: only informational checks are not up) or `DOWN` (503: a
critical one is not), with `{"checks": {name: {"status": ..., "reason": ..., "informational": true}}}`.
With `http.metrics.enabled` the Prometheus text is served at `http.metrics.path`, behind
`http.metrics.bearer-token` when one is set (compared as digests in constant time); enabling it
without a token logs a warning.

Not as in Java: a request line `net/http` cannot parse (an invalid escape that Jetty also refuses) is
answered with `net/http`'s own plain-text `400`, not the envelope. The router is the framework's
own segment trie rather than `ServeMux`, so two patterns that overlap with neither more specific
(`/a/{x}/c` and `/a/b/{y}`) are accepted, as in Java.

## The single-page application

`spa.New(fsys, spa.Options{})` indexes a built SPA once, at start-up, from an `fs.FS`
(`//go:embed all:dist` in the application's own module, as `web/embed.go` does; `os.DirFS` for
development). Files and directories starting with `.` are not indexed, links are not followed, and
at most 10 000 files and 64 MiB are held. `Routes.SPA` mounts it as the router's **fallback**: it
answers only a request whose path matches no route, so a matched route is never shadowed and a
path with another method keeps the router's `405`. In the contract's order:

- outside the prefix (`Options.Prefix`, default `/`): the normal `404`;
- under an **API prefix** (the first literal segment of every registered route, `api` and `auth`,
  and `Options.APIPrefixes`; matched by whole segment): the JSON `404`, `Cache-Control: no-store`,
  never `index.html`, for every method;
- a method other than `GET` or `HEAD`: `405` with `Allow: GET, HEAD`;
- an unclean path (empty, dot or dotfile segment, backslash, control character): `404`;
- `index.html` (the prefix itself or a client route): `200`, `Cache-Control: no-cache`, a strong ETag
  (the first 16 bytes of SHA-256 as hex), `304` for a matching `If-None-Match` (a list, `W/` or `*`);
- an indexed file: files under `assets/` are `public, max-age=31536000, immutable`, others
  `no-cache`, with the content type from the contract's fixed table;
- a missing file under `assets/` or with a static-asset extension: the JSON `404`, `no-store`;
- anything else is a client route and gets `index.html`.

A client route and a server route therefore never share a first segment. Start-up problems: no
`index.html` (a problem unless `APP_ENV` is `local` or `test`, where it logs a warning and answers
`404` with a plain-text "frontend not built"), a second SPA, a route at the prefix itself, a
parameter as the first segment under the prefix, an API prefix that equals the first segment of an
indexed file.

Every SPA response carries `X-Content-Type-Options`, `Referrer-Policy: same-origin`,
`X-Frame-Options: DENY` and a `Content-Security-Policy`. The **strict** policy is the default
(`spa.Strict(hashes...)` takes the script hashes of inline snippets, for example the colour-mode
snippet from `@flowcatalyst/ui/csp`; it also sends `Cross-Origin-Opener-Policy` and
`Permissions-Policy`); `spa.Baseline()` is the opt-in that only restricts framing, plugins and
`<base>`. The Java builder has had the same default since 2026-10-15, so the conformance test
uses the default policy. Without a policy in `spa.Options.CSP`, the default takes its script hashes
from `csp-hashes.json` beside `index.html` (the `flowcatalystUi()` Vite plugin writes it; since G5c,
2026-10-18, as Java's `Spa` does): the file is never served (the usual JSON `404`), is not counted
among the files, and a file that is not `{"scriptHashes": ["sha256-..."]}` (or holds a value that is
not a `sha256-`, `sha384-` or `sha512-` hash) fails `spa.New`. A policy passed in `Options.CSP`
replaces the file, `spa.Baseline()` included, and the file is then not read. The vectors do not cover
the file; `httpfw/spa_test.go` has the Java `SpaTest` cases. No compression, `Range` or `Last-Modified`. The ETag is
stored under the name `ETag` as the contract spells it, where `http.Header.Get` looks for `Etag`;
a client on the wire sees either.

## CORS

Off by default. Listing origins in `http.cors.allowed-origins` (`scheme://host[:port]` each, nothing
after it, or `*` alone for any origin) turns it on for every route; the other settings have defaults:

| Setting | Default |
|---|---|
| `http.cors.allowed-origins` | empty: off |
| `http.cors.allowed-methods` | `GET, HEAD, POST, PUT, PATCH, DELETE` |
| `http.cors.allowed-headers` | `Authorization, Content-Type, DPoP, Idempotency-Key, Last-Event-ID, X-Correlation-Id` |
| `http.cors.exposed-headers` | `X-Correlation-Id, Idempotency-Replayed` |
| `http.cors.max-age` | `10m` (0 to 24h), the preflight's `Access-Control-Max-Age` |

A preflight (`OPTIONS` with `Access-Control-Request-Method`) from a listed origin is answered `204`
with the allowed methods, headers and max age before the application's middleware and the context
resolver run, so it is never authenticated (a browser sends no credentials with one). A request from
a listed origin gets `Access-Control-Allow-Origin` (the origin, or `*`) and the exposed headers;
every response carries `Vary: Origin` while CORS is on. Credentials are never allowed:
`Access-Control-Allow-Credentials` is never sent and there is no setting for it (sessions contract
section 7.2), so a session cookie is never usable across origins. A request from any other origin, or without `Origin`, is served as before with no
CORS headers, so the browser keeps the answer from the page. A malformed origin, method or header
name fails start-up naming the setting. CORS is for bearer-token callers from another origin: the
sessions CSRF guard still refuses a cookie-authenticated unsafe request from another origin.

## Server-sent events

Package `httpfw/sse` writes a `text/event-stream` response. The handler subscribes to its own live
source first, then calls `sse.Serve`, which writes the headers (`no-cache`, `X-Accel-Buffering: no`)
and the reconnection delay, replays what the client missed since its `Last-Event-ID` (the header, or
the `lastEventId` query parameter for EventSource polyfills) through the application's `sse.Replay`
page by page (`ReplayLimit`, default 1000), then writes the live events, skipping any whose id the
replay already sent. An idle stream sends a `: ping` comment every `Heartbeat` (default 25 s). It
returns when the client goes, when the live channel is closed (the client reconnects with its
`Last-Event-ID`) or when shutdown begins (`httpfw.Draining`), so an open stream never holds the drain.
`http.request-timeout` does not cut a stream. From `httpfw/sse/sse_test.go`:

```go
return sse.Serve(w, r, live, sse.Options{Replay: replayFrom(history, &calls), ReplayLimit: 2})
```

An `sse.Event` is an `ID` (increasing within the stream; 0 sends none), a `Type` (the `event:` field;
a line break in it is an error) and `Data` (one `data:` line per line). The event log, its fan-out
to the instances and its retention are the application's.

## Idempotency keys

Package `httpfw/idempotency` makes a write safe to send twice: a request with an `Idempotency-Key`
header runs once per client, caller and key, and a repeat gets the first answer again (status,
`Content-Type` and body, with `Idempotency-Replayed: true`). The keys are kept in the application's
database, table `fw_idempotency_keys` (migration set of module `http-idempotency`, applied to the
default database), and an hourly loop deletes the expired ones. From
`httpfw/idempotency/idempotency_test.go`:

```go
declared := idempotency.Declare(app)
```

```go
keys := idempotency.New(b, declared, db, opts...)
rt.Handle("POST /orders", handler, httpfw.RequiresContext(), httpfw.With(keys.Middleware))
```

- The first request takes the key. A success (2xx) keeps its answer; an error, a status of 300 or
  more, or a panic frees the key, so a retry does the work. A streamed answer (the handler flushed
  before it returned) is not kept either: its key is freed, so a repeat runs again.
- A repeat with the same method, path, query and body gets the kept answer. One that arrives while
  the first still runs waits for it (`idempotency.Wait`, default 10 s) and gets its answer, or `409
  IDEMPOTENCY_IN_PROGRESS` when the wait runs out: two concurrent requests run the handler once.
- The same key for another request is `422 IDEMPOTENCY_KEY_REUSED`; a key that is not 1 to 200
  printable ASCII characters is `400 IDEMPOTENCY_KEY_INVALID`; a key on a request whose context has
  no principal is `400 IDEMPOTENCY_KEY_UNSUPPORTED` (it cannot be scoped to a caller, so it is not
  run unprotected). The middleware on a route that resolves no context fails with `500`.
- Keys expire after `idempotency.Retention` (default 24 h): a repeat after it runs again. A
  reservation whose request never finished (the process stopped) is taken over after
  `idempotency.InFlightTimeout` (default 5 min).
- The body is read within the route's body limit. Only the status, `Content-Type` and body are
  replayed, never `Set-Cookie` or other headers.
