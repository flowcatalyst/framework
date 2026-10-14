package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.LoginFailureHandler;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.database.AuthDatabaseModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The orders demo as a web application: the write path of {@link OrdersDemo}, the HTTP server,
 * sessions and login against the platform, the typed orders API and the single-page application
 * built from {@code ui/apps/orders} ({@link OrdersSpaModule}). {@code java -jar ... openapi
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
    return builder(auth -> auth);
  }

  /**
   * The application's modules, with the auth module's builder adjusted (the end-to-end launcher
   * switches the sign-in rate limit off: one test run signs in far more than 20 times a minute from
   * one address).
   *
   * @param customise changes to the auth module's builder
   * @return a builder, not started
   */
  public static AppBuilder builder(UnaryOperator<AuthModule.Builder> customise) {
    return OrdersDemo.builder()
        .module(new JettyHttpModule())
        .module(new AuthDatabaseModule())
        .module(
            customise
                .apply(AuthModule.builder())
                .application(new ApplicationCode("orders-demo"))
                .postLogoutRedirect("/signed-out")
                // A failed sign-in lands on the SPA's page for it, not on the JSON envelope.
                .loginFailure(LoginFailureHandler.redirectTo("/sign-in-failed"))
                .permissions(
                    PermissionMapper.scopes()
                        .and(
                            PermissionMapper.byRole(
                                Map.of(
                                    "orders-demo:clerk", Set.of(OrdersApi.READ, OrdersApi.MANAGE),
                                    "orders-demo:viewer", Set.of(OrdersApi.READ)))))
                .build())
        .module(new OrdersApiModule())
        .module(new OrdersSpaModule());
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
