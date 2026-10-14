package io.flowcatalyst.framework.locks.redis;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreFactory;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;
import org.jspecify.annotations.Nullable;

/**
 * The {@code redis} lock driver (locks contract section 5), chosen in code (config-and-code
 * contract, section 3.4). List {@code RedisModule} too; the connection is configuration.
 *
 * <pre>{@code
 * LocksModule.builder().store("shared", RedisLockStores.redis("cache", "orders:lock:")).build()
 * }</pre>
 */
public final class RedisLockStores {

  /** The default key prefix. */
  public static final String DEFAULT_PREFIX = "fw:lock:";

  /** The longest prefix accepted. */
  public static final int MAX_PREFIX_LENGTH = 100;

  private RedisLockStores() {}

  /**
   * Locks on the default Redis connection, under {@value #DEFAULT_PREFIX}.
   *
   * @return the factory
   */
  public static LockStoreFactory redis() {
    return factory(null, DEFAULT_PREFIX);
  }

  /**
   * Locks on a named Redis connection, under {@value #DEFAULT_PREFIX}.
   *
   * @param connection a Redis instance under {@code redis.instances}
   * @return the factory
   */
  public static LockStoreFactory redis(String connection) {
    return factory(connection, DEFAULT_PREFIX);
  }

  /**
   * Locks on a named Redis connection under a key prefix.
   *
   * @param connection a Redis instance under {@code redis.instances}
   * @param prefix not blank, at most 100 characters
   * @return the factory
   * @throws IllegalArgumentException for a blank or too long prefix
   */
  public static LockStoreFactory redis(String connection, String prefix) {
    return factory(connection, prefix);
  }

  private static LockStoreFactory factory(@Nullable String connection, String prefix) {
    if (prefix.isBlank() || prefix.length() > MAX_PREFIX_LENGTH) {
      throw new IllegalArgumentException("prefix must not be blank and at most 100 characters");
    }
    return (name, lookup) -> {
      RedisConnections connections = lookup.get(RedisConnections.class);
      RedisConnection chosen = connection == null ? connections.defaultInstance() : connections.get(connection);
      return new LockStore(new RedisLockDriver(chosen.client(), prefix), lookup.get(MonotonicClock.class));
    };
  }
}
