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
import io.flowcatalyst.framework.flowcatalyst.FlowCatalystModule;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.httpclient.module.HttpClientModule;
import io.flowcatalyst.framework.httpclient.module.HttpClientOptions;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.database.DatabaseLockStores;
import io.flowcatalyst.framework.locks.database.LocksDatabaseModule;
import io.flowcatalyst.framework.secrets.SecretStores;
import io.flowcatalyst.framework.secrets.SecretsModule;
import java.time.Duration;
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

  /**
   * The numbers a test changes: how often one person may comment, and how often the gauges refresh.
   *
   * @param commentLimit how often one person may comment
   * @param gaugeInterval how often the gauges of active and overdue tickets are refreshed
   */
  public record Tuning(RateLimitPolicy commentLimit, Duration gaugeInterval) {

    /**
     * The numbers of a deployment.
     *
     * @return thirty comments a minute and a refresh every thirty seconds
     */
    public static Tuning defaults() {
      return new Tuning(TicketsApiModule.COMMENT_LIMIT, TicketsReceiversModule.GAUGE_INTERVAL);
    }
  }

  private TicketsWeb() {}

  /**
   * The application's modules.
   *
   * @return a builder, not started
   */
  public static AppBuilder builder() {
    return builder(auth -> auth, Tuning.defaults());
  }

  /**
   * The application's modules, with the auth module's builder adjusted and other numbers (the
   * end-to-end launcher switches the sign-in rate limit off, a test makes the comment limit small).
   *
   * @param customise changes to the auth module's builder
   * @param tuning the numbers a test changes
   * @return a builder, not started
   */
  public static AppBuilder builder(UnaryOperator<AuthModule.Builder> customise, Tuning tuning) {
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
        // The platform: the service account, the definitions the sync sends, the receivers.
        .module(new FlowCatalystModule())
        .module(new TicketsDefinitionsModule())
        // The scheduled-job receiver takes an overlap lock; the database store is shared by replicas.
        .module(LocksModule.builder().store("main", DatabaseLockStores.database()).build())
        .module(new LocksDatabaseModule())
        // A client's key for the notification service: one environment variable per client
        // (TICKETS_NOTIFY_<CLIENT>__NOTIFY_KEY). The base URL of the service is configuration.
        .module(SecretsModule.builder().store("env", SecretStores.env("TICKETS_NOTIFY_")).build())
        .module(HttpClientModule.builder().client(NotifyAssignee.CLIENT, HttpClientOptions.defaults()).build())
        .module(new TicketsReceiversModule(tuning.gaugeInterval()))
        .module(new TicketsApiModule(tuning.commentLimit()))
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
