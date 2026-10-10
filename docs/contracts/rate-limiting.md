# Contract: rate limiting for HTTP routes (MA item 9)

Status: built 2026-10-10. Security-sensitive: a limiter that can be bypassed or turned against
legitimate callers is worse than none. Read section 5 before changing anything here.

## 1. Model

A **limiter** is a named instance (core contract section 2) with a policy and a store:

```yaml
rate-limits:
  instances:
    api:   { driver: memory, limit: 100, period: 1m, burst: 20 }
    login: { driver: redis, connection: default, limit: 5, period: 1m }
```

- **Policy** (`RateLimitPolicy`): `limit` requests per `period`, with up to `burst` at once (default
  `burst` = `limit`). Ranges: `limit` and `burst` 1 to 1 000 000, `period` 1 ms to 1 day, and
  `period / limit` at least 1 µs.
- **Algorithm:** GCRA (the generic cell rate algorithm), which is a token bucket of size `burst`
  refilled at `limit / period`, stored as one number per key (the theoretical arrival time, TAT).
  For a request of cost `c` at time `now`: `tat = max(stored, now)`, `next = tat + c * T` with
  `T = period / limit`; allowed when `now >= next - burst * T`, then `next` is stored; otherwise
  limited, nothing stored, retry after `next - burst * T - now`.
- `RateLimiter.acquire(key, cost)` returns `Decision`: `Allowed(remaining, resetAfter)` or
  `Limited(retryAfter, resetAfter)` (`resetAfter`: time until the bucket is full again). `cost` is
  1 to `burst`. A store that cannot answer throws `RateLimitStoreException` (unchecked).
- Registry `RateLimiters` (`get(name)`), provided by `RateLimitModule` (module id `rate-limit`, in
  `framework-http`), which registers the `memory` driver; `RateLimitRedisModule`
  (`framework-ratelimit-redis`) registers `redis`.

## 2. Drivers

### `memory`

One process only: with several replicas each has its own budget, so the effective limit is
multiplied by the replica count. Time from the application's `MonotonicClock`. Keys in a
`ConcurrentHashMap`, each decision atomic per key. **Bounded:** at most `max-keys` keys (default
100 000, 1 to 10 000 000). When full, keys whose bucket is full again (`stored <= now`) are purged
(at most once a second); if it is still full, new keys share **one overflow bucket** (logged at WARN
once a minute), so a flood of distinct keys can neither grow memory nor reset the limits of existing
keys, and new callers are still limited together rather than refused outright.

### `redis`

All replicas share the budget. Keys `<prefix><instance>:<key>` (default prefix `fw:rl:`), each
holding the TAT in microseconds of the **Redis server's clock** (`TIME`), so replicas' clocks are
never compared. One Lua script (loaded at start, reloaded on `NOSCRIPT`) does the whole decision
atomically; an allowed request sets the key with `PX` = time until the bucket is full again, so idle
keys expire on their own and Redis memory stays bounded by active callers. Config: `connection`
(Redis instance name, default the default instance), `prefix`. Standalone Redis or Valkey (database
contract section 3); if the server loses data the affected buckets start full again.

## 3. The middleware

```java
Middleware<Request, Response> limit = RateLimit.by(limiters.get("api"), KeyResolvers.clientAddress()).build();
routes.post("/login", handler, RouteOptions.none().with(limit));
```

`RateLimit.by(limiter, keyResolver)` with `cost(n)` (default 1), `headers(boolean)` (default true),
`onStoreFailure(ALLOW | DENY)` (default `ALLOW`), then `build()`.

- **Limited:** `429` with the standard envelope `{"error":"RATE_LIMITED","message":"too many
  requests"}`, `Retry-After` in whole seconds (rounded up, at least 1), and the handler is not
  called.
- **Headers** (when on, on allowed and limited answers): `RateLimit-Limit` (the burst),
  `RateLimit-Remaining`, `RateLimit-Reset` (seconds until full, rounded up), the IETF draft names.
