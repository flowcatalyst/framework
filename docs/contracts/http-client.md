# Contract: the HTTP client (MA item 6)

Status: built 2026-10-10. Owner-confirmed defaults (2026-10-09): a blocking API on virtual threads
over Vert.x; a standalone library plus a thin framework adapter; retries for idempotent requests
only by default; a client per consumed service; auth strategies with token caching and refresh;
JSON with the framework's Jackson 3; timeouts; `Context` propagation; convenience methods. Wanted
before M4c (login uses it).

## 1. Modules and the new dependency

| Module | Contents | Depends on |
|---|---|---|
| `framework-httpclient` | the library: `HttpClients`, `ServiceClient`, requests, responses, auth, retries | `io.vertx:vertx-core`, Jackson 3, SLF4J API, JSpecify. **Not** `framework-core`: usable without the framework |
| `framework-httpclient-module` | `HttpClientModule` (id `http-client`): configured clients as named instances, `Context` propagation, metrics, lifecycle | `framework-core`, `framework-httpclient` |

**New third-party dependency: Vert.x core (5.1.x)** and through it Netty. Why: the owner chose
Vert.x for the client (connection pooling, HTTP/1.1 and HTTP/2, a non-blocking core that many
virtual threads can share without a thread per connection); `java.net.http` was the alternative and
was not chosen. Vert.x appears in no public signature of the library: callers see only framework
types and JDK types, so the engine can be replaced. The version is managed in `framework-bom`.

## 2. The library

```java
try (HttpClients clients = HttpClients.create()) {
  ServiceClient orders =
      clients.service(ServiceOptions.builder("orders", URI.create("https://orders.example.com")).build());
  Order order = orders.get("/orders/{id}").pathParam("id", "42").send().json(Order.class);
}
```

- **`HttpClients`** owns one Vert.x instance and its pools; `AutoCloseable`; thread-safe; create one
  per application. `service(ServiceOptions)` returns a `ServiceClient` for one consumed service.
- **`ServiceOptions`** (record + builder): `name` (`[a-z][a-z0-9-]*`), `baseUrl` (absolute `http`
  or `https`, a host, no user info, no query or fragment; its path is a prefix), `connectTimeout`
  (default `5s`), `requestTimeout` (default `30s`, per attempt, the whole exchange including the
  body), `maxResponseBytes` (default 10 MiB, at most 1 GiB), `retry` (`RetryPolicy`), `auth`
  (`Auth`, default none), default headers.
- **Requests:** `client.get(path)`, `post`, `put`, `patch`, `delete`, `head`, and
  `request(method, path)` return a `RequestBuilder`: `pathParam(name, value)`, `query(name, value)`
  (repeatable), `header(name, value)`, `json(Object)`, `body(bytes, contentType)`,
  `form(Map)`, `idempotencyKey(key)`, `retryable(boolean)`, `timeout(Duration)`, then `send()`.
  - The path must start with `/` and is appended to the base URL's path. It may not contain `//`,
    `?`, `#`, `..` or `.` segments, or `\`; **a request can never leave its service's host**
    (the SSRF rule: a request URL is never taken from input).
  - `{name}` placeholders are filled by `pathParam`, percent-encoded as one segment (every
    character except unreserved ones, so `/`, `?`, `#` and `%` are encoded). A value that is empty,
    `.` or `..` is `IllegalArgumentException`. An unfilled placeholder, or a `pathParam` for a name
    the path does not have, is `IllegalArgumentException`.
  - Header names are tokens; header values may not contain CR, LF or NUL (`IllegalArgumentException`:
    no header injection). `Host`, `Content-Length`, `Transfer-Encoding` and `Connection` are set by
    the client and refused.
- **`ClientResponse`**: `status()`, `header(name)`, `headers(name)`, `bodyBytes()`, `bodyText()`
  (UTF-8), `json(Class<T>)` / `json(TypeReference)`, `isSuccess()` (2xx). `send()` never throws for
  an HTTP status; a non-2xx answer is a response.
