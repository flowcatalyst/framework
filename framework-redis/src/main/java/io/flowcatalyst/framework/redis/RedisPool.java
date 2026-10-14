package io.flowcatalyst.framework.redis;

import java.time.Duration;

/**
 * The client pool of a Redis instance (config-and-code contract, section 3.3). Given in code to
 * {@link RedisModule.Builder#pool(RedisPool)}; the instance's connection (URL, password) stays
 * configuration.
 *
 * @param timeout connect and read timeout, and the longest wait for a pooled connection; more than
 *     0, at most 5 minutes
 * @param maxSize the most pooled connections, 1 to 1000
 */
public record RedisPool(Duration timeout, int maxSize) {

  /** The longest timeout accepted. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  /** The largest pool accepted. */
  public static final int MAX_SIZE_LIMIT = 1000;

  /** Checks every value. */
  public RedisPool {
    if (!timeout.isPositive() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException("timeout must be more than 0 and at most 5m, was " + timeout);
    }
    if (maxSize < 1 || maxSize > MAX_SIZE_LIMIT) {
      throw new IllegalArgumentException("maxSize must be 1 to 1000, was " + maxSize);
    }
  }

  /**
   * A 2 second timeout and 16 connections.
   *
   * @return the defaults
   */
  public static RedisPool defaults() {
    return new RedisPool(Duration.ofSeconds(2), 16);
  }

  /**
   * A copy with another timeout.
   *
   * @param value more than 0, at most 5 minutes
   * @return the copy
   */
  public RedisPool withTimeout(Duration value) {
    return new RedisPool(value, maxSize);
  }

  /**
   * A copy with another maximum size.
   *
   * @param value 1 to 1000
   * @return the copy
   */
  public RedisPool withMaxSize(int value) {
    return new RedisPool(timeout, value);
  }
}
