<!-- types: FieldPrincipal, FieldPermissionMapper, FieldOption, FieldResult, FieldValid, FieldInvalid, FieldNonceRequired, FieldUnavailable, FieldTokens, FieldReplayCache, MemoryFieldReplayCache, ReplayCache, FieldRevocation, WorkerSuspended, DeviceRevoked, SessionRevoked, FieldDevice, FieldTokenMinter, FieldProofSigner, DeviceOption, ProofOption, Source -->
# Go: field workers

Drivers, pickers and yard-gate staff sign in to the **platform** with a worker code and a PIN, from
an enrolled device, through the platform's `/field/*` routes and its `field-js` library. The
platform issues a short **field access token** bound to the device's key. Your application never
sees a PIN and hosts no field login: it only verifies what the device presents with each request.
The rules are in section 15 of the
[sessions and login contract](../../contracts/sessions-and-login.md#15-field-workers-amendment-2026-11-03);
this page is the Go side. (Sessions, bearer tokens and the rest of package `auth` are unchanged:
[Go: sessions and login](sessions-and-login.md).)

Field authentication is off by default. You opt in with `auth.FieldWorkers` in the module's build
options; it needs three things the module reports at start-up if they are missing:

- `auth.Application(code)`: field tokens are addressed (`aud`) to the application's code;
- `auth.cookie-keys`: the application nonces are derived from these keys (any store, not only the
  cookie store);
- nothing else, but with more than one replica you also pass a shared replay cache (below).

```go
m, err := d.Build(ctx, b,
	auth.SessionStore(authredis.Factory(clients)),
	auth.Application(fw.MustApplicationCode("orders")),
	auth.FieldWorkers(
		auth.MapFieldPermissions(auth.FieldPermissionsByDeviceMode(
			[]string{"orders:thing:create", "orders:thing:view"}, // PERSONAL device
			[]string{"orders:thing:view"},                        // SHARED kiosk
		)),
		auth.FieldReplay(replay), // a shared cache when there is more than one replica
	),
)
```

## The request

`Authorization: DPoP <field access token>` and a `DPoP: <proof>` header (RFC 9449). The resolver
checks the `DPoP` scheme after `Bearer`; there is no fall back to the cookie, CSRF does not apply
(as for bearer tokens), and a `401` is never turned into a redirect. Without `auth.FieldWorkers`
the `DPoP` scheme is a plain `401`.

The OpenAPI document lists the `dpop` security scheme (HTTP scheme `DPoP`, `bearerFormat`
`fc-field+jwt`; operations then list `session`, `bearer`, `dpop`). The document is written in the
declare phase, before `Build`, so pass `auth.DeclareFieldWorkers()` to `auth.Declare` as well as
`auth.FieldWorkers` to `Build`; `Build` fails when only one of them is given. Without field workers
the document is unchanged.

The token must be RS256 from the platform's keys (the same key cache as `PlatformTokens`), with
JOSE `typ` `fc-field+jwt`, `iss` the platform, `aud` your application code, `token_use` `field`,
a `sub` starting `fwu_` and the claims `client_id`, `client_identifier`, `sid` and
`cnf.jkt` (`device_id` too, except on a device that is not enrolled: `device_mode` `UNENROLLED`). A token with `pcr: true` (a temporary PIN: it works only on the platform's
`/field/auth/pin`) is answered `403 PIN_CHANGE_REQUIRED`. The proof must be an ES256 JWS whose
`jwk` is a public P-256 key with the thumbprint `cnf.jkt`, with `htm` the method, `htu` your
`auth.public-base-url` plus the request's escaped path (never the `Host` header, no query),
`ath` the hash of the token, a `jti` not seen before, an `iat` within 24 hours of your clock
(field devices keep bad time) and a valid application **nonce**.

The answers:

| Situation | Answer |
|---|---|
| any refusal of the token or the proof | `401 UNAUTHENTICATED`, `WWW-Authenticate: DPoP error="invalid_token"` (the reason is logged at debug level) |
| no nonce, or one that is not valid | `401 USE_DPOP_NONCE`, `WWW-Authenticate: DPoP error="use_dpop_nonce"`; the client retries once with the nonce in the response |
| `pcr` token | `403 PIN_CHANGE_REQUIRED` |
| the platform's keys or the replay cache cannot be read | `503 UNAVAILABLE` |

Every response to a request that used the `DPoP` scheme carries a fresh `DPoP-Nonce` header,
success or error, so a client rarely needs the retry.

## Application nonces

The platform's own nonces are single use and stored there, so your application issues its own,
statelessly: five-minute buckets, a MAC under a key derived (HKDF-SHA256, info `fw-field-nonce`)
from a cookie key, valid in the current and the previous bucket under **any** configured cookie
key. Rotating `auth.cookie-keys` therefore does not break devices; removing a key from the list
does invalidate the nonces made with it. Nothing is stored.

The **replay cache** remembers each proof's `jti` per device key for 11 minutes. The default is
`auth.NewMemoryFieldReplayCache`, and the module logs one WARNING at start-up saying that with more
than one replica a captured proof can be replayed against another replica inside the window. With
several replicas pass the Redis cache (key `fw:dpop:{jkt}:{jti}`, `SET NX PX`):

```go
cache, err := authredis.ReplayCacheOf(clients) // the default Redis instance
...
auth.FieldReplay(cache)
```

A replay cache that cannot be asked answers `503`: the check is never skipped. (Replay also needs
the access token, because of `ath`, and is limited to the same method and URL.)

## In a handler

A field request builds the usual `fw.Context`:

| Context | From a field token |
|---|---|
| principal | `sub` (`fwu_...`), kind `fw.PrincipalFieldWorker` (`FIELD_WORKER`) |
| client | `client_identifier`; the module's client selector is **not** consulted |
| application | `aud` |
| permissions | the field permission mapping below |

`auth.Current(r)` gives the `Authenticated` with `Source` `auth.SourceField` and `Field`, a
`*auth.FieldPrincipal`: `WorkerID`, `WorkerCode`, `Name`, `ClientID`, `ClientIdentifier`, `AppCode`,
`DeviceID`, `DeviceMode` (`PERSONAL` or `SHARED`), `AMR`, `AuthTime`, `SessionID` and `ExpiresAt`.
A field worker has no platform authority: its `Principal` has no roles, applications, clients or
tier.

**Permissions** come from the field mapping, separate from the session and bearer mapping, and the
default grants none, so a route that requires a permission refuses field workers until you map
them. `auth.FieldPermissionsAll(...)` grants one set, `auth.FieldPermissionsByDeviceMode(personal,
shared)` one set per device mode (a device whose mode is neither is treated as `SHARED`, the
lesser), or write a `FieldPermissionMapper`.

A route that must be field-only, or never field, says so:

```go
rt.HandleContext("POST /api/field/only", handler, httpfw.With(auth.RequireSource(auth.SourceField)))
rt.HandleContext("POST /api/field/never", handler, httpfw.With(auth.RequireSource(auth.SourceSession, auth.SourceBearer)))
```

## Online check before a high-value action

Access tokens live ten minutes, and a revoked device or a suspended worker keeps a valid token until
it expires. For an action that cannot wait (opening a gate, releasing a load) use the FlowCatalyst
module's middleware. It asks `POST /api/field-tokens/introspect` with the application's service
account, which needs the permission `platform:iam:field-token:introspect`:

```go
rt.HandleContext("POST /api/gate/open", open, httpfw.With(fc.RequireLiveField(30*time.Second)))
```

An active answer is remembered per `sid` for the given time (`0`: always ask); inactive answers are
never remembered. Not active: `401` with `DPoP error="invalid_token"`. The platform cannot be asked
(down, an error, a service account without the permission, the module disabled): `503`, it **fails
closed**. A request that was not authenticated as a field worker is `403`.

## Revocation events

The platform emits `platform:iam:field-worker:suspended`, `platform:iam:field-device:revoked` and
`platform:iam:field-session:revoked`. Register one receiver for the three, in the build function:

```go
err := flowcatalyst.OnFieldRevocation(fc.Receivers(), func(ctx context.Context, cx fw.Context, e flowcatalyst.FieldRevocation) error {
	switch e := e.(type) {
	case flowcatalyst.WorkerSuspended: // e.WorkerID, e.ClientID
	case flowcatalyst.DeviceRevoked:   // e.DeviceID, e.ClientID
	case flowcatalyst.SessionRevoked:  // e.SessionID, e.WorkerID, e.DeviceID, e.ClientID, e.Reason
	}
	return nil // an error is a transient failure: the platform delivers again
})
```

The values are built from the event data as the platform writes it. `SessionRevoked.SessionID` is the
login family and is set only when one family was revoked (a refresh-token reuse); an administrator
revoking every session of a worker or a device gives `WorkerID` or `DeviceID` and no `SessionID`.
Use the events for **application state** (cached work assignments, open gate sessions), not for
token validity. The application must also declare a subscription to the event types with
`Definitions.Subscription`, which the sync creates. **Not verified:** that a real platform accepts
and delivers an application's subscription to `platform:iam:field-*` events (no platform was run
for this build; see the [flowcatalyst contract](../../contracts/flowcatalyst.md) section 5).

