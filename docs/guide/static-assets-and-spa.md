<!-- types: Spa, Csp, Routes, SpaTesting, TestClient -->
# Static assets and the SPA

An application serves one built single-page application (Vite's output: `index.html`, the
content-hashed files under `assets/`, a few unhashed files such as `favicon.svg`) from the same
origin and process as its JSON API. `framework-http` does it as the **router's fallback**: a request
whose path matches no route goes to the SPA, which answers with a file, `index.html`, or a 404. The
rules, and why each exists, are in
[contracts/static-assets-and-spa.md](../contracts/static-assets-and-spa.md); this page is how to use
them.

## Mounting it

In a module's `boot`, next to its routes. Behaviour is code; there is no configuration key.

```java
// the build Maven copied into the jar (see "Packaging" below)
routes.spa(Spa.classpath("fw-spa").build());

// or a local build on disk, during development, without rebuilding the jar
routes.spa(Spa.directory(Path.of("web/dist")).build());
```

`build()` reads every regular file under the root once, at start-up, and keeps it in memory with its
content type and ETag; nothing is read per request, so a file written after start-up is not served.
Symbolic links are not followed, and names starting with `.` (Vite's `.vite/` manifest, editor
files) are not indexed. A classpath root must be a `file:` directory or a directory inside one jar
(nested "fat" jars are a start-up problem).

The builder:

| Method | Default | What it does |
|---|---|---|
| `prefix("/app")` | `/` | where the SPA lives; outside it the router's normal 404 answers. Vite's `base` must match (`/app/`) |
| `apiPrefix("runtime")` | none | an extra API prefix (below), for something served outside the router |
| `assetsDirectory("static")` | `assets` | Vite's `build.assetsDir`: its files are `immutable`, a missing one is a 404 |
| `maxFiles(n)`, `maxBytes(n)` | 10 000, 64 MiB | more is a start-up problem |
| `contentSecurityPolicy(Csp.strict(...))` | `Csp.baseline()` | see "Security headers" |

A malformed prefix or segment is an `IllegalArgumentException` at once; problems with the files or
the route table are start-up problems, listed together with every other one.

## What it answers

For a path no route matched, the first row that applies answers:

| Request | Answer |
|---|---|
| outside the SPA's prefix | the normal `404 NOT_FOUND` envelope |
| under an **API prefix** | `404`, `{"error": "NOT_FOUND", "message": "no route for this path"}`, `no-store`, for every method; never `index.html` |
| not `GET` or `HEAD` | `405 METHOD_NOT_ALLOWED`, `Allow: GET, HEAD`, `no-store` |
| an unclean path (`..`, `.`, `//`, a trailing `/`, a dot file, `\`, an encoded `/`, NUL or a control character) | `404`, `no-store` |
| `/` (the prefix) or `/index.html` | `index.html`, `no-cache` |
| an indexed file | the file: under `assets/` `public, max-age=31536000, immutable`, otherwise `no-cache` |
| a missing file under `assets/`, or with a static-asset extension (`.js`, `.css`, `.png`, `.json`, ...) | `404`, `no-store`: a stale chunk after a deploy fails as a 404, not as HTML parsed as JavaScript |
| anything else (a client route such as `/orders/7` or `/ingestions/acme.orders`) | `index.html`, `200`, so vue-router's history mode takes over |

Every file carries a strong ETag (the first 16 bytes of the SHA-256 of its bytes, in hex) and
`If-None-Match` answers `304`. `HEAD` is `GET` without the body (with the same `Content-Length`).
`Range` and `If-Modified-Since` are ignored: the answer is always the whole file. Requests are
labelled `(spa)` in the access log and the HTTP metrics, never with their path.

A path with a malformed percent-escape (`%zz`) is the router's `400 VALIDATION`, before the SPA.
Over Jetty, most unclean paths (`..`, `%2e%2e`, `//`, `\`, `%00`) are refused by Jetty's own URI
checks with `400 VALIDATION` before the framework sees them; the framework's `404` applies to what
gets through, and either way nothing outside the index is ever read.

## API prefixes

A path under an API prefix never gets `index.html`, so a mistyped or removed API call fails as JSON
instead of answering `200` and HTML. The API prefixes are matched by whole first segment after the
SPA's prefix (`/apiary` is a client route, `/api/x` is not) and are:

1. **the first segment of every route** you register (`/api/v1/orders` gives `api`, `/health/ready`
   gives `health`, a receiver under `/fc` gives `fc`);
2. `api` and `auth`, always;
3. the ones you add with `apiPrefix(...)`.

The consequence: **a client route and a server route never share a first segment.** With a route
`GET /orders/{id}`, `/orders` and `/orders/7/edit` are API paths and answer the JSON 404; put the
SPA's order pages under another first segment (for example `/app/orders`, or route the API under
`/api`). The start-up log lists them: `SPA / (web): 10 files, API prefixes api, auth, health, runtime`.

Start-up fails when:

