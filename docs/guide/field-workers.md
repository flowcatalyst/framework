<!-- types: FieldOptions, FieldPermissions, FieldPrincipal, FieldTokens, FieldTokenResult, FieldReplayCache, RedisFieldReplayCache, TestFieldDevice, RequireLiveField, FieldRevocation, FieldIntrospection, Receivers, PlatformClient, AuthModule, Authenticated, Auth, AuthTesting, TestOidcProvider, PrincipalKind, Context -->
# Field workers

Drivers, pickers and yard-gate staff do not have platform accounts. They sign in to the **platform**
with a worker code and a PIN from an enrolled device (through its `/field/*` routes and the `field-js`
library), and the platform issues a short **field access token** bound to the device's key. Your
application never sees a PIN and hosts no field login: it only verifies what the device presents on
each request. The specification is [section 15 of the sessions and login
contract](../contracts/sessions-and-login.md); this page is the Java side.

Field workers are **off by default**. Sessions and bearer tokens work as before.

## Turning it on

```java
AuthModule.builder()
    .application(new ApplicationCode("orders"))     // a token's audience must equal this
    .fieldWorkers(FieldOptions.builder()
        .permissions(FieldPermissions.byDeviceMode(Set.of("orders:gate:open", "orders:gate:view"), Set.of("orders:gate:view")))
        .replayCache(lookup -> RedisFieldReplayCache.create(lookup.get(RedisConnections.class).defaultInstance()))
        .build())
    .build()
```

- It needs `.application(...)` and `auth.cookie-keys` (the nonces below derive from them); without
  either, start-up fails with a message that says which.
- The `DPoP` scheme then works on every route that requires a context. Without the opt-in the
  `DPoP` scheme is answered `401`.
- `FieldOptions` also takes `proofWindow` (default 24 hours, see below).

## What a request looks like

`Authorization: DPoP <field access token>` and `DPoP: <proof>` (RFC 9449). The framework checks, in
order:

