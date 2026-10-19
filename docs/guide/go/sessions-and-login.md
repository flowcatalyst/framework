<!-- types: Module, Declared, ContextResolver, Sessions, Authenticated, PermissionMapper, ClientSelector, LoginFailure, LoginFailureHandler, ReturnToPolicy, Testing, PlatformTokens, AccessToken, TokenResult, Valid, Invalid, Unavailable, Principal, Provider, Signing, Store, Kind, StoreFactory, MemoryStore, CookieStore, CookieKeys -->
# Go: sessions and login

Package `auth` is the Go implementation of the [sessions and login contract](../../contracts/sessions-and-login.md)
(the Java page is [Sessions and login](../sessions-and-login.md)): the application as its own
backend-for-frontend and an OIDC relying party of the FlowCatalyst platform. It is built in slices
(G5b, 2026-10-18); this page says what exists, what was checked and what was not. **Java is the
reference implementation**: where the contract is silent the Go code follows the Java code, and the
shared vectors under `conformance/` are the truth Go must pass.

State of the slices:

| Slice | Contents | State |
|---|---|---|
| 1 | `httpfw.SessionsGuard` / `PublicFormGuard` (the F16 CSRF rules) and `httpfw.SecurityHeaders` ([HTTP page](http.md#csrf-and-security-headers)) | built |
| 2 | the relying party (discovery, JWKS cache, PKCE S256 authorize URL, code exchange, id_token checks), `PlatformTokens`, `authtest.Provider` | built |
| 3 | session stores (`memory`, `cookie`, `database`, `redis`), session and login records, idle and absolute lifetimes | built |
| 4 | the four endpoints, the context resolver, 401 versus 302, client selection and permission mapping, `Context.permissions`, `auth.Testing`, the login-failure vector | built |
| G5c | `examples/orders-demo` on the module and the browser end-to-end launcher ([below](#the-orders-demo-on-sessions-and-the-browser-suite)) | built |

## Verifying platform tokens on their own

`auth.NewPlatformTokens(issuer, timeout, clock, logger, opts...)` verifies a platform **access token**
(contract section 5.6) without any session machinery, for an application that accepts service
tokens on some endpoints. It is safe for concurrent use; build one and keep it, because it caches the
platform's discovery document and signing keys.

```go
tokens, err := auth.NewPlatformTokens("https://platform.example.com", 10*time.Second, b.Clock(), logger)
switch r := tokens.Verify(ctx, bearer).(type) {
case auth.Valid:        // r.Token.Principal, r.Token.MayActFor("acme"), r.Token.MayUse("orders"), r.Token.ExpiresAt
case auth.Invalid:      // 401; r.Reason is the contract's name (alg, signature, expired, token_use, ...)
case auth.Unavailable:  // 503: the keys cannot be read right now; not a verdict on the token
}
```

An `http://` issuer is refused unless you pass `auth.AllowPlainHTTP()`; do that only when `APP_ENV`
is `local` or `test`. The verifier reads keys detached from the request that triggers the read, so a
client that hangs up cannot hold new signing keys back.

The rules are the contract's, in its order: at most 16 384 characters and three base64url parts
(`malformed`); `alg` exactly `RS256`, decided before any key is looked at, so `none`, `HS256` and
every other value are refused with no algorithm confusion (`alg`); `crit` absent; a non-empty `kid`;
the key for that `kid` from the platform's own key set (`unknown_kid`); the RS256 signature
(`signature`); `iss` equal to the issuer; `aud` the issuer; `exp`, `nbf` and `iat` with a fixed
60-second allowance; `token_use` exactly `api` (an identity token from a default login client is
refused); a non-empty `sub`. `jku`, `x5u` and `jwk` header parameters are never read. Keys are
read on first use, kept for an hour (the old ones stay usable if a later read fails), read again
when a token names an unknown `kid` at most once a minute counting every read, and with none ever
read and the platform down the platform is not called again for five seconds. Discovery is
refused (and the next try reads it again) when its `issuer` differs, an endpoint is missing, the
token endpoint or keys are off the issuer's origin, or `S256` is not offered. Only RSA keys of at
least 2048 bits with `use` absent or `sig` and `alg` absent or `RS256` are kept.

Go differences, none visible on the wire: tokens are parsed with `encoding/json/v2`, which refuses
a duplicate member name (Java keeps the last), so such a token is `malformed` here; and the base64url
alphabet is checked before decoding because Go's decoder would skip a newline.

## The test identity provider

`authtest.Provider` (package `auth/authtest`, the counterpart of the Java `TestOidcProvider`) is a
real HTTP server on a free port serving discovery, JWKS, `/oauth/authorize` (signs the configured
user in at once), `/oauth/token` (PKCE and `redirect_uri` checked, single-use codes, public and
confidential clients) and `/auth/oidc/session/end` (which, like the platform, sends the browser on to `post_logout_redirect_uri` when the client is registered and the address is on the origin of the client's redirect URI; any other call gets `{"message":"Session ended"}`). It mints access tokens (`AccessToken`) and can
be told to misbehave: `IDTokenSigning` (`SigningNone`, `SigningHS256WithPublicKey`,
`SigningOtherKey`, `SigningTamperedSignature`, `SigningTamperedPayload`), `IDTokenChanges`,
`IDTokenHeaderChanges`, `DiscoveryChanges`, `AuthorizeError`, `TokenStatus`, `RotateKey`, `Down`.
Share the application's clock (`fwtest.Clock` in tests) so times line up.

What was checked (package tests, over the real provider): every id_token rule of section 5.5 through
the relying party, each with its `details.reason` name and with a mutation check (a deliberately
broken `alg` or nonce comparison fails tests); the access-token rules; the authorize URL's parameter
order; the RFC 7636 challenge vector; public and confidential code exchange; the 60-second
allowance exactly; key rotation, the once-a-minute read limit (also under 32 concurrent callers),
the hourly read, and the five-second back-off. Not checked: an id_token or discovery document from a
real FlowCatalyst platform.

## Session stores

A session lives in a `auth.Store` (contract section 6.3): `Create(kind, json, expiresAt)` returns the
handle that is the cookie value, `Read`, `Take` (read and delete in one atomic step, so a login can
complete once), `Update` and `Delete`, over records of kind `KindSession` or `KindLogin`. The
store's expiry is the application's clock (`fw.Clock`), never the store's. A store that cannot
answer returns an error wrapping `auth.ErrStore` (the request gets `500`), never "no session". A
store is built when the application starts, from an `auth.StoreFactory` (Go has no implicit
default: the database store is the documented one, and you name it):

| Driver | Factory | Storage |
|---|---|---|
| `database` | `authdb.Factory(dbs)`; `authdb.Declare(app)` contributes the migration | table `fw_session` (the Java module's migration file, module id `auth-database`, byte for byte), only `base64url(SHA-256(handle))` stored; the loop `auth-session-purge` deletes expired rows every 10 minutes, 1000 at a time |
| `redis` | `authredis.Factory(clients)` | key `fw:sess:<kind>:<hash>`, value `<expiry in epoch milliseconds>` newline `<json>`, `PX` the time to expiry; `Take` is `GETDEL` (Redis 6.2 or later); `Update` is `SET ... XX` |
| `memory` | `auth.MemoryStoreFactory()` | one process only, for tests and single-process development |
| `cookie` | `auth.NewCookieStore(keys, clock)` | nothing on the server: the handle is the sealed record |

Server stores answer "none" without a store call for a handle that is not 43 characters of
`[A-Za-z0-9_-]` (`auth.IsWellFormedHandle`); `auth.NewHandle` makes 32 random bytes from the system
CSPRNG.

The cookie store seals with AES-256-GCM, `v1.<kid>.<base64url(iv || ciphertext || tag)>`, the record
kind bound in as additional data (so a login record cannot be replayed as a session), the expiry
inside the plaintext. `auth.ParseCookieKeys` reads `auth.cookie-keys`
(`<kid>:<base64url of 32 bytes>[,...]`): the first key seals, every key opens, a malformed list, a
key that is not 32 bytes or a repeated kid is an error that never contains key material.
`auth.GenerateCookieKey("k1")` makes a key. A sealed value over 3 800 characters is an error. Its
limits, stated plainly: logout cannot revoke a copy taken before, and `Take` cannot be atomic.

The records are the contract's JSON (6.1, 6.2): the same bytes Java writes and reads, times as
`Instant.toString` text, optional values omitted, never `null`; permissions and tokens are not
stored. Lifetimes (6.4): absolute (default 8 hours, never extended) and idle (default 30 minutes);
`lastSeenAt` is written back only when older than the smaller of one minute and half the idle timeout;
the store expiry is the earlier of the two; an expired session is deleted when read.

What was checked: one contract suite (`auth/storetest`) runs against the memory, database (embedded
PostgreSQL) and Redis (a real `redis-server`) stores: malformed handles, kind separation, expiry to
the millisecond, take once and 16 concurrent takers (exactly one wins), update without resurrection,
revocation, unique handles; the cookie store's tamper cases (every changed byte, wrong key, wrong
kind, expiry, key rotation, oversize, key-list errors without key material); the session and login
JSON byte for byte and a document with Java-style fractional seconds; lifetimes with a fake clock.
Mutation checks: dropping the kind from the memory key, or the kind from the sealing context, fails
tests. Not checked: a store shared by a live Java and a live Go process (the table and JSON are
the same, but only Go wrote and read it), a real Redis cluster.

## Wiring the module

```go
web := httpfw.Declare(app)
authd := auth.Declare(app, web)            // settings, and getAuthSession / logout in the OpenAPI document
authdb.Declare(app)                        // the fw_session migration (module auth-database)
...
// in the build function
mod, err := authd.Build(ctx, b,
	auth.SessionStore(authdb.Factory(dbs)),                  // or authredis.Factory, auth.MemoryStoreFactory(), auth.CookieSessions()
	auth.Application(fw.MustApplicationCode("ingestor")),
	auth.Permissions(auth.ScopesMapper().And(auth.ByRole(map[string][]string{
		"ingestor:operator": {"ingestor:ingestion:manage"},
	}))))
srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{mod.Routes(), mine}, httpfw.WithResolver(mod.Resolver()))
```

Configuration holds only `auth.issuer`, `auth.client-id`, `auth.client-secret` (set for a CONFIDENTIAL
client; unset, empty or blank is a PUBLIC one), `auth.public-base-url` and `auth.cookie-keys` (cookie store only); the component `auth` is
claimed, so `auth.isuer` or `auth.idle-timeout` fails start-up. Everything else is a build option,
with the contract's defaults: `IdleTimeout` (30 min), `AbsoluteTimeout` (8 h), `Permissions`
(`ScopesMapper`: a bearer token's `scope`, nothing for a session), `Clients` (`HomeClient`),
`Application`, `DefaultLanding` (`/`), `ReturnTo` (same-origin path), `PostLogoutRedirect`,
`FailureHandler` (`JSONFailures`; `RedirectFailuresTo(path)` gives `302 path?error=CODE`), `Scope`,
`LoginTheme`, `RateLimit` / `NoRateLimit` (default: memory limiter `auth`, 20 a minute per client
address, burst 20), `HTTPTimeout` (10 s). `auth.NoBearerTokens()` at `Declare` declares sessions only.
Start-up fails, with every problem listed, for: no session store named, plain http outside `local`
and `test` (for `auth.public-base-url` and `auth.issuer`; inside them it is logged at WARN and the
cookies lose `Secure` and the `__Host-` prefix), a public base URL with a path, `offline_access` in
the scope, an idle timeout longer than the absolute one, a bad cookie key list.

**The endpoints** (contract section 4, all `Cache-Control: no-store`): `GET /auth/login` (PKCE `S256`,
`state`, `nonce`, a 10-minute login record, the binding cookie `__Host-fw_login_<16 hex of
SHA-256(state)>`, then `302` to the authorize endpoint with the parameters in the contract's order),
`GET /auth/callback` (the eight ordered checks of 4.2: `INVALID_CALLBACK`, `LOGIN_NOT_STARTED_HERE`,
`INVALID_STATE`, `LOGIN_REFUSED`, `LOGIN_FAILED`, `INVALID_ID_TOKEN` with `details.reason`,
`PLATFORM_UNAVAILABLE`; every answer clears the binding cookie; the login record is taken once; a
session cookie present at the callback is deleted first, which is the rotation), `GET /auth/session`
(always `200`; a stale cookie is cleared) and `POST /auth/logout` (CSRF-checked when a session cookie
is present, revokes, clears the cookie, names the platform's `endSessionUrl` when it has one). The
last two are the typed operations of `httpfw/sessionapi`; their OpenAPI components are the
`conformance/openapi/auth.json` ones.

**Authenticating requests** (7.1): a bearer token wins and never falls back to the cookie (a bad one
is `401`, unreadable keys `503 UNAVAILABLE`); any other `Authorization` scheme is `401`; otherwise
the session cookie, after the CSRF rules on an unsafe method, with the timeouts checked and
`lastSeenAt` written back when due; otherwise `401 UNAUTHENTICATED`. The `fw.Context` carries the
principal and its kind, the client (the identifier, never the platform id; unless it is `_platform`
the principal must be allowed to act for it, else `403 CLIENT_NOT_PERMITTED`), the application code
and `Permissions` from the mapper. `auth.Current(r)` returns the `Authenticated` (source, expiries),
`auth.Require("perm")` is route middleware answering `403 FORBIDDEN`, and `Sessions.Current(r)` reads
the session on a route that does not require a context. A `401` (returned or from a handler) for an
HTML navigation (`GET`/`HEAD`, no `Authorization`, `Sec-Fetch-Mode: navigate` or, without that
header, `Accept` containing `text/html`) becomes `302 /auth/login?returnTo=<path and query>`.
A rate-limited or platform-unreachable `/auth/login` is answered, for such a navigation, by the
failure handler with `RATE_LIMITED` / `PLATFORM_UNAVAILABLE` (4.1.1); everyone else keeps the JSON.

**Test support**: `mod.Testing()` (refused unless `APP_ENV` is `test`) signs a principal in through
the real store: `cookie, _ := t.Cookie(ctx, auth.User("prn_1", "ingestor:operator"))`
(`auth.ClientUser` for a one-client user), then send `Cookie` and, for an unsafe method,
`Sec-Fetch-Site: same-origin`. `authtest.Provider` and `storetest.Run` are above.

## The orders demo on sessions, and the browser suite

`orders-demo serve` (`examples/orders-demo/ordersweb/app.go`, since G6 a library package the two commands share) no longer has a fixed-token resolver or `501`
session endpoints. `Declare` calls `authdb.Declare(app)` and `auth.Declare(app, web)`; `BuildWeb`
builds the module with `auth.SessionStore(authdb.Factory(dbs))`, `auth.Application("orders-demo")`,
`auth.PostLogoutRedirect("/signed-out")`, `auth.RedirectFailuresTo("/sign-in-failed")` and the Java
`OrdersWeb`'s role table (`orders-demo:clerk` reads and manages orders, `viewer` reads, `admin` and
`auditor` hold the admin permissions of the admin area, [G6](admin.md)), and passes
`mod.Routes()` and `mod.Resolver()` to `httpfw.NewServer`. It needs `AUTH_ISSUER`, `AUTH_CLIENT_ID` and
`AUTH_PUBLIC_BASE_URL`; the scenario command (`orders-demo` with no argument) declares none of this.

`orders-demo e2e <app-port> <control-port> [--dist <dir>] [<public-origin>]` (`ordersweb/e2e.go`) is the Go
counterpart of the Java `OrdersE2e` on the [e2e kit](../e2e-kit.md): the real application on an
embedded PostgreSQL 18 (never 15432), `authtest.Provider` as the platform, the kit's control endpoints
(`ready`, `sign-in-as`, `provider-down`, `authorize-error`, `end-sessions`) and the orders' own
(`reset`, `login-rate-limit`), the `READY` line, and an orderly stop on SIGTERM or SIGINT.
`--dist` serves the SPA from a directory (`vite build` writes `examples/orders-demo/web/dist`), so a
rebuilt SPA needs no recompile. With the platform extension (the command of the `flowcatalyst` module, below)
the launcher also stands up a fake platform, and `/control/platform-reads?value=403` makes its reads and
writes fail (empty: normal) and `/control/reset` puts its users and invitations back; the core command
has no platform and `platform-reads` answers `ok` and does nothing.

`make -C go e2e-orders` builds the SPA and the launcher of the `flowcatalyst` module
(`go/flowcatalyst/examples/orders-demo`: the same `ordersweb` library with the platform-backed admin
sections and a fake platform, see [the application admin](admin.md)) and runs the shared suite
`ui/apps/orders/e2e` through the kit's runner with `examples/orders-demo/e2e/playwright.config.ts` (the
kit's configuration with the Go launcher as the web server), plus the Go-only specs in
`examples/orders-demo/e2e/session.spec.ts` (cookie attributes, the CSRF refusal, a copied cookie dead
after sign-out, the next sign-in's person). Result on 2026-10-18 with the admin area (G6), Chromium,
strict CSP monitor and axe: 53 shared tests, 49 run: 48 passed, 4 skipped (the screenshots, as on Java)
and 1 failed; with the Go-only specs, 52 passed. The failure is `auth.spec.ts` "sign out ends the
session", which asserts that the final URL carries `post_logout_redirect_uri=`. The Java launcher points
the platform's `end_session_endpoint` at the SPA's own `/signed-out` (another origin than the issuer's),
which the Go module ignores as the security review's I1 requires; the Go provider's end-session
endpoint is on the issuer and redirects to `/signed-out`, so the page is reached (and the heading is
asserted) but with no query string. Java's `Platform` does not yet apply I1 (see "Differences").

### Differences from Java, and what was not verified

- No implicit default store: Go has no service lookup, so the store is named (`auth.SessionStore`);
  `authdb` is the documented default. `Module.Routes()` is a table to pass to `httpfw.NewServer`; the
  Java module installs its routes itself.
- A permission string `fw.Context` cannot hold (blank, or an empty segment) is left out of the
  Context and logged at WARN, where Java's `Context` accepts any string.
- A `401` is turned into a `302` when it comes back as an error; a handler that writes a `401`
  itself is not redirected. A refused callback `state` percent-escape in the query is
  `INVALID_CALLBACK`; a malformed `returnTo` escape is ignored (the default landing).
- `Set-Cookie` attribute order is Java's (`Path`, `Max-Age`, `HttpOnly`, `SameSite`, `Secure`).
- Checked: `conformance/auth/login-failure.json`, all 20 cases, through the real dispatcher with the
  rate limiter and a down provider; the login and callback refusals of 4.2 each; CSRF, bearer order,
  client selection, 401 versus 302, timeouts, cookie shapes for https and the http fallback, the
  cookie store end to end, start-up refusals; mutation checks on the CSRF check and the state
  comparison. Not checked: a browser (the tests send requests in-process and follow redirects by
  hand), a real FlowCatalyst platform, a Java and a Go replica sharing one session table at once,
  the `conformance/openapi/auth.json` vector against this module's document beyond what G3 already
  checks, and the security review the milestone asks for (owner's ruling: Opus for the slice). A
  browser has now driven the module through `orders-demo` (G5c, above): Chromium only, against
  `authtest.Provider`, never a real platform.
- The security review's I1 (contract 5.1: `end_session_endpoint` must be https on the issuer's origin,
  else ignored) is in Go but not yet in the Java `Platform`; the Java `E2eLauncher` relies on the
  opposite (it points that endpoint at the SPA's origin).

## Operating notes (security review 2026-10-18)

- **Rotate `auth.cookie-keys` routinely** (for example quarterly, and at once if a key may have
  leaked): add the new key first, keep the old one for the absolute timeout, then remove it.
- **Behind a proxy, replace the default rate limit.** The default keys on the TCP peer, which is the
  proxy, so all users share one bucket. Pass `auth.RateLimit(...)` with a limiter keyed by
  `ratelimit.ForwardedFor(n)`. The default logs one WARN the first time it sees `X-Forwarded-For`.
- `/auth/logout` clears the cookie only for a request that carried the session cookie and passed
  the CSRF check; an empty `Sec-Fetch-Site:` header is refused.
- Discovery: `authorization_endpoint` must be https; `end_session_endpoint` must be https on the
  issuer's origin or it is ignored.
