package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.MigrationSet;
import java.time.InstantSource;

/**
 * Contributes the {@code outbox_messages} migration (the FlowCatalyst SDK's table, unchanged) and
 * provides the {@link OutboxWriter}. List it with the database module and the
 * {@link UseCaseModule}.
 */
public final class OutboxModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "outbox";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, OutboxModule.class, "db/migration/outbox");
    app.provide(
        OutboxWriter.class,
        lookup -> new OutboxWriter(lookup.get(Ids.class), lookup.get(InstantSource.class)));
  }
}