1. **The token**, with the same keys, cache and issuer as bearer tokens, RS256 only: JOSE `typ`
   `fc-field+jwt`, `iss`, `aud` equal to your application code (a string), `exp`/`nbf`/`iat`,
   `token_use` `field`, `sub` starting `fwu_`, and `client_id`, `client_identifier`, `device_id`,
   `sid` and `cnf.jkt` present, and `client_identifier` a valid client identifier. A `device_mode` that is neither `PERSONAL` nor `SHARED` is treated as `SHARED` (the lesser permission set). A `pcr` claim (a temporary-PIN token, valid only on the
   platform's PIN change) is answered `403 PIN_CHANGE_REQUIRED`.
2. **The proof**: signed by the key named in its header (ES256 only), whose RFC 7638 thumbprint equals
   the token's `cnf.jkt`; `htm` equals the method; `htu` equals `auth.public-base-url` plus the request
   path (no query, and the `Host` header is never used); `ath` is the hash of the token; `jti` is
   present; `iat` is within the window.
3. **The application nonce** and the **replay cache**.

Any refusal is `401 UNAUTHENTICATED` with `WWW-Authenticate: DPoP error="invalid_token"`; the reason
is logged at debug level. There is no fall-back to the session cookie, and CSRF does not apply (as for
bearer tokens). A key set that cannot be read is `503 UNAVAILABLE`.

### Nonces

Field devices keep bad time, so a proof's `iat` is accepted within 24 hours of your clock and
freshness comes from a nonce. The platform's nonces are stored platform-side, so your application
issues its own, statelessly: a nonce is the five-minute bucket and a MAC of it under a key derived
(HKDF-SHA256) from your cookie key. It is valid in its bucket and the next, under any configured
cookie key, so rotating `auth.cookie-keys` does not break devices.

Every answer to a `DPoP` request carries a fresh `DPoP-Nonce` header, success or error. A missing or
invalid nonce is `401` with `WWW-Authenticate: DPoP error="use_dpop_nonce"` and the code
`USE_DPOP_NONCE`; the `field-js` library retries once.

### The replay cache

A proof's `jti` is remembered per device key for 11 minutes. The default is **in memory**, and start-up
logs one WARN saying so: with more than one replica a captured proof can be replayed against another
replica inside the window. Applications with several replicas pass the Redis cache
(`RedisFieldReplayCache`, in `framework-auth-redis`; key `fw:dpop:{jkt}:{jti}`, `SET NX PX`). A cache
that cannot be reached answers `503`: a proof is never accepted unchecked. Replay also needs the
access token and is limited to the same method and URL.

## In your routes

The request's `Context` has the principal `fwu_...`, the kind `PrincipalKind.FIELD_WORKER`, the
**client from the token** (`client_identifier`; your `ClientSelector` is not consulted, because a
field worker belongs to exactly one client), the application, and the permissions from your **field**
mapping.

```java
routes.post("/api/v1/gates/open", gates::open,
    RouteOptions.requiresContext()
        .with(Auth.requireSource(Authenticated.Source.FIELD))
        .with(Auth.require("orders:gate:open")));

Optional<FieldPrincipal> worker = Auth.current(request).orElseThrow().field();
```

`FieldPrincipal` holds `workerId`, `workerCode`, `name`, `clientId`, `clientIdentifier`, `appCode`,
`deviceId`, `deviceMode` (`PERSONAL` or `SHARED`), `amr`, `authTime`, `sessionId` and `expiresAt`. A
field worker has no roles, applications, clients or tier: no platform authority.

**Permissions are yours to map**, separately from sessions and bearer tokens
(`FieldPermissions`). The default grants **none**, so a route that requires a permission refuses field
workers until you map them. `FieldPermissions.all(...)` gives a fixed set; `byDeviceMode(personal,
shared)` gives shared kiosks less.

`Auth.requireSource(...)` makes a route field-only (`Source.FIELD`) or never-field (`SESSION` and
`BEARER`).

`FieldTokens` (provided when field workers are on) verifies a token on its own, without a proof, and
answers `FieldTokenResult.Valid`, `Invalid` or `Unavailable`; use it for a check outside the request
path.

## Before a high-value action: `RequireLiveField`

Access tokens live ten minutes, so a revoked device or a suspended worker keeps a valid token until it
expires. For an action that cannot wait, add `RequireLiveField` (in `framework-flowcatalyst`):

```java
routes.post("/api/v1/gates/open", gates::open,
    RouteOptions.requiresContext()
        .with(Auth.requireSource(Authenticated.Source.FIELD))
        .with(RequireLiveField.of(app, Duration.ofSeconds(30))));
```

It calls the platform's `POST /api/field-tokens/introspect` with your service account, which needs the
permission `platform:iam:field-token:introspect` (`PlatformClient.introspectFieldToken`; the pinned SDK
has no method for it yet, so it is one plain HTTP call).

- `active: false`: `401 UNAUTHENTICATED` with `WWW-Authenticate: DPoP error="invalid_token"`.
- The platform cannot be asked (unreachable, refuses the service account, or answers nonsense): `503
  UNAVAILABLE`. It **fails closed**.
- An active answer is remembered per login family (`sid`) for the given time (zero: never) and never
  past the token's own expiry. An inactive answer is not remembered.
- A request that is not a field worker's is answered `403 FORBIDDEN`; a present `sub` that differs from the request's worker counts as inactive.

## Revocation events

The platform emits `platform:iam:field-worker:suspended`, `platform:iam:field-device:revoked` and
`platform:iam:field-session:revoked`. They are for **application state** (cached work assignments, open
gate sessions), not for token validity: tokens expire on their own within ten minutes.

```java
Receivers receivers = app.get(Receivers.class);
receivers.onFieldRevocation((ctx, event) -> {
  switch (event) {
    case FieldRevocation.WorkerSuspended w -> assignments.dropFor(w.workerId());
    case FieldRevocation.DeviceRevoked d -> gates.closeFor(d.deviceId());
    case FieldRevocation.SessionRevoked s -> gates.closeSession(s.sessionId());
  }
});
```

Declare the subscription so the definition sync registers it: `FieldRevocation.subscription(code,
"/fc/dispatch")`. The handler may run more than once for one event; make it idempotent. Data that lacks
what its type needs is rejected, not retried.

`SessionRevoked` names what the platform names: the login family (`sessionId`, the platform's `familyId`) only when one family was revoked (a rotated refresh token presented again); otherwise the worker or the device whose sessions ended. It also carries `clientId` and `reason`. An event that lacks the identifier it is about is rejected, not retried.

## Testing

```java
TestOidcProvider provider = TestOidcProvider.start(clock);
TestFieldDevice device = TestFieldDevice.create(clock);
String token = provider.fieldToken("orders", device.jkt(), Map.of());          // any claim can be overridden
String proof = device.proof("GET", "https://app.example/api/me", nonce, token);
client.get("/api/me").header("Authorization", "DPoP " + token).header("DPoP", proof).send();
```

- `TestFieldDevice.proof(...)` is valid unless you pass a `Flaw` (`OTHER_DEVICE_KEY`, `BAD_SIGNATURE`,
  `WRONG_METHOD`, `WRONG_URL`, `WRONG_ATH`, `STALE`, `NO_JTI`, `NO_NONCE`, `WRONG_TYP`, `WRONG_ALG`,
  `PRIVATE_MEMBER`). To get a nonce as a device does, send without one and read `DPoP-Nonce` from the
  `401` (`TestFieldDevice.nonceFrom`).
- `AuthTesting.of(app).fieldRequest(worker, device, provider::signField, "POST", "/api/things")` returns
  the `Authorization` and `DPoP` headers (a valid proof with a valid nonce). Like all of `AuthTesting`
  it is refused unless `APP_ENV` is `test`.
- The platform's own vectors are in `conformance/field/` and run in `FieldConformanceTest`; nonces
  have their own tests (`FieldWorkersTest`).

## Limits

- No field login, PIN, enrolment or refresh here: those are the platform and `field-js`.
- Server-rendered field pages are not a thing: a field page is a page `field-js` calls with `fetch`, and
  no field cookie exists.
- The OpenAPI document does not yet list a `DPoP` security scheme.
