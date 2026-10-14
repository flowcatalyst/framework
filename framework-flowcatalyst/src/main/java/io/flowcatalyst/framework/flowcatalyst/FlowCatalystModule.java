package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Criticality;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FlowCatalyst module (id {@code flowcatalyst}): provides {@link PlatformClient} and adds the
 * {@code flowcatalyst} health check, and the {@code outbox} health check when the default database
 * has an {@code outbox_messages} table (the platform's outbox processor delivers that table; this
 * module only watches its backlog).
 *
 * <p>The platform is not on the request path, so a platform that cannot be reached at start-up
 * does not stop the application: the failure is logged and the health check reports it. Missing
 * or malformed settings do stop it, but only while the module is enabled.
 *
 * <p>Configuration holds the platform's address, this application's code, the service account's
 * credentials and the receiver secrets; the module's behaviour is {@link FlowCatalystOptions},
 * given in code (config-and-code contract, section 3.8). The module claims the {@code
 * flowcatalyst} component and provides the resolved {@link FlowCatalystOptions}.
 *
 * <pre>{@code
 * flowcatalyst:
 *   base-url: https://platform.example.com
 *   application: orders
 *   client-id: orders-service
 *   client-secret: ...   # from the environment: FLOWCATALYST_CLIENT_SECRET
 * }</pre>
 */
public final class FlowCatalystModule implements Module {

  private static final Logger LOG = LoggerFactory.getLogger(FlowCatalystModule.class);

  /** The module id. */
  public static final String ID = "flowcatalyst";

  /** The name of the outbox backlog health check. */
  public static final String OUTBOX_CHECK = "outbox";

  /** {@code flowcatalyst.base-url}: the platform's base URL. */
  public static final Setting<URI> BASE_URL =
      Setting.uri("flowcatalyst.base-url")
          .describedAs("Base URL of the FlowCatalyst platform. Required while enabled.");

  /** {@code flowcatalyst.application}: this application's code on the platform. */
  public static final Setting<String> APPLICATION =
      Setting.string("flowcatalyst.application")
          .describedAs("This application's code on the platform, [a-z][a-z0-9-]*. Required while enabled.");

  /** {@code flowcatalyst.client-id}: the service account's client id. */
  public static final Setting<String> CLIENT_ID =
      Setting.string("flowcatalyst.client-id")
          .describedAs("OAuth client id of the application's service account. Required while enabled.");

  /** {@code flowcatalyst.client-secret}: the service account's client secret. */
  public static final Setting<Secret> CLIENT_SECRET =
      Setting.secret("flowcatalyst.client-secret")
          .describedAs("OAuth client secret of the application's service account. Required while enabled.");

  /** {@code flowcatalyst.subscription-target-base-url}: what path-style subscription targets resolve against. */
  public static final Setting<URI> SUBSCRIPTION_TARGET_BASE_URL =
      Setting.uri("flowcatalyst.subscription-target-base-url")
          .describedAs("Base URL that subscription targets given as a path (/webhooks/orders) resolve against.");

  /** {@code flowcatalyst.receivers.signing-secret}: the secret the platform signs deliveries with. */
  public static final Setting<Secret> RECEIVERS_SIGNING_SECRET =
      Setting.secret("flowcatalyst.receivers.signing-secret")
          .describedAs(
              "The shared signing secret of the service account that signs deliveries to this application;"
                  + " used by each receiver without its own. Required for a receiver with handlers"
                  + " when ReceiverOptions.requireSignature is true and it has no secret of its own.");

  /** {@code flowcatalyst.receivers.bearer-token}: an optional second gate on deliveries. */
  public static final Setting<Secret> RECEIVERS_BEARER_TOKEN =
      Setting.secret("flowcatalyst.receivers.bearer-token")
          .describedAs(
              "Shared bearer token; when set, deliveries must also carry 'Authorization: Bearer <token>' (AND-ed with the signature).");

  /** {@code flowcatalyst.receivers.dispatch.signing-secret}: overrides the shared secret for dispatch jobs. */
  public static final Setting<Secret> RECEIVERS_DISPATCH_SIGNING_SECRET =
      Setting.secret("flowcatalyst.receivers.dispatch.signing-secret")
          .describedAs(
              "Signing secret for deliveries to the dispatch receiver (the subscription's or"
                  + " connection's service account). Falls back to flowcatalyst.receivers.signing-secret.");

  /** {@code flowcatalyst.receivers.dispatch.bearer-token}: overrides the shared bearer token for dispatch jobs. */
  public static final Setting<Secret> RECEIVERS_DISPATCH_BEARER_TOKEN =
      Setting.secret("flowcatalyst.receivers.dispatch.bearer-token")
          .describedAs(
              "Bearer token for the dispatch receiver. Falls back to flowcatalyst.receivers.bearer-token.");

