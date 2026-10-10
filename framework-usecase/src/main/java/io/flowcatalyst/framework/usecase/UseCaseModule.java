package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.metrics.Metrics;
import java.time.InstantSource;

/**
 * Provides {@link UseCases} (the runner, over the default database instance and the
 * {@link OutboxWriter}) and {@link Events}. List it after the database module, together with
 * the {@link OutboxModule}.
 *
 * <pre>{@code
 * App.builder()
 *     .module(new DatabaseModule())
 *     .module(new OutboxModule())
 *     .module(new UseCaseModule())
 *     .module(new OrdersModule())
 *     .start();
 * }</pre>
 */
public final class UseCaseModule implements Module {

  /** The module id. */
  public static final String ID = "usecase";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.provide(Events.class, lookup -> new Events(lookup.get(InstantSource.class), lookup.get(Ids.class)));
    app.provide(
        UseCases.class,
        lookup ->
            new UseCases(
                lookup.get(Databases.class).defaultInstance(),
                lookup.get(OutboxWriter.class),
                lookup.get(InstantSource.class),
                d -> Thread.sleep(d),
                lookup.get(Metrics.class),
                lookup.get(MonotonicClock.class)));
  }
}
