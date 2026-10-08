package io.flowcatalyst.framework.core;

/** The outcome of one health check. */
public sealed interface HealthResult {

  /** The check passed. */
  record Up() implements HealthResult {}

  /**
   * The check failed.
   *
   * @param reason why
   */
  record Down(String reason) implements HealthResult {}

  /** The check did not finish within the health timeout. */
  record TimedOut() implements HealthResult {}
}
