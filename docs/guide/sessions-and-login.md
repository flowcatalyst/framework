<!-- types: AuthModule, AuthDatabaseModule, DatabaseSessionStore, RedisSessionStore, SessionStore, MemorySessionStore, CookieSessionStore, CookieKeys, SessionHandles, SessionStoreException, PlatformTokens, TokenResult, AccessToken, Principal, PrincipalKind, Authenticated, Auth, Sessions, AuthContextResolver, PermissionMapper, ClientSelector, ReturnToPolicy, LoginFailureHandler, LoginFailure, CsrfGuard, AuthTesting, TestOidcProvider, Context, ContextResolver, RouteOptions -->
# Sessions and login

Your application signs people in through the FlowCatalyst platform and keeps them signed in with a
session cookie; services call it with platform access tokens. Both arrive at your routes as one
`Context` with a principal, a client and permissions. The application is its own
backend-for-frontend: the browser never sees a token, the platform hosts the login form, and the
framework renders no login pages.

The specification, language-neutral and shared with the Go, Rust and TypeScript implementations,
is [contracts/sessions-and-login.md](../contracts/sessions-and-login.md). Coming from the
`flowcatalyst-token` library? See [Migrating from flowcatalyst-token](migrating-from-flowcatalyst-token.md).

## What you get

| Piece | Artifact |
|---|---|
| `AuthModule`: `/auth/login`, `/auth/callback`, `/auth/session`, `/auth/logout`, the `ContextResolver`, `Sessions`, `PlatformTokens` | `framework-auth` |
| `AuthDatabaseModule`: the default session store (`fw_session`) | `framework-auth-database` |
| `RedisSessionStore` | `framework-auth-redis` |
| `MemorySessionStore`, `CookieSessionStore` | `framework-auth` |
| `TestOidcProvider` (a real test identity provider) | `framework-auth` test-jar |

`framework-auth` depends on `framework-core`, `framework-http` and Jackson only; it brings no
database. The calls to the platform (discovery, keys, the token endpoint) use the JDK's HTTP client.

## Wiring it

```java
App.builder()
    .module(new JettyHttpModule())
    .module(new DatabaseModule())
    .module(new AuthDatabaseModule())              // the default store: fw_session in the default database
    .module(AuthModule.builder()
        .application(new ApplicationCode("orders"))
        .permissions(PermissionMapper.scopes().and(PermissionMapper.byRole(Map.of(
            "orders:clerk", Set.of("orders:thing:create", "orders:thing:view")))))
        .build())
    .module(new OrdersModule())
    .start();
```

