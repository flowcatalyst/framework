package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.usecase.Events;
import java.time.InstantSource;

/** The application's own module: its migrations and its use cases. */
public final class TicketsModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "tickets";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, TicketsModule.class, "db/migration/tickets");
    app.provide(
        TicketUseCases.class,
        lookup ->
            new TicketUseCases(
                new TicketRepository(),
                new SlaPolicyRepository(),
                lookup.get(Databases.class).defaultInstance(),
                lookup.get(Events.class),
                lookup.get(Ids.class),
                lookup.get(InstantSource.class),
                TicketUseCases.Policies.defaults()));
  }
}
