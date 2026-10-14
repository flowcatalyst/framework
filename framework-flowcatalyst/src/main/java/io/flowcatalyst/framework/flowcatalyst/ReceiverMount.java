package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.locks.Locks;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mounts the receiver routes in {@code boot} and checks the registrations in {@code started}. */
final class ReceiverMount {

  private static final Logger LOG = LoggerFactory.getLogger(ReceiverMount.class);

  private ReceiverMount() {}

  /**
   * Mounts {@code {path}/dispatch} and {@code {path}/scheduled-jobs} when an HTTP module provides
   * {@link Routes}. Handlers may be registered after this (they are looked up per request);
   * deliveries are answered 503 until {@link #open} has checked them.
   *
   * @return the scheduled-job runner, for the module to stop at shutdown; null when nothing was
   *     mounted
   */
  static @Nullable ScheduledJobRunner mount(App app, PlatformClient client) {
    Config config = app.config();
    ReceiverOptions options = app.get(FlowCatalystOptions.class).receivers();
    if (!options.enabled()) {
      LOG.info("flowcatalyst: the receivers are disabled (ReceiverOptions), so the receiver routes are not mounted");
      return null;
    }
    String prefix = options.path();
    Duration dispatchTimeout = options.dispatchTimeout();
    Duration jobTimeout = options.jobTimeout();
    Duration lease = options.jobLockLease();
    Duration retryDelay = options.completionRetryDelay();
    Optional<Routes> routes = app.find(Routes.class);
    if (routes.isEmpty()) {
      LOG.debug("flowcatalyst: no HTTP module, so no receivers are mounted");
      return null;
    }
    boolean requireSignature = options.requireSignature();
    if (!requireSignature) {
      LOG.warn(
          "flowcatalyst: ReceiverOptions.requireSignature is false: unsigned deliveries to {} are"
              + " accepted. Anyone who can reach these routes can run your handlers.",
          prefix);
    }
    InstantSource clock = app.get(InstantSource.class);
    ReceiverGate dispatchGate =
        new ReceiverGate(
            effective(
                config,
                FlowCatalystModule.RECEIVERS_DISPATCH_SIGNING_SECRET,
                FlowCatalystModule.RECEIVERS_SIGNING_SECRET),
            effective(
                config,
                FlowCatalystModule.RECEIVERS_DISPATCH_BEARER_TOKEN,
                FlowCatalystModule.RECEIVERS_BEARER_TOKEN),
            requireSignature,
            clock);
    ReceiverGate jobsGate =
        new ReceiverGate(
            effective(
                config,
                FlowCatalystModule.RECEIVERS_JOBS_SIGNING_SECRET,
                FlowCatalystModule.RECEIVERS_SIGNING_SECRET),
            effective(
                config,
                FlowCatalystModule.RECEIVERS_JOBS_BEARER_TOKEN,
                FlowCatalystModule.RECEIVERS_BEARER_TOKEN),
            requireSignature,
            clock);
    Receivers receivers = app.get(Receivers.class);
    Routes own = routes.get().forModule(FlowCatalystModule.ID);
    String dispatch = prefix + "/dispatch";
    own.post(
        dispatch,
        new DispatchReceiver(
            receivers, dispatchGate, client.application(), client::servicePrincipal, dispatchTimeout, dispatch));
    ScheduledJobRunner runner =
        new ScheduledJobRunner(
            client,
            client.application(),
            app.find(Locks.class).orElse(null),
            jobTimeout,
            lease,
            retryDelay);
    String jobs = prefix + "/scheduled-jobs";
    own.post(
        jobs, new ScheduledJobReceiver(receivers, jobsGate, runner, client, client::servicePrincipal, jobs));
    receivers.markMounted();
    return runner;
  }

  /**
   * Starts the background purge of old {@code fw_dispatch_once} rows when the default database has
   * that table (the module's migration creates it whenever the database module is listed).
   *
   * @return the running purge, for the module to stop at shutdown; null when there is no table
   */
  static @Nullable OncePurge startOncePurge(App app) {
    Optional<Databases> databases = app.find(Databases.class);
    if (databases.isEmpty() || databases.get().names().isEmpty()) {
      return null;
    }
    Database db = databases.get().defaultInstance();
    try {
      if (!db.tableExists(OncePurge.TABLE)) {
        LOG.info("flowcatalyst: no {} table in '{}', so no purge task", OncePurge.TABLE, db.name());
        return null;
      }
    } catch (SQLException e) {
      LOG.warn("flowcatalyst: could not look for table {}: {}; no purge task", OncePurge.TABLE, e.getMessage());
      return null;
    }
    ReceiverOptions options = app.get(FlowCatalystOptions.class).receivers();
    OncePurge purge =
        new OncePurge(
            db,
            app.find(Locks.class).orElse(null),
            options.onceRetention(),
            options.oncePurgeInterval(),
            app.get(InstantSource.class));
    purge.start();
    return purge;
  }