`AuthModule` provides the `ContextResolver`, so every route declared with
`RouteOptions.requiresContext()` is now authenticated. Do not provide your own as well (two
providers fail start-up); see [Your own resolver](#your-own-resolver) for mixing in other
credentials.

### Configuration: connections and secrets only

| Key | Environment variable | Meaning |
|---|---|---|
| `auth.issuer` (required) | `AUTH_ISSUER` | the platform's base URL, which is also its tokens' `iss` |
| `auth.client-id` (required) | `AUTH_CLIENT_ID` | the OAuth client id of your login client |
| `auth.client-secret` | `AUTH_CLIENT_SECRET` | only for a CONFIDENTIAL login client; leave it unset for a PUBLIC one |
| `auth.public-base-url` (required) | `AUTH_PUBLIC_BASE_URL` | your application's origin as browsers see it, for example `https://orders.example.com`; no path |
| `auth.cookie-keys` | `AUTH_COOKIE_KEYS` | only for the cookie store (below) |

Everything else (timeouts, permissions, where to land, rate limits) is set on the builder, in code
([config and code](config.md#what-configuration-holds)). The module claims the `auth` component, so
any other key under `auth` in a file (a misspelt `auth.isuer`, an `auth.idle-timeout`) fails
start-up as unknown.

**Register the login client on the platform.** An anchor administrator calls
`POST /api/applications/{id}/provision-login-client` with `redirectUris:
["https://orders.example.com/auth/callback"]` (`clientType` `PUBLIC` is the default and needs no
secret). Add a post-logout redirect URI to the client if you use
[single logout](#signing-out).

**Plain http** (`http://localhost:8091`) is accepted only when `APP_ENV` is `local` or `test`: the
cookies then lose `Secure` and the `__Host-` prefix, and a WARN says so at start-up. In any other
environment an http `auth.public-base-url` or `auth.issuer` fails start-up.

## Signing in

The browser (or your SPA) goes to `/auth/login?returnTo=/orders`. The framework:

1. reads the platform's discovery document (once; a failure answers `502 PLATFORM_UNAVAILABLE`, or your failure page for a browser navigation),
2. creates a login record (`state`, `nonce`, PKCE verifier, the `returnTo`) for 10 minutes and sets a
   binding cookie `__Host-fw_login_<hash>` for this browser,
3. redirects to the platform's `/oauth/authorize` with PKCE S256.

The platform signs the person in with its own UI and redirects to `/auth/callback`. The callback
completes only in the browser that started it (`400 LOGIN_NOT_STARTED_HERE` otherwise), only once
(`400 INVALID_STATE` on a replay), exchanges the code, verifies the id_token (RS256 only; issuer,
audience, `azp`, expiry with 60 seconds of allowance, `nonce`; an access token is never taken for
an id_token), starts a new session (any older session cookie is deleted first: rotation), sets
`__Host-fw_session` and redirects to `returnTo`.

`returnTo` must be a same-origin path (`/x`, not `//x`, no `\`, no control characters, at most 2048
characters); anything else lands on the default landing, `/`. Change it with
`.defaultLanding("/home")` or a `ReturnToPolicy` of your own.

A failed callback answers the JSON error envelope by default (`INVALID_CALLBACK`,
`LOGIN_NOT_STARTED_HERE`, `INVALID_STATE`, `LOGIN_REFUSED`, `LOGIN_FAILED`, `INVALID_ID_TOKEN`,
`PLATFORM_UNAVAILABLE`; the full table is in the contract, section 4.2). To send people to a page
of yours instead:

```java
AuthModule.builder().loginFailure(LoginFailureHandler.redirectTo("/signed-out"))
// a failed sign-in now answers 302 /signed-out?error=LOGIN_REFUSED
```

The same handler answers a sign-in that cannot even start, **for a browser navigation only**: a
rate-limited `/auth/login` (or `/auth/callback`) gives `?error=RATE_LIMITED`, a platform that cannot
be reached at discovery gives `?error=PLATFORM_UNAVAILABLE`. "Browser navigation" is the test used
for the `401` redirect below (`GET`/`HEAD`, no `Authorization`, `Sec-Fetch-Mode: navigate` or, without
that header, `Accept: text/html`). Your SPA's `fetch` and any API client still get the JSON envelope
and the `429` or `502`; with the default handler nobody sees a change. The kit's
`FwSignInFailedPage` has a sentence for each code.

`.loginTheme("acme")` adds `client=acme` to the authorize URL so the platform shows that client's
login branding.

## The session endpoint

`GET /auth/session` always answers `200`. An SPA reads it at start and when the tab regains focus:

```json
{"authenticated":true,"principal":{"id":"prn_USER1","kind":"USER","name":"Ada Lovelace","email":"ada@example.test"},
 "tier":"ANCHOR","client":"_platform","clients":["*"],"applications":["*"],"roles":["orders:clerk"],
 "permissions":["orders:thing:create","orders:thing:view"],"authTime":"2026-10-13T08:00:00Z",
 "expiresAt":"2026-10-13T16:00:00Z","idleExpiresAt":"2026-10-13T08:35:00Z",
 "csrf":{"mode":"fetch-metadata","jsonBodies":true}}
```

or `{"authenticated":false,"loginUrl":"/auth/login"}`. Use `permissions` to show and hide things;
every route still checks on the server.

This endpoint and logout are **typed operations** (`getAuthSession` and `logout`), so they are in your
application's OpenAPI document with fixed components (`AuthSession`, a union of
`AuthSessionAuthenticated` and `AuthSessionAnonymous` told apart by `authenticated`, and `AuthLogout`),
and `AuthModule` contributes the `session` and `bearer` security schemes ([Typed operations and
OpenAPI](openapi.md)). A token whose `tier` is not one the platform issues (`ANCHOR`, `PARTNER`,
`CLIENT`) answers without `tier`.

## Protecting routes

```java
routes.get("/api/me", handler, RouteOptions.requiresContext());
routes.post("/api/things", handler,
    RouteOptions.requiresContext().with(Auth.require("orders:thing:create")));
```

- No session and no token: `401 UNAUTHENTICATED`. For an **HTML navigation** (`GET` or `HEAD`,
  no `Authorization`, `Sec-Fetch-Mode: navigate` or, without that header, `Accept: text/html`) the
  framework answers `302 /auth/login?returnTo=<the path and query>` instead, so a server-rendered
  page sends people to sign in. An SPA's `fetch` gets the `401` and navigates itself.
- `Auth.require(permission)` answers `403 FORBIDDEN` when the context lacks it. In a use case,
  check `context.hasPermission(...)` in `authorize` (see [Context](context.md#permissions)).
- `Auth.current(request)` returns the `Authenticated` (principal, roles, client, permissions,
  whether it came from a session or a bearer token, and the expiries).

### Permissions

The platform's id_token carries **roles** (`orders:clerk`), not permissions, and only your
application knows what its roles mean. So the mapping is code:

| Helper | Grants |
|---|---|
| `PermissionMapper.scopes()` (the default) | the `scope` of a bearer access token; nothing for a browser session |
| `PermissionMapper.byRole(Map.of(role, Set.of(permissions...)))` | the permissions of each role held |
| `a.and(b)` | the union |
| `PermissionMapper.none()` | nothing |

With the default, a signed-in person can do nothing that needs a permission until you map your
roles: a missing mapping fails closed. The mapping runs on every request, so changing it takes
effect at the next request after a deployment.

### Which client

A `Context` always has a client, and a `ClientId` holds the client's **identifier** (its code,
such as `acme`), not its `clt_` id (see [Context](context.md#what-goes-in-a-clientid)). The default
`ClientSelector.homeClient()` takes the principal's home client, translated to its identifier from
the token's `clients` entry `clt_1:acme`; failing that, when `clients` names exactly one client
with its identifier (a client's service token: access tokens carry no home client), that
identifier; otherwise `_platform` ("no particular client"), as for an anchor user. A selector of your own can take the client from a header or a path:

```java
AuthModule.builder().clients((principal, request) -> new ClientId(request.header("X-Client")))
```

Whatever it returns, the framework checks that the principal may act for it (its `clients` claim
holds `*`, the id, or `id:identifier`) and answers `403 CLIENT_NOT_PERMITTED` otherwise.

## Bearer tokens

A request with `Authorization: Bearer <platform access token>` is authenticated by the token, not
the cookie: RS256 against the platform's published keys, issuer and audience equal to
`auth.issuer`, `token_use` `api` (a default login's identity token is refused). An invalid token is
`401` and **never** falls back to the cookie. When the platform's keys cannot be read the answer is
`503 UNAVAILABLE`, so an outage is not reported as bad credentials. Keys are cached for an hour,
read again when a token names an unknown key (at most once a minute), and kept while the platform
is down. `.bearerTokens(false)` accepts browser sessions only.

To check platform tokens **without** sessions, for example on one endpoint of a service that has
no login, build the verifier yourself:

```java
PlatformTokens tokens = PlatformTokens.create(issuer, Duration.ofSeconds(10), app.get(InstantSource.class));
switch (tokens.verify(bearer)) {
  case TokenResult.Valid v when v.token().mayActFor(clientId) -> { /* go on */ }
  case TokenResult.Valid v -> { return HttpErrors.forbidden("FORBIDDEN", "not for this client"); }
  case TokenResult.Invalid i -> { return HttpErrors.unauthenticated("credentials are required"); }
  case TokenResult.Unavailable u -> { return HttpErrors.error(503, "UNAVAILABLE", "try again"); }
}
```

Keep one `PlatformTokens` per platform (it caches the keys). With `AuthModule` listed, use the
provided one: `app.get(PlatformTokens.class)`.

`create` refuses an `http://` issuer (`IllegalArgumentException`): keys and tokens would cross the
network unprotected. In a local or test environment build the verifier with
`PlatformTokens.createAllowingPlainHttp(issuer, timeout, clock)`; the call site is the opt-in.
(`AuthModule` itself allows `http` only when `APP_ENV` is `local` or `test`.)

## CSRF

A request with an unsafe method (`POST`, `PUT`, `PATCH`, `DELETE`) that is authenticated by the
session cookie must come from your own pages: `Sec-Fetch-Site` `same-origin` or `none`, or, from an
older browser without that header, an `Origin` equal to `auth.public-base-url`'s origin. A request
with neither header is refused. Refusals are `403 CROSS_ORIGIN_REQUEST`. A body must then be JSON
(`application/json` or `application/*+json`), else `415`. These are the platform's own rules. There
is no CSRF token to fetch or send, and no CORS: a browser's `fetch` from your own origin passes as is.

Bearer-token requests skip the check. For a public `POST` of your own (a sign-up form), put
`CsrfGuard.publicForm(publicBaseUrl).middleware()` on the route.

## Signing out

`POST /auth/logout` (subject to the CSRF rules) deletes the session, clears the cookie and answers
(the clearing `Set-Cookie` is sent only to a request that carried a session cookie and passed the
CSRF check, so a cross-site POST cannot make a browser drop its session):

```json
{"loggedOut":true,"endSessionUrl":"https://platform.example.com/auth/oidc/session/end?client_id=orders-web&post_logout_redirect_uri=https%3A%2F%2Forders.example.com%2Fbye"}
```

Navigate to `endSessionUrl` to end the platform session as well (single logout); otherwise only
your application is signed out. When the platform's discovery document has no end-session endpoint,
or cannot be read, the answer is `{"loggedOut":true}`: the field is left out, never `null`. The
same happens when the document names one that is not `https` (`http` only in `local` and `test`),
is not on the issuer's origin, or carries user-info: it is ignored and logged at WARN, because the
browser is sent there. The sign-in's `authorization_endpoint` must also be `https` outside those
two environments. `post_logout_redirect_uri` is there when you set
`.postLogoutRedirect("/bye")` and register that URL on the platform client.

## Sessions: stores and timeouts

| Store | How | Revocable on logout |
|---|---|---|
| `database` (default) | list `AuthDatabaseModule`; table `fw_session` in the default database; expired rows purged every 10 minutes by the loop `auth-session-purge` | yes |
| `redis` | `.sessions(lookup -> RedisSessionStore.create(lookup.get(RedisConnections.class).defaultInstance(), lookup.get(InstantSource.class)))` | yes |
| `cookie` | `.cookieSessions()` and `auth.cookie-keys` | **no**: a copy of the cookie taken before logout works until it expires |
| `memory` | `.sessions(lookup -> MemorySessionStore.create(lookup.get(InstantSource.class)))`; one process | yes |

The server stores keep only a SHA-256 of the cookie value, so a leaked table holds no usable
cookie. **Cookie keys** are `<kid>:<base64url of 32 bytes>`, comma-separated; the first seals, all
open. `CookieKeys.generate("k1")` makes one. To rotate, put a new key first, keep the old one for
the absolute timeout, then remove it.

| Timeout | Default | Builder |
|---|---|---|
| idle | 30 minutes | `.idleTimeout(Duration)` |
| absolute | 8 hours | `.absoluteTimeout(Duration)` |

A session's last activity is written back at most once a minute. The session keeps **no platform
token**: roles and permissions are those of the sign-in, so a role change or a deactivation at the
platform takes effect at the next sign-in, at most the absolute timeout later.

## Rate limits

`/auth/login` and `/auth/callback` are limited per client address: by default a memory limiter
named `auth`, 20 a minute, burst 20. Replace it, for example to share it across replicas or to key
by the forwarded address behind a proxy:

```java
AuthModule.builder().rateLimit(RateLimit.by(
    RedisRateLimiter.create("auth", redis, RateLimitPolicy.of(20, Duration.ofMinutes(1))),
    KeyResolvers.forwardedFor(1)).build())
```

or remove it with `.noRateLimit()`. See [Rate limiting](rate-limiting.md).

The default keys on the TCP peer, which behind a proxy is the proxy: every user then shares one
bucket. The first time a request carries `X-Forwarded-For` under the default limiter, the module logs
one WARN saying so; the fix is the `forwardedFor` limiter above.

## Your own resolver

To accept credentials the framework does not know (a fixed operator token, say) next to platform
tokens and sessions, turn off the provided resolver and delegate:

```java
.module(AuthModule.builder().provideContextResolver(false).build())
// in your module's register:
app.provide(ContextResolver.class, lookup -> {
  AuthContextResolver platform = lookup.get(AuthContextResolver.class);
  return request -> isOperatorToken(request)
      ? Context.of(ClientSelector.PLATFORM).withPrincipal(new PrincipalId("operator")).withPermissions(Set.of("ingestor:*:*"))
      : platform.resolve(request);
});
```

## Testing

**Your routes with a signed-in user:** `AuthTesting` creates a real session in your configured
store and gives you the cookie. It works only when `APP_ENV` is `test`.

```java
AuthTesting auth = AuthTesting.of(app);
String cookie = auth.cookie(AuthTesting.user("prn_T", "orders:clerk"));
TestResponse me = client.get("/api/me").header("Cookie", cookie).send();
client.post("/api/things").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").jsonBody("{}").send();
String acmeCookie = auth.cookie(AuthTesting.clientUser("prn_C", "clt_1", "acme"));
```

**Bearer tokens and the login flow:** `TestOidcProvider` (in the `framework-auth` test-jar) is a
real HTTP server shaped like the platform: discovery, keys, authorize, token, end-session.

```java
TestOidcProvider provider = TestOidcProvider.start(clock);
provider.client("app-client", "", "https://app.example/auth/callback");
String token = provider.accessToken(Map.of("sub", "prn_SVC", "scope", "orders:thing:create"));
provider.idTokenSigning(TestOidcProvider.Signing.NONE);        // and other ways to misbehave
provider.down(true);
```

```xml
<dependency>
  <groupId>io.flowcatalyst.framework</groupId>
  <artifactId>framework-auth</artifactId>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

## Limits

- No refresh: sessions keep no platform token (above). Portal logins (`/portal/*`) are not
  supported.
- The platform must be reachable from your application at `auth.issuer`, the same URL browsers
  use; a separate internal URL is not supported.
- No login, error or not-permitted pages: the platform hosts login, and anything else is your
  application's (or, later, the UI module's).
- With the cookie store, logout cannot revoke a copied cookie, and a login record cannot be
  consumed atomically (the platform's single-use code stops a replayed callback).
