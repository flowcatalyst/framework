package io.flowcatalyst.framework.http.jetty;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Criticality;
import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Dispatcher;
import io.flowcatalyst.framework.http.MetricsEndpoint;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.metrics.Metrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP server module (id {@code http}): provides {@link Routes} and {@link HttpServer}, adds
 * the health endpoints, compiles the route table and starts Jetty in {@code started}
 * (after every module's {@code boot}), and stops it gracefully in {@code shutdown}.
 *
 * <p>Module order is irrelevant: routes are registered in {@code boot}, and the table is compiled
 * only once all modules have booted.
 */
public final class JettyHttpModule implements Module {

  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpModule.class);

  /** The module id. */
  public static final String ID = "http";

  /** {@code http.host}: the address to bind. */
  public static final Setting<String> HOST =
      Setting.string("http.host").withDefault("0.0.0.0").describedAs("Address the HTTP server binds to.");

  /** {@code http.port}: the port; 0 picks a free one. */
  public static final Setting<Integer> PORT =
      Setting.integer("http.port")
          .withDefault(8080)
          .describedAs("Port the HTTP server listens on; 0 picks any free port.");

  /** {@code http.max-body-bytes}: the largest request body accepted. */
  public static final Setting<Long> MAX_BODY_BYTES =
      Setting.longValue("http.max-body-bytes")
          .withDefault(1_048_576L)
          .describedAs("Largest request body accepted, in bytes; larger requests get 413.");

  /** {@code http.request-timeout}: idle timeout for reads. */
  public static final Setting<Duration> REQUEST_TIMEOUT =
      Setting.duration("http.request-timeout")
          .withDefault(Duration.ofSeconds(30))
          .describedAs("Idle timeout of a connection while a request is read or a response written.");

  /** {@code http.shutdown-grace}: how long in-flight requests may finish on shutdown. */
  public static final Setting<Duration> SHUTDOWN_GRACE =
      Setting.duration("http.shutdown-grace")
          .describedAs("Time in-flight requests may take on shutdown; defaults to app.shutdown.drain.");

  /** {@code http.metrics.enabled}: whether the Prometheus endpoint is mounted. */
  public static final Setting<Boolean> METRICS_ENABLED =
      Setting.bool("http.metrics.enabled")
          .withDefault(false)
          .describedAs("Mount the Prometheus metrics endpoint at http.metrics.path.");

  /** {@code http.metrics.path}: where the Prometheus endpoint is mounted. */
  public static final Setting<String> METRICS_PATH =
      Setting.string("http.metrics.path")
          .withDefault("/metrics")
          .describedAs("Path of the Prometheus metrics endpoint: literal segments, no trailing slash.");

  /** {@code http.metrics.bearer-token}: when set, scrapes must present it. */
  public static final Setting<Secret> METRICS_BEARER_TOKEN =
      Setting.secret("http.metrics.bearer-token")
          .describedAs("When set, the metrics endpoint requires 'Authorization: Bearer <token>'.");

  private static final Pattern LITERAL_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.declare(HOST);
    app.declare(PORT);
    app.declare(MAX_BODY_BYTES);
    app.declare(REQUEST_TIMEOUT);
    app.declare(SHUTDOWN_GRACE);
    app.declare(METRICS_ENABLED);
    app.declare(METRICS_PATH);
    app.declare(METRICS_BEARER_TOKEN);
    // the environment decides whether an SPA without index.html may start (local, test)
    app.provide(Routes.class, lookup -> new Routes(lookup.get(Config.class).environment()));
    app.provide(
        HttpServer.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          int port = config.get(PORT);
          if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("http.port must be between 0 and 65535");
          }
          long max = config.get(MAX_BODY_BYTES);
          if (max < 0) {
            throw new IllegalArgumentException("http.max-body-bytes must not be negative");
          }
          return new HttpServer(
              config.get(HOST),
              port,
              max,
              config.get(REQUEST_TIMEOUT),
              config.find(SHUTDOWN_GRACE).orElse(null),
              lookup.get(MonotonicClock.class));
        });
  }

  @Override
  public void boot(App app) {
    Routes routes = app.get(Routes.class);
    HttpServer server = app.get(HttpServer.class);
    Routes own = routes.forModule(ID);
    own.get("/health/live", r -> Response.json(200, Map.of("status", "UP")));
    own.get("/health/ready", r -> ready(app, server));
    if (app.config().get(METRICS_ENABLED)) {
      String path = app.config().get(METRICS_PATH);
      if (!LITERAL_PATH.matcher(path).matches()) {
        throw new StartupException(
            List.of(
                Problem.of(ID, METRICS_PATH.key(), "must be literal segments such as /metrics")));
      }
      Secret token =
          app.config().find(METRICS_BEARER_TOKEN).filter(t -> !t.reveal().isBlank()).orElse(null);
      if (token == null) {
        LOG.warn("http: the metrics endpoint {} is enabled without http.metrics.bearer-token", path);
      }
      Metrics metrics = app.get(Metrics.class);
      if (!metrics.readable()) {
        LOG.warn("http: the metrics endpoint is enabled but app.metrics.driver records nothing");
      }
      own.get(path, MetricsEndpoint.handler(metrics, token));
    }
  }

  @Override
  public void started(App app) {
    Routes routes = app.get(Routes.class);
    HttpServer server = app.get(HttpServer.class);
    ContextResolver resolver = app.find(ContextResolver.class).orElse(null);
    Dispatcher dispatcher =
        routes.compile(resolver, app.get(MonotonicClock.class), app.get(Metrics.class));
    for (String line : dispatcher.describeRoutes()) {
      LOG.info("route {}", line);
    }
    server.start(dispatcher, app.shutdownDrain());
  }

  @Override
  public void shutdown(App app) {
    app.get(HttpServer.class).stop();
  }

  private static Response ready(App app, HttpServer server) {
    if (server.isDraining()) {
      return Response.json(503, Map.of("status", "DOWN", "reason", "shutting down"));
    }
    HealthReport report = app.health().check();
    Map<String, Object> checks = new LinkedHashMap<>();
    for (Map.Entry<String, HealthResult> e : report.results().entrySet()) {
      Map<String, Object> one = new LinkedHashMap<>();
      switch (e.getValue()) {
        case HealthResult.Up up -> one.put("status", "UP");
        case HealthResult.Down down -> {
          one.put("status", "DOWN");
          one.put("reason", down.reason());
        }
        case HealthResult.TimedOut timedOut -> one.put("status", "TIMED_OUT");
      }
      if (report.criticality(e.getKey()) == Criticality.INFORMATIONAL) {
        one.put("informational", true);
      }
      checks.put(e.getKey(), one);
    }
    // UP: every check up. DEGRADED: only informational checks are not up (still ready, 200).
    // DOWN: a critical check is not up (503).
    String status = report.isUp() ? "UP" : report.isReady() ? "DEGRADED" : "DOWN";
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status);
    body.put("checks", checks);
    return Response.json(report.isReady() ? 200 : 503, body);
  }
}