- a route sits at the SPA's prefix itself (`GET /`, or `POST /`, which would make `GET /` a 405), or
  its first segment after the prefix is a parameter (`/{tenant}/orders`: every path would be an API
  path);
- an API prefix equals the first segment of a built file (a route under `/assets` would hide every
  asset);
- a second SPA is mounted (one per application);
- the root is not a directory, a limit is exceeded, or the classpath root is somewhere it cannot be
  read.

## Not built

With no `index.html` (the developer built only the Java side, or the directory does not exist):

- when `APP_ENV` is `local` or `test`, start-up logs a WARN and every request the SPA would answer
  gets `404` with the plain-text body `Frontend not built into this application. Run the web build
  (pnpm --dir web build) and rebuild.` (`no-store`); unknown API paths keep the JSON 404;
- in **any other environment it is a start-up problem**: a deployment without its UI must not start
  and look healthy.

`Routes` learns the environment from the HTTP module (`Config.environment()`). A table you create
yourself in a test is `new Routes("test")`; `new Routes()` knows no environment and is as strict as a
deployed one.

## Security headers

Every answer of the SPA (`304`, `404` and `405` included) carries:

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` (on **every** framework response, API and errors too) |
| `Referrer-Policy` | `same-origin` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `frame-ancestors 'none'; object-src 'none'; base-uri 'self'` |

That **baseline** policy restricts framing, plugins and `<base>` only, as the platform's does. The
**strict** policy is opt-in:

```java
Spa.directory(dist).contentSecurityPolicy(Csp.strict("sha256-AbC+/09=")).build()
```

It allows scripts, styles, images (`data:` too), fonts and connections from the application's own
origin only, plus the hashes you pass for inline snippets (such as a colour-mode script), and adds
`Cross-Origin-Opener-Policy: same-origin` and `Permissions-Policy: camera=(), microphone=(),
geolocation=()`.

**TODO (default flip):** U0 verified that Nuxt UI runs under the strict policy
([`../ui/u0.md`](../ui/u0.md)), so the contract now makes strict the default (owner ruling
2026-10-14), on two conditions: the SPA is built with the `flowcatalystUi()` Vite plugin from
`@flowcatalyst/ui` and sets Zod to `jitless` before any schema exists. The `Spa` builder has not
been changed yet: today the baseline is still its default, and strict is opt-in as above. The
follow-up is to flip the default, taking the exact CSP and script hash from `@flowcatalyst/ui/csp`.
Until then, an application that turns strict on must check its own pages in a browser.

HSTS is the TLS terminator's job; there is no compression (a proxy or CDN compresses) and no CORS.

## Packaging: Maven copies the build, never runs Node

The SPA is built by its own toolchain (`pnpm --dir web build`, Vite writing `web/dist/`); Maven only
copies the output into the jar as an ordinary resource. From `examples/orders-demo/pom.xml`:

```xml
<resources>
  <resource>
    <directory>src/main/resources</directory>
  </resource>
  <resource>
    <directory>web/dist</directory>
    <targetPath>fw-spa</targetPath>
  </resource>
</resources>
```

Listing `<resources>` replaces Maven's default, so keep `src/main/resources` in it. A missing
`web/dist` is skipped by Maven, which gives a jar without the SPA: it starts in `local` and `test`
with the not-built answer and fails start-up anywhere else. CI therefore runs the web build first,
then `./mvnw verify`.

## Development

Run Vite's dev server (`pnpm --dir web dev`, port 5173) and let it proxy the API prefixes to the
application (on its own `http.port`; never 8080, where a local FlowCatalyst runs), and the HTML
navigations `/auth/login` and `/auth/callback` too, so the sign-in round trip reaches the
application. Set `auth.public-base-url` to the Vite origin so cookies and the OIDC redirect are the
page's origin, as in production. To serve a local build without rebuilding the jar, mount
`Spa.directory(Path.of("web/dist"))`.

## Testing

`SpaTesting.assertServesBuild(client)` checks a built SPA the way the contract asks every
application to: `index.html` with its caching and security headers, the first hashed asset it
references (`immutable`), a missing asset (`404`, `no-store`) and an unknown API path (JSON `404`,
never HTML). It throws `AssertionError` with what was wrong.

```java
Routes routes = new Routes("test");
routes.forModule("web").spa(Spa.directory(dist).build());
SpaTesting.assertServesBuild(TestClient.of(routes));
```

With a prefix, `SpaTesting.assertServesBuild(client, "/app")`. In an application, run it against the
real jar's SPA (`Spa.classpath("fw-spa")`) in a test that runs after the web build.

## Differences from the platform's handler

The platform's SPA handler (`flowcatalyst-go/frontend/handler.go`) is the reference; these are on
purpose: the API prefixes are derived from the routes instead of a fixed list (so the platform's
`/auth/login` client route would be an API path here); the `405` and the missing-asset `404` are the
JSON envelope instead of plain text; `nosniff` is on every response; the index is built at start-up
(a missing `index.html` is found then, not on the first request); and unclean paths (`/dashboard/`, `//x`) are a 404 instead of being cleaned to a client
route.
