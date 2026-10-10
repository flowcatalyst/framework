package io.flowcatalyst.framework.core;

/**
 * Whether a health check decides readiness.
 *
 * <p>A {@link #CRITICAL} check that is not up makes the application not ready ({@code /health/ready}
 * answers 503). An {@link #INFORMATIONAL} check is run and reported the same way, but its result
 * never changes readiness: use it for dependencies the application can serve traffic without, such
 * as the FlowCatalyst platform. Configuration can change a check's criticality by name
 * ({@code app.health.critical}, {@code app.health.informational}).
 */
public enum Criticality {
  /** Not up means not ready. The default for {@link Health#add(String, HealthCheck)}. */
  CRITICAL,
  /** Reported, never decides readiness. */
  INFORMATIONAL
}
