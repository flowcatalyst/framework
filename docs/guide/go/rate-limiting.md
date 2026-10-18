<!-- types: Policy, Limiter, PolicyLimiter, Decision, Allowed, Limited, Memory, KeyFunc, PolicyFunc, OnStoreFailure, Option, MemoryOption -->
# Go: rate limiting

Packages `ratelimit` (the policy, the GCRA arithmetic, the memory limiter, the HTTP middleware and
the key functions) and `ratelimit/redisrl` (the Redis limiter) implement the
[rate-limiting contract](../../contracts/rate-limiting.md). Limiters are code: there is no
`rate-limits` configuration section, no registry and no module (a leftover section fails start-up
naming where it moved). The Java page is [Rate limiting](../rate-limiting.md); the algorithm, the
numbers, the headers and the Redis keys and script are the same, so a Java and a Go replica sharing a
Redis server share one budget.

## Building limiters

```go
api, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(100, time.Minute, 20))
login, err := redisrl.FromClient(ctx, "login", redis, ratelimit.MustPolicy(5, time.Minute, 5))
```

- A `Policy` is `limit` per `period` with up to `burst` at once. `NewPolicy` checks the ranges
  (limit and burst 1 to 1 000 000, period 1 ms to 1 day, period/limit at least 1 microsecond, window
  `burst * period / limit` at most about 73 years) and wraps `ratelimit.ErrInvalidPolicy`;
  `PolicyOf(limit, period)` sets the burst to the limit; `MustPolicy` is for literals. The zero
  `Policy` is invalid and refused everywhere.
- The name is `[a-z][a-z0-9_-]*`, at most 63 characters; it labels the metrics and namespaces the
  Redis keys.
- `NewMemory` counts per process (with three replicas a caller gets three budgets) and is bounded:
  `MaxKeys` (default 100 000, 1 to 10 000 000); when full, keys whose bucket is full again are purged
  (at most once a second) and new keys share one overflow bucket, so a flood of keys can neither
  grow memory nor reset another caller's limit. `MemoryClock` and `MemoryLogger` are for tests and
  the overflow warning.
- `redisrl.New` and `redisrl.FromClient` load the Lua script, so the server must be reachable (an
  error wrapping `ratelimit.ErrStore` otherwise); build the limiter in the build function after the
  Redis connections. `redisrl.Prefix` changes the key prefix (default `fw:rl:`); use the same name and
  prefix in every replica. The script is a byte-for-byte copy of the Java one: a test reads it out
  of `RedisRateLimiter.java` and compares.

`Limiter.Acquire(ctx, key, cost)` returns an `Allowed` (remaining, time to full) or `Limited` (retry
after, time to full); a store that cannot answer is an error wrapping `ErrStore`. `PolicyLimiter`
adds `AcquireWith(ctx, key, cost, policy)`, which decides under another policy against the same
stored state; both limiters implement it.

## The middleware

```go
limit, err := ratelimit.Middleware(api, ratelimit.ClientAddress(),
	ratelimit.WithMetrics(b.Metrics()), ratelimit.WithLogger(b.Logger("api")))
routes.Handle("POST /login", handler, httpfw.With(limit))
```

`Middleware` returns an `httpfw.Middleware`: global (`Routes.Use`) it runs before routing and
before context resolution; on a route (`httpfw.With`) it runs after context resolution, so
`Client()` and `Principal()` work there. A limited request is `429 RATE_LIMITED` with `Retry-After`
(whole seconds, rounded up, at least 1) and the handler is not called; allowed and limited answers
carry `RateLimit-Limit` (the burst of the policy used), `RateLimit-Remaining` and `RateLimit-Reset`
unless `WithHeaders(false)`. `WithCost(n)` (1 to the burst), `WithOnStoreFailure(ratelimit.Deny)` (the default `Allow` lets the request through;
`Deny` answers `503 UNAVAILABLE`; either way it is logged at most once a minute and counted).
Metrics: `fw_http_rate_limited_total`, `fw_http_rate_limit_store_failures_total` and, with a policy
function, `fw_http_rate_limit_policy_failures_total`, all labelled `limiter`.

Keys: `ClientAddress()` is the peer address (IPv6 grouped by its /64, never a header);
`ForwardedFor(n)` is the entry `n` from the right of `X-Forwarded-For` for an application behind `n`
trusted proxies, falling back to the peer; `Header(name)` is the value hashed with SHA-256; `Client()`
and `Principal()` come from the route's context. A key function that returns `""` puts the request in
one shared bucket (never unlimited); keys over 200 characters are replaced by their SHA-256.

## Per-request policies

`WithPolicy(func(r) (Policy, ok, error))` decides each request with a policy of its own (for a limit
that differs per caller and changes while the application runs). `ok=false` means the limiter's
default policy; an error, the zero policy or a burst below the cost is a policy failure: counted,
logged, and then `WithOnStoreFailure` decides (`Allow` decides the request with the default policy,
never unlimited; `Deny` is `503`). The function must be an in-memory lookup. A change takes effect
from the key's next request, on every replica, with a cap at an empty bucket when a smaller window
makes the old debt impossible (contract section 3a).

`ratelimit.Unlimited` returned by the policy function lets the request through with no decision, no state and no `RateLimit-*` headers; only that value means it (`Policy.IsUnlimited`), never `ok=false`, an error or a policy with the same numbers.

## Differences from Java

- Conformance (`conformance/ratelimit/http.json`): the peer address is parsed from `RemoteAddr`, which always carries a port, so `203.0.113.5:80` is the address `203.0.113.5` (Java is given the bare host and answers the shared bucket). `netip` refuses what `InetAddress.ofLiteral` reads: leading zeros (`010.001.001.001`), short forms (`1.2.3`) and a bare integer (`2130706433`); Go answers the shared bucket, the stricter result. Any IPv6 zone is dropped (Java refuses a named one such as `%eth0`). `ForwardedFor` splits each header line as Java does (trailing empty entries dropped).

- Errors are values: `Middleware` returns an error where Java throws `IllegalArgumentException`
  (a cost above the burst, a policy function on a limiter that cannot take one).
- The middleware is an `httpfw.Middleware`; the key function takes an `*http.Request` and the
  peer address is parsed from `RemoteAddr`.
- IP literals are parsed with `net/netip` (dotted-quad IPv4, IPv6 with an optional zone, which is
  dropped; an IPv4-mapped IPv6 address is the IPv4 key). Java's `InetAddress.ofLiteral` was not
  compared on exotic spellings, which only matters for a hostile `X-Forwarded-For`.