  /**
   * Closes registration, checks it, and opens the receivers to deliveries.
   *
   * @throws StartupException when handlers are registered but cannot be served safely
   */
  static void open(App app, PlatformClient client) {
    Receivers receivers = app.get(Receivers.class);
    receivers.seal();
    boolean jobs = receivers.hasJobHandlers();
    boolean any = receivers.hasDispatchHandlers() || jobs;
    if (!client.isEnabled()) {
      if (any) {
        LOG.warn("flowcatalyst: receivers are registered but the module is disabled; nothing is mounted");
      }
      return;
    }
    List<Problem> problems = new ArrayList<>();
    Config config = app.config();
    ReceiverOptions options = app.get(FlowCatalystOptions.class).receivers();
    if (!options.enabled()) {
      if (any) {
        LOG.warn(
            "flowcatalyst: handlers are registered (dispatch {}, scheduled jobs {}) but the receivers"
                + " are disabled (ReceiverOptions); nothing is mounted, so the platform cannot deliver"
                + " to them",
            receivers.dispatchPatterns().stream().sorted().toList(),
            receivers.jobCodes().stream().sorted().toList());
      }
      return;
    }
    if (any && !receivers.isMounted()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              null,
              "receivers are registered but no HTTP module provides Routes to mount them on"));
    }
    if (options.requireSignature()) {
      if (receivers.hasDispatchHandlers()
          && effective(
                  config,
                  FlowCatalystModule.RECEIVERS_DISPATCH_SIGNING_SECRET,
                  FlowCatalystModule.RECEIVERS_SIGNING_SECRET)
              == null) {
        problems.add(missingSecret(FlowCatalystModule.RECEIVERS_DISPATCH_SIGNING_SECRET, "dispatch"));
      }
      if (jobs
          && effective(
                  config,
                  FlowCatalystModule.RECEIVERS_JOBS_SIGNING_SECRET,
                  FlowCatalystModule.RECEIVERS_SIGNING_SECRET)
              == null) {
        problems.add(missingSecret(FlowCatalystModule.RECEIVERS_JOBS_SIGNING_SECRET, "scheduled-job"));
      }
    }
    if (!receivers.exclusiveJobCodes().isEmpty() && !hasDefaultLockInstance(app)) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              null,
              "scheduled-job handlers "
                  + receivers.exclusiveJobCodes().stream().sorted().toList()
                  + " need the overlap lock, but no lock store is given: list"
                  + " LocksModule.builder().store(...) with a default store (DatabaseLockStores or"
                  + " RedisLockStores across replicas), or register them with"
                  + " JobOptions.alwaysConcurrent()"));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    if (receivers.hasDispatchHandlers()) {
      LOG.info(
          "flowcatalyst: dispatch handlers for {}",
          receivers.dispatchPatterns().stream().sorted().toList());
    }
    if (jobs) {
      LOG.info("flowcatalyst: scheduled-job handlers for {}", receivers.jobCodes().stream().sorted().toList());
    }
    receivers.markReady();
    if (any) {
      reportServicePrincipal(client);
    }
  }

  /**
   * Resolves the service principal once in the background and logs it (contract section 4b), so a
   * token that names none is visible at start-up, not only on the first delivery. Never blocks or
   * fails start-up: deliveries resolve it again and answer 503 while it is missing.
   */
  private static void reportServicePrincipal(PlatformClient client) {
    Thread.ofVirtual()
        .name("flowcatalyst-service-principal")
        .start(
            () -> {
              try {
                LOG.info("flowcatalyst: receiver handlers run as service principal {}", client.servicePrincipal());
              } catch (RuntimeException e) {
                LOG.warn(
                    "flowcatalyst: cannot run receiver handlers yet: {}; deliveries are answered 503"
                        + " until the platform token names the application's service principal",
                    e.getMessage());
              }
            });
  }

  private static Problem missingSecret(Setting<Secret> own, String receiver) {
    return Problem.of(
        FlowCatalystModule.ID,
        own.key(),
        "required while "
            + receiver
            + " handlers are registered and ReceiverOptions.requireSignature is true (or set the"
            + " shared "
            + FlowCatalystModule.RECEIVERS_SIGNING_SECRET.key()
            + ")");
  }

  /** A receiver's own non-blank secret, else the shared non-blank one, else null. */
  private static @Nullable Secret effective(Config config, Setting<Secret> own, Setting<Secret> shared) {
    Secret mine = nonBlank(config.find(own));
    return mine != null ? mine : nonBlank(config.find(shared));
  }

  private static boolean hasDefaultLockInstance(App app) {
    Optional<Locks> locks = app.find(Locks.class);
    if (locks.isEmpty()) {
      return false;
    }
    try {
      locks.get().defaultInstance();
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  private static @Nullable Secret nonBlank(Optional<Secret> secret) {
    return secret.filter(s -> !s.reveal().isBlank()).orElse(null);
  }
}
