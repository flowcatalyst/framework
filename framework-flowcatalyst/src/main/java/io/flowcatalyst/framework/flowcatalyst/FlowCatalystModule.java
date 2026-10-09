package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FlowCatalyst module (id {@code flowcatalyst}): provides {@link PlatformClient} and adds the
 * {@code flowcatalyst} health check.
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
    app.provide(PlatformClient.class, lookup -> buildClient(lookup.get(Config.class)));
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (!client.isEnabled()) {
      LOG.info("flowcatalyst is disabled (flowcatalyst.enabled=false)");
      return;
    }
    app.health().add(ID, client::verifyCredentials);
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
        baseUrl.orElseThrow(), code, clientId.orElseThrow(), clientSecret.orElseThrow(), timeout);
  }

  private static void require(List<Problem> problems, Setting<?> setting, boolean present) {
    if (!present) {
      problems.add(Problem.of(ID, setting.key(), "required while flowcatalyst.enabled is true"));
    }
  }
}
