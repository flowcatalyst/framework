package io.flowcatalyst.framework.http.jetty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JettyHttpModuleTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path dir;

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> get(App app, String path) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(Apps.url(app, path))).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> r) throws Exception {
    return MAPPER.readTree(r.body());
  }

  @Test
  void answersARealRequestThroughTheFullPipeline() throws Exception {
    try (App app =
        Apps.start(dir, Apps.env(), r -> r.get("/orders/{id}", req -> Response.ok(Map.of("id", req.pathParam("id"), "q", String.valueOf(req.query("q"))))))) {
      HttpResponse<String> r =
          client.send(
              HttpRequest.newBuilder(URI.create(Apps.url(app, "/orders/a%20b?q=1+2"))).header("X-Correlation-Id", "c-1").build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.headers().firstValue("content-type")).hasValue("application/json");
      assertThat(r.headers().firstValue("x-correlation-id")).hasValue("c-1");
      assertThat(json(r).get("id").asString()).isEqualTo("a b");
      assertThat(json(r).get("q").asString()).isEqualTo("1 2");
      assertThat(r.headers().firstValue("content-length")).isPresent();
    }
  }

  @Test
  void moduleOrderIsIrrelevantTheJettyModuleMayComeFirst() throws Exception {
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Apps.env())
            .module(new JettyHttpModule())
            .module(Apps.routes("late", r -> r.get("/late", req -> Response.text(200, "late"))))
            .start()) {
      assertThat(get(app, "/late").statusCode()).isEqualTo(200);
      assertThat(get(app, "/health/live").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void neverSendsAServerHeaderOrVersion() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> r.get("/x", req -> Response.text(200, "x")))) {
      HttpResponse<String> ok = get(app, "/x");
      HttpResponse<String> notFound = get(app, "/nope");
      for (HttpResponse<String> r : List.of(ok, notFound)) {
        assertThat(r.headers().firstValue("server")).isEmpty();
        assertThat(r.headers().firstValue("x-powered-by")).isEmpty();
        assertThat(r.body()).doesNotContain("Jetty").doesNotContain("at org.");
      }
      assertThat(notFound.statusCode()).isEqualTo(404);
      assertThat(json(notFound).get("error").asString()).isEqualTo("NOT_FOUND");
    }
  }

  @Test
  void handlersRunOnVirtualThreads() throws Exception {
    try (App app =
        Apps.start(dir, Apps.env(), r -> r.get("/v", req -> Response.ok(Map.of("virtual", Thread.currentThread().isVirtual()))))) {
      assertThat(json(get(app, "/v")).get("virtual").asBoolean()).isTrue();
    }
  }

  @Test
  void portZeroBindsAFreePortAndReportsIt() throws Exception {
    try (App a = Apps.start(dir, Apps.env(), r -> {});
        App b = Apps.start(dir, Apps.env(), r -> {})) {
      int pa = a.get(HttpServer.class).port();
      int pb = b.get(HttpServer.class).port();
      assertThat(pa).isPositive();
      assertThat(pb).isPositive().isNotEqualTo(pa);
      assertThat(get(a, "/health/live").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void aBodyOverTheLimitByContentLengthIs413AndTheConnectionCloses() throws Exception {
    try (App app =
        Apps.start(dir, Apps.env("HTTP_MAX_BODY_BYTES", "100"), r -> r.post("/in", req -> Response.ok(Map.of("n", req.bodyBytes().length))))) {
      int port = app.get(HttpServer.class).port();
      try (RawHttp raw = new RawHttp(port)) {
        String body = "x".repeat(1000);
        raw.send("POST /in HTTP/1.1\r\nHost: l\r\nContent-Length: 1000\r\n\r\n" + body);
        RawHttp.Reply reply = raw.read();
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.headers().get("connection")).isEqualToIgnoringCase("close");
        assertThat(MAPPER.readTree(reply.body()).get("error").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(raw.isClosedByPeer()).isTrue();
      }
    }
  }

  @Test
  void aBodyOverTheLimitWhileReadingIs413() throws Exception {
    try (App app =
        Apps.start(dir, Apps.env("HTTP_MAX_BODY_BYTES", "100"), r -> r.post("/in", req -> Response.ok(Map.of("n", req.bodyBytes().length))))) {
      // no Content-Length: a chunked body
      HttpRequest chunked =
          HttpRequest.newBuilder(URI.create(Apps.url(app, "/in")))
              .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream("y".repeat(5000).getBytes(StandardCharsets.UTF_8))))
              .build();
      HttpResponse<String> r = client.send(chunked, HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).isEqualTo(413);
      assertThat(json(r).get("error").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }
  }

  @Test
  void aBodyWithinTheLimitIsRead() throws Exception {
    try (App app =
        Apps.start(dir, Apps.env("HTTP_MAX_BODY_BYTES", "100"), r -> r.post("/in", req -> Response.ok(Map.of("n", req.bodyBytes().length))))) {
      HttpResponse<String> r =
          client.send(
              HttpRequest.newBuilder(URI.create(Apps.url(app, "/in"))).POST(HttpRequest.BodyPublishers.ofString("z".repeat(100))).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(json(r).get("n").asInt()).isEqualTo(100);
    }
  }

  @Test
  void headRequestsHaveNoBody() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> r.get("/x", req -> Response.text(200, "hello")))) {
      HttpResponse<String> r =
          client.send(
              HttpRequest.newBuilder(URI.create(Apps.url(app, "/x"))).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).isEmpty();
    }
  }

  @Test
  void anUnsupportedMethodIs405() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> r.get("/x", req -> Response.text(200, "hello")));
        RawHttp raw = new RawHttp(app.get(HttpServer.class).port())) {
      raw.send("TRACE /x HTTP/1.1\r\nHost: l\r\n\r\n");
      assertThat(raw.read().status()).isEqualTo(405);
    }
  }

  @Test
  void streamedBodiesAreFlushedPerWrite() throws Exception {
    CountDownLatch clientSawFirst = new CountDownLatch(1);
    try (App app =
        Apps.start(
            dir,
            Apps.env(),
            r ->
                r.get(
                    "/s",
                    req ->
                        Response.stream(
                            200,
                            "text/event-stream",
                            out -> {
                              out.write("first\n".getBytes(StandardCharsets.UTF_8));
                              out.flush();
                              try {
                                if (!clientSawFirst.await(5, TimeUnit.SECONDS)) {
                                  throw new IOException("client never saw the first chunk");
                                }
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                              out.write("second\n".getBytes(StandardCharsets.UTF_8));
                            })))) {
      HttpResponse<InputStream> r =
          client.send(HttpRequest.newBuilder(URI.create(Apps.url(app, "/s"))).build(), HttpResponse.BodyHandlers.ofInputStream());
      assertThat(r.headers().firstValue("content-type")).hasValue("text/event-stream");
      try (InputStream in = r.body()) {
        byte[] first = in.readNBytes(6);
        assertThat(new String(first, StandardCharsets.UTF_8)).isEqualTo("first\n");
        clientSawFirst.countDown();
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("second\n");
      }
    }
  }

  // ---- health -------------------------------------------------------------------------------

  @Test
  void liveIsAlwaysUp() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> {})) {
      HttpResponse<String> r = get(app, "/health/live");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).isEqualTo("{\"status\":\"UP\"}");
    }
  }

  @Test
  void readyReportsEveryCheck() throws Exception {
    io.flowcatalyst.framework.core.Module checks =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "checks";
          }

          @Override
          public void boot(App app) {
            app.health().add("good", () -> {});
          }
        };
    try (App app = Apps.start(dir, Apps.env(), r -> {}, checks)) {
      HttpResponse<String> r = get(app, "/health/ready");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).isEqualTo("{\"status\":\"UP\",\"checks\":{\"good\":{\"status\":\"UP\"}}}");
    }
  }

  @Test
  void readyIs503WithTheReasonWhenACheckIsDownOrTimesOut() throws Exception {
    io.flowcatalyst.framework.core.Module checks =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "checks";
          }

          @Override
          public void boot(App app) {
            app.health().add("bad", () -> {
              throw new IllegalStateException("db unreachable");
            });
            app.health().add("slow", () -> Thread.sleep(5000));
            app.health().add("good", () -> {});
          }
        };
    try (App app = Apps.start(dir, Apps.env("APP_HEALTH_TIMEOUT", "200ms"), r -> {}, checks)) {
      HttpResponse<String> r = get(app, "/health/ready");
      assertThat(r.statusCode()).isEqualTo(503);
      JsonNode body = json(r);
      assertThat(body.get("status").asString()).isEqualTo("DOWN");
      assertThat(body.get("checks").get("bad").get("status").asString()).isEqualTo("DOWN");
      assertThat(body.get("checks").get("bad").get("reason").asString()).contains("db unreachable");
      assertThat(body.get("checks").get("slow").get("status").asString()).isEqualTo("TIMED_OUT");
      assertThat(body.get("checks").get("good").get("status").asString()).isEqualTo("UP");
      assertThat(get(app, "/health/live").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void readyIs200AndDegradedWhenOnlyAnInformationalCheckIsDown() throws Exception {
    io.flowcatalyst.framework.core.Module checks =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "checks";
          }

          @Override
          public void boot(App app) {
            app.health()
                .add(
                    "platform",
                    () -> {
                      throw new IllegalStateException("platform unreachable");
                    },
                    io.flowcatalyst.framework.core.Criticality.INFORMATIONAL);
            app.health().add("good", () -> {});
          }
        };
    try (App app = Apps.start(dir, Apps.env(), r -> {}, checks)) {
      HttpResponse<String> r = get(app, "/health/ready");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body())
          .isEqualTo(
              "{\"status\":\"DEGRADED\",\"checks\":{\"platform\":{\"status\":\"DOWN\","
                  + "\"reason\":\"IllegalStateException: platform unreachable\",\"informational\":true},"
                  + "\"good\":{\"status\":\"UP\"}}}");
    }
    // The same check made critical by configuration takes the application out of rotation.
    try (App app =
        Apps.start(dir, Apps.env("APP_HEALTH_CRITICAL", "platform"), r -> {}, checks)) {
      HttpResponse<String> r = get(app, "/health/ready");
      assertThat(r.statusCode()).isEqualTo(503);
      JsonNode body = json(r);
      assertThat(body.get("status").asString()).isEqualTo("DOWN");
      assertThat(body.get("checks").get("platform").has("informational")).isFalse();
    }
  }

  @Test
  void healthNeedsNoAuthenticationEvenWhenAResolverThrows() throws Exception {
    io.flowcatalyst.framework.core.Module resolver =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "auth";
          }

          @Override
          public void register(io.flowcatalyst.framework.core.AppBuilder app) {
            app.provide(
                io.flowcatalyst.framework.http.ContextResolver.class,
                l ->
                    r -> {
                      throw new io.flowcatalyst.framework.http.HttpException(401, "UNAUTHENTICATED", "no");
                    });
          }
        };
    try (App app =
        Apps.start(dir, Apps.env(), r -> r.get("/secure", req -> Response.noContent(), RouteOptions.requiresContext()), resolver)) {
      assertThat(get(app, "/health/ready").statusCode()).isEqualTo(200);
      assertThat(get(app, "/secure").statusCode()).isEqualTo(401);
    }
  }

  // ---- start-up failures --------------------------------------------------------------------

  @Test
  void bindFailureFailsStartUp() throws Exception {
    try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Throwable t =
          catchThrowable(() -> Apps.start(dir, Apps.env("HTTP_PORT", String.valueOf(taken.getLocalPort())), r -> {}).close());
      assertThat(t).isInstanceOf(StartupException.class);
      List<Problem> problems = ((StartupException) t).problems();
      assertThat(problems).hasSize(1);
      assertThat(problems.getFirst().module()).isEqualTo("http");
      assertThat(problems.getFirst().message()).contains("cannot listen on 127.0.0.1:" + taken.getLocalPort());
    }
  }

  @Test
  void duplicateRoutesAndMissingResolversAreReportedAsStartUpProblems() {
    Throwable t =
        catchThrowable(
            () ->
                Apps.start(
                        dir,
                        Apps.env(),
                        r -> {
                          r.get("/a/{x}", req -> Response.noContent());
                          r.get("/a/{y}", req -> Response.noContent());
                          r.get("/secure", req -> Response.noContent(), RouteOptions.requiresContext());
                        })
                    .close());
    assertThat(t).isInstanceOf(StartupException.class);
    List<Problem> problems = ((StartupException) t).problems();
    assertThat(problems).hasSize(2);
    assertThat(problems).extracting(Problem::message).anyMatch(m -> m.contains("duplicate route")).anyMatch(m -> m.contains("ContextResolver"));
    assertThat(problems).extracting(Problem::module).containsOnly("test");
  }

  @Test
  void aHealthRouteOfTheApplicationCollidesWithTheBuiltInOne() {
    Throwable t = catchThrowable(() -> Apps.start(dir, Apps.env(), r -> r.get("/health/live", req -> Response.noContent())).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems().getFirst().message()).contains("'http'").contains("'test'");
  }

  @Test
  void routesCannotBeRegisteredOnceListening() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> {})) {
      Routes routes = app.get(Routes.class);
      assertThatIllegalStateException().isThrownBy(() -> routes.get("/late", req -> Response.noContent()));
    }
  }

  @Test
  void invalidSettingsFailStartUp() {
    Throwable t = catchThrowable(() -> Apps.start(dir, Apps.env("HTTP_PORT", "70000"), r -> {}).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("http.port");
    assertThat(catchThrowable(() -> Apps.start(dir, Apps.env("HTTP_PORT", "abc"), r -> {}).close())).isInstanceOf(StartupException.class);
  }

  @Test
  void aValidationErrorFromAHandlerIsAnEnvelopeOverTheWire() throws Exception {
    try (App app =
        Apps.start(
            dir,
            Apps.env(),
            r ->
                r.get(
                    "/v",
                    req -> {
                      throw ValidationException.of("f", "BAD", "is bad");
                    }))) {
      HttpResponse<String> r = get(app, "/v");
      assertThat(r.statusCode()).isEqualTo(400);
      assertThat(json(r).get("details").get("f").asString()).isEqualTo("BAD: is bad");
    }
  }

  @Test
  void readTimeoutAppliesToASilentClient() throws Exception {
    try (App app = Apps.start(dir, Apps.env("HTTP_REQUEST_TIMEOUT", "300ms"), r -> r.post("/in", req -> Response.ok(Map.of("n", req.bodyBytes().length))));
        RawHttp raw = new RawHttp(app.get(HttpServer.class).port())) {
      raw.send("POST /in HTTP/1.1\r\nHost: l\r\nContent-Length: 10\r\n\r\nabc"); // promises 10, sends 3
      long start = System.nanoTime();
      boolean closed;
      try {
        RawHttp.Reply reply = raw.read();
        closed = reply.status() >= 400;
      } catch (IOException e) {
        closed = true;
      }
      assertThat(closed).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
  }

  @Test
  void theMetricsEndpointIsOffByDefault() throws Exception {
    try (App app = Apps.start(dir, Apps.env(), r -> {})) {
      assertThat(get(app, "/metrics").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void theMetricsEndpointServesRealRequestMetricsBehindItsToken() throws Exception {
    try (App app =
        Apps.start(
            dir,
            Apps.env(
                "HTTP_METRICS_ENABLED", "true",
                "HTTP_METRICS_PATH", "/internal/metrics",
                "HTTP_METRICS_BEARER_TOKEN", "scrape-me"),
            r -> r.get("/ping", req -> Response.text(200, "pong")))) {
      assertThat(get(app, "/ping").statusCode()).isEqualTo(200);
      assertThat(get(app, "/internal/metrics").statusCode()).isEqualTo(401);
      HttpResponse<String> scraped =
          client.send(
              HttpRequest.newBuilder(URI.create(Apps.url(app, "/internal/metrics")))
                  .header("Authorization", "Bearer scrape-me")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(scraped.statusCode()).isEqualTo(200);
      assertThat(scraped.headers().firstValue("Content-Type"))
          .hasValue("text/plain; version=0.0.4; charset=utf-8");
      assertThat(scraped.body())
          .contains("fw_http_server_requests_total{method=\"GET\",route=\"/ping\",status=\"200\"} 1\n");
    }
  }

  @Test
  void aMetricsPathThatIsNotLiteralFailsStartUp() {
    Throwable t =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                Apps.start(
                        dir,
                        Apps.env("HTTP_METRICS_ENABLED", "true", "HTTP_METRICS_PATH", "/m/{x}"),
                        r -> {})
                    .close());
    assertThat(t).isInstanceOf(io.flowcatalyst.framework.core.StartupException.class);
    assertThat(t.getMessage()).contains("http.metrics.path");
  }
}
