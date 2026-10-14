# Contract: static assets and the SPA

Status: written 2026-10-14, before any code, for the UI direction decided that day; rulings 2, 4 and
5 of section 12 confirmed by the owner the same day (Nuxt UI SPA as
the primary UI for the Java and Go frameworks; [`../handover.md`](../handover.md), "UI direction").
*Built in Java 2026-10-14 (MS, `framework-http`); amendments the build found are marked "Amended
2026-10-14 (MS)".* Go has not built it. **Language-neutral**: Java (`framework-http`) builds it first and Go
(`httpfw/spa`, [`../go/capabilities.md`](../go/capabilities.md) section 5) implements the same
rules. Java names appear only where the Java shape is described.

The reference is the FlowCatalyst platform's SPA handler as fixed on 2026-10-13
(`flowcatalyst-go/frontend/handler.go`, `docs/platform-bug-fixes.md` F16, finding 2). Where this
file differs from the platform, the difference is listed in section 11 with its reason; everything
else is meant to answer as the platform answers.

## 1. Shape

An application serves one built single-page application (Vite output: `index.html`, content-hashed
files under `assets/`, a few unhashed files such as `favicon.ico`) next to its JSON API, from the
same origin and the same process. The browser's sign-in, session and CSRF rules are those of
[`sessions-and-login.md`](sessions-and-login.md); this contract is only about serving files.

- **The SPA is the router's fallback.** It is consulted only for a request whose **path matches no
  route** (http contract section 2). A path that matches a route with another method keeps the
  router's `405` with `Allow`; a matched route is never shadowed by a file. So the http contract's
  routing rules are unchanged: no wildcard routes are added (this replaces the trailing-wildcard
  amendment the Go design proposed as decision 28).
- **One SPA per application**, mounted at a **prefix** (default `/`; for example `/app`). Outside the
  prefix the fallback is the framework's normal `404 NOT_FOUND` envelope.
- The SPA handler runs **inside the framework's pipeline** like any terminal handler: correlation id,
  error mapping, access log, then the application's global middleware (http contract section 4). The
  access log and the HTTP metrics label its requests with the fixed template `(spa)`, never the raw
  path (cardinality).
- Nothing is rendered or templated on the server: `index.html` is served as built. Configuration the
  SPA needs at run time comes from an API call (for example `GET /auth/session`), never from values
  injected into the HTML.

## 2. Declaring it

Behaviour is code (owner ruling 2026-10-13); there is no configuration key for the SPA.

```java
// Java. In a module's boot.
routes.spa(Spa.classpath("fw-spa")      // or Spa.directory(Path.of("web/dist")) for development
    .prefix("/")                         // default "/"
    .apiPrefix("runtime")                // extra API prefixes (section 5); usually none are needed
    .build());                           // reads and indexes the files (section 3)
```

