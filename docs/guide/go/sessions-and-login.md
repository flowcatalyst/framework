<!-- types: PlatformTokens, AccessToken, TokenResult, Valid, Invalid, Unavailable, Principal, Provider, Signing, Store, Kind, StoreFactory, MemoryStore, CookieStore, CookieKeys -->
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
| 4 | the four endpoints, the context resolver, 401 versus 302, `Context.permissions`, test helpers, the login-failure vector | not yet |

## Verifying platform tokens on their own

`auth.NewPlatformTokens(issuer, timeout, clock, logger)` verifies a platform **access token**
(contract section 5.6) without any session machinery, for an application that accepts service
tokens on some endpoints. It is safe for concurrent use; build one and keep it, because it caches the
platform's discovery document and signing keys.

```go
tokens, err := auth.NewPlatformTokens("https://platform.example.com", 10*time.Second, b.Clock(), logger)
switch r := tokens.Verify(ctx, bearer).(type) {
case auth.Valid:        // r.Token.Principal, r.Token.MayActFor("acme"), r.Token.ExpiresAt
case auth.Invalid:      // 401; r.Reason is the contract's name (alg, signature, expired, token_use, ...)
case auth.Unavailable:  // 503: the keys cannot be read right now; not a verdict on the token
}
```

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
confidential clients) and `/auth/oidc/session/end`. It mints access tokens (`AccessToken`) and can
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
