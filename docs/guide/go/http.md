<!-- types: CSRFGuard, Routes, Dispatcher, Server, Declared, Error, Detail, ServiceError, HandlerFunc, ContextHandlerFunc, Middleware, ContextResolver, RouteOption, Option, SPA, Options, CSP, Handler -->
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
`http.metrics.bearer-token`), a claim on the `http` component (a misspelt key under it fails
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
undeclared or declared but not bound, and the SPA's own checks. Registering after the server was
built panics.

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
`http.max-body-bytes` (by `Content-Length` or while reading, `Connection: close`), unknown members,
duplicate names and wrong-case names refused. A decode failure is one `INVALID_JSON` detail at the
member's path (`lines[1].sku`); the message never echoes the value.

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
UNSUPPORTED_MEDIA_TYPE`. There is no synchronizer token and no CORS, anywhere. `PublicFormGuard` is
the variant for an application's own public `POST` routes (a sign-up form): a request with neither
header passes, and the body must still be JSON. Both are `*CSRFGuard`; use `Check(r)` in a handler or
`Middleware()` on a route:

```go
rt.Handle("POST /signup", signup, httpfw.With(httpfw.PublicFormGuard(base).Middleware()))
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
