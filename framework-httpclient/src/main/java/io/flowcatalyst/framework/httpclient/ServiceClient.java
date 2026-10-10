package io.flowcatalyst.framework.httpclient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consumed service: requests relative to its base URL, with its timeouts, auth and retry
 * policy (http-client contract, sections 2 to 4). Thread-safe; share it.
 *
 * <pre>{@code
 * Order order = orders.get("/orders/{id}").pathParam("id", "42").sendJson(Order.class);
 * }</pre>
 */
public final class ServiceClient {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceClient.class);

  private final ServiceOptions options;
  private final Transport transport;
  private final LongSupplier nanos;
  private final InstantSource wallClock;
  private final @Nullable TokenCache tokens;
  private final Map<String, String> extraHeaders;
  private final String basePath;

  ServiceClient(
      ServiceOptions options,
      Transport transport,
      LongSupplier nanos,
      InstantSource wallClock,
      @Nullable TokenCache tokens,
      Map<String, String> extraHeaders) {
    this.options = options;
    this.transport = transport;
    this.nanos = nanos;
    this.wallClock = wallClock;
    this.tokens = tokens;
    this.extraHeaders = Map.copyOf(extraHeaders);
    String path = options.baseUrl().getRawPath();
    this.basePath = path == null || path.equals("/") ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /**
   * The service's name.
   *
   * @return the name
   */
  public String name() {
    return options.name();
  }

  /**
   * The service's options.
   *
   * @return the options
   */
  public ServiceOptions options() {
    return options;
  }

  /**
   * The same service with extra headers on every request (for example a correlation id). Shares
   * connections and the cached token.
   *
   * @param headers names to values
   * @return a client
   */
  public ServiceClient withHeaders(Map<String, String> headers) {
    Map<String, String> merged = new LinkedHashMap<>(extraHeaders);
    headers.forEach(
        (name, value) -> {
          Headers.check(name, value);
          merged.put(name, value);
        });
    return new ServiceClient(options, transport, nanos, wallClock, tokens, merged);
  }

  /**
   * Starts a request.
   *
   * @param method the method
   * @param path starts with {@code /}; {@code {name}} placeholders allowed
   * @return the builder
   * @throws IllegalArgumentException for a path that could leave the service
   */
  public RequestBuilder request(RequestMethod method, String path) {
    return new RequestBuilder(this, method, path);
  }

  /**
   * Starts a GET.
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder get(String path) {
    return request(RequestMethod.GET, path);
  }

  /**
   * Starts a HEAD.
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder head(String path) {
    return request(RequestMethod.HEAD, path);
  }

  /**
   * Starts a POST (not retried unless it has an idempotency key or is marked retryable).
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder post(String path) {
    return request(RequestMethod.POST, path);
  }

  /**
   * Starts a PUT.
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder put(String path) {
    return request(RequestMethod.PUT, path);
  }

  /**
   * Starts a PATCH (not retried unless it has an idempotency key or is marked retryable).
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder patch(String path) {
    return request(RequestMethod.PATCH, path);
  }

  /**
   * Starts a DELETE.
   *
   * @param path the path
   * @return the builder
   */
  public RequestBuilder delete(String path) {
    return request(RequestMethod.DELETE, path);
  }

  /**
   * GETs and decodes a 2xx JSON body.
   *
   * @param <T> the type
   * @param path the path
   * @param type the type
   * @param pathParams values for the placeholders, in the order they appear
   * @return the decoded body
   * @throws UnexpectedStatusException for a status that is not 2xx
   */
  public <T> T getJson(String path, Class<T> type, String... pathParams) {
    RequestBuilder b = get(path);
    List<String> names = new ArrayList<>(b.path.names());
    if (names.size() != pathParams.length) {
      throw new IllegalArgumentException(path + " has " + names.size() + " placeholders, got " + pathParams.length + " values");
    }
    for (int i = 0; i < pathParams.length; i++) {
      b.pathParam(names.get(i), pathParams[i]);
    }
    return b.sendJson(type);
  }

  /**
   * POSTs a JSON body and decodes a 2xx JSON answer. Not retried.
   *
   * @param <T> the type
   * @param path the path, without placeholders
   * @param body the body
   * @param type the answer's type
   * @return the decoded answer
   * @throws UnexpectedStatusException for a status that is not 2xx
   */
  public <T> T postJson(String path, Object body, Class<T> type) {
    return post(path).json(body).sendJson(type);
  }

  // ---- running a request ---------------------------------------------------------------------

  ClientResponse execute(RequestBuilder r) {
    String description = r.description();
    URI target = target(r);
    boolean retryEligible =
        r.retryable != null
            ? r.retryable
            : r.method.idempotent() || r.headers.containsKey("Idempotency-Key");
    RetryPolicy retry = options.retry();
    Duration timeout = r.timeout != null ? r.timeout : options.requestTimeout();
    boolean authRepeated = false;
    int attempt = 0;
    while (true) {
      attempt++;
      Map<String, List<String>> headers = new LinkedHashMap<>();
      headers.put("User-Agent", List.of("flowcatalyst-framework-httpclient"));
      options.defaultHeaders().forEach((k, v) -> headers.put(k, List.of(v)));
      extraHeaders.forEach((k, v) -> headers.put(k, List.of(v)));
      headers.putAll(r.headers);
      String usedToken = applyAuth(headers, description);

      long start = nanos.getAsLong();
      Transport.Incoming in;
      try {
        in =
            transport.exchange(
                new Transport.Outgoing(
                    r.method, target, headers, r.body, options.connectTimeout(), timeout, options.maxResponseBytes()));
      } catch (Transport.Failure f) {
        long took = nanos.getAsLong() - start;
        observe(r, 0, f.kind, took, attempt);
        if (retryEligible && attempt < retry.maxAttempts() && f.kind != HttpClientException.Kind.RESPONSE_TOO_LARGE) {
          pause(backoff(retry, attempt), description);
          continue;
        }
        throw new HttpClientException(f.kind, description + ": " + describe(f.kind), f.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HttpClientException(HttpClientException.Kind.INTERRUPTED, description + ": interrupted", e);
      }
      observe(r, in.status(), null, nanos.getAsLong() - start, attempt);
      ClientResponse response = new ClientResponse(in.status(), in.headers(), in.body(), options.mapper(), description);

      if (in.status() == 401 && !authRepeated && refreshAfterUnauthorized(usedToken)) {
        authRepeated = true;
        attempt--; // the repeat after a refused credential is not a retry
        continue;
      }
      if (retryEligible && attempt < retry.maxAttempts() && retry.retryOnStatuses().contains(in.status())) {
        Duration retryAfter = retryAfter(response.header("Retry-After"));
        if (retryAfter != null && retryAfter.compareTo(retry.maxRetryAfter()) > 0) {
          return response; // the service asked for a longer wait than we are willing to block
        }
        pause(retryAfter != null ? retryAfter : backoff(retry, attempt), description);
        continue;
      }
      return response;
    }
  }

  private URI target(RequestBuilder r) {
    String path = basePath + r.path.fill(r.pathParams);
    StringBuilder query = new StringBuilder();
    for (String[] q : r.query) {
      query.append(query.length() == 0 ? "" : "&")
          .append(PathTemplate.encode(q[0]))
          .append('=')
          .append(PathTemplate.encode(q[1]));
    }
    URI base = options.baseUrl();
    String authority = base.getRawAuthority();
    return URI.create(base.getScheme() + "://" + authority + path + (query.length() == 0 ? "" : "?" + query));
  }

  /** Adds the auth header; returns the bearer token used, for a later invalidation. */
  private @Nullable String applyAuth(Map<String, List<String>> headers, String description) {
    try {
      return switch (options.auth()) {
        case Auth.None n -> null;
        case Auth.Bearer b -> {
          String token = b.token().get();
          Headers.checkValue("Authorization", token);
          headers.put("Authorization", List.of("Bearer " + token));
          yield token;
        }
        case Auth.Basic b -> {
          String pair = b.user() + ":" + b.password().get();
          headers.put(
              "Authorization",
              List.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8))));
          yield null;
        }
        case Auth.Header h -> {
          String value = h.value().get();
          Headers.checkValue(h.name(), value);
          headers.put(h.name(), List.of(value));
          yield null;
        }
        case Auth.OAuthClientCredentials c -> {
          TokenCache cache = tokens;
          if (cache == null) {
            throw new IllegalStateException("no token cache");
          }
          String token = cache.token();
          headers.put("Authorization", List.of("Bearer " + token));
          yield token;
        }
      };
    } catch (HttpClientException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpClientException(HttpClientException.Kind.INTERRUPTED, description + ": interrupted", e);
    } catch (RuntimeException e) {
      throw new HttpClientException(
          HttpClientException.Kind.AUTH,
          description + ": the auth strategy failed (" + e.getClass().getSimpleName() + ")",
          null);
    }
  }

  private boolean refreshAfterUnauthorized(@Nullable String usedToken) {
    return switch (options.auth()) {
      case Auth.Bearer b -> {
        Runnable callback = b.onUnauthorized();
        if (callback == null) {
          yield false;
        }
        callback.run();
        yield true;
      }
      case Auth.OAuthClientCredentials c -> {
        TokenCache cache = tokens;
        if (cache != null && usedToken != null) {
          cache.invalidate(usedToken);
        }
        yield true;
      }
      default -> false;
    };
  }

  private void observe(RequestBuilder r, int status, HttpClientException.@Nullable Kind failure, long took, int attempt) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "{} {} {} -> {} in {}ms (attempt {})",
          options.name(),
          r.method,
          r.path.text(),
          failure == null ? Integer.toString(status) : failure.name(),
          took / 1_000_000,
          attempt);
    }
    try {
      options.observer().attempted(options.name(), r.method, r.path.text(), status, failure, took, attempt);
    } catch (RuntimeException e) {
      LOG.warn("the client observer of {} failed", options.name(), e);
    }
  }

  private static Duration backoff(RetryPolicy retry, int attempt) {
    long cap = retry.maxBackoff().toNanos();
    long exp = retry.initialBackoff().toNanos() << Math.min(attempt - 1, 20);
    long bound = Math.min(cap, exp < 0 ? cap : exp);
    return bound <= 0 ? Duration.ZERO : Duration.ofNanos(ThreadLocalRandom.current().nextLong(bound + 1));
  }

  private @Nullable Duration retryAfter(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    if (v.matches("\\d{1,9}")) {
      return Duration.ofSeconds(Long.parseLong(v));
    }
    try {
      Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      Duration d = Duration.between(wallClock.instant(), at);
      return d.isNegative() ? Duration.ZERO : d;
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static void pause(Duration wait, String description) {
    if (wait.isZero()) {
      return;
    }
    try {
      Thread.sleep(wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpClientException(HttpClientException.Kind.INTERRUPTED, description + ": interrupted", e);
    }
  }

  private static String describe(HttpClientException.Kind kind) {
    return switch (kind) {
      case CONNECT -> "could not connect";
      case TIMEOUT -> "timed out";
      case RESPONSE_TOO_LARGE -> "the response is larger than maxResponseBytes";
      case IO -> "the exchange failed";
      case DECODE -> "the body could not be decoded";
      case AUTH -> "no credentials";
      case INTERRUPTED -> "interrupted";
    };
  }

  @Override
  public String toString() {
    return "ServiceClient[" + options.name() + " " + options.baseUrl().getHost() + "]";
  }
}
