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
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mounts the receiver routes in {@code boot} and checks the registrations in {@code started}. */
final class ReceiverMount {

  private static final Logger LOG = LoggerFactory.getLogger(ReceiverMount.class);

  /** Literal segments only: the prefix is not a pattern. */
  private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");

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
    List<Problem> problems = new ArrayList<>();
    String prefix = config.get(FlowCatalystModule.RECEIVERS_PATH);
    if (!PATH.matcher(prefix).matches()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_PATH.key(),
              "must be a path of literal segments starting with '/' and without a trailing '/'"));
    }
    Duration dispatchTimeout = config.get(FlowCatalystModule.RECEIVERS_DISPATCH_TIMEOUT);
    if (dispatchTimeout.isZero() || dispatchTimeout.isNegative()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_DISPATCH_TIMEOUT.key(),
              "must be positive"));
    }
    Duration jobTimeout = config.get(FlowCatalystModule.RECEIVERS_JOB_TIMEOUT);
    if (jobTimeout.isZero() || jobTimeout.isNegative()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID, FlowCatalystModule.RECEIVERS_JOB_TIMEOUT.key(), "must be positive"));
    }
    Duration lease = config.get(FlowCatalystModule.RECEIVERS_JOB_LOCK_LEASE);
    if (lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofHours(24)) > 0) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_JOB_LOCK_LEASE.key(),
              "must be from 1s to 24h"));
    }
    Duration retryDelay = config.get(FlowCatalystModule.RECEIVERS_COMPLETION_RETRY_DELAY);
    if (retryDelay.isNegative() || retryDelay.compareTo(Duration.ofMinutes(1)) > 0) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_COMPLETION_RETRY_DELAY.key(),
              "must be from 0 to 1m"));
    }
    Duration retention = config.get(FlowCatalystModule.RECEIVERS_ONCE_RETENTION);
    if (retention.isZero() || retention.isNegative()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_ONCE_RETENTION.key(),
              "must be positive"));
    }
    Duration purgeInterval = config.get(FlowCatalystModule.RECEIVERS_ONCE_PURGE_INTERVAL);
    if (purgeInterval.isZero() || purgeInterval.isNegative()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_ONCE_PURGE_INTERVAL.key(),
              "must be positive"));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    Optional<Routes> routes = app.find(Routes.class);
    if (routes.isEmpty()) {
      LOG.debug("flowcatalyst: no HTTP module, so no receivers are mounted");
      return null;
    }
    boolean requireSignature = config.get(FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE);
    if (!requireSignature) {
      LOG.warn(
          "flowcatalyst: {} is false: unsigned deliveries to {} are accepted. Anyone who can reach"
              + " these routes can run your handlers.",
          FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE.key(),
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
        new DispatchReceiver(receivers, dispatchGate, client.application(), dispatchTimeout, dispatch));
    ScheduledJobRunner runner =
        new ScheduledJobRunner(
            client,
            client.application(),
            app.find(Locks.class).orElse(null),
            jobTimeout,
            lease,
            retryDelay);
    String jobs = prefix + "/scheduled-jobs";
    own.post(jobs, new ScheduledJobReceiver(receivers, jobsGate, runner, client, jobs));
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
    Config config = app.config();
    OncePurge purge =
        new OncePurge(
            db,
            app.find(Locks.class).orElse(null),
            config.get(FlowCatalystModule.RECEIVERS_ONCE_RETENTION),
            config.get(FlowCatalystModule.RECEIVERS_ONCE_PURGE_INTERVAL),
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
    if (any && !receivers.isMounted()) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              null,
              "receivers are registered but no HTTP module provides Routes to mount them on"));
    }
    if (config.get(FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE)) {
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
    if (jobs && !hasDefaultLockInstance(app)) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              null,
              "scheduled-job handlers "
                  + receivers.jobCodes().stream().sorted().toList()
                  + " need the overlap lock, but no lock instance is configured: list the locks"
                  + " module with a default instance (driver database or redis across replicas)"));
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
  }

  private static Problem missingSecret(Setting<Secret> own, String receiver) {
    return Problem.of(
        FlowCatalystModule.ID,
        own.key(),
        "required while "
            + receiver
            + " handlers are registered and "
            + FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE.key()
            + " is true (or set the shared "
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
