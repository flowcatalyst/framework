<!-- types: PlatformTokens, TokenResult, AccessToken, Principal, PrincipalKind, AuthModule, AuthDatabaseModule, AuthContextResolver, Auth, PermissionMapper, TestOidcProvider, AuthTesting -->
# Migrating from flowcatalyst-token

The first application built on the framework carried its own token library,
`libs/java/flowcatalyst-token` (`TokenVerifier`, `VerifiedToken`, `InvalidTokenException`, and
`FakeIssuer` for tests), "until the framework does". `framework-auth` now does, and adds what the
library never had: browser sign-in, sessions and a `ContextResolver`. This page maps one onto the
other and lists the steps for the two applications that used it, the ingest runtime (a
Camel-based service that only checks tokens) and the ingest control plane (an operator API that
wants a login).

## What maps to what

| flowcatalyst-token | framework-auth |
|---|---|
| `new TokenVerifier(issuer, timeout, clock)` | `PlatformTokens.create(issuer, timeout, clock)` |
| `verifier.verify(token)` returning `VerifiedToken`, throwing `InvalidTokenException`, `IOException`, `InterruptedException` | `tokens.verify(token)` returning `TokenResult`: `Valid(AccessToken)`, `Invalid(reason)`, `Unavailable(reason)`; nothing thrown |
| `VerifiedToken.subject()` | `accessToken.principal().id().value()` |
| `VerifiedToken.type()` (`"SERVICE"`, `"USER"`) | `accessToken.principal().kind()` (`PrincipalKind.SERVICE`, `USER`; `io.flowcatalyst.framework.context.PrincipalKind`, also on `Context`) |
| `VerifiedToken.scopes()`, `hasScope(s)` | `accessToken.principal().scopes()`, `accessToken.hasScope(s)` |
| `VerifiedToken.clients()`, `mayActFor(clientId)` | `accessToken.principal().clients()`, `accessToken.mayActFor(client)` |
| `InvalidTokenException` message | `TokenResult.Invalid.reason()`: `malformed`, `alg`, `crit`, `kid`, `unknown_kid`, `signature`, `iss`, `aud`, `expired`, `not_yet_valid`, `iat`, `token_use`, `sub` |
| `IOException` (keys unreadable) | `TokenResult.Unavailable` |
| `FakeIssuer` (test-jar) | `TestOidcProvider` (`framework-auth` test-jar) |
| a hand-written `Middleware` checking a platform token | `AuthModule`'s `ContextResolver` and `Auth.require(permission)` |

### What stays the same

RS256 only; the keys from the discovery document's `jwks_uri`, kept for an hour, read again at
most once a minute for an unknown `kid`, kept while the platform is down; issuer and audience equal
to the platform's base URL (a trailing `/` ignored); `token_use` must be `api`; `sub` required;
60 seconds of clock allowance; tokens over 16 384 characters refused.

### What is stricter

- A token must name its `kid` (the library looked up the empty id).
- A `crit` header is refused; base64 padding in a part is refused.
- An `nbf` that is not a number is refused (the library ignored it); an `iat` more than 60 seconds
  in the future is refused (the library did not look).
- Keys must be RSA of at least 2048 bits with `alg` absent or `RS256`; others are ignored.
- The discovery document's `issuer` must equal the configured issuer, and its `jwks_uri` and
  `token_endpoint` must be on the issuer's origin.
- After a failed first read of the keys, the platform is not called again for 5 seconds (the
  library tried on every request).

### What is wider

`mayActFor` also accepts the client's **identifier** (`acme` matches the claim entry
`clt_1:acme`), because the framework's `ClientId` holds identifiers. A platform id still matches as
before.

## The ingest runtime (tokens only)

`HttpIngest` checks a platform token for ingestions whose auth is `FlowCatalystToken`. The runtime
has no login, so it needs `PlatformTokens` only, not `AuthModule`.

1. In `apps/ingest-runtime/pom.xml`, replace the `flowcatalyst-token` dependency (and its test-jar)
   with `io.flowcatalyst.framework:framework-auth` (and its test-jar in test scope). Versions come
   from `framework-bom`.
2. In `RuntimeModule`, build the verifier from the same setting as before:

   ```java
   PlatformTokens tokens = PlatformTokens.create(
       app.config().get(FlowCatalystModule.BASE_URL),
       app.config().get(FlowCatalystModule.TIMEOUT),
       app.get(InstantSource.class));
   ```