## Testing

Everything works over real HTTP routes in your tests, with `APP_ENV=test`:

```go
tt, _ := module.Testing()
device := authtest.NewFieldDevice(authtest.DeviceClock(clock))
provider.FieldAudience("orders")
headers, _ := tt.FieldRequest(provider, device, "POST", "/api/echo", auth.FieldPrincipal{DeviceMode: "SHARED"})
// headers carry Authorization and DPoP; zero FieldPrincipal fields get defaults
```

- `authtest.Provider.FieldToken(claims)` mints a field access token (RS256, `typ` `fc-field+jwt`,
  ten minutes, the audience set with `FieldAudience`), any claim overridable (a `nil` removes it);
  `FieldTokenFor(device, claims)` also binds it to a device.
- `authtest.NewFieldDevice()` is a device with an ES256 key: `JKT()`, `Proof(method, url, nonce,
  token, opts...)`. Options break one rule each: `ProofHTM`, `ProofHTU`, `ProofATH`, `ProofJTI`,
  `ProofIssuedAt`, `ProofWithoutNonce`, `ProofWithoutJTI`, `ProofSignedBy(other)`,
  `ProofTamperedSignature`, `ProofHeader`, `ProofJWKMember`.
- `Testing.FieldNonce()` returns a current nonce; `authtest.NonceFrom(header)` reads the
  `DPoP-Nonce` of a response, as a client does after a first `401`.

## What was checked

`go/auth` tests over real HTTP routes: every refusal of sections 15.2 and 15.3 (a field token as
`Bearer`, an API token as `DPoP`, a missing proof, a proof from another key, claim by claim, each
proof rule), replay through the memory and the Redis cache (a Redis server from `PATH`, as for the
Redis session store), nonces (the algorithm written out a second time in the test, the previous
bucket, a nonce under an older configured key, a key rotated out, two buckets ago), `use_dpop_nonce`
and its header, `pcr`, the ignored client selector, the three mappings, `RequireSource`, and the
`DPoP` scheme with field workers off. `go/flowcatalyst` tests: `RequireLiveField` (active, inactive,
the platform failing in five ways, unreachable, the per-`sid` cache) and `OnFieldRevocation` (each
of the three events, rejections, a failing handler). `TestFieldConformance` runs every case of
`conformance/field/` (the platform's vectors, commit `b8c04573`) with the nonce check off and the
proof window at the vectors' five minutes. **Not checked:** a request from the real `field-js`
library, and a real platform delivering the revocation events.
