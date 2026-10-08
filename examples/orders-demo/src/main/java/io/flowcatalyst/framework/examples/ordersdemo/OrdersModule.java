package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.usecase.Events;

/** The application's own module: its migrations and its use cases. */
public final class OrdersModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "orders";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, OrdersModule.class, "db/migration/orders");
    app.provide(
        OrderUseCases.class,
        lookup ->
            new OrderUseCases(
                new OrderRepository(),
                lookup.get(Databases.class).defaultInstance(),
                lookup.get(Events.class),
                lookup.get(Ids.class),
                OrderUseCases.Policies.defaults()));
  }
}