  /** {@code flowcatalyst.receivers.scheduled-jobs.signing-secret}: overrides the shared secret for scheduled jobs. */
  public static final Setting<Secret> RECEIVERS_JOBS_SIGNING_SECRET =
      Setting.secret("flowcatalyst.receivers.scheduled-jobs.signing-secret")
          .describedAs(
              "Signing secret for deliveries to the scheduled-job receiver (the application's service"
                  + " account). Falls back to flowcatalyst.receivers.signing-secret.");

  /** {@code flowcatalyst.receivers.scheduled-jobs.bearer-token}: overrides the shared bearer token for scheduled jobs. */
  public static final Setting<Secret> RECEIVERS_JOBS_BEARER_TOKEN =
      Setting.secret("flowcatalyst.receivers.scheduled-jobs.bearer-token")
          .describedAs(
              "Bearer token for the scheduled-job receiver. Falls back to flowcatalyst.receivers.bearer-token.");

  private final Function<Config, FlowCatalystOptions> options;
  private volatile @Nullable ScheduledJobRunner runner;
  private volatile @Nullable OncePurge purge;

  /** The defaults ({@link FlowCatalystOptions#defaults()}). */
  public FlowCatalystModule() {
    this(FlowCatalystOptions.defaults());
  }

  /**
   * With options given in code (config-and-code contract, section 3.8).
   *
   * @param options how the module behaves
   */
  public FlowCatalystModule(FlowCatalystOptions options) {
    this(config -> options);
  }

  /**
   * With options computed from configuration once it is loaded, for an application that reads them
   * from settings of its own (for example whether a platform is there at all).
   *
   * @param options computes the options
   */
  public FlowCatalystModule(Function<Config, FlowCatalystOptions> options) {
    this.options = options;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("flowcatalyst");
    app.provide(FlowCatalystOptions.class, lookup -> options.apply(lookup.get(Config.class)));
    app.declare(BASE_URL);
    app.declare(APPLICATION);
    app.declare(CLIENT_ID);
    app.declare(CLIENT_SECRET);
    app.declare(SUBSCRIPTION_TARGET_BASE_URL);
    app.declare(RECEIVERS_SIGNING_SECRET);
    app.declare(RECEIVERS_BEARER_TOKEN);
    app.declare(RECEIVERS_DISPATCH_SIGNING_SECRET);
    app.declare(RECEIVERS_DISPATCH_BEARER_TOKEN);
    app.declare(RECEIVERS_JOBS_SIGNING_SECRET);
    app.declare(RECEIVERS_JOBS_BEARER_TOKEN);
    app.provide(Receivers.class, lookup -> new Receivers());
    // The table behind DispatchDelivery.once; applied to the default database instance when the
    // database module is listed.
    MigrationSet.contribute(app, FlowCatalystModule.class, "db/migration/flowcatalyst");
    app.provide(
        PlatformClient.class,
        lookup -> buildClient(lookup.get(Config.class), lookup.get(FlowCatalystOptions.class)));
    app.provide(
        DefinitionCatalog.class,
        lookup -> {
          PlatformClient client = lookup.get(PlatformClient.class);
          if (!client.isEnabled()) {
            return DefinitionCatalog.empty();
          }
          Config config = lookup.get(Config.class);
          return DefinitionCatalog.build(
              lookup.contributions(Definitions.Contribution.class),
              client.application(),
              config.find(SUBSCRIPTION_TARGET_BASE_URL).isPresent());
        });
    app.provide(
        DefinitionSync.class,
        lookup ->
            new DefinitionSync(lookup.get(PlatformClient.class), lookup.get(DefinitionCatalog.class)));
    app.command(new SyncCommand());
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (!client.isEnabled()) {
      LOG.info("flowcatalyst is disabled (FlowCatalystOptions.enabled is false)");
      return;
    }
    // Informational: the platform is not on the request path, so an outage must not take every
    // replica out of rotation. app.health.critical can make it decide readiness again.
    // With receiver handlers open it also needs the service principal (contract section 4b): a
    // token that does not name one runs no handler, and this is where an operator sees why.
    Receivers receivers = app.get(Receivers.class);
    app.health()
        .add(
            ID,
            () -> {
              client.verifyCredentials();
              if (receivers.isReady() && (receivers.hasDispatchHandlers() || receivers.hasJobHandlers())) {
                client.servicePrincipal();
              }
            },
            Criticality.INFORMATIONAL);
    addOutboxCheck(app);
    runner = ReceiverMount.mount(app, client);
    purge = ReceiverMount.startOncePurge(app);
    // Learn early, and in the log, whether the credentials work. Never blocks or fails start-up.
    Thread.ofVirtual()
        .name("flowcatalyst-first-token")
        .start(
            () -> {
              try {
                client.verifyCredentials();
                LOG.info("flowcatalyst: platform reachable, credentials accepted");
              } catch (RuntimeException e) {
                LOG.warn(
                    "flowcatalyst: platform not usable at start-up ({}); the application continues"
                        + " and the '{}' health check reports it",
                    e.getMessage(),
                    ID);
              }
            });
  }

