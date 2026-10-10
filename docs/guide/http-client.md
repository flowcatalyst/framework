<!-- types: HttpClients, ServiceClient, ServiceOptions, RequestBuilder, ClientResponse, RequestMethod, RetryPolicy, Auth, ClientCredentials, HttpClientException, UnexpectedStatusException, ClientObserver, HttpClientModule, ServiceClients -->
# Calling other services: the HTTP client

A blocking client for virtual threads, built on Vert.x: one `ServiceClient` per service you call,
each with its base URL, timeouts, auth and retry policy. Two artifacts:

- `framework-httpclient`: the library. It does not depend on `framework-core`, so it works outside
  the framework too.
- `framework-httpclient-module`: `HttpClientModule`, which builds clients from configuration, adds
  the `Context`'s correlation id, records metrics and closes the engine at shutdown.

The specification is [contracts/http-client.md](../contracts/http-client.md).

## Configured clients

List `HttpClientModule` and configure a client per service:

```yaml
http-clients:
  instances:
    billing:
      base-url: https://billing.example.com/api
      request-timeout: 10s
      retry: { max-attempts: 3, initial-backoff: 200ms }
      auth:
        type: client-credentials
        token-url: https://login.example.com/oauth/token
        client-id: orders-service
        scope: billing
        # client-secret: from the environment, HTTP_CLIENTS_INSTANCES_BILLING_AUTH_CLIENT_SECRET
```

Then, in a handler or use case, with the `Context` you are working in (from
`HttpClientModuleTest`):

```java
ServiceClient billing = app.get(ServiceClients.class).get("billing", ctx);
assertThat(billing.get("/invoices/{id}").pathParam("id", "i1").send().status()).isEqualTo(200);
```

`get(name, ctx)` sends `X-Correlation-Id` with the context's correlation id on every request, and
nothing else from the context (the client id is not sent to other services).

| Key | Default | Meaning |
|---|---|---|
| `base-url` | required | absolute `http(s)` URL; its path is a prefix of every request |
| `connect-timeout` | `5s` | |
| `request-timeout` | `30s` | per attempt, the whole exchange including the body |
| `max-response-bytes` | `10485760` | larger answers fail (`RESPONSE_TOO_LARGE`) |
| `retry.max-attempts`, `retry.initial-backoff`, `retry.max-backoff`, `retry.max-retry-after` | `3`, `200ms`, `5s`, `30s` | see [Retries](#retries) |
| `auth.type` | `none` | `none`, `bearer` (`token`), `basic` (`username`, `password`), `header` (`name`, `value`), `client-credentials` (`token-url`, `client-id`, `client-secret`, `scope`, `audience`, `method`: `post` or `basic`) |

Secrets (`token`, `password`, `value`, `client-secret`) are secret settings: give them through the
environment or `config/secrets.local.yaml`. An unknown key fails start-up. Module settings:
`http-client.event-loops` (2) and `http-client.max-connections-per-host` (50).

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
double from `initial-backoff` to `max-backoff` with jitter; a `Retry-After` up to `max-retry-after`
is honoured, and a longer one returns the response at once instead of blocking. When attempts run
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
not processed). The client id and secret go in the form body by default (`method: basic` puts them
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
