package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.metrics.Metrics;
import java.time.Duration;
import java.util.List;

/**
 * The built-in module: declares the settings of the lifecycle itself and provides {@link Metrics}
 * and {@link Background}. Always first.
 */
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

  static final Setting<Metrics.Driver> METRICS_DRIVER =
      Setting.enumOf("app.metrics.driver", List.of(Metrics.Driver.values()))
          .withDefault(Metrics.Driver.MEMORY)
          .describedAs("Metrics driver: memory (readable, Prometheus endpoint) or noop.");

  static final Setting<Integer> METRICS_MAX_SERIES =
      Setting.integer("app.metrics.max-series")
          .withDefault(1000)
          .describedAs(
              "Most series one metric may hold (1 to 100000); further label combinations are dropped.");

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
    app.declare(METRICS_DRIVER);
    app.declare(METRICS_MAX_SERIES);
    app.provide(
        Metrics.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          int maxSeries = config.get(METRICS_MAX_SERIES);
          if (maxSeries < 1 || maxSeries > 100_000) {
            throw new StartupException(
                List.of(Problem.of(ID, METRICS_MAX_SERIES.key(), "must be 1 to 100000")));
          }
          return switch (config.get(METRICS_DRIVER)) {
            case MEMORY -> Metrics.memory(maxSeries, lookup.get(MonotonicClock.class));
            case NOOP -> Metrics.noop();
          };
        });
    app.provide(
        Background.class,
        lookup ->
            new Background(
                lookup.get(Health.class),
                lookup.get(Metrics.class),
                lookup.get(MonotonicClock.class)));
  }
}
