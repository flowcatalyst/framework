# Contract: sessions and login (M4c)

Status: M4c, written 2026-10-13 before the Java code; Java built the same day (`framework-auth`,
`framework-auth-database`, `framework-auth-redis`). Amendments found by the build are marked.
*Amended 2026-10-14 for the SPA (UI direction, [`../handover.md`](../handover.md)): section 4.5 (what
a single-page application relies on, the two endpoints as typed operations) and 4.4 (`endSessionUrl`
omitted rather than `null`); both built with MO the same day.* **Language-neutral and JSON-first**: Java,
Go, Rust and TypeScript implement the same endpoints, cookies, JSON shapes, error codes and
verification rules. Java names appear only where the Java shape is described.

Platform facts come from the platform's own source (`flowcatalyst-go`, `internal/platform/auth`,
read 2026-10-13); where this file and the platform disagree, the platform's source wins and this
file is wrong. The platform's F16 fixes (`docs/platform-bug-fixes.md`, F16 and its follow-up) are
applied here on purpose: the same provenance rules, the same login-state binding, the same codes.

Owner rulings used: the application is its own backend-for-frontend; tokens never reach the
browser; session drivers `cookie`, `database`, `redis` with `database` the default; behaviour in
code, only connection and secret settings in configuration (2026-10-13); no framework-rendered
login pages (the platform hosts the login UI), so M4c does not depend on M-UI.

## 1. Shape

The application is an OIDC relying party of the FlowCatalyst platform (authorization code flow,
PKCE S256, `state`, `nonce`). The browser holds one opaque cookie; the session lives on the server
(or, with the `cookie` driver, sealed in that cookie). The platform's login UI is used as is: the
application redirects to it and never renders a login form.

| Caller | How it authenticates | Unauthenticated answer |
|---|---|---|
| a browser (an SPA or server-rendered pages) | the session cookie | API call: `401` JSON; HTML navigation: `302` to `/auth/login?returnTo=` (section 7.4) |
| a service, a script, another application | `Authorization: Bearer <platform access token>` | `401` JSON |

Platform calls the application makes stay on the application's own service account (the
FlowCatalyst module's client credentials). The login's access token is not used for platform calls:
for a default login client it is `token_use=identity` and the platform API refuses it.

## 2. Modules (Java)

| Artifact | Contents | Depends on |
|---|---|---|
| `framework-auth` | the endpoints, the relying party, the access-token verifier, the session manager, the `memory` and `cookie` drivers, the `ContextResolver`, the CSRF guard, `AuthTesting` | `framework-core`, `framework-http`, Jackson |
| `framework-auth-database` | the `database` driver, the `fw_session` migration, the purge loop | `framework-auth`, `framework-database` |
| `framework-auth-redis` | the `redis` driver | `framework-auth`, `framework-redis` |

`framework-http` keeps depending on `framework-core` only (http contract section 1). HTTP calls to
the platform (discovery, keys, token endpoint) use the JDK's `java.net.http.HttpClient`, not
`framework-httpclient`: three GETs and a form POST do not justify Vert.x on the login path of an
HTTP-only application (ruling taken; section 12).

## 3. Configuration and code

Only connection and secret settings are configuration (owner ruling 2026-10-13):

| Key | Type | Required | Meaning |
|---|---|---|---|
| `auth.issuer` | URI | yes | the platform's base URL; must equal the discovery document's `issuer` and every token's `iss` exactly (a trailing `/` is removed before comparing) |
| `auth.client-id` | string | yes | the OAuth client id of the application's login client |
| `auth.client-secret` | secret | no | set for a CONFIDENTIAL login client; unset, empty or blank (spaces only) means a PUBLIC client, no `Authorization` header on the token request (the platform refuses a secret from a public client) |
| `auth.public-base-url` | URI | yes | the application's public origin as the browser sees it, for example `https://ingest.example.com`; the redirect URI is `{public-base-url}/auth/callback` and the trusted origin of the CSRF check is its origin |
| `auth.cookie-keys` | secret | only for the `cookie` driver | the keys that seal cookies (section 6.5) |

Everything else is code, on the module's builder (Java: `AuthModule.builder()`):

