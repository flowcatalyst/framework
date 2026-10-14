package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.database.AuthDatabaseModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import java.util.Map;
import java.util.Set;

/**
 * The orders demo as a web application: the write path of {@link OrdersDemo}, the HTTP server,
 * sessions and login against the platform, and the typed orders API. {@code java -jar ... openapi
 * --out web/openapi.json} writes its OpenAPI document without a database or configuration.
 */
public final class OrdersWeb {

  private OrdersWeb() {}

  /**
   * The application's modules.
   *
   * @return a builder, not started
   */
  public static AppBuilder builder() {
    return OrdersDemo.builder()
        .module(new JettyHttpModule())
        .module(new AuthDatabaseModule())
        .module(
            AuthModule.builder()
                .application(new ApplicationCode("orders-demo"))
                .permissions(
                    PermissionMapper.scopes()
                        .and(
                            PermissionMapper.byRole(
                                Map.of(
                                    "orders-demo:clerk", Set.of(OrdersApi.READ, OrdersApi.MANAGE),
                                    "orders-demo:viewer", Set.of(OrdersApi.READ)))))
                .build())
        .module(new OrdersApiModule());
  }

  /**
   * Serves the application, or runs a command ({@code openapi}).
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
