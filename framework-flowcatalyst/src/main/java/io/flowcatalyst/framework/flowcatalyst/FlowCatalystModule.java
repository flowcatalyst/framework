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
 * or malformed settings do stop it, but only while {@code flowcatalyst.enabled} is true.
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

  /** {@code flowcatalyst.enabled}: whether the module talks to the platform at all. */
  public static final Setting<Boolean> ENABLED =
      Setting.bool("flowcatalyst.enabled")
          .withDefault(true)
          .describedAs("Whether the FlowCatalyst module is active; when false no setting below is needed.");

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

  /** {@code flowcatalyst.timeout}: the timeout of calls to the platform. */
  public static final Setting<Duration> TIMEOUT =
      Setting.duration("flowcatalyst.timeout")
          .withDefault(Duration.ofSeconds(30))
          .describedAs("Timeout of requests to the platform.");

  /** {@code flowcatalyst.subscription-target-base-url}: what path-style subscription targets resolve against. */
  public static final Setting<URI> SUBSCRIPTION_TARGET_BASE_URL =
      Setting.uri("flowcatalyst.subscription-target-base-url")
          .describedAs("Base URL that subscription targets given as a path (/webhooks/orders) resolve against.");

  /** {@code flowcatalyst.outbox.stale-after}: when the {@code outbox} health check goes down. */
  public static final Setting<Duration> OUTBOX_STALE_AFTER =
      Setting.duration("flowcatalyst.outbox.stale-after")
          .withDefault(Duration.ofMinutes(5))
          .describedAs(
              "The 'outbox' health check is down when the oldest undelivered outbox row is older than this.");

  /** {@code flowcatalyst.receivers.enabled}: whether the receiver routes exist at all. */
  public static final Setting<Boolean> RECEIVERS_ENABLED =
      Setting.bool("flowcatalyst.receivers.enabled")
          .withDefault(true)
          .describedAs(
              "Mount the receiver routes. False mounts neither /dispatch nor /scheduled-jobs even when"
                  + " handlers are registered (start-up warns). With true, a receiver that has no"
                  + " handler answers 404.");

  /** {@code flowcatalyst.receivers.path}: where the receiver routes are mounted. */
  public static final Setting<String> RECEIVERS_PATH =
      Setting.string("flowcatalyst.receivers.path")
          .withDefault("/fc")
          .describedAs("Path prefix of the receivers: {path}/dispatch and {path}/scheduled-jobs.");

  /** {@code flowcatalyst.receivers.signing-secret}: the secret the platform signs deliveries with. */
  public static final Setting<Secret> RECEIVERS_SIGNING_SECRET =
      Setting.secret("flowcatalyst.receivers.signing-secret")
          .describedAs(
              "The shared signing secret of the service account that signs deliveries to this application;"
                  + " used by each receiver without its own. Required for a receiver with handlers"
                  + " when require-signature is true and it has no secret of its own.");

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

  /** {@code flowcatalyst.receivers.require-signature}: whether unsigned deliveries are refused. */
  public static final Setting<Boolean> RECEIVERS_REQUIRE_SIGNATURE =
      Setting.bool("flowcatalyst.receivers.require-signature")
          .withDefault(true)
          .describedAs("Refuse unsigned deliveries. False accepts them (start-up logs a warning).");

  /** {@code flowcatalyst.receivers.dispatch-timeout}: how long a dispatch handler may run. */
  public static final Setting<Duration> RECEIVERS_DISPATCH_TIMEOUT =
      Setting.duration("flowcatalyst.receivers.dispatch-timeout")
          .withDefault(Duration.ofSeconds(25))
          .describedAs(
              "How long a dispatch-job handler may run before it is interrupted and the platform is"
                  + " answered 503. Keep it below the job's timeout (platform default 30s).");

  /** {@code flowcatalyst.receivers.job-timeout}: how long a scheduled job may run by default. */
  public static final Setting<Duration> RECEIVERS_JOB_TIMEOUT =
      Setting.duration("flowcatalyst.receivers.job-timeout")
          .withDefault(Duration.ofMinutes(10))
          .describedAs(
              "How long a scheduled job may run when its firing has no timeoutSeconds; then its"
                  + " thread is interrupted and a tracked job is reported as failed.");

  /** {@code flowcatalyst.receivers.job-lock-lease}: the lease of the scheduled-job overlap lock. */
  public static final Setting<Duration> RECEIVERS_JOB_LOCK_LEASE =
      Setting.duration("flowcatalyst.receivers.job-lock-lease")
          .withDefault(Duration.ofMinutes(10))
          .describedAs(
              "Lease of the scheduled-job overlap lock, refreshed every third of it while the job"
                  + " runs: how long a crashed run blocks the next one.");

  /** {@code flowcatalyst.receivers.completion-retry-delay}: the first wait before retrying a completion. */
  public static final Setting<Duration> RECEIVERS_COMPLETION_RETRY_DELAY =
      Setting.duration("flowcatalyst.receivers.completion-retry-delay")
          .withDefault(Duration.ofSeconds(1))
          .describedAs(
              "Wait before the first retry of a failed scheduled-job completion call; it doubles"
                  + " for each of the 3 retries.");

  /** {@code flowcatalyst.receivers.once-retention}: how long {@code fw_dispatch_once} rows are kept. */
  public static final Setting<Duration> RECEIVERS_ONCE_RETENTION =
      Setting.duration("flowcatalyst.receivers.once-retention")
          .withDefault(Duration.ofDays(30))
          .describedAs(
              "How long DispatchDelivery.once keeps its records; older rows are purged. Keep it longer"
                  + " than the platform can redeliver a job.");

  /** {@code flowcatalyst.receivers.once-purge-interval}: how often old {@code fw_dispatch_once} rows are purged. */
  public static final Setting<Duration> RECEIVERS_ONCE_PURGE_INTERVAL =
      Setting.duration("flowcatalyst.receivers.once-purge-interval")
          .withDefault(Duration.ofHours(1))
          .describedAs("How often the background purge of old fw_dispatch_once rows runs.");

  /** {@code flowcatalyst.sync.on-boot}: whether start-up also syncs definitions. */
  public static final Setting<Boolean> SYNC_ON_BOOT =
      Setting.bool("flowcatalyst.sync.on-boot")
          .withDefault(false)
          .describedAs("Sync definitions to the platform at start-up. Normally the deploy runs 'sync' instead.");

  private volatile @Nullable ScheduledJobRunner runner;
  private volatile @Nullable OncePurge purge;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.declare(ENABLED);
    app.declare(BASE_URL);
    app.declare(APPLICATION);
    app.declare(CLIENT_ID);
    app.declare(CLIENT_SECRET);
    app.declare(TIMEOUT);
    app.declare(SUBSCRIPTION_TARGET_BASE_URL);
    app.declare(SYNC_ON_BOOT);
    app.declare(OUTBOX_STALE_AFTER);
    app.declare(RECEIVERS_ENABLED);
    app.declare(RECEIVERS_PATH);
    app.declare(RECEIVERS_SIGNING_SECRET);
    app.declare(RECEIVERS_BEARER_TOKEN);
    app.declare(RECEIVERS_DISPATCH_SIGNING_SECRET);
    app.declare(RECEIVERS_DISPATCH_BEARER_TOKEN);
    app.declare(RECEIVERS_JOBS_SIGNING_SECRET);
    app.declare(RECEIVERS_JOBS_BEARER_TOKEN);
    app.declare(RECEIVERS_REQUIRE_SIGNATURE);
    app.declare(RECEIVERS_DISPATCH_TIMEOUT);
    app.declare(RECEIVERS_JOB_TIMEOUT);
    app.declare(RECEIVERS_JOB_LOCK_LEASE);
    app.declare(RECEIVERS_COMPLETION_RETRY_DELAY);
    app.declare(RECEIVERS_ONCE_RETENTION);
    app.declare(RECEIVERS_ONCE_PURGE_INTERVAL);
    app.provide(Receivers.class, lookup -> new Receivers());
    // The table behind DispatchDelivery.once; applied to the default database instance when the
    // database module is listed.
    MigrationSet.contribute(app, FlowCatalystModule.class, "db/migration/flowcatalyst");
    app.provide(PlatformClient.class, lookup -> buildClient(lookup.get(Config.class)));
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
      LOG.info("flowcatalyst is disabled (flowcatalyst.enabled=false)");
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
    if (!client.isEnabled() || !app.config().get(SYNC_ON_BOOT)) {
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
    Duration staleAfter = app.config().get(OUTBOX_STALE_AFTER);
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new StartupException(
          List.of(Problem.of(ID, OUTBOX_STALE_AFTER.key(), "must be positive")));
    }
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

  private static PlatformClient buildClient(Config config) {
    if (!config.get(ENABLED)) {
      return PlatformClient.disabled();
    }
    List<Problem> problems = new ArrayList<>();
    Optional<URI> baseUrl = config.find(BASE_URL);
    Optional<String> application = config.find(APPLICATION);
    Optional<String> clientId = config.find(CLIENT_ID);
    Optional<Secret> clientSecret = config.find(CLIENT_SECRET);
    Duration timeout = config.get(TIMEOUT);

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
    if (timeout.isZero() || timeout.isNegative()) {
      problems.add(Problem.of(ID, TIMEOUT.key(), "must be positive"));
    }
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
      problems.add(Problem.of(ID, setting.key(), "required while flowcatalyst.enabled is true"));
    }
  }
}