- **Store failure:** `ALLOW` lets the request through, `DENY` answers `503 UNAVAILABLE`; either way
  it is logged at WARN (at most once a minute per limiter) and counted.
- **Keys** are namespaced by the limiter's instance name. A resolver that returns null puts the
  request in one shared bucket `-` (never unlimited: a caller must not escape the limit by omitting
  what the key is made of). Keys longer than 200 characters are replaced by their SHA-256.
- **Where it runs:** as global middleware (`Routes.use`) it runs before routing and before context
  resolution, so it sees no route and no `Context`; as route middleware (`RouteOptions.none().with(...)`) it runs
  after context resolution, so `KeyResolvers.client()` and `principal()` work there.
- **Metrics:** `fw_http_rate_limited_total{limiter}` (limited answers) and
  `fw_http_rate_limit_store_failures_total{limiter}`.

## 4. Key resolvers

`KeyResolver` is `@Nullable String key(Request)`. `KeyResolvers`:

- `clientAddress()`: the connection's remote address (`Request.remoteAddress()`, new: the server
  adapter fills it; the in-memory test client uses `127.0.0.1` unless a test sets another). IPv6
  addresses are grouped by their /64 prefix, since one host commonly holds a whole /64.
- `forwardedFor(trustedProxies)`: for an application behind `trustedProxies` (1 to 10) proxies that
  each **append** to `X-Forwarded-For`: the entry `trustedProxies` from the right, which is the
  address the outermost trusted proxy saw. Entries to the left of it are written by the caller and
  are ignored. When the header has fewer entries, or the chosen entry is not an IP literal, the
  connection's remote address is used. Parsing never resolves names (`InetAddress.ofLiteral`).
  Several header lines are read in order as one list.
- `header(name)`: a header's value, **hashed** (SHA-256, hex) so an API key never becomes a
  Redis key, a log line or a metric label. A missing header is null (the shared bucket).
- `client()`, `principal()`: from the route's `Context` (route middleware only); null without one.

## 4a. As built (2026-10-10)

- `Request.remoteAddress()` is new (http contract section 3): the peer's IP literal, filled by the
  Jetty adapter from the connection, never from a header; `RawRequest` gained the component (the old
  constructor remains, with no address); `TestClient` calls default to `127.0.0.1` and take
  `.remoteAddress(value)`.
- The middleware is built in code, so it records metrics only when given the registry:
  `RateLimit.by(...).metrics(app.get(Metrics.class))`; without it the metrics go to a no-op registry.
- `memory`: the overflow bucket is one extra key, so the map holds at most `max-keys + 1` keys,
  plus at most one key per request racing to insert a first key at the moment the map fills.
- `RateLimitModule.policy(InstanceConfig)` reads `limit`, `period` and `burst` for any driver's
  factory; `limit` and `period` are required.
- The `redis` driver works in microseconds: `period / limit` is truncated to whole microseconds.

## 5. Abuse cases and rulings

| Threat | Handling |
|---|---|
| Spoofed `X-Forwarded-For` to dodge or frame | Only `forwardedFor(n)` reads it, and only the entry the trusted proxies wrote; `clientAddress()` never reads headers |
| Distinct keys to exhaust memory | `memory`: bounded map, purge, overflow bucket; `redis`: keys expire when their bucket is full |
| Omitting the key material | null key → one shared bucket, not unlimited |
| IPv6 address rotation | /64 grouping |
| Secrets in keys | header values hashed; long keys hashed |
| Huge `cost` or bad policy | refused at build or configuration time (`IllegalArgumentException`, start-up problems) |
| Clock skew between replicas | `redis` uses the server's clock only |
| Store outage | `ALLOW` by default, logged and counted; `DENY` available |

### Rulings pending owner

1. Fail open (`ALLOW`) on a store outage by default: availability over protection. A login or
   password-reset limiter should set `DENY`.
2. IPv6 grouping by /64 by default (fixed, not configurable).
3. The overflow bucket when the `memory` map is full, instead of refusing new keys or evicting
   existing ones.