3. In `HttpIngest.platformToken`, replace the `try`/`catch` with a switch:

   ```java
   return switch (tokens.verify(token)) {
     case TokenResult.Valid v when v.token().mayActFor(ingestion.clientId()) -> null;
     case TokenResult.Valid v -> HttpErrors.forbidden("FORBIDDEN", "the token's principal may not act for this client");
     case TokenResult.Invalid i -> {
       LOG.debug("ingestion {} refused a token: {}", ingestion.id(), i.reason());
       yield unauthenticated();
     }
     case TokenResult.Unavailable u -> {
       LOG.warn("the platform's signing keys could not be read: {}", u.reason());
       yield HttpErrors.error(503, "UNAVAILABLE", "tokens cannot be checked right now; send it again");
     }
   };
   ```

   The answers (`401`, `403 FORBIDDEN`, `503 UNAVAILABLE`) are unchanged.
4. In `HttpIngestTest`, replace `FakeIssuer.start()` with `TestOidcProvider.start(InstantSource.system())`
   and `platform.accessToken(now, scope, clients)` with
   `platform.accessToken(Map.of("scope", scope, "clients", clients))`. Tokens are issued at the
   provider's clock; for an expired one pass `"exp"` in the map. `rotateKey()`, `down(boolean)` and
   `keyFetches()` keep their names.

## The ingest control plane (operator API and a login)

Today `AdminAuth` accepts a fixed bearer token, a basic login or a platform access token with the
permission in `admin-api.flowcatalyst.scope`. With the framework the operator API is a set of
context routes, a browser session works on it as well as a service token, and the permission is
code.

1. **Dependencies.** Replace `flowcatalyst-token` with `framework-auth` and `framework-auth-database`
   (the control plane already lists `DatabaseModule`, so sessions go in `fw_session`).
2. **The platform.** Register a login client for the control plane on the platform (anchor admin:
   `POST /api/applications/{id}/provision-login-client`, `redirectUris:
   ["https://<control-plane>/auth/callback"]`, `clientType` `PUBLIC`), and give the operators a role
   of the `ingestor` application, for example `ingestor:operator`.
3. **Configuration.** Replace `admin-api.flowcatalyst.issuer` with `auth.issuer`; add
   `auth.client-id` and `auth.public-base-url`. Drop `admin-api.flowcatalyst.scope`: the permission
   becomes a constant in code, so the start-up WARN about "any access token may change ingestions"
   disappears with it.
4. **Modules.** In the control plane's `App.builder()`:

   ```java
   .module(new AuthDatabaseModule())
   .module(AuthModule.builder()
       .application(new ApplicationCode("ingestor"))
       .permissions(PermissionMapper.scopes().and(PermissionMapper.byRole(Map.of(
           "ingestor:operator", Set.of("ingestor:admin:ingestion:manage")))))
       .provideContextResolver(false)   // only while the fixed token and basic login stay; see 6
       .build())
   ```

   `scopes()` keeps service accounts working: their token's `scope` carries
   `ingestor:admin:ingestion:manage` exactly as `AdminAuth` checked it.
5. **Routes.** Replace `RouteOptions.none().with(auth)` with
   `RouteOptions.requiresContext().with(Auth.require("ingestor:admin:ingestion:manage"))` on the five
   `/api/v1/ingestions` routes. The runtime API (`/runtime/v1/*`, the shared `RuntimeToken`) is not
   affected.
6. **The fixed token and the basic login.** The framework does not know them. Either drop them
   (a service account's token and the browser login cover both uses), and remove
   `.provideContextResolver(false)`; or keep them in a resolver of your own that delegates
   everything else to the provided `AuthContextResolver`, as in
   [Sessions and login: your own resolver](sessions-and-login.md#your-own-resolver).
7. **The placeholder page** at `/` can become a context route: an operator who is not signed in is
   redirected to `/auth/login` and comes back to it.
8. **Tests.** In `AdminApiTest`, `FakeIssuer` becomes `TestOidcProvider` (a second provider plays
   the stranger, whose tokens fail on `iss`); for browser-session cases use
   `AuthTesting.of(app).cookie(AuthTesting.user("prn_op", "ingestor:operator"))` and send unsafe
   calls with `Sec-Fetch-Site: same-origin`. `AdminApiTest` runs with `APP_ENV=ci` today; change
   it to `test`: the test provider is plain http, which `AuthModule` accepts only in `local` and
   `test`, and `AuthTesting` works only in `test`.
9. **Delete** `libs/java/flowcatalyst-token` and its module line in the root `pom.xml` once neither
   application depends on it.

Error answers seen by API clients do not change: `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `503
UNAVAILABLE`. New for browsers: `403 CROSS_ORIGIN_REQUEST` and `415` on cookie-authenticated
unsafe calls that are not same-origin JSON.
