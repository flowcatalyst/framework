package io.flowcatalyst.framework.locks.redis;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;

/**
 * Registers the {@code redis} lock driver. List it together with the Redis module and the locks
 * module.
 *
 * <pre>{@code
 * locks:
 *   instances:
 *     shared: { driver: redis, connection: default, prefix: "fw:lock:" }
 * }</pre>
 *
 * <p>{@code connection} names a Redis instance (default: the default instance); {@code prefix}
 * defaults to {@code fw:lock:}.
 */
public final class LocksRedisModule implements Module {

  /** The module id. */
  public static final String ID = "locks-redis";

  /** The driver name used in configuration. */
  public static final String DRIVER = "redis";

  /** The default key prefix. */
  public static final String DEFAULT_PREFIX = "fw:lock:";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers()
        .register(
            LockStore.class,
            DRIVER,
            (name, config, lookup, built) -> {
              RedisConnections connections = lookup.get(RedisConnections.class);
              RedisConnection connection =
                  config
                      .findString("connection")
                      .map(connections::get)
                      .orElseGet(connections::defaultInstance);
              String prefix = config.string("prefix", DEFAULT_PREFIX);
              return new LockStore(
                  new RedisLockDriver(connection.client(), prefix),
                  lookup.get(MonotonicClock.class));
            });
  }
}
