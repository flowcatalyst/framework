package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsOptions;
import java.util.function.Function;

/**
 * The built-in module: provides {@link Metrics} (with the driver chosen in code, {@link
 * AppBuilder#metrics(MetricsOptions)}) and {@link Background}, and claims the {@code app}
 * configuration component, which has no keys left (config-and-code contract, section 3.1). Always
 * first.
 */
final class CoreModule implements Module {

  static final String ID = "core";

  private final Function<Config, MetricsOptions> metrics;

  CoreModule(Function<Config, MetricsOptions> metrics) {
    this.metrics = metrics;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("app");
    app.provide(
        Metrics.class,
        lookup -> {
          MetricsOptions options = metrics.apply(lookup.get(Config.class));
          return switch (options.driver()) {
            case MEMORY -> Metrics.memory(options.maxSeries(), lookup.get(MonotonicClock.class));
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