  @Override
  public void started(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    ReceiverMount.open(app, client);
    if (!client.isEnabled() || !app.get(FlowCatalystOptions.class).syncOnBoot()) {
      return;
    }
    // The platform is not on the request path: a failed sync is logged, never fatal.
    try {
      app.get(DefinitionSync.class).run(DefinitionSync.Mode.APPLY);
    } catch (SyncException e) {
      LOG.error("flowcatalyst: sync on boot failed; the application continues. {}", e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("flowcatalyst: sync on boot failed; the application continues", e);
    }
  }

  @Override
  public void shutdown(App app) {
    OncePurge p = purge;
    if (p != null) {
      p.stop(app.shutdownDrain());
    }
    ScheduledJobRunner r = runner;
    if (r != null) {
      // Running jobs are interrupted and reported as failed; the platform client is still open.
      r.stop(app.shutdownDrain());
    }
  }

  /**
   * Adds the {@code outbox} health check when the default database instance has an
   * {@code outbox_messages} table. Without the database module, without an instance, or without
   * the table (an application that writes no outbox), there is nothing to watch and no check is
   * added. Migrations have run by now (they run when {@code Databases} is built), so a missing
   * table is really missing.
   */
  private static void addOutboxCheck(App app) {
    Duration staleAfter = app.get(FlowCatalystOptions.class).outboxStaleAfter();
    Optional<Databases> databases = app.find(Databases.class);
    if (databases.isEmpty() || databases.get().names().isEmpty()) {
      LOG.debug("flowcatalyst: no database instance, so no '{}' health check", OUTBOX_CHECK);
      return;
    }
    Database db = databases.get().defaultInstance();
    boolean present;
    try {
      present = db.tableExists(OutboxHealthCheck.TABLE);
    } catch (SQLException e) {
      // Unknown: add the check, which reports the database error rather than hiding it.
      LOG.warn(
          "flowcatalyst: could not look for table {} in database instance '{}': {}",
          OutboxHealthCheck.TABLE,
          db.name(),
          e.getMessage());
      present = true;
    }
    if (!present) {
      LOG.info(
          "flowcatalyst: database instance '{}' has no {} table, so no '{}' health check",
          db.name(),
          OutboxHealthCheck.TABLE,
          OUTBOX_CHECK);
      return;
    }
    // Informational, like the platform check: a stalled processor is an alert, not a reason to stop
    // serving requests.
    app.health()
        .add(
            OUTBOX_CHECK,
            new OutboxHealthCheck(db, staleAfter, app.get(InstantSource.class)),
            Criticality.INFORMATIONAL);
  }

  private static PlatformClient buildClient(Config config, FlowCatalystOptions options) {
    if (!options.enabled()) {
      return PlatformClient.disabled();
    }
    List<Problem> problems = new ArrayList<>();
    Optional<URI> baseUrl = config.find(BASE_URL);
    Optional<String> application = config.find(APPLICATION);
    Optional<String> clientId = config.find(CLIENT_ID);
    Optional<Secret> clientSecret = config.find(CLIENT_SECRET);
    Duration timeout = options.timeout();

    require(problems, BASE_URL, baseUrl.isPresent());
    require(problems, APPLICATION, application.isPresent());
    require(problems, CLIENT_ID, clientId.isPresent());
    require(problems, CLIENT_SECRET, clientSecret.isPresent());
    baseUrl.ifPresent(
        u -> {
          String scheme = u.getScheme();
          if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
              || u.getHost() == null) {
            problems.add(
                Problem.of(ID, BASE_URL.key(), "must be an absolute http or https URL"));
          }
        });
    ApplicationCode code = null;
    if (application.isPresent()) {
      try {
        code = new ApplicationCode(application.get());
      } catch (IllegalArgumentException e) {
        problems.add(Problem.of(ID, APPLICATION.key(), "must match [a-z][a-z0-9-]*"));
      }
    }
    if (!problems.isEmpty() || code == null) {
      throw new StartupException(problems);
    }
    return PlatformClient.create(
        baseUrl.orElseThrow(),
        code,
        clientId.orElseThrow(),
        clientSecret.orElseThrow(),
        timeout,
        config.find(SUBSCRIPTION_TARGET_BASE_URL).orElse(null));
  }

  private static void require(List<Problem> problems, Setting<?> setting, boolean present) {
    if (!present) {
      problems.add(Problem.of(ID, setting.key(), "required while the module is enabled (FlowCatalystOptions)"));
    }
  }
}
