package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Routes;
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
   * Mounts {@code {path}/dispatch} when an HTTP module provides {@link Routes}. Handlers may be
   * registered after this (they are looked up per request); deliveries are answered 503 until
   * {@link #open} has checked them.
   */
  static void mount(App app, PlatformClient client) {
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
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    Optional<Routes> routes = app.find(Routes.class);
    if (routes.isEmpty()) {
      LOG.debug("flowcatalyst: no HTTP module, so no receivers are mounted");
      return;
    }
    boolean requireSignature = config.get(FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE);
    if (!requireSignature) {
      LOG.warn(
          "flowcatalyst: {} is false: unsigned deliveries to {} are accepted. Anyone who can reach"
              + " these routes can run your handlers.",
          FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE.key(),
          prefix);
    }
    ReceiverGate gate =
        new ReceiverGate(
            nonBlank(config.find(FlowCatalystModule.RECEIVERS_SIGNING_SECRET)),
            nonBlank(config.find(FlowCatalystModule.RECEIVERS_BEARER_TOKEN)),
            requireSignature,
            app.get(InstantSource.class));
    Receivers receivers = app.get(Receivers.class);
    Routes own = routes.get().forModule(FlowCatalystModule.ID);
    String dispatch = prefix + "/dispatch";
    own.post(
        dispatch,
        new DispatchReceiver(receivers, gate, client.application(), dispatchTimeout, dispatch));
    receivers.markMounted();
  }

  /**
   * Closes registration, checks it, and opens the receivers to deliveries.
   *
   * @throws StartupException when handlers are registered but cannot be served safely
   */
  static void open(App app, PlatformClient client) {
    Receivers receivers = app.get(Receivers.class);
    receivers.seal();
    boolean any = receivers.hasDispatchHandlers();
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
    if (any
        && config.get(FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE)
        && nonBlank(config.find(FlowCatalystModule.RECEIVERS_SIGNING_SECRET)) == null) {
      problems.add(
          Problem.of(
              FlowCatalystModule.ID,
              FlowCatalystModule.RECEIVERS_SIGNING_SECRET.key(),
              "required while receivers are registered and "
                  + FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE.key()
                  + " is true"));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    if (any) {
      LOG.info(
          "flowcatalyst: dispatch handlers for {}",
          receivers.dispatchPatterns().stream().sorted().toList());
    }
    receivers.markReady();
  }

  private static @Nullable Secret nonBlank(Optional<Secret> secret) {
    return secret.filter(s -> !s.reveal().isBlank()).orElse(null);
  }
}
