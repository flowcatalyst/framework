package io.flowcatalyst.framework.push.redis;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.core.Provider;
import io.flowcatalyst.framework.push.Log;
import io.flowcatalyst.framework.push.PushModule;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;

/**
 * Selects the Redis log driver (A2) for {@code push.driver=redis}, chosen in code like the lock
 * stores. List {@code RedisModule} too; the connection is configuration: the Redis instance
 * {@code push.redis-instance}, or the default instance when it is not set.
 *
 * <pre>{@code
 * PushModule.builder().application(new ApplicationCode("fulfil")).redis(RedisPushLogs.redis())
 * }</pre>
 */
public final class RedisPushLogs {

  /** The longest prefix accepted. */
  public static final int MAX_PREFIX_LENGTH = 100;

  private RedisPushLogs() {}

  /**
   * The driver on the instance {@code push.redis-instance} (the default instance when unset), with
   * the keys {@code fw:push} and {@code fw:push:end}.
   *
   * @return the provider, for {@code PushModule.Builder.redis}
   */
  public static Provider<Log> redis() {
    return redis("");
  }

  /**
   * The same under a key prefix, which goes before both keys (tests give each test its own).
   *
   * @param prefix at most 100 characters
   * @return the provider, for {@code PushModule.Builder.redis}
   * @throws IllegalArgumentException for a prefix that is too long
   */
  public static Provider<Log> redis(String prefix) {
    if (prefix.length() > MAX_PREFIX_LENGTH) {
      throw new IllegalArgumentException("prefix must be at most 100 characters");
    }
    return lookup -> {
      RedisConnections connections = lookup.get(RedisConnections.class);
      String instance = lookup.get(Config.class).find(PushModule.REDIS_INSTANCE).orElse("");
      RedisConnection chosen;
      if (instance.isEmpty()) {
        try {
          chosen = connections.defaultInstance();
        } catch (IllegalStateException e) {
          throw new IllegalStateException("push.driver is redis, but no Redis instance is configured", e);
        }
      } else {
        try {
          chosen = connections.get(instance);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("push.redis-instance: " + e.getMessage(), e);
        }
      }
      return new RedisPushLog(chosen.client(), prefix);
    };
  }
}