- **Convenience:** `sendJson(Class<T>)` returns the decoded body of a 2xx response and throws
  `UnexpectedStatusException` (status, service, method, path template, and the `error` code of the
  platform's error envelope when the body has one) otherwise; the exception never carries the body.
  `ServiceClient.getJson(path, type, pathParams...)` and `postJson(path, body, type)` are shorthands.
- **JSON** is Jackson 3 configured like the framework's (ISO dates) but **tolerant on read**
  (unknown properties ignored: another service may add fields). `ServiceOptions.mapper` replaces it.
- **Failures** are `HttpClientException` (unchecked) with a `Kind`: `CONNECT` (could not connect),
  `TIMEOUT`, `RESPONSE_TOO_LARGE`, `IO` (the exchange broke), `DECODE` (the body is not the JSON asked
  for; the parser's message is not attached because it can quote the body), `AUTH` (an auth strategy
  could not get credentials; a supplier's own exception is reduced to its class name), `INTERRUPTED`
  (the calling thread was interrupted; the flag is restored). Messages
  name the service, method and path template, never header values, bodies, tokens or query strings.
- Using a client after its `HttpClients` was closed is `IllegalStateException` ("the HTTP client
  engine is closed"): a programming error, not a failure of the exchange.
- **Redirects are not followed** (a redirect is returned as a response): following one could send
  the `Authorization` header to another host.
- **Logging:** DEBUG per attempt: service, method, path template, status or failure kind, duration,
  attempt number. Never query strings, headers or bodies.
- **Observation:** `ServiceOptions.observer(ClientObserver)` is told each finished attempt (service,
  method, path template, status or `Kind`, nanoseconds, attempt); the adapter records metrics with it.

## 3. Retries

`RetryPolicy` (record): `maxAttempts` (default 3, 1 to 10; 1 = no retry), `initialBackoff`
(`200ms`), `maxBackoff` (`5s`), `maxRetryAfter` (`30s`), `retryOnStatuses` (`429, 502, 503, 504`).
`RetryPolicy.none()` turns retries off.

- **Which requests may be retried:** methods that are idempotent by definition (`GET`, `HEAD`,
  `OPTIONS`, `PUT`, `DELETE`); a request with an idempotency key (`idempotencyKey(key)` sets the
  `Idempotency-Key` header); a request marked `retryable(true)`. **`POST` and `PATCH` are never
  retried otherwise.** `retryable(false)` turns retries off for one request.
- **What is retried:** `CONNECT`, `TIMEOUT` and `IO` failures, and responses with a status in
  `retryOnStatuses`. When attempts run out, the last response is returned (or the last failure
  thrown).
- **Waits:** exponential (`initialBackoff * 2^(n-1)`, capped at `maxBackoff`) with full jitter. A
  `Retry-After` header (seconds or an HTTP date) on a retried status is honoured when it is at most
  `maxRetryAfter`; when it is longer the response is returned at once rather than waited for.
- Waiting is interruptible (`INTERRUPTED`).

## 4. Auth

`Auth` is applied to every attempt. Strategies:

| Strategy | Sends |
|---|---|
| `Auth.none()` | nothing |
| `Auth.bearer(Supplier<String> token)` | `Authorization: Bearer <token>`, the supplier asked per attempt (for example `platform::accessToken`) |
| `Auth.bearer(token, Runnable onUnauthorized)` | as above; on a `401` the runnable runs (clear a cached token) and the request is sent once more |
| `Auth.basic(user, Supplier<String> password)` | `Authorization: Basic ...` |
| `Auth.header(name, Supplier<String> value)` | an API-key header |
| `Auth.clientCredentials(ClientCredentials)` | OAuth 2 client credentials (below) |

**Client credentials:** `ClientCredentials.builder(tokenUrl, clientId, Supplier<String>
clientSecret)` with `scope`, `audience`, `method` (`POST`, the default: `client_id` and
`client_secret` in the form body, as the FlowCatalyst platform expects; or `BASIC`).

- The token is fetched with the same `HttpClients` (its own connect and request timeouts: the
  service's), `grant_type=client_credentials`, and cached until `expires_in` minus a skew of
  `min(30s, expires_in / 10)`; without `expires_in`, 5 minutes. `token_type`, when present, must be
  `Bearer` (case-insensitive).
- **Single flight:** concurrent callers needing a new token wait for one token request.
- A `401` from the service drops the cached token and sends the request **once more** with a new
  token, for every method (a `401` means the request was refused, not processed). A second `401` is
  returned.
- A failed token request (transport error, non-2xx, malformed answer) is `HttpClientException(AUTH)`;
  its message has the token URL's host and the status, never the secret or the answer body. The
  token URL must be `https`, or `http` to a loopback address (tests, local platforms).

## 5. The framework adapter

*Amended 2026-10-14 ([config and code](config-and-code.md), section 3.9): clients are named in code,
`HttpClientModule.builder().client(name, HttpClientOptions)`, with timeouts, size limit, retry
and the auth strategy (`ClientAuth`) as code. Configuration keeps, per client,
`http-clients.instances.<name>.base-url` and the credentials of its auth strategy; the engine
settings `http-client.*` are builder calls.*

`HttpClientModule` provides `HttpClients` (closed at shutdown) and `ServiceClients`, a registry
of configured clients (core contract section 2, default driver `http`):

```yaml
http-clients:
  instances:
    billing:
      base-url: https://billing.example.com
      request-timeout: 10s
      retry: { max-attempts: 3, initial-backoff: 200ms }
      auth: { type: client-credentials, token-url: https://login.example.com/oauth/token,
              client-id: orders-service, scope: billing }   # client-secret: a secret, from env
```

- Keys: `base-url` (required), `connect-timeout`, `request-timeout`, `max-response-bytes`,
  `retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff`, `retry.max-retry-after`;
  `auth.type` `none` (default), `bearer` (`auth.token`, secret), `basic` (`auth.username`,
  `auth.password` secret), `header` (`auth.name`, `auth.value` secret), `client-credentials`
  (`auth.token-url`, `auth.client-id`, `auth.client-secret` secret, `auth.scope`, `auth.audience`,
  `auth.method` `post`/`basic`). Unknown keys fail start-up, as for every capability.
- `ServiceClients.get(name)` returns the configured client; `get(name, Context)` returns it with
  **`X-Correlation-Id`** set to the context's correlation id on every request (only that: the
  client id is not sent to other services by default). `register(name, ServiceOptions)` adds a
  client built in code (for example with `Auth.bearer(platform::accessToken, platform::clearToken)`).
- Metrics (background-and-metrics contract): `fw_http_client_requests_total{service, method,
  outcome}` (`outcome` the status code, or the failure kind in lower case) and
  `fw_http_client_request_duration_seconds{service, method}`, one per attempt.

## 5a. As built (2026-10-10)

- Engine settings of the adapter: `http-client.event-loops` (default 2, 1 to 64) and
  `http-client.max-connections-per-host` (default 50, 1 to 1000); the library's
  `HttpClients.Settings` takes the same plus a monotonic and a wall clock (the adapter passes the
  application's).
- `ServiceClients.register(ServiceOptions)` returns the client; the options' own observer is
  replaced by the metrics observer. `ServiceClients.names()` lists configured and registered clients.
- Registered clients can be removed (2026-10-20): `unregister(name)` (false when absent, throws for
  a configured client), `replace(options)` (register or swap) and `registerScoped(options)`
  (`Registration`, `AutoCloseable`). Removal drops the client's `service` series from both client
  metrics (`Counter.removeSeries` / `Timer.removeSeries`, new default methods that return 0 for a
  driver keeping no series); a held reference keeps working, unmeasured. Serialised on the
  registry lock; each client's observer is retired under its own lock so no attempt re-creates a series.
- Every request carries `User-Agent: flowcatalyst-framework-httpclient`. Gzip and deflate answers
  are decompressed (the size limit applies to the bytes received).
- A `RESPONSE_TOO_LARGE` failure is never retried (a larger answer will not get smaller).
- The repeat after a `401` does not count as a retry attempt.

## 6. Tests

Against a real local HTTP server (the JDK's `com.sun.net.httpserver`): every method, JSON both ways,
path and query encoding and the refused paths and values, header injection refused, response size
limit, timeouts, connect failures, retries (idempotent methods, idempotency key, `POST` not retried,
`Retry-After` honoured and too long, attempts exhausted), redirects not followed, every auth strategy,
client credentials (cached, refreshed after expiry, single flight under concurrency, `401` retried
once, a failing token endpoint whose message holds no secret), `UnexpectedStatusException` without
the body, the adapter's configuration, `Context` propagation and metrics. TLS is not exercised by a
test (no certificate fixture); `https` base URLs use Vert.x's TLS with host verification and the
JDK's default trust store.