| Behaviour | Default |
|---|---|
| session store | the provided `database` store (Java: list `AuthDatabaseModule`) |
| idle timeout | 30 minutes |
| absolute timeout | 8 hours (the platform's own session in production) |
| permission mapping (section 7.3) | the `scope` claim of a bearer access token; a browser session gets no permissions until the application maps roles |
| client selection (section 7.3) | the principal's home client, else `_platform` |
| the application code put in the `Context` | none |
| default landing after login | `/` |
| `returnTo` policy (section 4.1) | same-origin path only |
| post-logout redirect (section 4.4) | none |
| login failure answer (section 4.2) | the JSON error envelope |
| scopes requested | `openid profile email` |
| login theme hint (`client=` on the authorize URL) | none |
| rate limit on `/auth/login` and `/auth/callback` (section 8) | 20 requests a minute per client address, burst 20, in memory |
| bearer tokens accepted | yes |
| HTTP timeout for platform calls | 10 seconds |

## 4. Endpoints

All four answer `Cache-Control: no-store`. Errors use the http contract's envelope
`{"error": CODE, "message": "...", "details": {...}}`.

### 4.1 `GET /auth/login?returnTo=<path>`

1. `returnTo` is optional. It is kept only when it is a **same-origin path**: starts with `/`, does
   not start with `//`, contains no `\`, no character below U+0020 and no U+007F, and is at most
   2048 characters. Anything else is replaced by the default landing (not refused: a bad
   `returnTo` must not break signing in). An application may replace the policy in code; whatever
   the replacement returns is checked against the same rule, and the default landing is used when
   it fails.
2. Fetch (or reuse) the discovery document (section 5.1). If it cannot be read: `502
   PLATFORM_UNAVAILABLE`, answered as in 4.1.1.
3. Create a **login record** (section 6.2): `state`, `nonce`, the PKCE `codeVerifier` (each 32
   random bytes from a CSPRNG, base64url without padding; the verifier is 43 characters), the kept
   `returnTo`, `createdAt`. Store it with a lifetime of **10 minutes** (the platform's code
   lifetime).
4. Set the **login binding cookie** (section 5.3) whose value is the record's handle.
5. `302` to the authorization endpoint with, in this order: `response_type=code`, `client_id`,
   `redirect_uri` (`{public-base-url}/auth/callback`), `scope`, `state`, `nonce`,
   `code_challenge` (base64url of SHA-256 of the ASCII verifier), `code_challenge_method=S256`, and
   `client` when a login theme is set. Values are percent-encoded (`application/x-www-form-urlencoded`
   rules for the query).

Several logins may be in flight in one browser (two tabs): each has its own binding cookie.

#### 4.1.1 A sign-in that cannot start, for a browser (ruled 2026-10-15)

`/auth/login` (and the rate limit in front of it and of `/auth/callback`, section 8) can refuse
before the platform is involved: `429 RATE_LIMITED`, `502 PLATFORM_UNAVAILABLE`. Who is asking decides
the answer:

- A **browser navigation**, the test of 7.4 (`GET` or `HEAD`, no `Authorization` header,
  `Sec-Fetch-Mode: navigate`, or, when that header is absent, an `Accept` containing `text/html`),
  is answered by the **same failure handler as the callback** (4.2): the default handler is the JSON
  envelope, so an application that has not configured one sees no change; `redirectTo(page)` gives
  `302 Location: <page>?error=<CODE>` (the code is `RATE_LIMITED` or `PLATFORM_UNAVAILABLE`). The
  redirect is `no-store`, sets no login binding cookie, and drops the limiter's `Retry-After`. The
  status is the callback's (`302` from `redirectTo`), not `303`: both are followed with a `GET`, and
  one handler gives one answer.
- **Every other request** (an SPA's `fetch`, an API client, `curl` without `Accept: text/html`, a
  request with `Authorization`) keeps the JSON envelope and the status above, unchanged. This is for
  frontends only; nothing is negotiated for APIs.

The codes carry no secret. A custom rate limit (`.rateLimit(...)`) that answers `429` is covered the
same way; any other status it answers is left alone. Vector: `conformance/auth/login-failure.json`.

### 4.2 `GET /auth/callback?code=&state=` (or `?error=&error_description=&state=`)

Checked in this order; the first failure answers, and **every** answer of this endpoint clears the
binding cookie named by `state` (when `state` is a well-formed value):

| Step | Failure | Answer |
|---|---|---|
| 1. `state` present, `[A-Za-z0-9_-]{43}` | absent or malformed | `400 INVALID_CALLBACK` |
| 2. the binding cookie for this `state` is present | absent (another browser, or a forged link) | `400 LOGIN_NOT_STARTED_HERE`, message "This sign-in was not started in this browser. Start the sign-in again." |
| 3. **take** the login record by the cookie's handle (read and delete in one step, so a callback can complete once) | no record (expired, already used) | `400 INVALID_STATE` |
| 4. the record's `state` equals the parameter (constant-time) | differs | `400 INVALID_STATE` |
| 5. no `error` parameter | the platform answered an error (for example `access_denied`, `login_required`) | `400 LOGIN_REFUSED`, `details.error` = the platform's code (`[a-z_]{1,64}` or `invalid`); `error_description` is not echoed |
| 6. `code` present, 1 to 512 characters | absent | `400 INVALID_CALLBACK` |
| 7. exchange the code (section 5.4) | the token endpoint answers 4xx | `400 LOGIN_FAILED`, `details.error` = the platform's `error` |
| | 5xx, timeout, connection failure, or not JSON | `502 PLATFORM_UNAVAILABLE` |
| 8. verify the id_token (section 5.5) with the record's `nonce` | any rule fails | `400 INVALID_ID_TOKEN`, `details.reason` = the rule (section 5.5's names); the token is never logged or echoed |
| | the keys cannot be read | `502 PLATFORM_UNAVAILABLE` |
| 9. build the session (section 6.1); **rotation**: if the request carried a session cookie, that session is deleted first | the store fails | `500 INTERNAL` |
| 10. set the session cookie, `302` to the record's `returnTo` | | |

An application may replace the failure answer in code (Java: `.loginFailure(handler)`, receiving
the code and the request; for example a `302` to its own error page with `?error=CODE`). The
status codes above are the default and the conformance reference. The same handler also answers a
browser navigation to a sign-in that cannot start (4.1.1). Failures are logged at INFO with
the code and the correlation id, never with a token, a code or the state.

### 4.3 `GET /auth/session`

Always `200` (the SPA's guard reads it without error handling). Never a redirect.

Authenticated by the session cookie:

```json
{
  "authenticated": true,
  "principal": {"id": "prn_0HZXEQ5Y8JY5Z", "kind": "USER", "name": "Ada Lovelace", "email": "ada@example.com"},
  "tier": "ANCHOR",
  "client": "_platform",
  "clients": ["*"],
  "applications": ["*"],
  "roles": ["ingestor:operator"],
  "permissions": ["ingestor:ingestion:manage"],
  "authTime": "2026-10-13T08:00:00Z",
  "expiresAt": "2026-10-13T16:00:00Z",
  "idleExpiresAt": "2026-10-13T08:42:00Z",
  "csrf": {"mode": "fetch-metadata", "jsonBodies": true}
}
```

- `client` is the client the `Context` would carry (section 7.3); `permissions` are computed by the
  application's mapping at the time of the call (section 7.3), sorted.
- `name` and `email` are omitted when the id_token had none; `tier` is omitted when absent.
- `expiresAt` is the absolute expiry; `idleExpiresAt` the idle expiry as of this call. Reading
  `/auth/session` counts as activity.
- `csrf` tells the SPA what unsafe calls need (section 7.2): no token, same-origin `fetch`, JSON
  bodies. The object is fixed in this version.

Not authenticated (no cookie, unknown, expired or revoked session):

```json
{"authenticated": false, "loginUrl": "/auth/login"}
```

and a stale session cookie is cleared. A bearer token on this endpoint is ignored: it describes the
browser session only.

### 4.4 `POST /auth/logout`

- A cookie-authenticated unsafe request: the CSRF rules of section 7.2 apply (a cross-site logout
  is refused). A body is optional; if there is one it must be JSON. Without a session cookie there
  is nothing to protect and no check runs.
- **Amended 2026-10-18 (security review F2): the clearing `Set-Cookie` is sent only when the
  request carried a session cookie and passed the CSRF check.** Before, every answer cleared the
  cookie, so a cross-site `POST` (which carries no `SameSite=Lax` cookie, so is never refused) still
  made the browser drop its session cookie: a forced logout. Without a session cookie the answer is
  the same `200` and sets nothing. **Java hand-off:** the Java `/auth/logout` has the same
  behaviour and is fixed separately; see the hand-off list in section 14.
- Deletes the session from the store (revocation; for the `cookie` driver nothing is stored, see
  6.5), clears the session cookie, answers `200`:

```json
{"loggedOut": true, "endSessionUrl": "https://platform.example.com/auth/oidc/session/end?client_id=...&post_logout_redirect_uri=..."}
```

- `endSessionUrl` is the platform's `end_session_endpoint` with `client_id` and, when a
  post-logout redirect is configured in code, `post_logout_redirect_uri` (`{public-base-url}` +
  the configured path; it must be registered as a post-logout redirect URI of the platform client).
  The SPA navigates to it to end the platform session as well (single logout); ignoring it logs out
  of the application only. **Omitted** when discovery has no `end_session_endpoint` or cannot be
  read. *Amended 2026-10-14: was `null`; omitted matches section 6.1 ("optional values are omitted,
  not null") and the OpenAPI contract's "may be absent" rule. Built with MO: logout is a typed
  operation and the field is left out; an SPA that tests `endSessionUrl` for truthiness works with
  both. Ruled by the owner 2026-10-14: typed operations omit `null`s, plain routes keep
  them (OpenAPI contract section 13, ruling 4), so the omission arrives with MO and nothing else
  changes on the wire.*
- Without a session the answer is the same `200` (logout is idempotent).

### 4.5 For a single-page application

*Added 2026-10-14.* What a browser SPA served by the application (the
[static-assets contract](static-assets-and-spa.md)) relies on. Nothing in sections 4.1 to 4.4
changes behaviour for it; this section fixes the typed shapes and the client-side rules.

**Typed operations.** `GET /auth/session` and `POST /auth/logout` are declared as operations of the
[OpenAPI contract](openapi.md) (section 8 there), public (no `security`), so the SPA's client is
generated. Their components are fixed here, identical in every implementation and recorded in
`conformance/openapi/auth.json`:

| Operation | `operationId` | Success | Component |
|---|---|---|---|
| `GET /auth/session` | `getAuthSession` | `200` | `AuthSession`: `oneOf` `AuthSessionAuthenticated`, `AuthSessionAnonymous` (no `discriminator` keyword: `authenticated` is a boolean, so each variant fixes it with `const`) |
| `POST /auth/logout` | `logout` | `200` | `AuthLogout`: `loggedOut` (`const: true`), `endSessionUrl` (string, may be absent) |

- `AuthSessionAuthenticated`: `authenticated` (`const: true`), `principal` (`AuthPrincipal`: `id`,
  `kind` (`enum`: `USER`, `SERVICE`), `name` and `email` may be absent), `tier` (`enum`: `ANCHOR`,
  `PARTNER`, `CLIENT`; may be absent), `client`, `clients`, `applications`, `roles`, `permissions`
  (arrays of strings, always present), `authTime`, `expiresAt`, `idleExpiresAt` (`date-time`),
  `csrf` (`AuthCsrf`: `mode` (`const: "fetch-metadata"`), `jsonBodies` (`const: true`)), in the order
  of section 4.3.
- `AuthSessionAnonymous`: `authenticated` (`const: false`), `loginUrl`.
- `logout` documents `403` (`CROSS_ORIGIN_REQUEST`) and `415`; `getAuthSession` documents nothing
  but `default`.

In TypeScript the union narrows on `session.authenticated === true`. Verified at U0
([`../ui/u0.md`](../ui/u0.md)): hey-api renders `const` as literal types (and `z.literal` in Zod).
*Also checked with MO on the emitted `conformance/openapi/fixture.json` with hey-api 0.93.1, the
platform's pin: the same.*

*As built (MO):* the operations carry summaries (`Read the browser session`, `Sign out of the
application`) and the tag `auth` (all in `conformance/openapi/auth.json`). `tier` is the enum of the
three tiers the platform issues; a token whose `tier` claim is anything else answers without `tier`
(before MO the value was echoed), so the answer always matches the document. A browser session
always has `authTime` and `idleExpiresAt`. Everything else on the wire is unchanged: the bytes of
both answers are those of M4c except the omitted `endSessionUrl`.

**Client rules** (for `@flowcatalyst/api-runtime`, and for any hand-written client):

1. **Same origin, cookies only.** Every call is a same-origin `fetch` with `credentials:
   "same-origin"` (the default); never `mode: "no-cors"`, never an `Authorization` header from the
   browser, never a token in storage. The browser adds `Sec-Fetch-Site: same-origin` (and `Origin`
   on unsafe methods), which is what section 7.2 checks: **no CSRF header or token is sent**.
2. **JSON bodies only.** Every request with a body sends `Content-Type: application/json`; a body
   without it is refused `415` (section 7.2, rule 5). A body-less `POST` (logout) needs no
   `Content-Type`.
3. **Start.** Read `GET /auth/session` once before mounting the router's guarded routes; when
   `authenticated` is `false`, navigate (`window.location.assign`) to
   `loginUrl + "?returnTo=" + encodeURIComponent(path + search + hash)`. Re-read it when the window
   regains focus, **not on a timer**: a read counts as activity (4.3), so polling would keep an idle
   session alive until its absolute expiry.
4. **401 on any call** means the session is gone: navigate to sign-in as in rule 3 (the server never
   redirects a `fetch`; section 7.4). A `403` is shown, not retried.
5. **Permissions in the browser** (`permissions` of the session) decide what is shown; every operation
   still checks on the server.
6. **Logout:** `POST /auth/logout`, then navigate to `endSessionUrl` when present (single logout), else
   to the application's signed-out page.
7. **Development:** `auth.public-base-url` is the Vite dev server's origin (static-assets contract
   section 9), so the cookie and the CSRF origin are the page's origin, as in production.

## 5. The relying party

### 5.1 Discovery

`GET {issuer}/.well-known/openid-configuration`, read on first use, kept for the life of the
process; a failed read is not kept (the next login tries again). Refused (treated as unreadable,
logged at WARN) when: `issuer` differs from `auth.issuer`; `authorization_endpoint`,
`token_endpoint` or `jwks_uri` is missing; `token_endpoint` or `jwks_uri` is not on the issuer's
origin (scheme, host and port), so a tampered document cannot send the code or the key fetch
elsewhere; `code_challenge_methods_supported` is present and lacks `S256`.

*Amended 2026-10-18 (security review I1):* `authorization_endpoint` must be `https` (`http` only
when `APP_ENV` is `local` or `test`), so a tampered document cannot send the browser to a
`javascript:`, `data:` or plain-http target. `end_session_endpoint` must be `https` **on the
issuer's origin** and carry no user-info; otherwise it is ignored (logged at WARN) and `endSessionUrl` is
omitted. Reading discovery and keys is never cancelled by the request that triggered it (see 5.2).

### 5.2 Keys (JWKS)

- Read from `jwks_uri`. Only keys with `kty` `RSA`, `use` absent or `sig`, `alg` absent or `RS256`,
  a non-empty `kid` and a modulus of at least 2048 bits are kept; others are ignored.
- Kept for **1 hour**; then read again on next use, keeping the old keys if the read fails.
- A token naming a `kid` not held triggers one read, **at most once a minute** counting every
  read, the first included (made-up `kid`s cannot turn every request into a platform call); still
  unknown after that: refused (`unknown_kid`).
- When no key has ever been read and a read fails, the platform is not called again for 5 seconds
  (the token is "unavailable", not invalid). *Added by the build.*
- The keys and the discovery document are read by the server only; nothing a token says (`jku`,
  `x5u`, `jwk` header parameters) is ever fetched or used.

*Amended 2026-10-18 (security review F1):* the key read is shared by every request, so it runs
detached from the triggering request's context (bounded by the HTTP timeout), and a failed read
whose triggering request had already been cancelled does not start the refetch back-off. Otherwise a
client that sends a token with an unknown `kid` and hangs up would keep new signing keys out for a
minute (5 seconds before the first read). Java had the same defect (an interrupted caller aborted the
read and left the back-off set); fixed 2026-10-18 by running the read detached (section 14).

*Amended 2026-10-18 (security review I2):* the standalone verifier (`NewPlatformTokens`) refuses an
`http://` issuer unless the caller passes `AllowPlainHTTP()` (local and test only); the module
already refuses it outside those environments.

### 5.3 Cookies

| Cookie | Name (https) | Value | Attributes |
|---|---|---|---|
| session | `__Host-fw_session` | the session handle (section 6) | `Path=/; Secure; HttpOnly; SameSite=Lax`, no `Domain`, `Max-Age` = seconds to the absolute expiry |
| login binding | `__Host-fw_login_<h>`, `<h>` = the first 16 hex characters of SHA-256 of the `state` | the login record's handle | `Path=/; Secure; HttpOnly; SameSite=Lax`, no `Domain`, `Max-Age=600` |

`SameSite=Lax` is required, not `Strict`: the callback is a top-level navigation from the platform's
origin and must carry the binding cookie. Clearing a cookie sends it with an empty value and
`Max-Age=0` and the same attributes.

**Development over http.** When `auth.public-base-url` is `http://`, browsers drop `Secure` cookies
and refuse the `__Host-` prefix, so the names become `fw_session` and `fw_login_<h>` and `Secure`
is not sent. This is allowed only when `APP_ENV` is `local` or `test`; otherwise start-up fails. It
is logged at WARN at start-up ("sessions over plain http: development only"). An http `auth.issuer`
follows the same rule.

A request's cookies are read from every `Cookie` header; a cookie name that appears twice is
treated as absent (a sibling host cannot shadow ours with a second value). A value in double quotes
is the value without them (RFC 6265 allows it, and the JDK's cookie manager sends it so; found by
the end-to-end test).

### 5.4 Code exchange

`POST {token_endpoint}`, `Content-Type: application/x-www-form-urlencoded`, `Accept:
application/json`, body `grant_type=authorization_code`, `code`, `redirect_uri` (identical to the
authorize request's), `client_id`, `code_verifier`. A CONFIDENTIAL client adds `Authorization:
Basic base64(urlencode(client_id) ":" urlencode(secret))` (RFC 6749 section 2.3.1); a PUBLIC client
sends no secret at all. The answer must be `200` JSON with a string `id_token`; anything else is a
failure (section 4.2 step 7). The `access_token` is not kept (section 6.4); a `refresh_token`, if
the platform sent one, is discarded.

### 5.5 id_token verification

In this order; the name in brackets is the `details.reason` of `INVALID_ID_TOKEN`:

1. At most 16 384 characters, three base64url parts without padding, the first two non-empty
   [`malformed`]. (An empty signature part, as `alg: none` produces, gets as far as step 2 and is
   refused there; an empty signature with `RS256` fails step 3.) *Amended by the build.*
2. The header is a JSON object; `alg` is exactly `RS256` [`alg`] (so `none`, `HS256` and every other
   value are refused before any key is looked at: no algorithm confusion); `crit` is absent
   [`crit`]; `kid` is a non-empty string [`kid`].
3. The key for `kid` (section 5.2) [`unknown_kid`]; the RS256 signature over the first two parts as
   sent verifies with it [`signature`].
4. The payload is a JSON object [`malformed`].
5. `iss` equals `auth.issuer` exactly [`iss`].
6. `aud` is a string equal to `auth.client-id`, or an array containing it [`aud`].
7. If `aud` is an array of more than one value, `azp` must be present; when `azp` is present it
   must equal `auth.client-id` [`azp`].
8. `exp` is a number and now is before `exp` + 60 s [`expired`].
9. If `nbf` is present it is a number and now is at or after `nbf` − 60 s [`not_yet_valid`]; if
   `iat` is present it is a number not later than now + 60 s [`iat`].
10. `nonce` is present and equals the login record's nonce (constant-time) [`nonce`].
11. `sub` is a non-empty string [`sub`].
12. `token_use` is absent [`token_use`]: an access token (which carries it and has the issuer as
    audience) is never accepted as an id_token.

The 60-second allowance is fixed. The clock is the application's injected clock.

### 5.6 Access-token (bearer) verification

The same parse, header, key and signature rules (5.5 steps 1 to 4), then: `iss` equals the issuer;
`aud` is the issuer (string, or an array containing it); `exp`, `nbf`, `iat` as in 5.5; `token_use`
is exactly `api` (an identity token from a default login client is refused: [`token_use`]); `sub`
is a non-empty string. The keys being unreadable is not a refusal of the token: it is `503
UNAVAILABLE` ("tokens cannot be checked right now"), so a platform outage is not reported as bad
credentials.

The verifier is usable on its own, without sessions (Java: `PlatformTokens`), for an application
that only accepts service tokens on some endpoints. Its result is a closed set: valid (the claims
below), invalid (a reason from the list above), unavailable.

| Claim | Meaning in the verified token |
|---|---|
| `sub` | the principal id |
| `type` | `USER` or `SERVICE` (the principal kind) |
| `tier` | `ANCHOR`, `PARTNER` or `CLIENT` |
| `scope` | the granted permissions, separated by spaces |
| `clients` | `["*"]`, or `"{id}:{identifier}"` / `"{id}"` entries |
| `roles`, `applications` | as in the id_token |
| `azp` | the OAuth client the token was issued to |

The platform's access tokens carry no `client_id` (home client) claim, unlike its id_tokens
(corrected by the build: the first draft listed one).

## 6. Sessions

### 6.1 The session record (JSON)

What the stores hold for a browser session. The JSON is part of the contract so a store can be
inspected and shared by implementations:

```json
{
  "v": 1,
  "principal": {"id": "prn_...", "kind": "USER", "name": "...", "email": "..."},
  "tier": "ANCHOR",
  "homeClient": "clt_...",
  "clients": ["*"],
  "applications": ["*"],
  "roles": ["ingestor:operator"],
  "authTime": "2026-10-13T08:00:00Z",
  "createdAt": "2026-10-13T08:00:03Z",
  "lastSeenAt": "2026-10-13T08:12:00Z",
  "absoluteExpiresAt": "2026-10-13T16:00:03Z"
}
```

Built from the verified id_token: `principal.id` = `sub`, `kind` = `type` (default `USER`), `name`,
`email`, `tier`, `homeClient` = `client_id`, `clients`, `applications`, `roles` (arrays of strings;
other element types dropped), `authTime` = `auth_time` (else now). Times are ISO-8601 UTC. Optional
values are omitted, not `null`. Permissions are **not** stored: they are computed on each request
by the application's mapping, so a deployment that changes the mapping takes effect at once.

### 6.2 The login record (JSON)

```json
{"v": 1, "state": "...", "nonce": "...", "codeVerifier": "...", "returnTo": "/ingestions", "createdAt": "..."}
```

### 6.3 The store

Operations on records of a **kind** (`session` or `login`):

| Operation | Meaning |
|---|---|
| `create(kind, json, expiresAt)` → handle | stores a new record; the handle is the cookie value |
| `read(kind, handle)` → json or none | none when absent, expired, of another kind, or the handle is malformed |
| `take(kind, handle)` → json or none | read and delete in one atomic step |
| `update(kind, handle, json, expiresAt)` → handle | replaces the record; returns the handle to send from now on (the same one for server stores) |
| `delete(kind, handle)` | removes it; absent is not an error |

Server stores (`database`, `redis`, `memory`): the handle is 32 random bytes from a CSPRNG,
base64url without padding (43 characters, `[A-Za-z0-9_-]{43}`); anything else is "none" without a
store call. Only `base64url(SHA-256(handle))` is stored, so a leaked table or dump does not contain
usable cookies. A store failure is an error (500), never "no session".

| Driver | Storage |
|---|---|
| `database` (default) | table `fw_session` in the default database (DDL below); reads compare `expires_at` with the application's clock; a background loop `auth-session-purge` deletes expired rows every 10 minutes, 1000 at a time |
| `redis` | key `fw:sess:{kind}:{hash}`, value `{expiresAt in epoch milliseconds}\n{json}` so the application's clock decides as in the other stores, `PX` = time to `expiresAt` so Redis drops it too; `take` is `GETDEL` (Redis 6.2 or later); `update` is `SET ... PX ... XX` (a deleted record is not brought back) |
| `memory` | one process only; for tests and single-process development; an expired record is removed when read, and all expired records every 1024 creations |
| `cookie` | no server state; the handle is the sealed record (6.5) |

```sql
CREATE TABLE fw_session (
    id_hash    text        PRIMARY KEY,
    kind       text        NOT NULL,
    data       jsonb       NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX fw_session_expires_at ON fw_session (expires_at);
```

### 6.4 Lifetimes, rotation, revocation, tokens

- **Absolute timeout**: `absoluteExpiresAt` = `createdAt` + absolute timeout; never extended.
- **Idle timeout**: a session whose `lastSeenAt` + idle timeout is past is expired (deleted, `401`).
  `lastSeenAt` is written back when it is older than the smaller of 1 minute and half the idle
  timeout, so a busy session does not write on every request. The store's `expiresAt` is the
  earlier of the absolute and the idle expiry.
- **Rotation**: a new handle at every login; a session cookie present at the callback is deleted
  first. There is no privilege change inside a session (authority is fixed at login), so no other
  rotation point exists.
- **Revocation**: logout deletes the record; the next request with the old cookie is
  unauthenticated. Expired records are refused on read whatever the purge has done.
- **Tokens (ruling taken, section 12):** the session keeps **no platform token**: not the access
  token (an identity token, useless for the platform API), not the id_token (logout uses
  `client_id`, which the platform accepts in place of `id_token_hint`), and no refresh token
  (`offline_access` is not requested). Nothing token-like ever reaches the browser. The
  consequence: the principal's roles are those of the login; a role change or a deactivation at the
  platform takes effect at the next login, so at most after the absolute timeout. Adding
  revalidation later means requesting `offline_access`, keeping the refresh token server-side only
  (refused with the `cookie` driver), and serialising refreshes per session across replicas: the
  platform rotates refresh tokens with reuse detection and no grace period, so two concurrent
  refreshes revoke the whole family.

### 6.5 The `cookie` driver

The record travels in the cookie, sealed: `v1.{kid}.{base64url(iv ‖ ciphertext ‖ tag)}` with
AES-256-GCM, a 12-byte random IV, a 128-bit tag, and additional authenticated data `fw:{kind}`
(so a login record cannot be replayed as a session). The plaintext is
`{"exp": "<ISO-8601 expiresAt>", "data": <record>}`; a record whose `exp` has passed is "none".

`auth.cookie-keys` is a comma-separated list of `{kid}:{base64url of 32 bytes}`, `kid` matching
`[A-Za-z0-9_-]{1,16}`. The **first** key seals; every listed key opens (rotation: add the new key
first, keep the old one for the absolute timeout, then remove it). A malformed list, a key that is
not 32 bytes, or a duplicate `kid` fails start-up; the error never contains key material. A sealed
value over 3 800 characters fails the login (`500 INTERNAL`, logged: too many roles for a cookie).

**Rotate routinely** (security review I7): the keys are the only secret between a cookie and a
forged session, so rotate `auth.cookie-keys` on a schedule (for example quarterly, and at once on any
suspected exposure) with the procedure above; rotation costs nobody a session when the old key stays
for the absolute timeout.

Limits stated plainly: logout clears the cookie but cannot revoke a copy taken before; `take` on a
login record cannot be atomic, so the binding cookie is cleared and the platform's single-use code
is what stops a replayed callback. Use `database` or `redis` where revocation matters.

## 7. Authenticating requests

### 7.1 Order

On a route declared to require a context:

1. `Authorization: Bearer <token>` present (and bearer tokens accepted): verify it (5.6). Invalid:
   `401 UNAUTHENTICATED`; there is **no** fall-back to the cookie. Keys unreadable: `503
   UNAVAILABLE`. The cookie is ignored and the CSRF rules do not apply (a browser cannot be made to
   send this header cross-site without a CORS preflight, and there is no CORS).
2. Any other `Authorization` scheme: `401 UNAUTHENTICATED`.
3. The session cookie: on an unsafe method, the CSRF rules (7.2) first; then the session is read,
   its timeouts checked (6.4), and `lastSeenAt` written back when due.
4. Nothing: `401 UNAUTHENTICATED` (section 7.4 turns it into a redirect for HTML navigations).

Handlers on other routes can read the session explicitly (Java: `Sessions.current(request)`); the
same CSRF rule applies on unsafe methods.

### 7.2 CSRF (the platform's F16 rules)

For a request with an unsafe method (anything but `GET`, `HEAD`, `OPTIONS`) authenticated by the
session cookie:

1. `Sec-Fetch-Site` present: must be `same-origin` or `none`.
2. Else `Origin` present: must equal the origin of `auth.public-base-url` (scheme, host, port).
   *Amended 2026-10-18 (security review I5):* the header counts as present even when its value is
   empty (`Sec-Fetch-Site:`), and an empty value is refused; it is never read as absent.
3. **Neither header: refused** (cookie-carrying scripts are not a supported client; they use bearer
   tokens).
4. Refused by 1 to 3: `403 CROSS_ORIGIN_REQUEST`, logged at INFO with the method, path and the two
   header values.
5. Then, if the request has a body (a declared length greater than 0, from the server or the
   `Content-Length` header, or `Transfer-Encoding` present), its media type must be `application/json` or `application/*+json`: otherwise `415
   UNSUPPORTED_MEDIA_TYPE`. A request without a body needs no `Content-Type`.
   *Amended 2026-11-02 (owner ruling R2, [`server-rendered-ui.md`](server-rendered-ui.md) section 8):* a route
   the application declares as a form route (server-rendered pages) also accepts
   `application/x-www-form-urlencoded` and `multipart/form-data`. Rules 1 to 4 still apply to it unchanged.

No synchronizer token: Fetch Metadata, the Origin check, `SameSite=Lax` and JSON-only bodies leave
nothing for a token to add. **No CORS** is configured anywhere in the framework, so no credentialed
cross-origin request is ever permitted.

The public sign-in routes take no unsafe requests (`/auth/login` and `/auth/callback` are `GET`;
the platform hosts the credential forms). An application that adds a public `POST` next to them
(for example a sign-up form) applies the platform's CredentialCSRF rule with the same guard in its
"public" mode: the provenance check as above, but a request with neither header passes, and the
body must be JSON for every caller (Java: `CsrfGuard.publicForm()`).

### 7.3 The `Context`

| Context field | From a session | From a bearer token |
|---|---|---|
| `principal` | `principal.id` | `sub` |
| principal kind (added 2026-10-14, core contract section 3) | `principal.kind` | `type`: `SERVICE`, else `USER` |
| `client` | the client selection (below) | the client selection |
| `application` | the application code set in code, if any | the same |
| `permissions` | the permission mapping (below) | the permission mapping |
| correlation id | the request's | the request's |

**Client selection** is code. The `Context`'s client holds the client's **identifier** (its code,
`acme`), never the platform id (`clt_...`): the framework writes it into outbox rows as the
`clientCode` (core contract section 3, the guide's "What goes in a ClientId"). The default
selection, amended by the build:

1. the home client (`client_id` in an id_token, `homeClient` in the session) translated through the
   `clients` entry `{homeClient}:{identifier}`;
2. else, when `clients` holds exactly one entry and it has the form `{id}:{identifier}`, that
   identifier (a CLIENT-tier service token: the platform's access tokens carry no `client_id`);
3. else `_platform` (the same value as the receivers' platform client: "no particular client").

Whatever an application's selector returns, the framework then checks that the principal may act
for it, unless it is `_platform`: an entry of `clients` that is `*`, equals the value, starts with
`{value}:` (the value is an id) or ends with `:{value}` (the value is an identifier; identifiers
never contain `_`, ids always start with `clt_`, so the two cannot be confused). Otherwise `403
CLIENT_NOT_PERMITTED`.

**Permission mapping** is code: a function from the principal (kind, tier, roles, applications,
clients, and for a bearer token its `scope` list) to a set of permission strings. The default
returns the `scope` list of a bearer token and nothing for a session (an id_token carries roles, not
permissions; the application knows what its own roles mean). Provided helpers: the scopes; a
role-to-permissions table; the union of two mappings.

**Matching a permission** (core contract section 3, amended by M4c): a held permission matches a
required one when both have the same number of `:`-separated segments and each held segment is `*`
or equal to the required one (the platform's rule). `Context.hasPermission(required)`.

Java adds `Authenticated` on the request (Java: `Auth.current(request)`): the principal, its
roles, applications, clients, tier, the permissions, whether it came from a session or a bearer
token, and the session's expiries. A route middleware `Auth.require(permission)` answers `403
FORBIDDEN` when the context lacks it.

### 7.4 401 or 302

A `401 UNAUTHENTICATED` produced while resolving the context (or thrown by a handler) is turned into
`302 Location: /auth/login?returnTo=<path and query of the request, percent-encoded>` when the
request is an **HTML navigation**:

- the method is `GET` or `HEAD`, and
- there is no `Authorization` header, and
- `Sec-Fetch-Mode` is `navigate`, or, when `Sec-Fetch-Mode` is absent, `Accept` contains
  `text/html`.

Everything else keeps the `401` JSON envelope: an SPA's `fetch` sends `Sec-Fetch-Mode: cors` (or
`same-origin`) and navigates to `/auth/login?returnTo=` itself. A `401` from a bearer request is
never redirected.

## 8. Rate limiting

`/auth/login` and `/auth/callback` are public and each starts work against the platform, so they
are rate limited per client address with the rate-limiting contract's middleware. The limiter is
built in code (rate-limiting contract section 1, ruling 2026-10-13): the default is a memory
limiter named `auth`, 20 a minute, burst 20; an application replaces it (for example with a
`redis` limiter shared by replicas, or `KeyResolvers.forwardedFor` behind a proxy) or removes it in
code. **The default keys on the TCP peer** (`ratelimit.ClientAddress`), which behind a proxy is the
proxy: every user then shares one bucket of 20 a minute. Behind a proxy pass `auth.RateLimit` with a
limiter keyed by `ratelimit.ForwardedFor(n)`. *Amended 2026-10-18 (security review I3):* the Go
default logs one WARN the first time a request carries `X-Forwarded-For`, so the mistake shows up in
the logs (a cheaper and more reliable choice than guessing the deployment at start-up). `/auth/session` and `/auth/logout` are not limited (they cost one store read). The `429` is the rate-limiting contract's
envelope; to a browser navigation it is answered by the login failure handler with `RATE_LIMITED` (4.1.1).

## 9. Test support

- **Test identity provider** (Java: `TestOidcProvider`, in the `framework-auth` test-jar): a real
  HTTP server on a free port serving discovery, JWKS, `/oauth/authorize` (signs in a configured
  user at once and redirects with a code), `/oauth/token` (PKCE and `redirect_uri` checked,
  single-use codes, public and confidential clients) and `/auth/oidc/session/end`. It mints access
  tokens for bearer tests, and can be told to misbehave (wrong `aud`, `iss`, `nonce`, `alg`, an
  unknown `kid`, a tampered signature, expired, rotated keys, down).
- **Authenticated test requests** (Java: `AuthTesting`, main source set): creates a real session in
  the application's configured store for a given principal and returns the `Cookie` header (and the
  `Sec-Fetch-Site` header for unsafe calls), so an application's tests exercise its real routes,
  resolver and permission mapping without a provider. Refused unless `APP_ENV` is `test`.

## 10. Error codes

| Status | `error` | When |
|---|---|---|
| 400 | `INVALID_CALLBACK` | the callback's `state` or `code` is missing or malformed |
| 400 | `LOGIN_NOT_STARTED_HERE` | no binding cookie for this `state` in this browser |
| 400 | `INVALID_STATE` | the login record is gone (expired or used) or its state differs |
| 400 | `LOGIN_REFUSED` | the platform answered the authorize request with an error |
| 400 | `LOGIN_FAILED` | the token endpoint refused the code |
| 400 | `INVALID_ID_TOKEN` | an id_token rule failed (`details.reason`) |
| 401 | `UNAUTHENTICATED` | no valid session or bearer token |
| 403 | `CROSS_ORIGIN_REQUEST` | the CSRF provenance rule refused a cookie request |
| 403 | `CLIENT_NOT_PERMITTED` | the selected client is not among the principal's clients |
| 403 | `FORBIDDEN` | `Auth.require` found the permission missing |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | a cookie request's body is not JSON |
| 429 | `RATE_LIMITED` (rate-limiting contract) | over the limit on `/auth/login` or `/auth/callback`; to a browser navigation the failure handler's answer with this code (4.1.1) |
| 502 | `PLATFORM_UNAVAILABLE` | discovery, keys or the token endpoint cannot be reached during login (a browser navigation to `/auth/login`: the failure handler's answer, 4.1.1) |
| 503 | `UNAVAILABLE` | the keys cannot be read to check a bearer token |

The codes a failure handler can receive, and so a failure page's `?error=`, are eight: the seven
of 4.2 (`INVALID_CALLBACK`, `LOGIN_NOT_STARTED_HERE`, `INVALID_STATE`, `LOGIN_REFUSED`,
`LOGIN_FAILED`, `INVALID_ID_TOKEN`, `PLATFORM_UNAVAILABLE`) and `RATE_LIMITED`. The UI
kit's `signInFailureMessage` has a sentence for each.

## 11. Tests

Against the test identity provider, over its real HTTP server: the happy path end to end; wrong
state; replayed state (second callback); callback in a different browser (no binding cookie);
wrong nonce; wrong `aud`; wrong `iss`; `azp` mismatch; expired; `nbf` in the future; `alg: none`;
`HS256` signed with the public key as the secret (algorithm confusion); unknown `kid` (and the
one-fetch-a-minute limit); tampered signature and payload; `token_use` present in an id_token;
platform error answer; token endpoint 4xx and 5xx; discovery issuer mismatch and off-origin
endpoints. Sessions: cookie attributes for https and the http fallback (and its start-up refusal
outside local/test); rotation at login; logout revokes; idle and absolute expiry with an injected
clock; `lastSeenAt` write-back throttling; each driver against the store operations (database and
Redis in Testcontainers); cookie sealing with key rotation, a wrong key, a tampered value, a login
record replayed as a session. Requests: bearer wins over the cookie, a bad bearer does not fall
back, identity tokens refused, 503 when keys are unreadable; CSRF (each Sec-Fetch-Site value,
Origin match and mismatch, neither header, form and text bodies 415, body-less allowed, bearer
exempt); 401 versus 302 per the rules of 7.4; client selection and `CLIENT_NOT_PERMITTED`;
permission matching with wildcards; `Auth.require`; rate limit on the public routes; `AuthTesting`
refused outside test mode. Security-relevant assertions are mutation-checked (a deliberately
broken check must fail a test).

*Amended 2026-11-02 (owner ruling R2, section 7.2 rule 5; Go built in GU2, Java in GU3).* Form routes add these CSRF
cases, each with the route registered as a form route (Go `httpfw.AcceptForms()`) unless it says otherwise: an
`application/x-www-form-urlencoded` body and a `multipart/form-data` body pass with `Sec-Fetch-Site: same-origin`
and with a matching `Origin`; both are refused `403 CROSS_ORIGIN_REQUEST` with `Sec-Fetch-Site: cross-site`, with a
foreign `Origin`, and with neither header (the provenance rules run first and are unchanged); `text/plain`, a body
without a content type and an unparseable content type are still `415` on a form route; a form body on a route that
is **not** a form route is `415` (and so is one on a public-form guard); a bearer request is unaffected (exempt, as
before); a JSON body still passes on a form route. The Go tests are `TestCSRFFormRoutes` (`httpfw/csrf_test.go`),
`TestFormRoutesAcceptFormBodiesAfterProvenance` (`auth/request_test.go`, through the real resolver and store) and
`TestFormRoutePostsPassTheSessionsGuardOnlyWithProvenance` (`go/ui/fwui`); the mutation check is a form-route
test that fails when the route flag is ignored.

Conformance vectors: `conformance/auth/login-failure.json` (4.1.1: per request headers and
condition, the redirect or the envelope; written by `LoginFailureNavigationTest`; Go passes all 20 cases since G5b, 2026-10-18). The others (id_token cases, sealed cookies, the session
JSON) are not written; they are listed for when Go implements this contract.

## 12. Rulings

Ruled by the owner on 2026-10-14, for the SPA amendments (4.4, 4.5): typed operations omit `null`s
while plain routes keep them, so `endSessionUrl` is omitted once `/auth/session` and `/auth/logout`
are typed operations (MO); the client package section 4.5 refers to is `@flowcatalyst/api-runtime`
(linked by path until published under the `@flowcatalyst` scope). Rulings 1 to 7 below were taken
with the contract and **RULED by the owner on 2026-10-14** (confirmed as written; each stays
reversible by a later owner ruling like any other).

1. **No platform tokens in the session; no refresh** (6.4). Simplest correct choice for M4c:
   nothing to leak, nothing to rotate, no cross-replica refresh race. Cost: role changes and
   deactivation take effect at the next login.
2. **JDK HTTP client** for the three platform calls, not `framework-httpclient` (section 2). The
   plan said login would use the HTTP client module; the module brings Vert.x to every
   application that logs users in.
3. **`/auth/session` answers 200 with `authenticated: false`** rather than 401 (the Go design's
   sketch said 401): the SPA reads it at start without an error path, and a 401 there would be
   redirected by nothing anyway.
4. **http only in `local` and `test`** (5.3): the owner asked for a loud warning; start-up also fails
   outside those environments, because a session cookie over http in any shared environment is a
   credential in clear text.
5. **CSRF applies to `/auth/logout`** (the platform leaves its own logout unguarded): a forced
   logout is a nuisance worth the two-line check.
6. **Default permissions for a session are none** until the application maps its roles: a missing
   mapping fails closed.
7. **The `Context` gains `permissions`** (core contract section 3), because `authorize` steps see the
   `Context` and nothing else.

Ruled by the owner on 2026-10-15 (G9 of `ui/u3.md`): a failed or rate-limited `/auth/login` shows
the SPA's sign-in-failed page to a **browser navigation**, through the failure handler, with the new
code `RATE_LIMITED`; the contract is extended for frontends only, APIs keep the envelope (4.1.1).

## 13. Not in M4c

Server-rendered login, not-permitted or error pages (the platform hosts login; an application or
M-UI may add pages); refresh and revalidation (6.4); a client switcher in the session (an
application selects the client in code); portal logins (`/portal/*`, `ptu_` principals); a platform
URL for server-to-server calls different from the issuer (the platform must be reachable at
`auth.issuer` from the application); fixed bearer tokens and basic logins (application code, as
before); the `conformance/auth/` vectors.

## 14. Security review 2026-10-18: hand-off and known limits

Fixed in Go in the commits of that date: F1 (5.2), F2 (4.4), I1 (5.1), I2 (5.2), I3 (8), I5 (7.2),
I8 (the callback clears the binding cookie even when a store error answers `500`), I7 (6.5,
documentation).

**Java fixes** (all done 2026-10-18; `SecurityHardeningTest` holds a test for each, and the F1 one was
red before the fix: an interrupted first key read started the 5 second back-off):

1. **F2 (done)** `/auth/logout`: send the clearing `Set-Cookie` only when a session cookie was read and the
   CSRF check passed.
2. **I1 (done)** discovery: require `https` for `authorization_endpoint` and `end_session_endpoint` (`http`
   only in `local`/`test`), `end_session_endpoint` on the issuer's origin without user-info, else
   ignore it.
3. **I2 (done: `PlatformTokens.create` refuses `http`; `createAllowingPlainHttp` is the opt-in)** the standalone token verifier: refuse an `http://` issuer outside `local`/`test` unless
   explicitly opted in.
4. **I5 (done, test added)**: Java already refuses an empty `Sec-Fetch-Site`; keep its test (the Go side matched it).
5. **I8 (done)** callback: clear the binding cookie on the `500 INTERNAL` path of a store error too (taking the login record or creating the session).
6. **F1 (done: fixed, not only checked)** a cancelled request must not cancel the shared key read or start its back-off; Java runs the read on its own virtual thread; an interrupted caller only stops waiting (its interrupt flag is kept).
7. **I3 (done)**: the default limiter logs the same one-time WARN the first time a request carries `X-Forwarded-For`.

**Known limits, not addressed** (security review items I4, I6, I9 and I10; owner decision to skip;
the review report holds their text): recorded here so they are not mistaken for oversights.

## 15. Field workers (amendment 2026-11-03)

Field workers (drivers, pickers, yard-gate staff) sign in to the **platform** with a worker code
and PIN from an enrolled device, through the platform's `/field/*` routes and its `field-js`
library. The platform issues a short **field access token** bound to the device's key. An
application never sees a PIN and hosts no field login. It only verifies what the device presents.
Platform design: `flowcatalyst-go/docs/field-worker-identity-design.md` (section 6.1 is the
origin of this section). Wire reference: the "Field identity" section of the platform's
`docs/wire-contract.md`.

Field authentication is **off by default**. An application opts in with code (Go:
`auth.Build(..., auth.FieldWorkers(opts))`; Java: `AuthBuilder.fieldWorkers(opts)`). Sessions and
bearer tokens are unchanged.

### 15.1 The request

`Authorization: DPoP <field access token>` plus a `DPoP: <proof>` header (RFC 9449). *Amends 7.1:*
when field workers are enabled, the `DPoP` scheme is step 1b, after `Bearer`. Without the opt-in it
stays "any other scheme: `401`". There is no fall-back to the cookie, CSRF does not apply (as for
bearer tokens), and a `401` is never turned into a redirect (7.4: the request carries an
`Authorization` header).

### 15.2 Verifying the field access token

The parse, header, key and signature rules of 5.5 steps 1 to 4 (RS256 only, keys from the
platform's JWKS, the same cache), then:

| Check | Refusal reason |
|---|---|
| JOSE `typ` is exactly `fc-field+jwt` | `wrong_typ` |
| `iss` equals `auth.issuer` | `iss` |
| `aud` equals the application code (`flowcatalyst.application-code`), a string | `wrong_aud` |
| `exp`, `nbf`, `iat` as in 5.5 | `expired`, `not_yet_valid`, `iat` |
| `token_use` is exactly `field` | `wrong_token_use` |
| `sub` starts with `fwu_`; `client_id`, `client_identifier`, `sid`, `cnf.jkt` present (`device_id` too, unless the token is unenrolled: below) | `sub`, `claims` |
| no `pcr: true` claim (a temporary-PIN token works only on the platform's `/field/auth/pin`) | `pcr`, answered `403 PIN_CHANGE_REQUIRED` |

*Added by the Go build (2026-11-03):* the refusals of the parse, header, key and signature rules keep
their 5.5 names (`malformed`, `alg`, `crit`, `kid`, `unknown_kid`, `signature`); the platform's vectors
(15.9) call all of them `invalid_token`. `typ` is read after the signature has been checked, and the
checks of the table run in its order. `claims` also covers a `client_identifier` that is not a valid
client identifier (`[A-Za-z0-9._-]{1,100}`), because it becomes the `Context`'s client.

*Amended 2026-11-06 (field sign-in options):* **an unenrolled token** is signed in on a key that is not
an enrolled device. It has **no `device_id`** and `device_mode` is `UNENROLLED`; every other claim in
the table is still required, and the proof (15.3) works unchanged because `cnf.jkt` is the key.
`device_id` is therefore optional: absent, `FieldPrincipal.deviceId` is empty (Go) or null (Java). A
token that does carry `device_id` is read as before. **Impersonation**: a token whose worker has been
signed in by someone else carries `act` (RFC 8693) with `sub` (the actor's principal id, `fwu_...` for
a supervisor or `prn_...` for a platform user), `typ` (`field_worker` or `principal`) and `name`. Every
other claim is still the **worker's**. **Test identities** carry `test: true`. Neither claim is
required, and neither changes any refusal in the table.

A platform API token (`fc` access token, `token_use: api`) presented with the `DPoP` scheme is
refused (`wrong_typ`), and a field token presented as `Bearer` is refused by 5.6 (`aud` or `token_use`).
Keys unreadable: `503 UNAVAILABLE`, as in 5.6.

### 15.3 Verifying the proof

The proof is a compact JWS: header `typ: dpop+jwt`, `alg: ES256` only, `jwk` an EC P-256 public key
with no private members. The checks are:

- the signature verifies with that `jwk`;
- the RFC 7638 SHA-256 thumbprint of `jwk` equals the token's `cnf.jkt` (`jkt_mismatch`);
- `htm` equals the request method (`htm_mismatch`);
- `htu` equals `auth.public-base-url` + the request path, with no query or fragment (`htu_mismatch`).
  The `Host` header is never used. The path is the escaped path as received (Go:
  `URL.EscapedPath()`), and the comparison is exact;
- `ath` equals base64url(SHA-256(access token)) (`ath_mismatch`);
- `jti` is present (a string of at most 256 characters, else `invalid_proof`) and not seen before
  (`replayed`). The replay cache is written last, only for a proof that passed every other check;
- `iat` is within ±24 hours of the server clock (`stale_proof`). Field devices keep bad time, so
  freshness comes from the nonce;
- `nonce` is a valid application nonce (15.4). Missing or invalid: `401` with
  `WWW-Authenticate: DPoP error="use_dpop_nonce"`, a fresh `DPoP-Nonce` header and the envelope
  code `USE_DPOP_NONCE`. The client library retries once.

Any other refusal is `401 UNAUTHENTICATED` with `WWW-Authenticate: DPoP error="invalid_token"`. The
reason is logged at debug level, as for bearer tokens. *Added by the Go build:* a request with no
`DPoP` header is `missing_proof`; a proof that is not a compact JWS, has another `typ`, an `alg`
other than `ES256` or a `crit` header, whose `jwk` is not a public EC P-256 key on the curve (a `d`
member is refused), whose signature does not verify, or that lacks a numeric `iat` or a `jti`, is
`invalid_proof`; more than one `DPoP` header is `invalid_proof`. A replay cache that cannot be asked
is `503 UNAVAILABLE`.

### 15.4 Application nonces (stateless)

The platform's nonces are single-use and stored platform-side, so an application cannot check them.
The application issues its own nonces:

- `bucket` = floor(unix seconds / 300), as 8 bytes big-endian;
- `nonce` = base64url(`bucket` ‖ first 16 bytes of HMAC-SHA256(k, `"fw-field-nonce"` ‖ `bucket`));
- `k` = HKDF-SHA256 over the **current** cookie key (`auth.cookie-keys`, 5.3), with info
  `"fw-field-nonce"`. *Pinned by the Go build:* the input key material is the key's 32 raw bytes
  (the decoded value after `<kid>:`), the salt is empty (RFC 5869: a string of zero bytes), the
  output is 32 bytes. The MAC input is the ASCII bytes `fw-field-nonce` followed by the 8 bucket
  bytes. A valid nonce is exactly 24 bytes (32 characters); the 16 MAC bytes are compared in
  constant time, trying every configured key;
- a nonce is valid when its bucket is the current or the previous one and its MAC verifies under
  the key derived from **any** configured cookie key, so key rotation does not break devices.

Every response to a request that used the `DPoP` scheme carries a fresh `DPoP-Nonce` header,
success or error, so a client rarely needs the retry.

**The replay cache** remembers `jti` per `cnf.jkt` for 11 minutes, the nonce window plus one
minute. It is built in code (Go: `auth.FieldReplayCache`, one method `Remember(ctx, jkt, jti, ttl)`
returning whether the pair was new): `memory` by default, or `redis` through
`redisfw` (key `fw:dpop:{jkt}:{jti}`, `SET NX PX`; Go: `authredis.NewReplayCache`,
`authredis.ReplayCacheOf(clients)`). With more than one replica, the memory cache
lets a captured proof be replayed against another replica inside the window. The Go default logs
one WARN at start-up saying so. Applications with several replicas pass the Redis cache. Replay
also needs the access token (`ath`), and is limited to the same method and URL.

### 15.5 The `Context` and `Authenticated`

| Context field | From a field token |
|---|---|
| `principal` | `sub` (`fwu_...`) |
| principal kind | `FIELD_WORKER` (core contract section 3, amended 2026-11-03) |
| `client` | `client_identifier`. *Amends 7.3:* the application's client selector is **not** consulted. A field worker belongs to exactly one client, and the token names it |
| `application` | `aud` |
| `permissions` | the field permission mapping (below) |
| correlation id | the request's |
| actor | `act`, when present (*added 2026-11-06*): the operator behind the worker. The `principal` stays the worker, the **subject** (core contract section 3 and write-path section 7 say where the operator is recorded) |

`Authenticated` gains the source `FIELD` and a field part, `FieldPrincipal`: `workerId`,
`workerCode`, `name`, `clientId` (`clt_...`), `clientIdentifier`, `appCode`, `deviceId`,
`deviceMode` (`PERSONAL`, `SHARED` or `UNENROLLED`; `deviceId` is empty/null when `UNENROLLED`), `amr`
(list), `authTime`, `sessionId` (`sid`), `expiresAt`, `actor` (`id`, `type` `field_worker` or
`principal`, `name`; from `act`; absent when the worker signed in as themselves) and `test` (boolean). Its roles, applications, clients and tier are empty. A field worker has no platform
authority.

**Permission mapping** is code, separate from the session and bearer mapping:
`FieldPermissions(FieldPrincipal) -> set of permission strings`. The **default is none** (rule 6
of 12 carries over), so a route that requires a permission refuses field workers until the
application maps them. Helpers: a fixed set (`fieldPermissions.all(...)`; Go `auth.FieldPermissionsAll`) and one set per
device mode (`fieldPermissions.byDeviceMode(personal, shared)`; Go `auth.FieldPermissionsByDeviceMode`),
because shared kiosks usually get less. A device mode that is neither `PERSONAL` nor `SHARED` gets
the `shared` set (the lesser); that includes `UNENROLLED`. *Added 2026-11-06:* a map form gives each
mode its own set, `PERSONAL`, `SHARED` and `UNENROLLED`, and a mode with no entry gets **no**
permissions (it fails closed): Go `auth.FieldPermissionsByDeviceModes`, Java
`FieldPermissions.byDeviceMode(Map)`. The Go option that sets the mapping is `auth.MapFieldPermissions`.
Field workers need `auth.Application` (the audience) and `auth.cookie-keys` (the nonces), and
start-up fails naming whichever is missing.

*Added 2026-11-06 (application-held permissions):* an application that keeps a worker's roles in its
own store gives a **resolver** instead of (or beside) the mapping: Go `FieldPermissionResolver`,
`func(ctx, FieldPrincipal) ([]string, error)`, set by `auth.FieldPermissionsFrom`; Java
`FieldPermissionResolver.permissions(Request, FieldPrincipal)`, which may throw, set by
`FieldOptions.Builder.permissionResolver`. It is called on every authenticated field request, after
the token and proof check out, with the **request's own context** (Go: the request's `ctx`, so the
application's request-scoped values and its database are at hand; Java: the `Request`). The result
is the worker's permission set exactly as the mapping's would be; a nil or empty result grants
nothing (fail closed, as for the mapping). An **error is not "no permissions"**: a resolver that
fails (Go: returns an error; Java: throws) answers `503 UNAVAILABLE`, the same body and
`DPoP-Nonce` header as the keys-unreadable answer in 15.1, never `401` or `403`, so the device
retries rather than treating a store outage as a refusal. One of mapping and resolver is in force:
the last one given wins, and the existing mapping helpers are unchanged (the mapping is adapted to
a resolver that never fails).

A route that must be field-only or never-field checks
`Authenticated.source` (Go: `auth.RequireSource(auth.SourceField)`).

### 15.6 Online check before a high-value action (flowcatalyst module)

Access tokens live 10 minutes. A revoked device or suspended worker keeps a valid token until it
expires. For actions that cannot wait (opening a gate, releasing a load), route middleware in the
`flowcatalyst` module (Go: `flowcatalyst.RequireLiveField(maxAge)`) calls the platform's `POST
/api/field-tokens/introspect {token}` with the application's service account, which needs the
permission `platform:iam:field-token:introspect`. The answer `{active, sub, clientId, workerCode,
deviceId, appCode, amr, authTime, exp}` is cached per `sid` for `maxAge` (default 0: no cache); only
`active: true` answers are cached, and an answer whose `sub` is present and differs from the
request's worker counts as `active: false`. On a request that was not authenticated as a field
worker the middleware answers `403 FORBIDDEN` (it never lets such a request through unchecked).

- `active: false`: `401 UNAUTHENTICATED` with `WWW-Authenticate: DPoP error="invalid_token"`.
- Platform unreachable: `503 UNAVAILABLE`. It fails closed: a high-value action is never let
  through unchecked.

### 15.7 Revocation events (flowcatalyst module)

The platform emits `platform:iam:field-worker:suspended`, `platform:iam:field-device:revoked` and
`platform:iam:field-session:revoked`. `OnFieldRevocation(handler)` (Go:
`flowcatalyst.OnFieldRevocation`) registers a receiver for the three. The handler gets a sealed
value, `WorkerSuspended{workerId, clientId}`, `DeviceRevoked{deviceId, clientId}` or
`SessionRevoked{sessionId, workerId, deviceId}`, built from the event data as the platform writes it
(the platform's `fieldidentity/events.go` and `operations.go`). *Pinned by the Go build:* the data of
`field-worker:suspended` is `{workerId, clientId, workerCode}`; of `field-device:revoked`
`{deviceId, clientId, mode}`; of `field-session:revoked` `{clientId, reason, count, workerId?,
deviceId?, applicationId?, familyId?}`. `SessionRevoked.sessionId` is therefore `familyId` (the
token's `sid`), which the platform sets only when it revokes one family (a refresh-token reuse); an
administrator revoking all sessions of a worker or a device gives `workerId` or `deviceId` and no
`sessionId`. The value also carries `clientId` and `reason`. An event whose data lacks the identifier
it is about (`workerId`; `deviceId`; all of `familyId`, `workerId` and `deviceId`) is rejected as
undeliverable. The events are for **application state** (cached work
assignments, open gate sessions), not for token validity: tokens expire on their own within 10
minutes. The definition sync declares the subscription. **To check when building:** that an
application's subscription to `platform:iam:field-*` events is accepted and delivered by a real
platform (`fcdev`). If it is not, record it as platform work in `flowcatalyst.md` section 5 and keep
the receiver.

### 15.8 Test support

- The test identity provider (9) mints field access tokens: Go `authtest.Provider.FieldToken(claims)`,
  Java `TestOidcProvider.fieldToken(..)`. Defaults are a valid token for the application under test,
  and any claim can be overridden.
- **A test device**: Go `authtest.NewFieldDevice()`, Java `TestFieldDevice`. It holds an ES256 key,
  gives its `jkt`, and `Proof(method, url, nonce, token)` returns a valid proof, with options to
  break each rule. A helper fetches an application nonce from a first `401`.
- `AuthTesting` gains `fieldRequest(principal)`, which returns the `Authorization` and `DPoP`
  headers for a route under test. It is refused unless `APP_ENV` is `test`. *Go:*
  `auth.Testing.FieldRequest(provider, device, method, path, auth.FieldPrincipal{})` (the provider and
  the device above, passed as the interfaces `auth.FieldTokenMinter` and `auth.FieldProofSigner`;
  zero fields of the principal get defaults); `Testing.FieldNonce()` returns a current application
  nonce without the 401 round trip, and `authtest.NonceFrom(header)` reads one from a response.

### 15.9 Conformance

`conformance/field/` holds the platform's generated vectors (`jwks.json`, `cases.json`, copied
from `flowcatalyst-go/api/field-fixtures/` with the source commit in `conformance/field/README.md`).
Each case gives `accessToken`, `dpopProof`, `method`, `url`, `now` and `expect` (`valid` or a
refusal reason in 15.2 and 15.3). The platform's vectors carry the **platform's** nonces, so the
conformance run turns the application-nonce check off through a test-only option. *Amended by the
Go build:* the vectors' `stale_proof` case is a proof one hour old, which the 24-hour window of 15.3
accepts, so the same test-only option also narrows the proof window to the vectors' five minutes.
And the vectors' `expect: invalid_token` stands for any refusal of the parse, header, key and
signature rules (15.2). *Added 2026-11-03:* the vectors' `missing_exp` case (a token with no `exp`) expects `invalid_token`;
the framework refuses it as `expired` (5.5 rule 8), and the conformance run counts that as a match.
*Added 2026-11-06:* four more `valid` cases carry a `principal` object that says what the verified
principal holds: `valid_unenrolled` (no `device_id`, `UNENROLLED`), `valid_impersonated_supervisor`
(`act` type `field_worker`), `valid_impersonated_principal` (`act` type `principal`, unenrolled) and
`valid_test` (`test: true`). Go and Java assert `deviceId`, `deviceMode`, `amr`, `actor` and `test`
against it (21 cases in all). Nonces are covered
by their own tests (15.10). Go and Java both run every case.

### 15.10 Tests

Every refusal in 15.2 and 15.3, over real HTTP routes:

- A field token as `Bearer`, and an API token as `DPoP`.
- A missing proof.
- A proof signed by another key.
- A replayed `jti`, through the memory cache and the Redis cache (Java: Testcontainers; Go: the Redis
  server `fwtest` starts from `PATH`, as for the Redis session store).
- A nonce from two buckets ago, a nonce under a rotated-out key (refused), and a nonce under the
  previous cookie key that is still configured (accepted).
- `use_dpop_nonce` and its `DPoP-Nonce` header.
- `pcr` answered with `403 PIN_CHANGE_REQUIRED`.
- The client selector ignored.
- The default mapping gives no permissions, and both helpers.
- `RequireSource`.
- `RequireLiveField`: active, inactive, platform down giving 503, and the per-`sid` cache.
- `OnFieldRevocation`: each of the three events.
- The DPoP branch disabled: the `DPoP` scheme answered `401`.

### 15.11 Not in this section

Field login, PINs, enrolment and refresh: these are the platform and `field-js`. Server-rendered
field pages: a field page is a page the `field-js` library calls with `fetch`, and no field cookie
exists. Offline receipts and unlock keys: platform phases P5 and P6.
