package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.Spa;

/**
 * Serves the tickets single-page application ({@code ui/apps/tickets}) from the jar: the build's
 * output, {@code web/dist}, is copied under {@code fw-spa} by this module's pom (the static-assets
 * contract, section 8). The policy is the strict one (section 7); the hash of the colour-mode
 * snippet is the one the Vite build wrote to {@code csp-hashes.json}, which {@link Spa} reads.
 */
public final class TicketsSpaModule implements Module {

  /** The module id. */
  public static final String ID = "tickets-spa";

  @Override
  public String id() {
    return ID;
  }

  /**
   * The SPA as served: the jar's {@code fw-spa} directory under the strict policy.
   *
   * @return the SPA
   */
  static Spa spa() {
    return Spa.classpath("fw-spa").build();
  }

  @Override
  public void boot(App app) {
    app.get(Routes.class).forModule(ID).spa(spa());
  }
}
