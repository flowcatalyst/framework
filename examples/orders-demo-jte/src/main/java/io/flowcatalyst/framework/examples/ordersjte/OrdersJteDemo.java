package io.flowcatalyst.framework.examples.ordersjte;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import io.flowcatalyst.framework.ui.UiAssets;
import io.flowcatalyst.framework.ui.UiModule;

/**
 * The orders pages as a server-rendered application: framework-ui's assets and the orders module
 * behind the HTTP server. Run it with {@code HTTP_PORT=<free port>}; it needs no database and
 * no configuration file. This is a second entry point beside {@code OrdersWeb}, which serves the
 * Nuxt UI single-page application.
 */
public final class OrdersJteDemo {

  private OrdersJteDemo() {}

  /**
   * The application's modules.
   *
   * @return a builder, not started
   */
  public static AppBuilder builder() {
    UiAssets assets = UiAssets.load();
    return App.builder()
        .module(new UiModule(assets))
        .module(new OrdersUiModule(assets))
        .module(new JettyHttpModule());
  }

  /**
   * Serves the pages.
   *
   * @param args the program arguments
   */
  public static void main(String[] args) {
    switch (builder().shutdownHook(true).launch(args)) {
      case Launch.Ran ran -> System.exit(ran.exitCode());
      case Launch.Started started -> { }
    }
  }
}
