package io.flowcatalyst.framework.http.ratelimit.redis;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ratelimit.RateLimitModule;
import io.flowcatalyst.framework.http.ratelimit.RateLimiter;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;

/**
 * Registers the {@code redis} rate-limit driver (module id {@code rate-limit-redis}). List it with
 * {@link RateLimitModule} and the Redis module.
 *
 * <pre>{@code
 * rate-limits:
 *   instances:
 *     login: { driver: redis, connection: default, prefix: "fw:rl:", limit: 5, period: 1m }
 * }</pre>
 */
public final class RateLimitRedisModule implements Module {

  /** The module id. */
  public static final String ID = "rate-limit-redis";

  /** The driver name. */
  public static final String DRIVER = "redis";

  /** The default key prefix. */
  public static final String DEFAULT_PREFIX = "fw:rl:";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers()
        .register(
            RateLimiter.class,
            DRIVER,
            (name, config, lookup, built) -> {
              RedisConnections connections = lookup.get(RedisConnections.class);
              RedisConnection connection =
                  config.findString("connection").map(connections::get).orElseGet(connections::defaultInstance);
              return new RedisRateLimiter(
                  name, RateLimitModule.policy(config), connection.client(), config.string("prefix", DEFAULT_PREFIX));
            });
  }
}
