<!-- types: PlatformTokens, AccessToken, TokenResult, Valid, Invalid, Unavailable, Principal, Provider, Signing -->
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
| 3 | session stores (`memory`, `cookie`, `database`, `redis`), lifetimes, rotation, revocation | not yet |
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
