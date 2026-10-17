package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.UseCaseModule;

/** The write path of the ticket service without a web server: the modules the use cases need. */
public final class TicketsCore {

  private TicketsCore() {}

  /**
   * The modules the use cases need, listed in code.
   *
   * @return a builder with the modules added
   */
  public static AppBuilder builder() {
    return App.builder()
        .module(new DatabaseModule())
        .module(new OutboxModule())
        .module(new UseCaseModule())
        .module(new TicketsModule());
  }
}
