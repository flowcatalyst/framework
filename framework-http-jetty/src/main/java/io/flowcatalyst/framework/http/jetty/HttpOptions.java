package io.flowcatalyst.framework.http.jetty;

import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How the HTTP server behaves (http contract section 8; config-and-code contract section 3.5).
 * Given to {@link JettyHttpModule#JettyHttpModule(HttpOptions)}; the address and port stay
 * configuration ({@code http.host}, {@code http.port}).
 *
 * <pre>{@code
 * new JettyHttpModule(HttpOptions.defaults().withMaxBodyBytes(10 * 1024 * 1024).withMetricsEndpoint("/metrics"))
 * }</pre>
 *
 * @param maxBodyBytes the largest request body accepted, 0 to 1 GiB; larger requests get 413
 * @param requestTimeout idle timeout of a connection while a request is read or a response
 *     written; more than 0, at most 1 hour
 * @param shutdownGrace how long in-flight requests may take on shutdown, 0 to 1 hour; null means
 *     the application's shutdown drain
 * @param metricsPath where the Prometheus endpoint is mounted (literal segments such as {@code
 *     /metrics}); null means no endpoint
 */
public record HttpOptions(
    long maxBodyBytes,
    Duration requestTimeout,
    @Nullable Duration shutdownGrace,
    @Nullable String metricsPath) {

  /** The largest body limit accepted. */
  public static final long MAX_BODY_LIMIT = 1L << 30;

  /** The longest request timeout and shutdown grace accepted. */
  public static final Duration MAX_DURATION = Duration.ofHours(1);

  private static final Pattern LITERAL_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");

  /** Checks every value. */
  public HttpOptions {
    if (maxBodyBytes < 0 || maxBodyBytes > MAX_BODY_LIMIT) {
      throw new IllegalArgumentException("maxBodyBytes must be 0 to 1 GiB, was " + maxBodyBytes);
    }
    if (!requestTimeout.isPositive() || requestTimeout.compareTo(MAX_DURATION) > 0) {
      throw new IllegalArgumentException(
          "requestTimeout must be more than 0 and at most 1h, was " + requestTimeout);
    }
    if (shutdownGrace != null
        && (shutdownGrace.isNegative() || shutdownGrace.compareTo(MAX_DURATION) > 0)) {
      throw new IllegalArgumentException("shutdownGrace must be 0 to 1h, was " + shutdownGrace);
    }
    if (metricsPath != null && !LITERAL_PATH.matcher(metricsPath).matches()) {
      throw new IllegalArgumentException(
          "metricsPath must be literal segments such as /metrics, was '" + metricsPath + "'");
    }
  }

  /**
   * 1 MiB bodies, a 30 second request timeout, the shutdown drain as grace, no metrics endpoint.
   *
   * @return the defaults
   */
  public static HttpOptions defaults() {
    return new HttpOptions(1_048_576L, Duration.ofSeconds(30), null, null);
  }

  /**
   * A copy with another body limit.
   *
   * @param value 0 to 1 GiB
   * @return the copy
   */
  public HttpOptions withMaxBodyBytes(long value) {
    return new HttpOptions(value, requestTimeout, shutdownGrace, metricsPath);
  }

  /**
   * A copy with another request timeout.
   *
   * @param value more than 0, at most 1 hour
   * @return the copy
   */
  public HttpOptions withRequestTimeout(Duration value) {
    return new HttpOptions(maxBodyBytes, value, shutdownGrace, metricsPath);
  }

  /**
   * A copy with its own shutdown grace instead of the application's shutdown drain.
   *
   * @param value 0 to 1 hour
   * @return the copy
   */
  public HttpOptions withShutdownGrace(Duration value) {
    return new HttpOptions(maxBodyBytes, requestTimeout, value, metricsPath);
  }

  /**
   * A copy that mounts the Prometheus endpoint. Metrics reveal routes and volumes; protect it with
   * the secret {@code http.metrics.bearer-token}.
   *
   * @param path literal segments, such as {@code /metrics}
   * @return the copy
   */
  public HttpOptions withMetricsEndpoint(String path) {
    return new HttpOptions(maxBodyBytes, requestTimeout, shutdownGrace, path);
  }
}
