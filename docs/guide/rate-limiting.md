<!-- types: RateLimit, RateLimiter, RateLimitPolicy, PolicyResolver, Decision, KeyResolver, KeyResolvers, MemoryRateLimiter, RateLimitStoreException, RedisRateLimiter, RedisConnections, Setting, Request -->
# Rate limiting

A middleware that answers `429 Too Many Requests` with `Retry-After` when a caller goes over its
budget. Limiters are built in code: `MemoryRateLimiter` (one process) or `RedisRateLimiter` (one
budget shared by every replica). A limiter has a default policy, and the middleware can decide each
request with a policy of its own, for limits that differ per caller and change while the
application runs. The specification, with its abuse cases, is
[contracts/rate-limiting.md](../contracts/rate-limiting.md).

## Building limiters

There is no configuration section and no module for rate limits: you build each limiter where you
build the rest of your wiring and pass it to the middleware.

```java
RateLimiter api = MemoryRateLimiter.create("api", new RateLimitPolicy(100, Duration.ofMinutes(1), 20));
RateLimiter login = RedisRateLimiter.create("login", app.get(RedisConnections.class).defaultInstance(), RateLimitPolicy.of(5, Duration.ofMinutes(1)));
```

- **The policy** (`RateLimitPolicy`): `limit` requests per `period`, with up to `burst` at once
  (`RateLimitPolicy.of(limit, period)` sets the burst to the limit). Ranges: `limit` and `burst` 1 to
  1 000 000, `period` 1 ms to 1 day, `period / limit` at least 1 µs, `burst * period / limit` at most
  about 73 years. Out of range is `IllegalArgumentException`.
- **The name** (`[a-z][a-z0-9_-]*`, at most 63 characters) labels the metrics and, for Redis,
  namespaces the keys. Give each limiter its own.
- **`MemoryRateLimiter.create(name, policy)`**, or `create(name, policy, maxKeys)` (default 100 000),
  or `create(name, policy, maxKeys, clock)` for tests. It counts per process: with three replicas a
  caller gets three budgets. It is bounded: when `maxKeys` callers are tracked, those whose bucket
  is full again are dropped, and if it is still full, new callers share one overflow bucket (a WARN
  says so). A flood of distinct keys can neither use up memory nor reset other callers' limits.
- **`RedisRateLimiter.create(name, connection, policy)`**, or with a key prefix as a fourth argument
  (default `fw:rl:`), in artifact `framework-ratelimit-redis`. The connection comes from the Redis
  module (`RedisModule`, configured under `redis.instances` as before: connection settings stay
  configuration). It keeps one budget for all replicas, decides atomically in a Lua script on the
  Redis server's clock, and lets idle keys expire by themselves. Building it loads the script, so
  build it in `boot` or a provider, when Redis is connected. Use the same name and prefix in every
  replica.

The algorithm is GCRA, a token bucket of size `burst` refilled at `limit / period`, stored as one
number per caller.

### Numbers from your own settings

To tune the numbers per environment, declare your own settings and pass the values in. From
`RateLimitTest`:

```java
static final Setting<Integer> LIMIT = Setting.integer("ingest.rate-limit").withDefault(100);
static final Setting<Duration> PERIOD = Setting.duration("ingest.rate-period").withDefault(Duration.ofMinutes(1));

record Limiters(RateLimiter api) {}

@Override
public void register(AppBuilder app) {
  app.declare(LIMIT);
  app.declare(PERIOD);
  app.provide(
      Limiters.class,
      lookup -> {
        Config config = lookup.get(Config.class);
        return new Limiters(
            MemoryRateLimiter.create("api", RateLimitPolicy.of(config.get(LIMIT), config.get(PERIOD))));
      });
}
```

`ingest.rate-limit: 20` in `application.yaml` (or `INGEST_RATE_LIMIT=20`) then gives 20 per minute.
See [Config](config.md) for settings.

## Using the middleware

```java
MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 2);
Routes routes = new Routes();
routes.get("/x", handler, RouteOptions.none().with(RateLimit.by(l, KeyResolvers.clientAddress()).metrics(metrics).build()));
```

(the shape of `RateLimitTest`; in an application the limiter is the one you built, the metrics come
from `app.get(Metrics.class)`.)

