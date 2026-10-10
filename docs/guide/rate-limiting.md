<!-- types: RateLimit, RateLimiter, RateLimiters, RateLimitPolicy, Decision, KeyResolver, KeyResolvers, RateLimitModule, MemoryRateLimiter, RateLimitStoreException, RedisRateLimiter, RateLimitRedisModule, Request -->
# Rate limiting

A middleware that answers `429 Too Many Requests` with `Retry-After` when a caller goes over its
budget. Limiters are configured instances: `memory` (one process) or `redis` (one budget shared by
every replica). The specification, with its abuse cases, is
[contracts/rate-limiting.md](../contracts/rate-limiting.md).

## Configuring limiters

List `RateLimitModule` (in `framework-http`) and, for Redis, `RedisModule` and
`RateLimitRedisModule` (artifact `framework-ratelimit-redis`):

```yaml
rate-limits:
  instances:
    api:   { driver: memory, limit: 100, period: 1m, burst: 20 }
    login: { driver: redis, connection: default, limit: 5, period: 1m }
```

| Key | Default | Meaning |
|---|---|---|
| `limit`, `period` | required | `limit` requests per `period` (1 to 1 000 000; 1 ms to 1 day) |
| `burst` | `limit` | how many may come at once; the bucket's size |
| `max-keys` (`memory`) | `100000` | most callers tracked; see below |
| `connection`, `prefix` (`redis`) | default instance, `fw:rl:` | the Redis instance and key prefix |

The algorithm is GCRA, a token bucket of size `burst` refilled at `limit / period`, stored as one
number per caller.

- **`memory`** counts per process: with three replicas a caller gets three budgets. It is bounded:
  when `max-keys` callers are tracked, those whose bucket is full again are dropped, and if it is
  still full, new callers share one overflow bucket (a WARN says so). A flood of distinct keys can
  neither use up memory nor reset other callers' limits.
- **`redis`** keeps one budget for all replicas, decides atomically in a Lua script on the Redis
  server's clock, and lets idle keys expire by themselves.

## Using the middleware

```java
MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 2);
Routes routes = new Routes();
routes.get("/x", handler, RouteOptions.none().with(RateLimit.by(l, KeyResolvers.clientAddress()).metrics(metrics).build()));
```

(the shape of `RateLimitTest`; in an application the limiter comes from
`app.get(RateLimiters.class).get("api")` and the metrics from `app.get(Metrics.class)`.)

- **Over the limit:** `429` with `{"error":"RATE_LIMITED","message":"too many requests"}` and
  `Retry-After` in whole seconds (rounded up). The handler is not called.
- **Headers** on every answer (turn off with `.headers(false)`): `RateLimit-Limit` (the burst),
  `RateLimit-Remaining`, `RateLimit-Reset` (seconds until the bucket is full).
- `.cost(n)` makes one request count as `n` (at most the burst).
- **When the store fails** (Redis down): by default the request goes through
  (`OnStoreFailure.ALLOW`), logged and counted in `fw_http_rate_limit_store_failures_total`.
  `.onStoreFailure(OnStoreFailure.DENY)` answers `503` instead; use it for login and password
  reset.
- With `.metrics(...)`, limited answers are counted in `fw_http_rate_limited_total{limiter}`.

**Where to put it:** as route middleware (`RouteOptions.none().with(...)`, or `requiresContext().with(...)`)
it runs after the `Context` is resolved, so `client()` and `principal()` keys work. As global
middleware (`routes.use(...)`) it runs before routing and context resolution, so only address and
header keys make sense there.

## Choosing the key

| Resolver | Key |
|---|---|
| `KeyResolvers.clientAddress()` | the connection's peer address (`request.remoteAddress()`); **never** a header. IPv6 addresses are grouped by /64 |
| `KeyResolvers.forwardedFor(n)` | behind `n` proxies that each append to `X-Forwarded-For`: the entry `n` from the right, the address your outermost proxy saw. Entries further left are written by the caller and ignored; with fewer entries, or an entry that is not an IP literal, the peer address |
| `KeyResolvers.header(name)` | the header's value, hashed (SHA-256), so an API key is never stored, logged or used as a label |
| `KeyResolvers.client()`, `principal()` | from the route's `Context` |

A request for which the resolver has no key (a missing header, no context) uses **one shared
bucket**, never no limit: a caller cannot escape the limit by leaving the key out. Keys longer than
200 characters are hashed.

Use `forwardedFor(n)` only when you know `n`: trusting one proxy too many lets a caller choose its
own key. Behind no proxy, use `clientAddress()`.

## Testing

`TestClient` calls come from `127.0.0.1`; `.remoteAddress("10.0.0.2")` sets another. A
`MemoryRateLimiter` takes the monotonic clock, so a test can move time instead of sleeping
(`RateLimitTest`). The `redis` driver is tested against a real Redis (`RedisRateLimiterTest`).
