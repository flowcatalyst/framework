package io.flowcatalyst.framework.core;

/** A health check: returning normally means up, throwing means down. */
@FunctionalInterface
public interface HealthCheck {

  /**
   * Runs the check.
   *
   * @throws Exception when the thing checked is not healthy; the message becomes the reason
   */
  void check() throws Exception;
}
