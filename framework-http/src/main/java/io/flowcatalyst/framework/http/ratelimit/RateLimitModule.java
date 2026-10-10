package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import java.time.Duration;

/**
 * The rate-limit module (id {@code rate-limit}): registers the {@code memory} driver and provides
 * {@link RateLimiters} from {@code rate-limits.instances}.
 *
 * <pre>{@code
 * rate-limits:
 *   instances:
 *     api: { driver: memory, limit: 100, period: 1m, burst: 20 }
 * }</pre>
 */
public final class RateLimitModule implements Module {

  /** The module id. */
  public static final String ID = "rate-limit";

  /** The in-process driver. */
  public static final String MEMORY = "memory";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers()
        .register(
            RateLimiter.class,
            MEMORY,
            (name, config, lookup, built) ->
                new MemoryRateLimiter(
                    name, policy(config), config.integer("max-keys", 100_000), lookup.get(MonotonicClock.class)));
    app.provide(
        RateLimiters.class,
        lookup -> new RateLimiters(Instances.build(RateLimiter.class, "rate-limits", lookup)));
  }

  /**
   * Reads {@code limit}, {@code period} and {@code burst} (default the limit) from an instance
   * section; for other drivers' factories.
   *
   * @param config the instance section
   * @return the policy
   */
  public static RateLimitPolicy policy(InstanceConfig config) {
    int limit = config.integer("limit", 0);
    Duration period = config.duration("period", Duration.ZERO);
    if (!config.has("limit") || !config.has("period")) {
      throw new IllegalArgumentException("limit and period are required");
    }
    return new RateLimitPolicy(limit, period, config.integer("burst", limit));
  }
}
