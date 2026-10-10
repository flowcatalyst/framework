package io.flowcatalyst.framework.httpclient;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * One consumed service (http-client contract, section 2).
 *
 * @param name {@code [a-z][a-z0-9-]*}, for logs and metrics
 * @param baseUrl absolute http(s), a host, no user info, query or fragment; its path is a prefix
 * @param connectTimeout how long a connection may take
 * @param requestTimeout how long one attempt may take, body included
 * @param maxResponseBytes the largest response body accepted, 1 B to 1 GiB
 * @param retry when requests are sent again
 * @param auth how requests authenticate
 * @param defaultHeaders headers on every request
 * @param mapper reads and writes JSON bodies
 * @param observer told about every finished attempt
 */
public record ServiceOptions(
    String name,
    URI baseUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    long maxResponseBytes,
    RetryPolicy retry,
    Auth auth,
    Map<String, String> defaultHeaders,
    ObjectMapper mapper,
    ClientObserver observer) {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
  static final long MAX_RESPONSE_LIMIT = 1L << 30;

  /** Checks every value. */
  public ServiceOptions {
    if (name.length() > 100 || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("service name must match [a-z][a-z0-9-]*");
    }
    String scheme = baseUrl.getScheme();
    if (!baseUrl.isAbsolute()
        || scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || baseUrl.getHost() == null
        || baseUrl.getRawUserInfo() != null
        || baseUrl.getRawQuery() != null
        || baseUrl.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "service " + name + ": base URL must be absolute http(s) with a host and no user info, query or fragment");
    }
    if (!connectTimeout.isPositive() || !requestTimeout.isPositive()) {
      throw new IllegalArgumentException("service " + name + ": timeouts must be positive");
    }
    if (maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_LIMIT) {
      throw new IllegalArgumentException("service " + name + ": maxResponseBytes must be 1 to 1 GiB");
    }
    Map<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : defaultHeaders.entrySet()) {
      Headers.check(e.getKey(), e.getValue());
      copy.put(e.getKey(), e.getValue());
    }
    defaultHeaders = Collections.unmodifiableMap(copy);
  }

  /**
   * Starts options with the defaults: connect 5 s, request 30 s, 10 MiB, {@link
   * RetryPolicy#defaults()}, no auth, the framework's tolerant JSON mapper, no observer.
   *
   * @param name the service name
   * @param baseUrl the base URL
   * @return a builder
   */
  public static Builder builder(String name, URI baseUrl) {
    return new Builder(name, baseUrl);
  }

  /** Builds {@link ServiceOptions}. */
  public static final class Builder {
    private final String name;
    private final URI baseUrl;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private long maxResponseBytes = 10L * 1024 * 1024;
    private RetryPolicy retry = RetryPolicy.defaults();
    private Auth auth = Auth.none();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private ObjectMapper mapper = JsonMapping.tolerant();
    private ClientObserver observer = ClientObserver.NONE;

    private Builder(String name, URI baseUrl) {
      this.name = name;
      this.baseUrl = baseUrl;
    }

    /**
     * Sets the connect timeout.
     *
     * @param value positive
     * @return this builder
     */
    public Builder connectTimeout(Duration value) {
      this.connectTimeout = value;
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param value positive
     * @return this builder
     */
    public Builder requestTimeout(Duration value) {
      this.requestTimeout = value;
      return this;
    }

    /**
     * Sets the largest response body accepted.
     *
     * @param value 1 B to 1 GiB
     * @return this builder
     */
    public Builder maxResponseBytes(long value) {
      this.maxResponseBytes = value;
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param value the policy
     * @return this builder
     */
    public Builder retry(RetryPolicy value) {
      this.retry = value;
      return this;
    }

    /**
     * Sets the auth strategy.
     *
     * @param value the strategy
     * @return this builder
     */
    public Builder auth(Auth value) {
      this.auth = value;
      return this;
    }

    /**
     * Adds a header sent on every request.
     *
     * @param name the name
     * @param value the value, without CR or LF
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Replaces the JSON mapper.
     *
     * @param value the mapper
     * @return this builder
     */
    public Builder mapper(ObjectMapper value) {
      this.mapper = value;
      return this;
    }

    /**
     * Sets the observer of attempts.
     *
     * @param value the observer
     * @return this builder
     */
    public Builder observer(ClientObserver value) {
      this.observer = value;
      return this;
    }

    /**
     * Builds and checks the options.
     *
     * @return the options
     */
    public ServiceOptions build() {
      return new ServiceOptions(
          name, baseUrl, connectTimeout, requestTimeout, maxResponseBytes, retry, auth, headers, mapper, observer);
    }
  }
}
