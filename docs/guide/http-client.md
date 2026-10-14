<!-- types: HttpClients, ServiceClient, ServiceOptions, RequestBuilder, ClientResponse, RequestMethod, RetryPolicy, Auth, ClientCredentials, HttpClientException, UnexpectedStatusException, ClientObserver, HttpClientModule, HttpClientOptions, ClientAuth, ServiceClients -->
# Calling other services: the HTTP client

A blocking client for virtual threads, built on Vert.x: one `ServiceClient` per service you call,
each with its base URL, timeouts, auth and retry policy. Two artifacts:

- `framework-httpclient`: the library. It does not depend on `framework-core`, so it works outside
  the framework too.
- `framework-httpclient-module`: `HttpClientModule`, which builds the clients you name in code
  (their base URL and credentials from configuration), adds the `Context`'s correlation id,
  records metrics and closes the engine at shutdown.

The specification is [contracts/http-client.md](../contracts/http-client.md).

## Configured clients

Name each client in code with how it behaves; configuration gives its base URL and the credentials
of its auth strategy (behaviour is code, [Config](config.md#what-configuration-holds)). From
`HttpClientModuleTest`:

```java
HttpClientModule module =
    HttpClientModule.builder()
        .client("platform", HttpClientOptions.defaults().withAuth(ClientAuth.clientCredentials().withScope("api")))
        .build();
```

```yaml
http-clients:
  instances:
    platform:
      base-url: https://platform.example.com
      auth:
        token-url: https://platform.example.com/oauth/token
        client-id: orders-service
        # client-secret: from the environment, HTTP_CLIENTS_INSTANCES_PLATFORM_AUTH_CLIENT_SECRET
```

Then, in a handler or use case, with the `Context` you are working in (from
`HttpClientModuleTest`):

```java
ServiceClient billing = app.get(ServiceClients.class).get("billing", ctx);
assertThat(billing.get("/invoices/{id}").pathParam("id", "i1").send().status()).isEqualTo(200);
```

`get(name, ctx)` sends `X-Correlation-Id` with the context's correlation id on every request, and
nothing else from the context (the client id is not sent to other services).

`HttpClientOptions` (checked when built, `IllegalArgumentException`):

| Method | Default | Meaning |
|---|---|---|
| `withConnectTimeout(Duration)` | `5s` | more than 0, at most 1 h |
| `withRequestTimeout(Duration)` | `30s` | per attempt, the whole exchange including the body; more than 0, at most 1 h |
| `withMaxResponseBytes(long)` | `10485760` | 1 B to 1 GiB; larger answers fail (`RESPONSE_TOO_LARGE`) |
| `withRetry(RetryPolicy)` | `RetryPolicy.defaults()`: 3 attempts, `200ms` to `5s`, `Retry-After` up to `30s` | see [Retries](#retries) |
| `withAuth(ClientAuth)` | `ClientAuth.none()` | the strategy; its credentials come from configuration |

Configuration of a client `<n>`, under `http-clients.instances.<n>`: `base-url` (required; absolute
`http(s)`, its path a prefix of every request) and the credentials its `ClientAuth` reads, all
required:

| `ClientAuth` | Keys under `auth` |
|---|---|
| `none()` | none |
| `bearer()` | `token` (secret) |
| `basic()` | `username`, `password` (secret) |
| `header(name)` | `value` (secret); the header name is code |
| `clientCredentials()`, then `withScope`, `withAudience`, `withMethod(POST or BASIC)` | `token-url`, `client-id`, `client-secret` (secret) |

Give secrets through the environment or `config/secrets.local.yaml`. A key in a client's section
that its strategy does not read, a section with no client of that name in code, and a client named
in code without a `base-url` all fail start-up. `.client(name, config -> ...)` computes the options
from a setting of your own ([Config](config.md#making-a-number-tunable)). The engine:
`HttpClientModule.builder().eventLoops(int)` (default 2, 1 to 64) and
`.maxConnectionsPerHost(int)` (default 50, 1 to 1000). `new HttpClientModule()` is the engine
defaults with no named client.

A client built in code (for example authenticated with the platform's own token) is added with
`register` (from `PlatformApiCallTest`):

```java
ServiceClient api =
    app.get(ServiceClients.class)
        .register(
            ServiceOptions.builder("platform", app.config().get(FlowCatalystModule.BASE_URL))
                .auth(Auth.bearer(platformClient::accessToken, platformClient::clearToken))
                .build());
```

## Requests and responses

```java
Order order =
    client()
        .get("/orders/{id}")
        .pathParam("id", "a/b c?#")
        .query("q", "x&y=z")
        .sendJson(Order.class);
```

(from `ServiceClientTest`). `get`, `post`, `put`, `patch`, `delete`, `head` and
`request(method, path)` start a request; then `pathParam`, `query`, `header`, `json(value)`,
`body(bytes, contentType)`, `form(map)`, `idempotencyKey(key)`, `retryable(boolean)`,
`timeout(duration)`.

- `send()` returns a `ClientResponse` **whatever the status**: `status()`, `isSuccess()`,
  `header(name)`, `bodyText()`, `bodyBytes()`, `json(Class)` and `json(TypeReference)`.
- `sendJson(type)` decodes a 2xx body and throws `UnexpectedStatusException` otherwise, with the
  status and, when the answer is the platform's error envelope, its `error` code; never the body.
- `getJson(path, type, pathParams...)` and `postJson(path, body, type)` are shorthands.
- JSON is the framework's Jackson 3, **tolerant of unknown properties** when reading (other services
  add fields).

### What the client refuses

A request can never leave its service: the path must start with `/` and may not contain `//`, `?`,
`#`, `\`, or `.`/`..` segments, and a path parameter is percent-encoded as one segment (so `/`, `?`,
`#` cannot sneak in) and may not be empty, `.` or `..`. Header values with CR, LF or NUL are refused
(no header injection), and `Host`, `Content-Length`, `Transfer-Encoding` and `Connection` are set by
the client only. All of these are `IllegalArgumentException` before anything is sent. Redirects are
**not followed** (they would carry your `Authorization` header elsewhere); a `3xx` is returned.

### Failures

No response is an `HttpClientException` with a `kind()`: `CONNECT`, `TIMEOUT`,
`RESPONSE_TOO_LARGE`, `IO`, `DECODE` (the body is not the JSON asked for), `AUTH` (no credentials,
for example a failing token endpoint) or `INTERRUPTED` (the interrupt flag is set again). Messages
name the service, method and path template, never header values, bodies, tokens or query strings.

## Retries

Only requests that are safe to repeat are retried: `GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE`, a
request with `idempotencyKey(key)` (sent as `Idempotency-Key`), or one marked `retryable(true)`.
**`POST` and `PATCH` are never retried otherwise.** `retryable(false)` sends any request once.

Retried: `CONNECT`, `TIMEOUT` and `IO` failures, and the statuses 429, 502, 503 and 504. Waits
double from the policy's initial backoff to its maximum with jitter; a `Retry-After` up to its
`maxRetryAfter` is honoured, and a longer one returns the response at once instead of blocking. When attempts run
out, the last response is returned.

## Auth

| Strategy | Sends |
|---|---|
| `Auth.none()` | nothing |
| `Auth.bearer(supplier)` | `Authorization: Bearer ...`, asked per attempt |
| `Auth.bearer(supplier, onUnauthorized)` | as above; after a `401` the callback runs and the request is sent once more |
| `Auth.basic(user, passwordSupplier)` | `Authorization: Basic ...` |
| `Auth.header(name, valueSupplier)` | an API-key header |
| `Auth.clientCredentials(...)` | OAuth 2 client credentials |

**Client credentials** fetch a token once and reuse it until shortly before it expires
(`expires_in` minus up to 30 seconds); concurrent callers share one token request. After a `401` the
token is dropped and the request is sent once more with a new one, whatever the method (a `401` was
not processed). The client id and secret go in the form body by default (`Method.BASIC` puts them
in an `Authorization` header). The token URL must be `https`, or `http` to `localhost`.

## Metrics

Every attempt is counted in `fw_http_client_requests_total{service, method, outcome}` (the status,
or the failure kind in lower case) and timed in `fw_http_client_request_duration_seconds{service,
method}` ([metrics](background-and-metrics.md#built-in-metrics)). Without the module, pass a
`ClientObserver` to `ServiceOptions.builder(...).observer(...)`.

## Without the framework

```java
try (HttpClients clients = HttpClients.create()) {
  ServiceClient orders =
      clients.service(ServiceOptions.builder("orders", URI.create("https://orders.example.com")).build());
}
```

`HttpClients` owns the engine (Vert.x and its connection pools); create one and close it at the end.
Using a client after that is `IllegalStateException`.

## Testing

The framework's own tests run the client against the JDK's `com.sun.net.httpserver` on a free local
port (`FakeServer` in `framework-httpclient`'s tests). TLS is not covered by a test: `https` uses
Vert.x's TLS with host verification and the JDK's default trust store.
