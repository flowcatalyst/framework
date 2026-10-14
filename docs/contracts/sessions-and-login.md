# Contract: sessions and login (M4c)

Status: M4c, written 2026-10-13 before the Java code; Java built the same day (`framework-auth`,
`framework-auth-database`, `framework-auth-redis`). Amendments found by the build are marked.
*Amended 2026-10-14 for the SPA (UI direction, [`../handover.md`](../handover.md)): section 4.5 (what
a single-page application relies on, the two endpoints as typed operations) and 4.4 (`endSessionUrl`
omitted rather than `null`); neither is built yet.* **Language-neutral and JSON-first**: Java,
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
| `auth.client-secret` | secret | no | set for a CONFIDENTIAL login client; unset means a PUBLIC client (the platform refuses a secret from a public client) |
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
   PLATFORM_UNAVAILABLE`.
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
status codes above are the default and the conformance reference. Failures are logged at INFO with
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
  not null") and the OpenAPI contract's "may be absent" rule. The Java code still writes `null` until
  logout becomes a typed operation (section 4.5); an SPA that tests `endSessionUrl` for truthiness
  works with both. Ruled by the owner 2026-10-14: typed operations omit `null`s, plain routes keep
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

In TypeScript the union narrows on `session.authenticated === true`. Whether hey-api's TypeScript and
Zod plugins render `const` as literal types is **unverified** and checked at the emitter step; if
not, the components use a one-value `enum` instead, in this section and the vector together.

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
3. **Neither header: refused** (cookie-carrying scripts are not a supported client; they use bearer
   tokens).
4. Refused by 1 to 3: `403 CROSS_ORIGIN_REQUEST`, logged at INFO with the method, path and the two
   header values.
5. Then, if the request has a body (a declared length greater than 0, from the server or the
   `Content-Length` header, or `Transfer-Encoding` present), its media type must be `application/json` or `application/*+json`: otherwise `415
   UNSUPPORTED_MEDIA_TYPE`. A request without a body needs no `Content-Type`.

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
code. `/auth/session` and `/auth/logout` are not limited (they cost one store read).

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
| 429 | (rate-limiting contract) | over the limit on `/auth/login` or `/auth/callback` |
| 502 | `PLATFORM_UNAVAILABLE` | discovery, keys or the token endpoint cannot be reached during login |
| 503 | `UNAVAILABLE` | the keys cannot be read to check a bearer token |

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

Conformance vectors (`conformance/auth/`: id_token cases, sealed cookies, the session JSON) are not
written in M4c; they are listed for when Go implements this contract.

## 12. Rulings taken here (owner may reverse)

Ruled by the owner on 2026-10-14, for the SPA amendments (4.4, 4.5): typed operations omit `null`s
while plain routes keep them, so `endSessionUrl` is omitted once `/auth/session` and `/auth/logout`
are typed operations (MO); the client package section 4.5 refers to is `@flowcatalyst/api-runtime`
(linked by path until published under the `@flowcatalyst` scope). Rulings 1 to 7 below were not part
of that answer and stay open to reversal.

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

## 13. Not in M4c

Server-rendered login, not-permitted or error pages (the platform hosts login; an application or
M-UI may add pages); refresh and revalidation (6.4); a client switcher in the session (an
application selects the client in code); portal logins (`/portal/*`, `ptu_` principals); a platform
URL for server-to-server calls different from the issuer (the platform must be reachable at
`auth.issuer` from the application); fixed bearer tokens and basic logins (application code, as
before); the `conformance/auth/` vectors.
