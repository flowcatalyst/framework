package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Setting;
import java.time.Duration;
import java.util.List;

/** The built-in module: declares the settings of the lifecycle itself. Always first. */
final class CoreModule implements Module {

  static final String ID = "core";

  static final Setting<Duration> SHUTDOWN_DRAIN =
      Setting.duration("app.shutdown.drain")
          .withDefault(Duration.ofSeconds(10))
          .describedAs("Time each shutdown step may take before it is logged as overrun.");

  static final Setting<Duration> HEALTH_TIMEOUT =
      Setting.duration("app.health.timeout")
          .withDefault(Duration.ofSeconds(2))
          .describedAs("Time each health check may take before it reports TimedOut.");

  static final Setting<List<String>> HEALTH_CRITICAL =
      Setting.stringList("app.health.critical")
          .withDefault(List.of())
          .describedAs(
              "Names of health checks that decide readiness, whatever their module asked for.");

  static final Setting<List<String>> HEALTH_INFORMATIONAL =
      Setting.stringList("app.health.informational")
          .withDefault(List.of())
          .describedAs(
              "Names of health checks that are reported but never make the application not ready.");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.declare(SHUTDOWN_DRAIN);
    app.declare(HEALTH_TIMEOUT);
    app.declare(HEALTH_CRITICAL);
    app.declare(HEALTH_INFORMATIONAL);
  }
}
