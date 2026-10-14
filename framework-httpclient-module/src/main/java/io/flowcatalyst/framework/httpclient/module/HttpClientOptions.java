package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.httpclient.RetryPolicy;
import java.time.Duration;

/**
 * How a client named in {@link HttpClientModule} behaves (config-and-code contract, section 3.6).
 * Its base URL and credentials stay configuration.
 *
 * <pre>{@code
 * HttpClientModule.builder()
 *     .client("billing", HttpClientOptions.defaults()
 *         .withRequestTimeout(Duration.ofSeconds(10))
 *         .withRetry(RetryPolicy.defaults().withMaxAttempts(3))
 *         .withAuth(ClientAuth.clientCredentials().withScope("billing")))
 *     .build()
 * }</pre>
 *
 * @param connectTimeout how long a connection may take; more than 0, at most 1 hour
 * @param requestTimeout how long one attempt may take, body included; more than 0, at most 1 hour
 * @param maxResponseBytes the largest response body accepted, 1 B to 1 GiB
 * @param retry when requests are sent again
 * @param auth how requests authenticate; the credentials come from configuration
 */
public record HttpClientOptions(
    Duration connectTimeout,
    Duration requestTimeout,
    long maxResponseBytes,
    RetryPolicy retry,
    ClientAuth auth) {

  /** The longest timeout accepted. */
  public static final Duration MAX_TIMEOUT = Duration.ofHours(1);

  /** The largest response limit accepted. */
  public static final long MAX_RESPONSE_LIMIT = 1L << 30;

  /** Checks every value. */
  public HttpClientOptions {
    check("connectTimeout", connectTimeout);
    check("requestTimeout", requestTimeout);
    if (maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_LIMIT) {
      throw new IllegalArgumentException(
          "maxResponseBytes must be 1 B to 1 GiB, was " + maxResponseBytes);
    }
  }

  private static void check(String field, Duration value) {
    if (!value.isPositive() || value.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(field + " must be more than 0 and at most 1h, was " + value);
    }
  }

  /**
   * Connect 5 s, request 30 s, 10 MiB, {@link RetryPolicy#defaults()}, no authentication.
   *
   * @return the defaults
   */
  public static HttpClientOptions defaults() {
    return new HttpClientOptions(
        Duration.ofSeconds(5), Duration.ofSeconds(30), 10L * 1024 * 1024, RetryPolicy.defaults(), ClientAuth.none());
  }

  /**
   * A copy with another connect timeout.
   *
   * @param value more than 0, at most 1 hour
   * @return the copy
   */
  public HttpClientOptions withConnectTimeout(Duration value) {
    return new HttpClientOptions(value, requestTimeout, maxResponseBytes, retry, auth);
  }

  /**
   * A copy with another per-attempt timeout.
   *
   * @param value more than 0, at most 1 hour
   * @return the copy
   */
  public HttpClientOptions withRequestTimeout(Duration value) {
    return new HttpClientOptions(connectTimeout, value, maxResponseBytes, retry, auth);
  }

  /**
   * A copy with another response size limit.
   *
   * @param value 1 B to 1 GiB
   * @return the copy
   */
  public HttpClientOptions withMaxResponseBytes(long value) {
    return new HttpClientOptions(connectTimeout, requestTimeout, value, retry, auth);
  }

  /**
   * A copy with another retry policy.
   *
   * @param value the policy (its ranges are checked by {@link RetryPolicy})
   * @return the copy
   */
  public HttpClientOptions withRetry(RetryPolicy value) {
    return new HttpClientOptions(connectTimeout, requestTimeout, maxResponseBytes, value, auth);
  }

  /**
   * A copy with another authentication strategy.
   *
   * @param value the strategy; its credentials come from configuration
   * @return the copy
   */
  public HttpClientOptions withAuth(ClientAuth value) {
    return new HttpClientOptions(connectTimeout, requestTimeout, maxResponseBytes, retry, value);
  }
}
