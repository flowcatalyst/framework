# Contract: rate limiting for HTTP routes (MA items 9 and 10)

Status: built 2026-10-10. Amended 2026-10-13 (MA item 10): limiters are built in code, not
declared in configuration (owner ruling; section 1), and a middleware may decide each request with
its own policy (section 3a). Security-sensitive: a limiter that can be bypassed or turned against
legitimate callers is worse than none. Read section 5 before changing anything here.

## 1. Model

A **limiter** is a value with a name, a policy and a store, **built in code** by the application
and held in its own wiring (owner ruling 2026-10-13: behaviour is code, not configuration):

```java
RateLimiter api = MemoryRateLimiter.create("api", new RateLimitPolicy(100, Duration.ofMinutes(1), 20));
RateLimiter login =
    RedisRateLimiter.create("login", app.get(RedisConnections.class).defaultInstance(), RateLimitPolicy.of(5, Duration.ofMinutes(1)));
```

There is no `rate-limits` configuration section, no registry and no rate-limit module. Only the
Redis **connection** is configuration (`redis.instances`, database contract section 3). An
application that wants its numbers tunable declares its own settings and passes the values in
(`RateLimitPolicy.of(config.get(LIMIT), config.get(PERIOD))`).

- **Name:** `[a-z][a-z0-9_-]*`, at most 63 characters, checked when the limiter is built. It
  namespaces the keys (section 2) and labels the metrics; give each limiter its own.

- **Policy** (`RateLimitPolicy`): `limit` requests per `period`, with up to `burst` at once (default
  `burst` = `limit`). Ranges: `limit` and `burst` 1 to 1 000 000, `period` 1 ms to 1 day,
  `period / limit` at least 1 µs, and the window `burst * period / limit` at most `Long.MAX_VALUE / 4`
  nanoseconds (about 73 years).
- **Algorithm:** GCRA (the generic cell rate algorithm), which is a token bucket of size `burst`
  refilled at `limit / period`, stored as one number per key (the theoretical arrival time, TAT).
  For a request of cost `c` at time `now`: `tat = max(stored, now)`, `next = tat + c * T` with
  `T = period / limit`; allowed when `now >= next - burst * T`, then `next` is stored; otherwise
  limited, nothing stored, retry after `next - burst * T - now`. Before that, `tat` is capped at
  `now + burst * T` (an empty bucket), and a limited request whose stored TAT was beyond the cap
  stores the capped value; with one fixed policy the cap never applies (section 3a).
- The policy given when the limiter is built is its **default**. A middleware may decide each request with a
  policy of its own (section 3a); the stored state is the same either way.
