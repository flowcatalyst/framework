package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.core.Instances;
import java.util.Set;

/** The configured limiters, by instance name. Provided by {@link RateLimitModule}. */
public final class RateLimiters {

  private final Instances<RateLimiter> instances;

  RateLimiters(Instances<RateLimiter> instances) {
    this.instances = instances;
  }

  /**
   * A limiter.
   *
   * @param name the instance name under {@code rate-limits.instances}
   * @return the limiter
   * @throws IllegalArgumentException naming the known instances when there is no such one
   */
  public RateLimiter get(String name) {
    return instances.get(name);
  }

  /**
   * The configured names.
   *
   * @return names in configuration order
   */
  public Set<String> names() {
    return instances.names();
  }
}
