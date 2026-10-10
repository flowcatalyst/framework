package io.flowcatalyst.framework.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of running every health check.
 *
 * @param results one result per check, in the order the checks were added
 * @param informational the names of the checks whose result does not decide readiness
 */
public record HealthReport(Map<String, HealthResult> results, Set<String> informational) {

  /** Copies the map and the set. */
  public HealthReport {
    results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    informational = Collections.unmodifiableSet(new LinkedHashSet<>(informational));
  }

  /**
   * A report in which every check is critical.
   *
   * @param results one result per check
   */
  public HealthReport(Map<String, HealthResult> results) {
    this(results, Set.of());
  }

  /**
   * Whether every check, critical and informational, is up.
   *
   * @return true when no check is down or timed out
   */
  public boolean isUp() {
    return results.values().stream().allMatch(r -> r instanceof HealthResult.Up);
  }

  /**
   * Whether every critical check is up; informational checks do not count. This is what
   * {@code /health/ready} answers.
   *
   * @return true when no critical check is down or timed out
   */
  public boolean isReady() {
    return results.entrySet().stream()
        .allMatch(e -> informational.contains(e.getKey()) || e.getValue() instanceof HealthResult.Up);
  }

  /**
   * The criticality a check ran with.
   *
   * @param name the check's name
   * @return its criticality; {@link Criticality#CRITICAL} for a name not in the report
   */
  public Criticality criticality(String name) {
    return informational.contains(name) ? Criticality.INFORMATIONAL : Criticality.CRITICAL;
  }
}
