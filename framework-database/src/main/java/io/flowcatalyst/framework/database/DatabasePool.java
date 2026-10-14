package io.flowcatalyst.framework.database;

import java.time.Duration;

/**
 * The connection pool of a database instance (config-and-code contract, section 3.2). Given in
 * code to {@link DatabaseModule.Builder#pool(DatabasePool)}; the instance's connection (URL, user,
 * password) stays configuration.
 *
 * <pre>{@code
 * DatabaseModule.builder().pool(DatabasePool.defaults().withMaxSize(20)).build()
 * }</pre>
 *
 * @param maxSize the most connections, 1 to 1000
 * @param minIdle connections kept open when idle, 0 to {@code maxSize}
 * @param connectTimeout how long a caller waits for a connection, 250 ms to 5 minutes
 */
public record DatabasePool(int maxSize, int minIdle, Duration connectTimeout) {

  /** The largest pool accepted. */
  public static final int MAX_SIZE_LIMIT = 1000;

  /** The shortest connect timeout accepted (HikariCP's own minimum). */
  public static final Duration MIN_CONNECT_TIMEOUT = Duration.ofMillis(250);

  /** The longest connect timeout accepted. */
  public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMinutes(5);

  /** Checks every value. */
  public DatabasePool {
    if (maxSize < 1 || maxSize > MAX_SIZE_LIMIT) {
      throw new IllegalArgumentException("maxSize must be 1 to 1000, was " + maxSize);
    }
    if (minIdle < 0 || minIdle > maxSize) {
      throw new IllegalArgumentException(
          "minIdle must be 0 to maxSize (" + maxSize + "), was " + minIdle);
    }
    if (connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0
        || connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "connectTimeout must be 250ms to 5m, was " + connectTimeout);
    }
  }

  /**
   * Ten connections, one kept idle, a 5 second connect timeout.
   *
   * @return the defaults
   */
  public static DatabasePool defaults() {
    return new DatabasePool(10, 1, Duration.ofSeconds(5));
  }

  /**
   * A copy with another maximum size.
   *
   * @param value 1 to 1000, at least {@code minIdle}
   * @return the copy
   */
  public DatabasePool withMaxSize(int value) {
    return new DatabasePool(value, minIdle, connectTimeout);
  }

  /**
   * A copy with another idle minimum.
   *
   * @param value 0 to {@code maxSize}
   * @return the copy
   */
  public DatabasePool withMinIdle(int value) {
    return new DatabasePool(maxSize, value, connectTimeout);
  }

  /**
   * A copy with another connect timeout.
   *
   * @param value 250 ms to 5 minutes
   * @return the copy
   */
  public DatabasePool withConnectTimeout(Duration value) {
    return new DatabasePool(maxSize, minIdle, value);
  }
}
