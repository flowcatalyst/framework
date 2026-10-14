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
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Dispatcher;
import io.flowcatalyst.framework.http.MetricsEndpoint;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.metrics.Metrics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP server module (id {@code http}): provides {@link Routes} and {@link HttpServer}, adds
 * the health endpoints, compiles the route table and starts Jetty in {@code started}
 * (after every module's {@code boot}), and stops it gracefully in {@code shutdown}. It claims the
 * {@code openapi} declaration-phase command ({@link OpenApi#command()}), and its {@link Routes}
 * know the operations modules declared ({@link OpenApi#declare}).
 *
 * <p>Module order is irrelevant: routes are registered in {@code boot}, and the table is compiled
 * only once all modules have booted.
 *
 * <p>Configuration holds the address only ({@code http.host}, {@code http.port}) and the metrics
 * endpoint's bearer token; how the server behaves is {@link HttpOptions}, given in code
 * (config-and-code contract, section 3.5). The module claims the {@code http} component.
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

  /** {@code http.metrics.bearer-token}: when set, scrapes must present it. */
  public static final Setting<Secret> METRICS_BEARER_TOKEN =
      Setting.secret("http.metrics.bearer-token")
          .describedAs(
              "When set, the metrics endpoint (HttpOptions.withMetricsEndpoint) requires"
                  + " 'Authorization: Bearer <token>'.");

  private final Function<Config, HttpOptions> options;
  private volatile @Nullable HttpOptions resolved;

  /** The defaults ({@link HttpOptions#defaults()}). */
  public JettyHttpModule() {
    this(HttpOptions.defaults());
  }

  /**
   * With options given in code (config-and-code contract, section 3.5).
   *
   * @param options how the server behaves
   */
  public JettyHttpModule(HttpOptions options) {
    this(config -> options);
  }

  /**
   * With options computed from configuration once it is loaded, for an application that reads
   * them from settings of its own.
   *
   * @param options computes the options
   */
  public JettyHttpModule(Function<Config, HttpOptions> options) {
    this.options = options;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("http");
    app.declare(HOST);
    app.declare(PORT);
    app.declare(METRICS_BEARER_TOKEN);
    // the environment decides whether an SPA without index.html may start (local, test); the
    // operations modules declared with OpenApi.declare must each be bound once (OpenAPI contract)
    app.provide(
        Routes.class,
        lookup -> {
          Routes routes = new Routes(lookup.get(Config.class).environment());
          routes.declare(OpenApi.operations(lookup));
          return routes;
        });
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
          HttpOptions o = options.apply(config);
          resolved = o;
          return new HttpServer(
              config.get(HOST),
              port,
              o.maxBodyBytes(),
              o.requestTimeout(),
              o.shutdownGrace(),
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
    HttpOptions o = resolved;
    String path = o == null ? null : o.metricsPath();
    if (path != null) {
      Secret token =
          app.config().find(METRICS_BEARER_TOKEN).filter(t -> !t.reveal().isBlank()).orElse(null);
      if (token == null) {
        LOG.warn("http: the metrics endpoint {} is enabled without http.metrics.bearer-token", path);
      }
      Metrics metrics = app.get(Metrics.class);
      if (!metrics.readable()) {
        LOG.warn("http: the metrics endpoint is enabled but the metrics driver (MetricsOptions) records nothing");
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
