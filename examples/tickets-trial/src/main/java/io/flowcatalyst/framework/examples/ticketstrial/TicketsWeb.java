package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.admin.AdminConfigModule;
import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.LoginFailureHandler;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.database.AuthDatabaseModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import java.util.function.UnaryOperator;

/**
 * The ticket service as a web application: the write path of {@link TicketsCore}, the HTTP server,
 * sessions and login against the platform, the typed API, the admin area and the single-page
 * application built from {@code ui/apps/tickets}. {@code java -jar ... openapi --out
 * web/openapi.json} writes its OpenAPI document without a database or configuration.
 */
public final class TicketsWeb {

  /** The application code. */
  static final ApplicationCode APPLICATION = new ApplicationCode(Permissions.APPLICATION);

  private TicketsWeb() {}

  /**
   * The application's modules.
   *
   * @return a builder, not started
   */
  public static AppBuilder builder() {
    return builder(auth -> auth, TicketsApiModule.COMMENT_LIMIT);
  }

  /**
   * The application's modules, with the auth module's builder adjusted and another comment limit
   * (the end-to-end launcher switches the sign-in rate limit off, a test makes the comment limit
   * small).
   *
   * @param customise changes to the auth module's builder
   * @param commentLimit how often one person may comment
   * @return a builder, not started
   */
  public static AppBuilder builder(UnaryOperator<AuthModule.Builder> customise, RateLimitPolicy commentLimit) {
    return TicketsCore.builder()
        .module(new JettyHttpModule())
        .module(new AuthDatabaseModule())
        .module(
            customise
                .apply(AuthModule.builder())
                .application(APPLICATION)
                .postLogoutRedirect("/signed-out")
                // A failed sign-in lands on the SPA's page for it, not on the JSON envelope.
                .loginFailure(LoginFailureHandler.redirectTo("/sign-in-failed"))
                // The roles are written once (Roles) and the sync sends the same ones to the platform.
                .permissions(PermissionMapper.scopes().and(PermissionMapper.byRole(Roles.byPlatformName())))
                .build())
        // The admin area (M4d): the skeleton, the configuration page and the service's own section.
        .module(AdminModule.of(APPLICATION))
        .module(AdminConfigModule.of(APPLICATION))
        .module(new TicketsAdminModule())
        .module(new TicketsApiModule(commentLimit))
        .module(new TicketsSpaModule());
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
