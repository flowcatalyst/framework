package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.util.List;

/**
 * The service's own admin section (the plug-in shape of M4d): the client's tickets counted by
 * status, from the application's own database, no platform data. The section, its operation and its
 * permission are declared in {@code register}; the handler is bound in {@code boot}; the SPA has the
 * matching page.
 */
public final class TicketsAdminModule implements Module {

  /** The module id. */
  public static final String ID = "tickets-admin";

  /** Who may read the queue overview. */
  public static final String VIEW = Permissions.APPLICATION + ":admin:tickets:view";

  /**
   * Tickets of one status.
   *
   * @param status the status
   * @param count how many tickets
   * @param flagged how many of those the sweep has flagged as past their deadline
   */
  public record StatusCountView(TicketStatus status, long count, long flagged) {}

  /**
   * The queue overview.
   *
   * @param statuses the tickets by status
   * @param active tickets somebody still has to resolve
   * @param flagged active tickets the sweep has flagged
   */
  public record TicketsOverview(List<StatusCountView> statuses, long active, long flagged) {}

  /** {@code GET /api/admin/tickets/overview}. */
  public static final Operation<NoBody, TicketsOverview> OVERVIEW =
      Operation.get("/api/admin/tickets/overview", TicketsOverview.class)
          .id("getAdminTicketsOverview")
          .summary("Count the client's tickets by status")
          .tag("admin")
          .permission(VIEW)
          .build();

  /** The navigation entry. */
  static final AdminSection SECTION =
      AdminSection.of("tickets", "Queue overview", "/admin/tickets", VIEW)
          .icon("i-lucide-chart-no-axes-column")
          .description("How many tickets are in each status, and how many ran out of time.")
          .serving(OVERVIEW);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, SECTION);
  }

  @Override
  public void boot(App app) {
    Database db = app.get(Databases.class).defaultInstance();
    app.get(Routes.class)
        .forModule(ID)
        .bind(
            OVERVIEW,
            r -> {
              List<StatusCountView> statuses =
                  TicketQueries.counts(db, r.context().client()).stream()
                      .map(c -> new StatusCountView(c.status(), c.count(), c.flagged()))
                      .toList();
              long active = statuses.stream().filter(s -> s.status().isActive()).mapToLong(StatusCountView::count).sum();
              long flagged = statuses.stream().filter(s -> s.status().isActive()).mapToLong(StatusCountView::flagged).sum();
              return Outcome.ok(new TicketsOverview(statuses, active, flagged));
            });
  }
}
