package io.flowcatalyst.framework.examples.ordersjte;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.ui.UiAssets;
import io.flowcatalyst.framework.ui.Views;
import io.flowcatalyst.framework.ui.kit.Props;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

/** Mounts the orders pages. List it after {@code UiModule} and before the HTTP server module. */
public final class OrdersUiModule implements Module {

  /** The module id. */
  public static final String ID = "orders-ui";

  private final UiAssets assets;

  /**
   * A module for pages that reference these assets.
   *
   * @param assets the assets served by {@code UiModule}
   */
  public OrdersUiModule(UiAssets assets) {
    this.assets = assets;
  }

  /**
   * The shell around every page: the navigation and the signed-out demo user.
   *
   * @return the shell props
   */
  static Props.AppShell shell() {
    return new Props.AppShell(
        "/orders",
        List.of(
            new Props.NavGroup(
                "Demo",
                List.of(new Props.NavItem("Orders", "/orders", "list", true, false, null)))),
        new Props.ShellUser("Demo user", "demo@example.com", "D"),
        "demo",
        "/orders",
        null,
        "/orders",
        null,
        null);
  }

  /**
   * The view helper the pages use.
   *
   * @param assets the assets
   * @return the views
   */
  static Views views(UiAssets assets) {
    return Views.builder(assets).titleSuffix(" - Orders demo").shell(r -> shell()).build();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void boot(App app) {
    InstantSource clock = InstantSource.system();
    new OrdersUi(views(assets), OrderStore.seeded(Instant.now()), clock).register(app.get(Routes.class).forModule(ID));
  }
}
