package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rate-limiting middleware (rate-limiting contract, section 3).
 *
 * <pre>{@code
 * Middleware<Request, Response> limit =
 *     RateLimit.by(limiters.get("api"), KeyResolvers.clientAddress()).build();
 * routes.post("/login", handler, RouteOptions.none().with(limit));
 * }</pre>
 */
public final class RateLimit {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimit.class);
  private static final long WARN_EVERY = 60_000_000_000L;
  static final String SHARED_KEY = "-";
  static final int KEY_MAX = 200;

  /** What to do when the limiter's store cannot answer. */
  public enum OnStoreFailure {
    /** Let the request through (the default). */
    ALLOW,
    /** Answer 503 UNAVAILABLE. */
    DENY
  }

  private RateLimit() {}

  /**
   * Starts a middleware.
   *
   * @param limiter the limiter
   * @param keys whose budget a request uses
   * @return a builder
   */
  public static Builder by(RateLimiter limiter, KeyResolver keys) {
    return new Builder(limiter, keys);
  }

  /** Builds the middleware. */
  public static final class Builder {
    private final RateLimiter limiter;
    private final KeyResolver keys;
    private int cost = 1;
    private boolean headers = true;
    private OnStoreFailure onStoreFailure = OnStoreFailure.ALLOW;
    private Metrics metrics = Metrics.noop();

    private Builder(RateLimiter limiter, KeyResolver keys) {
      this.limiter = limiter;
      this.keys = keys;
    }

    /**
     * Sets what one request costs.
     *
     * @param value 1 to the burst
     * @return this builder
     */
    public Builder cost(int value) {
      Gcra.checkCost(value, limiter.policy());
      this.cost = value;
      return this;
    }

    /**
     * Whether answers carry {@code RateLimit-Limit}, {@code RateLimit-Remaining} and {@code
     * RateLimit-Reset}; the default is true.
     *
     * @param value whether to send them
     * @return this builder
     */
    public Builder headers(boolean value) {
      this.headers = value;
      return this;
    }

    /**
     * Sets what happens when the store cannot answer.
     *
     * @param value ALLOW (default) or DENY
     * @return this builder
     */
    public Builder onStoreFailure(OnStoreFailure value) {
      this.onStoreFailure = value;
      return this;
    }

    /**
     * Records {@code fw_http_rate_limited_total} and {@code fw_http_rate_limit_store_failures_total}.
     *
     * @param value the registry, normally {@code app.get(Metrics.class)}
     * @return this builder
     */
    public Builder metrics(Metrics value) {
      this.metrics = value;
      return this;
    }

    /**
     * Builds the middleware.
     *
     * @return it
     */
    public Middleware<Request, Response> build() {
      RateLimiter l = limiter;
      KeyResolver k = keys;
      int c = cost;
      boolean h = headers;
      OnStoreFailure failure = onStoreFailure;
      Counter.Series limited =
          metrics
              .counter("fw_http_rate_limited_total", "Requests answered 429 by a rate limiter.", "limiter")
              .labels(l.name());
      Counter.Series storeFailures =
          metrics
              .counter(
                  "fw_http_rate_limit_store_failures_total",
                  "Requests a rate limiter could not decide because its store failed.",
                  "limiter")
              .labels(l.name());
      AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);
      return (request, next) -> {
        String key = key(k, request);
        Decision decision;
        try {
          decision = l.acquire(key, c);
        } catch (RateLimitStoreException e) {
          storeFailures.inc();
          long now = System.nanoTime();
          long last = lastWarn.get();
          if ((last == Long.MIN_VALUE || now - last >= WARN_EVERY) && lastWarn.compareAndSet(last, now)) {
            LOG.warn("rate limiter {}: the store failed ({}); requests are {}", l.name(), e.getMessage(),
                failure == OnStoreFailure.ALLOW ? "let through" : "refused");
          }
          if (failure == OnStoreFailure.DENY) {
            return HttpErrors.error(503, "UNAVAILABLE", "rate limiting is unavailable");
          }
          return next.handle(request);
        }
        return switch (decision) {
          case Decision.Allowed allowed -> {
            Response response = next.handle(request);
            yield h ? withHeaders(response, l, allowed.remaining(), allowed.resetAfter()) : response;
          }
          case Decision.Limited limit -> {
            limited.inc();
            Response response =
                HttpErrors.error(429, "RATE_LIMITED", "too many requests")
                    .header("Retry-After", Long.toString(seconds(limit.retryAfter())));
            yield h ? withHeaders(response, l, 0, limit.resetAfter()) : response;
          }
        };
      };
    }
  }

  static String key(KeyResolver resolver, Request request) {
    String key = resolver.key(request);
    if (key == null || key.isEmpty()) {
      return SHARED_KEY;
    }
    return key.length() > KEY_MAX ? "sha256:" + KeyResolvers.sha256(key) : key;
  }

  private static Response withHeaders(Response response, RateLimiter limiter, long remaining, Duration reset) {
    return response
        .header("RateLimit-Limit", Integer.toString(limiter.policy().burst()))
        .header("RateLimit-Remaining", Long.toString(remaining))
        .header("RateLimit-Reset", Long.toString(seconds(reset)));
  }

  /** Whole seconds, rounded up, at least 1. */
  static long seconds(Duration d) {
    long s = (d.toNanos() + 999_999_999L) / 1_000_000_000L;
    return Math.max(1, s);
  }
}
