package io.flowcatalyst.framework.core;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How health checks run and which decide readiness (core contract section 1; config-and-code
 * contract section 3.1). Given to {@link AppBuilder#health(HealthPolicy)}.
 *
 * <pre>{@code
 * App.builder().health(HealthPolicy.defaults().withTimeout(Duration.ofSeconds(1)).withCritical("flowcatalyst"));
 * }</pre>
 *
 * @param timeout how long each check may take before it reports {@code TimedOut}; more than 0, at
 *     most 5 minutes
 * @param critical names of checks that decide readiness whatever their module asked for
 * @param informational names of checks that never make the application not ready
 */
public record HealthPolicy(Duration timeout, Set<String> critical, Set<String> informational) {

  /** The longest timeout accepted. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  /** Checks the timeout and the names, and copies the sets (keeping their order). */
  public HealthPolicy {
    if (!timeout.isPositive() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "health timeout must be more than 0 and at most 5m, was " + timeout);
    }
    critical = names(critical);
    informational = names(informational);
    for (String name : critical) {
      if (informational.contains(name)) {
        throw new IllegalArgumentException(
            "health check '" + name + "' cannot be both critical and informational");
      }
    }
  }

  /**
   * A 2 second timeout and no criticality overrides.
   *
   * @return the defaults
   */
  public static HealthPolicy defaults() {
    return new HealthPolicy(Duration.ofSeconds(2), Set.of(), Set.of());
  }

  /**
   * A copy with another timeout.
   *
   * @param value more than 0, at most 5 minutes
   * @return the copy
   */
  public HealthPolicy withTimeout(Duration value) {
    return new HealthPolicy(value, critical, informational);
  }

  /**
   * A copy whose critical checks are these (replacing any given before).
   *
   * @param names check names
   * @return the copy
   */
  public HealthPolicy withCritical(String... names) {
    return new HealthPolicy(timeout, new LinkedHashSet<>(List.of(names)), informational);
  }

  /**
   * A copy whose informational checks are these (replacing any given before).
   *
   * @param names check names
   * @return the copy
   */
  public HealthPolicy withInformational(String... names) {
    return new HealthPolicy(timeout, critical, new LinkedHashSet<>(List.of(names)));
  }

  private static Set<String> names(Set<String> names) {
    Set<String> copy = new LinkedHashSet<>();
    for (String name : names) {
      if (name.isBlank()) {
        throw new IllegalArgumentException("a health check name must not be blank");
      }
      copy.add(name);
    }
    return Collections.unmodifiableSet(copy);
  }
}
