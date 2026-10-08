package io.flowcatalyst.framework.core;

import java.util.Map;

/**
 * The outcome of running every health check.
 *
 * @param results one result per check, in the order the checks were added
 */
public record HealthReport(Map<String, HealthResult> results) {

  /** Copies the map. */
  public HealthReport {
    results = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(results));
  }

  /**
   * Whether every check is up.
   *
   * @return true when no check is down or timed out
   */
  public boolean isUp() {
    return results.values().stream().allMatch(r -> r instanceof HealthResult.Up);
  }
}