- **Over the limit:** `429` with `{"error":"RATE_LIMITED","message":"too many requests"}` and
  `Retry-After` in whole seconds (rounded up). The handler is not called.
- **Headers** on every answer (turn off with `.headers(false)`): `RateLimit-Limit` (the burst of
  the policy the request was decided with), `RateLimit-Remaining`, `RateLimit-Reset` (seconds until
  the bucket is full).
- `.cost(n)` makes one request count as `n` (at most the default policy's burst).
- **When the store fails** (Redis down): by default the request goes through
  (`OnStoreFailure.ALLOW`), logged and counted in `fw_http_rate_limit_store_failures_total`.
  `.onStoreFailure(OnStoreFailure.DENY)` answers `503` instead; use it for login and password
  reset.
- With `.metrics(...)`, limited answers are counted in `fw_http_rate_limited_total{limiter}`.

**Where to put it:** as route middleware (`RouteOptions.none().with(...)`, or `requiresContext().with(...)`)
it runs after the `Context` is resolved, so `client()` and `principal()` keys work. As global
middleware (`routes.use(...)`) it runs before routing and context resolution, so only address and
header keys make sense there.

## A policy per request

When the limit depends on the caller and changes at run time (each ingestion has its own limit, set
in a control plane), give the middleware a `PolicyResolver`. From `RateLimitTest`, where `plane` is a
map the application keeps current:

```java
PolicyResolver fromPlane() {
  return r -> {
    String ingestion = r.header("X-Ingestion");
    return ingestion == null ? null : plane.get(ingestion);
  };
}

RateLimit.by(limiter(1, Duration.ofMinutes(1), 1), KeyResolvers.header("X-Ingestion"))
    .policy(fromPlane())
    .metrics(metrics)
    .build();
```

- **A change applies from the key's next request.** The limiter stores one time per key, not the
  policy, so there is nothing to migrate or reset, on either driver. Usage carries over in
  proportion to time: a caller that used half its bucket under the old policy has used half under
  the new one.
- **A lowered policy never makes a caller wait out the old one.** If the caller's usage is beyond
  what the new policy's bucket can hold, it counts as an empty bucket, and the next request is
  allowed after one interval (`period / limit`) of the new policy.
- **Null** from the resolver means the limiter's default policy. It is never "no limit".
- **No limit** is said out loud: return `RateLimitPolicy.UNLIMITED` for a caller that has none (an
  ingestion whose limit is "none" in the control plane). The request goes straight to the handler;
  the bucket is not read or written (so a later real policy for that key starts full) and the answer
  has no `RateLimit-*` headers, because there is no budget to report. Only that constant counts
  (`isUnlimited()` is an identity check): a policy with the same numbers is an ordinary policy, a
  resolver that throws is still a policy failure, and `acquire` called directly on a limiter does
  not know it. The same rule as above applies to where the value comes from: never from the caller.
- **A resolver that throws** (or returns a policy whose burst is below `.cost(n)`) is a policy
  failure: counted in `fw_http_rate_limit_policy_failures_total{limiter}`, logged at WARN at most
  once a minute, then handled like a store failure: with `ALLOW` (the default) the request is
  decided with the limiter's default policy, so it is still limited; with `DENY` it gets `503`.
  Choose the default policy with that in mind: it is the limit while your lookup fails.
- **Keep it cheap.** The resolver runs on every request. Read from memory (a map a `Background` loop
  refreshes, or a cache); never call the control plane per request. The middleware does not cache
  what it returns.
- **Never take the limit from the caller.** A policy read from a header or the body lets the caller
  choose its own limit. Look it up by something the caller cannot forge, or by the key.
- **One key, one policy.** Requests with the same key share one bucket whatever policy each brings,
  and a request with a smaller policy shrinks the shared usage to fit it. If two routes need
  different limits for the same caller, give them separate limiters.
- `.policy(...)` needs a limiter whose `supportsPerRequestPolicy()` is true (both shipped ones
  are); a hand-written `RateLimiter` that does not override it is refused when the middleware is
  built.

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

`TestClient` calls come from `127.0.0.1`; `.remoteAddress("10.0.0.2")` sets another.
`MemoryRateLimiter.create(name, policy, maxKeys, clock)` takes the monotonic clock, so a test can
move time instead of sleeping, including across a policy change (`RateLimitTest`). The Redis
limiter is tested against a real Redis (`RedisRateLimiterTest`).
