<!-- types: Engine, Service, Request, Response, Error, Kind, RetryPolicy, Auth, Credential, ClientCredentials, Observer, Attempt, ServiceOption, EngineOption, ClientOptions, ClientAuth, Clients, BuildOption, Declared -->
# Go: the HTTP client

Package `httpclient` (the library) and `httpclient/module` (the framework adapter) implement the
[HTTP client contract](../../contracts/http-client.md) on `net/http`. The library imports nothing
else of the framework, so it is usable alone, as `framework-httpclient` is. The Java page is
[Calling other services](../http-client.md); every behaviour below is the same unless a section says
otherwise.

## Clients named in code

One client per consumed service, named in code with its policy; configuration gives only its base
URL and the credentials of its authentication strategy (config-and-code contract section 3.9). From
`module_test.go`:

```go
d := module.Declare(app)                                   // in main
clients, err := module.Build(ctx, b, d,                    // in the build function
	module.WithClient("billing", module.DefaultClientOptions().
		WithRequestTimeout(10*time.Second).
		WithRetry(httpclient.DefaultRetry().WithMaxAttempts(5)).
		WithAuth(module.ClientCredentials().WithScope("billing"))))
billing, err := clients.For("billing", cx) // sends X-Correlation-Id from the context
```

```yaml
http-clients:
  instances:
    billing:
      base-url: https://billing.example.com/api
      auth:
        token-url: https://login.example.com/oauth/token
        client-id: orders-service
        # client-secret: from HTTP_CLIENTS_INSTANCES_BILLING_AUTH_CLIENT_SECRET
```

- `ClientOptions` (a value with `With...` copies, checked by `Build`): connect timeout 5s, request
  timeout 30s (per attempt, the whole exchange), `MaxResponseBytes` 10 MiB (1 B to 1 GiB), the retry
  policy and the `ClientAuth`: `module.None()`, `Bearer()` (`auth.token`), `Basic()` (`auth.username`,
  `auth.password`), `Header(name)` (`auth.value`) or `ClientCredentials()` (`auth.token-url`,
  `auth.client-id`, `auth.client-secret`; `WithScope`, `WithAudience`, `WithMethod`). Secrets are
  `config.Secret` values; a key the strategy does not read, a missing key it needs, a section for a
  client the code does not name and a client with no `base-url` are start-up problems, all reported
  at once. `WithClientFrom(name, f)` resolves the policy from the application's own settings once
  configuration is loaded.
- The old keys (`driver`, `connect-timeout`, `retry.*`, `auth.type`, ...) are removed keys: they
  fail start-up naming `module.ClientOptions`.
- The engine setting is configuration: `http-client.max-connections-per-host` (default 50, 1 to
  1000, `HTTP_CLIENT_MAX_CONNECTIONS_PER_HOST`). **Decision (G4):** the Java engine's
  `http-client.event-loops` has no Go counterpart (`net/http` has no event-loop pool to size), so it
  is not declared; the module claims `http-client`, so giving it fails start-up as an unknown key
  listing the real one, rather than being accepted and ignored.
- `Clients.Get(name)`, `For(name, cx)`, `Register(name, baseURL, opts...)` (a client built in code,
  measured too) and `Names()`. The engine is closed at shutdown.
- Metrics: `fw_http_client_requests_total{service, method, outcome}` (the status, or the failure kind
  in lower case) and `fw_http_client_request_duration_seconds{service, method}`, one per attempt.

## The library

```go
engine, err := httpclient.NewEngine(httpclient.MaxConnectionsPerHost(50))
defer engine.Close()
orders, err := engine.Service("orders", baseURL, httpclient.Retry(httpclient.DefaultRetry()))
order, err := httpclient.ReceiveJSON[Order](ctx, orders.Get("/orders/{id}").PathParam("id", "42"))
```

- A `Request` is built with `PathParam`, `Query`, `Header`, `JSON`, `Body`, `Form`, `IdempotencyKey`,
  `Retryable` and `Timeout`; a mistake (a path that could leave the service, a placeholder without a
  value, a header with CR/LF/NUL or a framing header) is collected and returned by `Send` as an error
  wrapping `ErrInvalidRequest`, with nothing sent. The path starts with `/`, may not contain `//`,
  `?`, `#`, `\`, `.` or `..` segments, and placeholder values are percent-encoded as one segment: a
  request can never leave its service's host.
- `Send(ctx)` returns an error only when there is no response; a status is a `Response`
  (`Status`, `Header`, `Headers`, `Bytes`, `Text`, `JSON`, `IsSuccess`). `ReceiveJSON[T]` decodes a
  2xx body (unknown members ignored) and returns an `*Error` of `KindUnexpectedStatus` otherwise,
  with the status and the error envelope's code, never the body.
- `*Error` has a `Kind` (`KindConnect`, `KindTimeout`, `KindResponseTooLarge`, `KindIO`,
  `KindDecode`, `KindAuth`, `KindCanceled`, `KindUnexpectedStatus`, `KindClosed`). Messages name the
  service, method and path template (`down GET /x: could not connect`), never a header value, a body,
  a token or a query string; a network cause is unwrapped from `*url.Error`, so it carries no URL.
- Redirects are returned, not followed. Gzip and deflate answers are decoded here; the size limit
  counts the bytes received and the decoded body is held to the same limit, so a small compressed
  body cannot expand without bound (stricter than Java). A declared `Content-Length` over the limit
  is refused before the body is read.
- Retries: only idempotent methods, requests with an `Idempotency-Key` and requests marked
  `Retryable(true)`; on connect, timeout and I/O failures and on the policy's statuses (default
  429, 502, 503, 504); exponential backoff with full jitter, `Retry-After` (whole seconds or an
  HTTP date) honoured up to `MaxRetryAfter`, longer returns the response at once; when attempts run
  out the last response is returned. A cancelled caller is never retried.
- Authentication: `NoAuth`, `Bearer(credential)`, `BearerRefreshing(credential, onUnauthorized)`,
  `Basic(user, credential)`, `APIKey(name, credential)` and `OAuthClientCredentials(cc)`. A
  `Credential` is a function asked on every attempt; an error from it is an `KindAuth` error naming
  only the type. Client credentials fetch one token for all concurrent callers, cache it until
  `expires_in` minus `min(30s, expires_in/10)` (5 minutes without `expires_in`), and on a 401 drop
  the token and send the request once more for every method, outside the retry count; a second 401
  is returned. The token URL must be `https` (or `http` to localhost), and a failing token endpoint's
  message has the host and the status, never the secret or the answer.

## Differences from Java

- Errors are values (`*Error`, `ErrInvalidRequest`) where Java throws `HttpClientException` and
  `IllegalArgumentException`; a cancelled or expired context is `KindCanceled` (Java's `INTERRUPTED`).
- `UnexpectedStatusException` is an `*Error` of `KindUnexpectedStatus`.
- No `event-loops` setting (above); `http-client.max-connections-per-host` is `MaxConnsPerHost` of
  the transport (and the idle limit).
- JSON bodies are written by the framework's own writer (`encoding/json/v2` formatted as Jackson
  does, as `httpfw` does); reading ignores unknown members.
- A decoded body is held to `MaxResponseBytes` too (above).