- `RateLimiter.acquire(key, cost)` returns `Decision`: `Allowed(remaining, resetAfter)` or
  `Limited(retryAfter, resetAfter)` (`resetAfter`: time until the bucket is full again). `cost` is
  1 to `burst`. A store that cannot answer throws `RateLimitStoreException` (unchecked).
  `acquire(key, cost, policy)` decides with the given policy instead of the default (`cost` 1 to
  that policy's `burst`); `supportsPerRequestPolicy()` says whether a limiter implements it. Both are
  default methods, so a limiter written against the original interface still compiles: it reports
  false, and its three-argument `acquire` accepts only its own policy
  (`UnsupportedOperationException` otherwise).
- `MemoryRateLimiter` is in `framework-http`; `RedisRateLimiter` in `framework-ratelimit-redis`
  (which needs `framework-redis` and its `RedisModule` for the connection).

## 2. Drivers

### `memory`

`MemoryRateLimiter.create(name, policy)`, `create(name, policy, maxKeys)`,
`create(name, policy, maxKeys, clock)`. One process only: with several replicas each has its own
budget, so the effective limit is multiplied by the replica count. Time from a `MonotonicClock`
(default `MonotonicClock.system()`; tests pass their own). Keys in a `ConcurrentHashMap`, each
decision atomic per key. **Bounded:** at most `maxKeys` keys (default 100 000, 1 to 10 000 000). When full, keys whose bucket is full again (`stored <= now`) are purged
(at most once a second); if it is still full, new keys share **one overflow bucket** (logged at WARN
once a minute), so a flood of distinct keys can neither grow memory nor reset the limits of existing
keys, and new callers are still limited together rather than refused outright.

### `redis`

`RedisRateLimiter.create(name, connection, policy)`, `create(name, connection, policy, prefix)`, with
a `RedisConnection` from `RedisConnections`. Building it loads the script, so it needs the server
(`RateLimitStoreException` otherwise); build it in `boot` or a provider, after start-up has
connected. All replicas share the budget: build the limiter with the same name and prefix in every
replica. Keys `<prefix><name>:<key>` (default prefix `fw:rl:`), each
holding the TAT in microseconds of the **Redis server's clock** (`TIME`), so replicas' clocks are
never compared. One Lua script (loaded at start, reloaded on `NOSCRIPT`) does the whole decision
atomically; an allowed request sets the key with `PX` = time until the bucket is full again, so idle
keys expire on their own and Redis memory stays bounded by active callers. Standalone Redis or
Valkey (database contract section 3); if the server loses data the affected buckets start full again.

## 3. The middleware

```java
Middleware<Request, Response> limit = RateLimit.by(limiters.get("api"), KeyResolvers.clientAddress()).build();
routes.post("/login", handler, RouteOptions.none().with(limit));
```

`RateLimit.by(limiter, keyResolver)` with `cost(n)` (default 1), `headers(boolean)` (default true),
`onStoreFailure(ALLOW | DENY)` (default `ALLOW`), `policy(PolicyResolver)` (default none: every
request uses the limiter's default policy; section 3a), then `build()`.

- **Limited:** `429` with the standard envelope `{"error":"RATE_LIMITED","message":"too many
  requests"}`, `Retry-After` in whole seconds (rounded up, at least 1), and the handler is not
  called.
- **Headers** (when on, on allowed and limited answers): `RateLimit-Limit` (the burst of the
  policy the request was decided with), `RateLimit-Remaining`, `RateLimit-Reset` (seconds until
  full, rounded up), the IETF draft names.
- **Store failure:** `ALLOW` lets the request through, `DENY` answers `503 UNAVAILABLE`; either way
  it is logged at WARN (at most once a minute per limiter) and counted.
- **Keys** are namespaced by the limiter's name. A resolver that returns null puts the
  request in one shared bucket `-` (never unlimited: a caller must not escape the limit by omitting
  what the key is made of). Keys longer than 200 characters are replaced by their SHA-256.
- **Where it runs:** as global middleware (`Routes.use`) it runs before routing and before context
  resolution, so it sees no route and no `Context`; as route middleware (`RouteOptions.none().with(...)`) it runs
  after context resolution, so `KeyResolvers.client()` and `principal()` work there.
- **Metrics:** `fw_http_rate_limited_total{limiter}` (limited answers),
  `fw_http_rate_limit_store_failures_total{limiter}` and
  `fw_http_rate_limit_policy_failures_total{limiter}` (section 3a).

## 3a. Per-request policies (MA item 10)

For a limit that differs per caller and changes while the application runs (for example each
ingestion's limit, set in a control plane), the middleware takes a resolver:

```java
RateLimit.by(ingest, ingestionKey).policy(request -> plane.policyFor(request)).build();
```

`PolicyResolver` is `@Nullable RateLimitPolicy policy(Request)`. It runs on every request, after the
key resolver, and the request is decided with the policy it returns (`acquire(key, cost, policy)`).
Without a resolver every request uses the limiter's default policy, exactly as before.

**No limit (2026-10-16, additive).** A resolver returns the constant `RateLimitPolicy.UNLIMITED` to
let a request through unlimited: the middleware calls the handler without computing a decision, reads
and writes no state, and sends no `RateLimit-*` headers. It is recognised by identity only
(`isUnlimited()`), so a policy with equal numbers, a null, or a throwing resolver can never mean "no
limit" (section 5). Not for `RateLimiter.acquire` called directly. Go and Rust have no equivalent
yet; the shared vectors do not cover per-request policies.

**State and the key namespace.** GCRA stores one TAT per key, an absolute time; the policy is an
input to each decision and is never stored, and it is not part of the key. So a change to a key's
policy takes effect from that key's next request, on every replica and on both drivers, with no
migration and no reset. All requests with one key share one TAT whatever policy each brings.

**The decision** with policy `(T, B)` (`T = period / limit`, `B = burst`) is section 1's, with the
cap: `tat = min(max(stored, now), now + B * T)`. Call `debt = stored - now` (time, not a count).

- **Raised** (more requests per period, or a larger burst): the debt is kept and measured against
  the new policy, so the new rate applies from the next request. Example: 10 per 10 s, burst 3
  (`T` = 1 s, window `B*T` = 3 s), all three used, debt 3 s. Raised to 10 per 1 s, burst 30
  (`T` = 100 ms, window 3 s): the next request is allowed 100 ms later instead of 1 s later.
- **Lowered** (fewer per period, or a smaller burst): when the debt is within the new window
  (`debt <= B*T`) it is kept, so what was used carries over in proportion to time (half the old
  window used is half the new window used, whatever the counts). When the debt is beyond the new
  window, the bucket is over-empty: it is **capped to empty**, and the next request of cost `c` is
  allowed after `c * T` under the new policy. That bounds the wait after any change to what an
  empty bucket under the new policy would impose; without the cap a caller that had used a large old
  burst would wait out the old window (up to a day) under the new, smaller one. The cap forgives only
  debt the new policy could never have accrued; from the next allowed request on, the new rate and
  burst hold. Example: 100 per 10 s, burst 100 (window 10 s), 50 used, debt 5 s. Lowered to 1 per
  1 s, burst 2 (window 2 s): retry after 1 s, not 4 s. Lowered instead to 1 per 1 s, burst 10
  (window 10 s): allowed at once with 4 remaining (5 of 10 used, as 50 of 100 were).
- The cap is **written back**: an allowed request stores `next` as always, and a limited request
  whose stored TAT was beyond the cap stores the capped value (otherwise a limited request stores
  nothing, as in section 1). Without the write-back the debt would stay beyond the window and the
  `retryAfter` a limited request reports would not hold; with it, a request of cost `c` at
  `now + retryAfter` is allowed. With one fixed policy the cap never applies: an allowed request stores at most
  `now + B*T`, and a stored value only ages. The `redis` key's TTL is the time until the stored TAT
  passes, which does not depend on the policy. The `memory` purge (`stored <= now`) does not either.
- The `memory` overflow bucket (section 2) is decided with each request's own policy.

**Headers** report the policy the request was decided with: `RateLimit-Limit` is its burst.

**Null and failures.**

- The resolver returns **null**: the request uses the limiter's default policy. Null means "no policy of
  its own for this request", an answer and not a failure, so it is not counted. As with a null key,
  it never means unlimited.
- The resolver **throws** (a `RuntimeException`, including a `RateLimitPolicy` constructor refusing
  out-of-range values), or returns a policy whose **burst is below the middleware's cost** (such a
  request could never be allowed): a **policy failure**. It is counted in
  `fw_http_rate_limit_policy_failures_total{limiter}`, logged at WARN (at most once a minute per
  middleware, with the exception's message, never the request), and then follows the middleware's
  `onStoreFailure`, the one setting for "the limiter cannot decide as configured": `ALLOW` decides
  the request with the limiter's default policy; `DENY` answers `503 UNAVAILABLE` and the handler is not
  called. Errors (`Error`) propagate.
- Ruling: a policy failure is never a bypass. Under `ALLOW` the request is still limited, by the
  limiter's default policy, which is therefore the limit while the lookup fails and should be set to a value
  that is acceptable for every caller (normally the most restrictive acceptable one). Denying always
  would turn a control-plane outage into an outage of every route that uses the resolver, which
  `ALLOW` (the default) exists to prevent; an application that prefers protection sets `DENY`, as for
  a store outage. The failure is logged and counted either way, so nothing is skipped silently.

**Build time.** `cost(n)` is checked against the limiter's default policy, as before. `policy(resolver)` on
a limiter whose `supportsPerRequestPolicy()` is false is refused with `IllegalArgumentException`
when the middleware is built, not at the first request. Both shipped drivers support it.

**Lookup cost.** The resolver runs on the request thread for every request, before the store call.
It must be an in-memory lookup: a map refreshed by a `Background` loop, or a cache with its own
expiry. Never a network call per request. The middleware does not cache resolved policies: how
stale a policy may be is the application's decision, and a change should reach the next request.

**Many distinct policies** cost nothing: neither driver keeps state per policy, the `redis` driver
runs one script that takes the interval and burst as arguments, and there is no cache to fill. A
`RateLimitPolicy` is a small record, range-checked when it is constructed.

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
- `memory`: the overflow bucket is one extra key, so the map holds at most `maxKeys + 1` keys,
  plus at most one key per request racing to insert a first key at the moment the map fills.
- The `redis` driver works in microseconds: `period / limit` is truncated to whole microseconds.

## 4b. As built (2026-10-13, MA item 10)

- Removed, with the configuration: `RateLimitModule` (module id `rate-limit`), `RateLimiters`,
  `RateLimitRedisModule` (`rate-limit-redis`), `RateLimitModule.policy(InstanceConfig)` and the
  `rate-limits.instances` section. A `rate-limits` key left in a file is no longer read. The
  constructors of both limiters are private; the `create` methods are the only way to build one.
- `RateLimitPolicy` refuses a window `burst * period / limit` beyond `Long.MAX_VALUE / 4`
  nanoseconds (about 73 years). Before, a policy such as 1 per day with a burst of 1 000 000 was
  accepted and the `memory` driver's `burst * T` overflowed `long`, refusing every request (the
  `redis` script works in doubles and was not affected). The bound leaves room for `now + window`
  plus a cost without overflow.

## 5. Abuse cases and rulings

| Threat | Handling |
|---|---|
| Spoofed `X-Forwarded-For` to dodge or frame | Only `forwardedFor(n)` reads it, and only the entry the trusted proxies wrote; `clientAddress()` never reads headers |
| Distinct keys to exhaust memory | `memory`: bounded map, purge, overflow bucket; `redis`: keys expire when their bucket is full |
| Omitting the key material | null key → one shared bucket, not unlimited |
| IPv6 address rotation | /64 grouping |
| Secrets in keys | header values hashed; long keys hashed |
| Huge `cost` or bad policy | refused when the policy, the limiter or the middleware is built (`IllegalArgumentException`) |
| Clock skew between replicas | `redis` uses the server's clock only |
| Store outage | `ALLOW` by default, logged and counted; `DENY` available |
| Caller picks its own limit | the policy must come from data the caller cannot choose (the `Context`, a server-side lookup keyed by something the server checked); a resolver that reads a limit from a header or body hands callers the limit |
| One key under several policies | they share one TAT; under the cap, a request decided with a smaller window forgives debt beyond it. Make the policy a function of what the key is made of, or give each policy its own limiter (its own name, so its own key namespace) or key |
| Resolver throws or is slow | a throw is a policy failure (section 3a: counted, logged, then `ALLOW` = the limiter's default policy or `DENY` = 503); never unlimited. Slowness is the application's: the resolver must be an in-memory lookup |
| Unbounded distinct policies | no per-policy state anywhere; ranges checked by `RateLimitPolicy` |

### Rulings pending owner

1. Fail open (`ALLOW`) on a store outage by default: availability over protection. A login or
   password-reset limiter should set `DENY`.
2. IPv6 grouping by /64 by default (fixed, not configurable).
3. The overflow bucket when the `memory` map is full, instead of refusing new keys or evicting
   existing ones.
4. Per-request policies (section 3a): the cap at an empty bucket after a policy change (instead of
   keeping the old debt); null from the resolver means the limiter's default policy, uncounted; a resolver
   failure follows `onStoreFailure` (`ALLOW` = the limiter's default policy, not unlimited).
