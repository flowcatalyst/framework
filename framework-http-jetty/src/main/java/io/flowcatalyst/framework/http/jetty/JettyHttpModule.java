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
import io.flowcatalyst.framework.http.CorsPolicy;
import io.flowcatalyst.framework.http.Dispatcher;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.MetricsEndpoint;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.sse.EventStreams;
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
 * The HTTP server module (id {@code http}): provides {@link Routes}, {@link HttpServer} and {@link
 * EventStreams} (ended before the drain, {@link HttpServer#beforeDrain}), adds
 * the health endpoints, compiles the route table and starts Jetty in {@code started}
 * (after every module's {@code boot}), and stops it gracefully in {@code shutdown}. It claims the
 * {@code openapi} declaration-phase command ({@link OpenApi#command()}), and its {@link Routes}
 * know the operations modules declared ({@link OpenApi#declare}).
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
          .withDefault(5_242_880L)
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

  /** {@code http.sse.heartbeat}: how long an event stream may be idle before it sends a comment. */
  public static final Setting<Duration> SSE_HEARTBEAT =
      Setting.duration("http.sse.heartbeat")
          .withDefault(EventStreams.DEFAULT_HEARTBEAT)
          .describedAs("How long a server-sent event stream may be idle before it sends a heartbeat comment.");

  /** {@code http.cors.allowed-origins}: the origins allowed across origins; empty turns CORS off. */
  public static final Setting<List<String>> CORS_ALLOWED_ORIGINS =
      Setting.stringList("http.cors.allowed-origins")
          .withDefault(List.of())
          .describedAs("Origins (https://host[:port]) allowed to call across origins, or '*'; empty turns CORS off.");

  /** {@code http.cors.allowed-methods}: the methods a preflight may ask for. */
  public static final Setting<List<String>> CORS_ALLOWED_METHODS =
      Setting.stringList("http.cors.allowed-methods")
          .withDefault(CorsPolicy.DEFAULT_METHODS.stream().map(Method::name).toList())
          .describedAs("Methods a cross-origin preflight may ask for.");

  /** {@code http.cors.allowed-headers}: the request headers a preflight may ask for. */
  public static final Setting<List<String>> CORS_ALLOWED_HEADERS =
      Setting.stringList("http.cors.allowed-headers")
          .withDefault(CorsPolicy.DEFAULT_HEADERS)
          .describedAs("Request headers a cross-origin preflight may ask for.");

  /** {@code http.cors.exposed-headers}: the response headers a page may read. */
  public static final Setting<List<String>> CORS_EXPOSED_HEADERS =
      Setting.stringList("http.cors.exposed-headers")
          .withDefault(CorsPolicy.DEFAULT_EXPOSED)
          .describedAs("Response headers a cross-origin page may read.");

  /** {@code http.cors.max-age}: how long a browser may cache a preflight's answer. */
  public static final Setting<Duration> CORS_MAX_AGE =
      Setting.duration("http.cors.max-age")
          .withDefault(Duration.ofMinutes(10))
          .describedAs("How long a browser may cache the answer to a cross-origin preflight.");

  private static final Pattern LITERAL_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");

  /** What the start-up OpenAPI check found, set when the route table is built. */
  private volatile List<String> documentProblems = List.of();

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    // the server's settings are service settings (config-and-code contract, section 3.4); claiming
    // the component makes a misspelt http key fail start-up
    app.claimComponent("http");
    app.declare(HOST);
    app.declare(PORT);
    app.declare(MAX_BODY_BYTES);
    app.declare(REQUEST_TIMEOUT);
    app.declare(SHUTDOWN_GRACE);
    app.declare(METRICS_ENABLED);
    app.declare(METRICS_PATH);
    app.declare(METRICS_BEARER_TOKEN);
    app.declare(SSE_HEARTBEAT);
    app.declare(CORS_ALLOWED_ORIGINS);
    app.declare(CORS_ALLOWED_METHODS);
    app.declare(CORS_ALLOWED_HEADERS);
    app.declare(CORS_EXPOSED_HEADERS);
    app.declare(CORS_MAX_AGE);
    // the environment decides whether an SPA without index.html may start (local, test); the
    // operations modules declared with OpenApi.declare must each be bound once (OpenAPI contract)
    app.provide(
        Routes.class,
        lookup -> {
          Routes routes = new Routes(lookup.get(Config.class).environment());
          routes.declare(OpenApi.operations(lookup));
          // the document is built now, so a type it cannot describe fails start-up (in started)
          documentProblems = OpenApi.check(lookup);
          return routes;
        });
    app.provide(EventStreams.class, lookup -> new EventStreams(lookup.get(Config.class).get(SSE_HEARTBEAT)));
    // java -jar app.jar openapi [--out web/openapi.json]: the document, after register only
    app.command(OpenApi.command());
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
    // open event streams end when shutdown begins, so they do not hold the drain for its grace
    server.beforeDrain(app.get(EventStreams.class)::closeAll);
    own.get("/health/live", r -> Response.json(200, Map.of("status", "UP")));
    own.get("/health/ready", r -> ready(app, server));
    CorsPolicy cors = cors(app.config());
    if (cors != null) {
      routes.cors(cors);
    }
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
    if (!documentProblems.isEmpty()) {
      throw new StartupException(
          documentProblems.stream()
              .map(p -> Problem.of(ID, "openapi", "the OpenAPI document cannot be written: " + p))
              .toList());
    }
    for (String line : dispatcher.describeRoutes()) {
      LOG.info("route {}", line);
    }
    server.start(dispatcher, app.shutdownDrain());
  }

  @Override
  public void shutdown(App app) {
    app.get(HttpServer.class).stop();
  }

  /** The CORS policy of {@code http.cors.*}, or null when no origin is allowed (CORS off). */
  private static @Nullable CorsPolicy cors(Config config) {
    List<String> origins = config.get(CORS_ALLOWED_ORIGINS).stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    if (origins.isEmpty()) {
      return null;
    }
    List<Method> methods = new java.util.ArrayList<>();
    for (String m : config.get(CORS_ALLOWED_METHODS)) {
      Method parsed = Method.parse(m.strip().toUpperCase(java.util.Locale.ROOT));
      if (parsed == null) {
        throw new StartupException(List.of(Problem.of(ID, CORS_ALLOWED_METHODS.key(), "not a method: '" + m.strip() + "'")));
      }
      methods.add(parsed);
    }
    try {
      return new CorsPolicy(
          origins,
          methods,
          config.get(CORS_ALLOWED_HEADERS).stream().map(String::strip).toList(),
          config.get(CORS_EXPOSED_HEADERS).stream().map(String::strip).toList(),
          config.get(CORS_MAX_AGE));
    } catch (IllegalArgumentException e) {
      throw new StartupException(List.of(Problem.of(ID, "http.cors", String.valueOf(e.getMessage()))));
    }
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
