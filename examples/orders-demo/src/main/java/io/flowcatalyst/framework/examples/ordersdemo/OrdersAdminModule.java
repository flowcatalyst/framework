package io.flowcatalyst.framework.examples.ordersdemo;

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

/**
 * The demo's one admin section of its own (M4d: the plug-in shape M4e's sections follow): an
 * overview of the client's orders, from the application's own database, no platform data. The
 * section, its operation and its permission are declared in {@code register}; the handler is bound
 * in {@code boot}; the SPA has the matching page (ui/apps/orders, {@code adminPages}).
 */
public final class OrdersAdminModule implements Module {

  /** The module id. */
  public static final String ID = "orders-admin";

  /** Who may read the orders overview. */
  public static final String VIEW = "orders-demo:admin:orders:view";

  /**
   * The orders overview.
   *
   * @param open orders still open
   * @param cancelled cancelled orders
   * @param openTotalCents the sum of the open orders, in cents
   */
  public record OrdersOverview(int open, int cancelled, long openTotalCents) {}

  /** {@code GET /api/admin/orders/overview}. */
  public static final Operation<NoBody, OrdersOverview> OVERVIEW =
      Operation.get("/api/admin/orders/overview", OrdersOverview.class)
          .id("getAdminOrdersOverview")
          .summary("Count the client's orders by status")
          .tag("admin")
          .permission(VIEW)
          .build();

  /** The navigation entry. */
  static final AdminSection SECTION =
      AdminSection.of("orders", "Orders overview", "/admin/orders", VIEW)
          .icon("i-lucide-chart-no-axes-column")
          .description("How many orders are open and cancelled.")
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
              int open = 0;
              int cancelled = 0;
              long openTotal = 0;
              for (OrderQueries.Summary s : OrderQueries.summaries(db, r.context().client())) {
                if (s.status() == OrderStatus.OPEN) {
                  open++;
                  openTotal += s.totalCents();
                } else {
                  cancelled++;
                }
              }
              return Outcome.ok(new OrdersOverview(open, cancelled, openTotal));
            });
  }
}