*Amended 2026-10-14 (MS), the Java shape as built:* `Spa.classpath(root)` (the context class
loader, else the framework's) and `Spa.classpath(root, loader)`; `Spa.directory(path)`; on the
builder `prefix`, `apiPrefix` (one segment each), `assetsDirectory` (default `assets`), `maxFiles`,
`maxBytes`, `contentSecurityPolicy(Csp.baseline() | Csp.strict(hashes...))`; `Routes.spa(spa)`. The
not-built rule of section 3 needs `APP_ENV`: `Routes` is created with the environment
(`new Routes(environment)`; the HTTP module passes `Config.environment()`); `new Routes()` knows no
environment and treats a missing `index.html` as a start-up problem. Builder misuse (a malformed
prefix or segment, a limit below 1, a malformed script hash) is `IllegalArgumentException`; what the
files and routes cause is a start-up problem.

```go
// Go
spa.Handler(dist, spa.Options{Prefix: "/", APIPrefixes: []string{"runtime"}}) // dist: an fs.FS from //go:embed all:dist
```

- A second SPA in one application is a start-up problem naming both registrants.
- A route registered for `GET /` (or for the prefix itself) when an SPA is mounted there is a
  start-up problem ("route GET / shadows the SPA's index.html"). *Amended 2026-10-14 (MS): for any
  method, and with a trailing slash too (`/app/`): a `POST /` makes `GET /` the router's `405`, which
  hides `index.html` as surely.*
- When the SPA is mounted at `/`, a route whose **first segment is a parameter** (`/{tenant}/x`) is
  a start-up problem: every first segment would be an API prefix (section 5). *Amended 2026-10-14
  (MS): for a mount at a prefix, the segment after the prefix.*
- *Amended 2026-10-14 (MS):* an API prefix (section 5, from any of its three sources) that equals the
  first segment of an indexed file (`assets`, `favicon.ico`) is a start-up problem: rule 2 would
  answer every one of those files with the JSON 404.

## 3. The file index

The handler builds an **index once at start-up** and serves only from it; files are not looked up
on disk or on the classpath per request.

| Source | Java | Go |
|---|---|---|
| a named classpath root | `Spa.classpath("fw-spa")`: the root must resolve to a `file:` directory or a directory inside one `jar:` file, which is opened and walked. Any other URL form is a start-up problem. This reads one named directory of the application's own jar; it is not classpath scanning | `//go:embed all:dist` in the application's own module (`web/embed.go`), passed as an `fs.FS` |
| a directory on disk | `Spa.directory(Path)`, for development | `os.DirFS`, for development |

- Every **regular file** under the root is indexed, with its bytes, its content type (section 6.2)
  and its ETag (section 6.3). Symbolic links are not followed. A file or directory whose name
  starts with `.` (Vite's `.vite/` manifest, editor files) is not indexed.
- **Limits**, changeable in code: at most 10 000 files and 64 MiB in total; over either is a
  start-up problem. The bytes are held in memory (a built SPA is a few megabytes and never changes
  for the life of the process).
- **No `index.html` at the root** ("frontend not built"): when `APP_ENV` is `local` or `test`, start-up
  logs at WARN ("SPA not built: run the web build; serving 404") and every request the SPA would
  have answered gets `404` with the plain-text body `Frontend not built into this application. Run
  the web build (pnpm --dir web build) and rebuild.\n`, `Cache-Control: no-store`. In any other
  environment it is a **start-up problem**: a deployment without its UI must not start and look
  healthy (owner principle: never skip silently).

## 4. Answering a request

For a request the router did not match, in this order; the first rule that applies answers:

| # | Request | Answer |
|---|---|---|
| 1 | path outside the SPA's prefix | the normal `404 NOT_FOUND` envelope (http contract section 5) |
| 2 | path under an **API prefix** (section 5) | `404`, the envelope `{"error": "NOT_FOUND", "message": "no route for this path"}` (the router's own 404, unchanged), `Cache-Control: no-store` and the section 6.1 headers. **Never** `index.html`, for any method |
| 3 | method other than `GET` or `HEAD` | `405`, `Allow: GET, HEAD`, the envelope `METHOD_NOT_ALLOWED`, `Cache-Control: no-store` |
| 4 | a path that is not clean (section 4.1) | `404` envelope `NOT_FOUND`, `Cache-Control: no-store` |
| 5 | empty (the prefix itself) or exactly `index.html` | `index.html` (section 6) |
| 6 | an indexed file | the file (section 6) |
| 7 | a path under the hashed directory (`assets/`, or `assets` itself), or whose last segment ends in a **static-asset extension** (section 4.2) | `404` envelope `NOT_FOUND`, `Cache-Control: no-store`: a stale hashed chunk after a deploy must fail as a 404, not load `index.html` as JavaScript; `no-store` so a deploy in progress does not leave a cached 404 |
| 8 | anything else (a client route: no static-asset extension) | `index.html`, `200` (section 6), so vue-router's history mode takes over |

`HEAD` is answered as `GET` with the same headers (including `Content-Length`) and no body.

*Amended 2026-10-14 (MS):*

- The **not-built answer** (section 3) applies after rule 2: an unknown API path keeps its JSON 404;
  every other request the SPA sees gets the not-built `404`.
- A path whose percent-escapes are malformed (`%zz`, invalid UTF-8) is refused by the router with
  `400 VALIDATION` before the SPA is consulted (http contract section 2, unchanged).
- Rules 1 and 2 are the router's own 404 and keep the access-log label `<unmatched>`; rules 3 to 8
  are labelled `(spa)`.
- `Content-Length` on `HEAD` is the dispatcher's, for every route: a `HEAD` answered from a `GET`
  with an in-memory body carries that body's length (an http contract section 2 amendment; before,
  the server sent `Content-Length: 0`).

### 4.1 Clean paths

The path after the prefix, split into segments and percent-decoded per segment (as the router
does), is clean when no segment is empty (no `//`, no trailing `/` except the prefix itself), `.` or
`..`, or starts with `.`, and no segment contains `\`, NUL or a character below U+0020 or U+007F.
*Amended 2026-10-14 (MS):* nor a `/` (an encoded `%2F`), so a decoded segment can never name a file in
another directory of the index.
Matching against the index is exact and case-sensitive. A directory is never a file: `/assets/`
falls through to rule 7.

*Amended 2026-10-14 (MS):* a server that refuses an unclean request line before the framework sees
it satisfies rule 4: Jetty's URI compliance answers most of them (`..`, `%2e%2e`, `//`, `\`, `%00`,
`%0d%0a`, even `%252e`) with `400` (the framework's `VALIDATION` envelope for Jetty's own errors),
and the path never reaches the index. The rule's `404` is what the framework answers for whatever
gets through, and what the in-process client and the conformance vector check.

### 4.2 Static-asset extensions

An allow-list, compared case-insensitively on the last segment's extension, never "has a dot"
(client routes carry identifiers that may contain dots, for example `/ingestions/acme.orders`):

`.js .mjs .cjs .css .map .json .webmanifest .txt .xml .wasm .html .htm .png .jpg .jpeg .gif .svg
.ico .webp .avif .bmp .woff .woff2 .ttf .otf .eot`

The platform's list, unchanged. A client route must therefore not end in one of these extensions.

## 5. API prefixes

A request under an API prefix is never answered with `index.html` (rule 2). The prefixes are
matched by **whole first segment** after the SPA's prefix (`/apiary` is not under `/api`) and are
the union of:

1. **the first literal segment of every registered route** (derived at start-up: `/api/v1/x` gives
   `api`, `/runtime/v1/poll` gives `runtime`, `/health/ready` gives `health`, a receiver under `/fc`
   gives `fc`);
2. `api` and `auth`, always (so an unknown `/api/x` or `/auth/x` is a JSON 404 even before any route
   under them exists);
3. those added in code (`apiPrefix(...)`), for a prefix served by something outside the router.

The consequence, stated plainly: **a client route and a server route never share a first segment.**
A server route at `/orders/{id}` makes every `/orders/...` path an API path; the SPA's own pages for
orders then live elsewhere (for example `/app/orders` or under a different first segment). The
platform keeps a fixed list (`api bff oauth .well-known control q`); deriving the list removes the
maintenance trap of a route added under a new prefix and silently answered with `index.html`.

## 6. Responses

### 6.1 Headers on every response of the SPA handler

Including `304`, `404`, `405` and the not-built answer:

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `same-origin` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `frame-ancestors 'none'; object-src 'none'; base-uri 'self'` (the **baseline**; section 7) |

`X-Content-Type-Options: nosniff` is also added by the framework to **every** response it sends,
API and errors included (an amendment to http contract section 4's fixed middleware; it costs
nothing and makes a JSON 404 unusable as a script).

### 6.2 Files

| | `index.html` | a file under the hashed directory (`assets/`) | any other indexed file (`favicon.ico`, `robots.txt`) |
|---|---|---|---|
| `Cache-Control` | `no-cache` (revalidated with the ETag, so a new deploy's shell is picked up on the next load; the platform's old `no-store` forced a full download) | `public, max-age=31536000, immutable` | `no-cache` |
| `ETag` | yes | yes | yes |
| `Content-Type` | by extension, table below | | |

The hashed directory is `assets` (Vite's `build.assetsDir` default); it can be changed in code to
match the build.

| Extension | `Content-Type` |
|---|---|
| `.html`, `.htm` | `text/html; charset=utf-8` |
| `.js`, `.mjs`, `.cjs` | `text/javascript; charset=utf-8` |
| `.css` | `text/css; charset=utf-8` |
| `.json`, `.map` | `application/json` |
| `.webmanifest` | `application/manifest+json` |
| `.txt` | `text/plain; charset=utf-8` |
| `.xml` | `application/xml` |
| `.wasm` | `application/wasm` |
| `.svg` | `image/svg+xml` |
| `.png`, `.jpg`/`.jpeg`, `.gif`, `.webp`, `.avif`, `.bmp`, `.ico` | `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `image/avif`, `image/bmp`, `image/x-icon` |
| `.woff`, `.woff2`, `.ttf`, `.otf`, `.eot` | `font/woff`, `font/woff2`, `font/ttf`, `font/otf`, `application/vnd.ms-fontobject` |
| anything else | `application/octet-stream` |

The table is fixed so Java and Go answer the same bytes (the JDK's and Go's MIME tables differ).

### 6.3 ETags and conditional requests

- The ETag is **strong**: `"` + the first 16 bytes of SHA-256 of the file's bytes as lowercase hex
  (32 characters) + `"` (the platform's form). Computed once, at indexing.
- `If-None-Match` (a list, or `*`) matching the file's ETag (weak comparison: `W/"x"` matches
  `"x"`): `304 Not Modified` with `ETag`, `Cache-Control` and the section 6.1 headers, no body and
  no `Content-Type`.
- No `Last-Modified` is sent; `If-Modified-Since` is ignored. `Range` is ignored (a `200` with the
  whole file; no `Accept-Ranges`).

## 7. Content-Security-Policy

- **Strict (the default, amended 2026-10-14 after U0):** the policy of [`../go/ui.md`](../go/ui.md) section 9 (`default-src
  'self'`, `script-src 'self'` plus the hashes the application passes for its inline snippets,
  `style-src 'self'`, `img-src 'self' data:`, `font-src 'self'`, `connect-src 'self'`,
  `frame-ancestors 'none'`, `base-uri 'none'`, `form-action 'self'`, `object-src 'none'`), with
  `Cross-Origin-Opener-Policy: same-origin` and `Permissions-Policy: camera=(), microphone=(),
  geolocation=()`. Java: `.contentSecurityPolicy(Csp.strict("sha256-..."))`.
- **Baseline (opt-in, in code):** the platform's policy, which restricts framing, plugins and
  `<base>` only: `frame-ancestors 'none'; object-src 'none'; base-uri 'self'`. It does not restrict
  scripts, styles or connections; an application whose pages cannot run under strict chooses it.
- **Why strict is the default.** The owner ruled (2026-10-14) that strict becomes the default if U0
  shows Nuxt UI runs under it. U0 ([`../ui/u0.md`](../ui/u0.md), commit 48a545b) verified it in
  Chromium under Playwright: the gallery (dashboard shell, modals, slideovers, popovers, menus, forms,
  tables, date inputs, toasts, both colour modes) produced no `securitypolicyviolation`, no console
  message about the policy and no request to another origin, with exactly the policy above, one
  script hash and no style source besides `'self'`. Firefox and WebKit were not run.
- **Conditions.** The result holds only for an SPA that (1) is built with the `flowcatalystUi()` Vite
  plugin from `@flowcatalyst/ui`, which turns Nuxt UI's runtime colour styles and `vaul-vue`'s
  injected styles into a stylesheet at build time and writes the colour-mode snippet into
  `index.html`; and (2) sets Zod to `jitless` (`z.config({ jitless: true })`) before any schema is
  built, because Zod 4 otherwise probes `new Function("")`, which is reported as a `script-src`
  violation (`@flowcatalyst/api-runtime` takes this over at U2). The one inline script's hash is
  `COLOR_MODE_SNIPPET_SHA256`, and the exact policy string is built by the CSP helpers, both exported
  from `@flowcatalyst/ui/csp`; the application passes that hash to the Spa builder. Components
  known to break the policy (`UBanner` with an `id`, `USplitter`) are forbidden by the package's lint.
- **Follow-up (not done; Java code unchanged by this amendment):** the Java `Spa` builder's default
  must flip to strict, with the exact CSP and script hash taken from `@flowcatalyst/ui/csp`, and the
  baseline becomes the explicit opt-in (`Csp.baseline()`). Until that change lands, `Spa` still
  defaults to the baseline.
- HSTS is the TLS terminator's job (the http contract leaves TLS outside the application).

## 8. Build integration

**Java (ruling taken, section 12):** Maven never runs Node. The SPA is built by its own toolchain
(`pnpm --dir web build`, Vite writing `web/dist/`); Maven copies the output into the jar as an
ordinary resource:

```xml
<!-- the application's pom.xml (sketch) -->
<resource>
  <directory>web/dist</directory>
  <targetPath>fw-spa</targetPath>
</resource>
```

CI runs the web build before `./mvnw verify`. A developer who builds only the Java side gets a jar
without the SPA, which starts in `local` and `test` with the not-built answer (section 3) and fails
start-up elsewhere. The application's tests include one that starts it with the SPA and checks
`index.html`, one hashed asset, a missing asset and an unknown API path with the headers of section
6 (the framework provides the assertion helper; Java: `SpaTesting.assertServesBuild`).

**Go:** `web/embed.go` with `//go:embed all:dist` in the application's own module (`go:embed`
cannot reach outside its module), as the platform does.

## 9. Development

Vite's dev server serves the SPA (`pnpm --dir web dev`, port 5173) and proxies to the application
(Java: the port in `http.port`; never 8080, where a developer's local FlowCatalyst runs):

- every API prefix of section 5 is proxied (`/api`, `/auth`, the application's own);
- HTML navigations stay with Vite **except** `/auth/login` and `/auth/callback`, which are proxied
  (the platform's proxy `bypass` rule), so the sign-in round trip reaches the application;
- `auth.public-base-url` is the **Vite origin** (`http://localhost:5173`) in development, so the
  OIDC redirect URI, the session cookie and the CSRF origin are all the page's origin, as in
  production. Sessions over plain http are allowed in `local` and `test` only (sessions contract
  section 5.3).

`Spa.directory(Path.of("web/dist"))` serves a local build without rebuilding the jar.

## 10. Tests

At minimum, each failing before its rule exists (the platform's `frontend/handler_test.go` matrix,
mutation-checked: dropping the API-prefix branch, the asset 404, `no-cache` on the index, the ETag,
or the 405 must each fail a test):

- client routes (with and without dots in a non-asset segment) answer `index.html` `200` `no-cache`
  with the ETag; `/` and `/index.html` too; `304` on `If-None-Match`, including `W/` and lists;
- an indexed hashed asset: `immutable`, the content type of section 6.2, the ETag; an unhashed file:
  `no-cache`;
- a missing asset under `assets/` and a missing file with each asset extension: `404` envelope
  `no-store`;
- API prefixes: by whole segment (`/apiary` is a client route), derived from routes and the fixed
  ones, for `GET`, `POST` and `DELETE`: `404` JSON, never HTML; a path matching a route with another
  method: the router's `405`;
- `POST` to a client route: `405` with `Allow: GET, HEAD`;
- unclean paths (`..`, `%2e%2e`, `//`, `\`, NUL, dotfiles) never leave the index and answer `404`;
- the section 6.1 headers on every answer, `nosniff` on API responses;
- `HEAD` equals `GET` without the body;
- start-up: no `index.html` (WARN and the not-built answer in `local`/`test`, a start-up problem
  otherwise), two SPAs, a route at `GET /`, a parameter first segment, the size and count limits;
- the real build: the application's jar (Java) or embedded build (Go) serves its own `index.html`
  and one hashed asset.

A conformance vector (`conformance/http/spa.json`: requests against a fixed fixture tree and the
expected status, headers and body hash) is written by the Java tests and compared by Go's. *Written 2026-10-14 by `SpaConformanceTest` (in process; `X-Correlation-Id` and `Content-Length`
left out of the headers, the body's length recorded instead).*

## 11. Differences from the platform's handler, on purpose

| Platform (2026-10-13) | This contract | Why |
|---|---|---|
| a fixed API prefix list | derived from the routes, plus `api`, `auth` and code (section 5) | no silent `index.html` for a route added under a new prefix |
| `405` and missing-asset `404` as plain text | the JSON envelope | http contract section 5: every non-2xx the framework produces has the envelope; with `nosniff` it cannot load as a script |
| `nosniff` only on the SPA's responses | on every response | cheap; one rule |
| reads the embedded FS per request, ETags cached on first use | an index built at start-up | a missing `index.html` is found at start-up, not on the first request |
| the fallback is chi's `NotFound` plus a GET-method-mismatch fallback | the router's fallback for a path with no route; a method mismatch is always the router's `405` | the framework's router already answers `405` correctly |

## 12. Rulings

Rulings 2, 4 and 5 were confirmed by the owner on 2026-10-14; the others were taken here and the
owner may reverse them.

1. **The SPA is the router's fallback**, not a wildcard route; the http contract keeps "no
   wildcards".
2. **API prefixes are derived from the routes** (section 5), so client and server routes never share
   a first segment. *Owner, 2026-10-14: accepted.*
3. **A missing `index.html` fails start-up** outside `local` and `test`.
4. **Maven never runs Node**; CI builds the SPA first and Maven copies `web/dist` (section 8). The
   alternative (the `frontend-maven-plugin`, which downloads Node and pnpm during the Maven build)
   keeps one command but puts a JavaScript toolchain inside every Java build. *Owner, 2026-10-14:
   confirmed; the SPA is built separately and Maven copies `web/dist`.*
5. **Strict CSP by default** (amended 2026-10-14: U0 showed Nuxt UI runs under it, under the
   conditions of section 7); the baseline is the opt-in. *Owner, 2026-10-14: the strict policy
   becomes the default if U0 shows Nuxt UI runs under it.* The Java `Spa` default still has to be
   flipped (section 7, follow-up).
6. **No compression** (no precompressed `.br`/`.gz` siblings, as [`../go/ui.md`](../go/ui.md) section 9
   sketched): compression is outside the http contract; a proxy or CDN compresses. Both
   implementations leave it out so they answer the same bytes.

## 13. Out of scope

Compression; `Range`; several SPAs in one application; server-side rendering or templating of
`index.html`; service-worker support beyond serving the file; CORS (none anywhere in the framework);
serving files that are not part of the built SPA (uploads, generated reports: those are routes).
