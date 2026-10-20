package io.flowcatalyst.framework.httpclient.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.RetryPolicy;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.httpclient.HttpClients;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The adapter: configured clients, Context propagation, metrics (http-client contract, section 5). */
class HttpClientModuleTest {

  @TempDir Path dir;

  HttpServer server;
  final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          seen.add(
              Map.of(
                  "path", exchange.getRequestURI().getRawPath(),
                  "correlation", String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id")),
                  "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                  "client", String.valueOf(exchange.getRequestHeaders().getFirst("X-FlowCatalyst-Client")),
                  "body", body));
          byte[] answer =
              exchange.getRequestURI().getPath().equals("/oauth/token")
                  ? "{\"access_token\":\"tok\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8)
                  : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, answer.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  App start(String yaml, Map<String, String> env) throws IOException {
    return start(yaml, env, new HttpClientModule());
  }

  App start(String yaml, Map<String, String> env, HttpClientModule module) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), yaml);
    return App.builder().configDirectory(dir).environment(env).module(module).start();
  }

  StartupException failure(String yaml, Map<String, String> env, HttpClientModule module) {
    Throwable t = catchThrowable(() -> start(yaml, env, module).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void aClientNamedInCodeSendsTheCorrelationIdAndIsMeasured() throws IOException {
    HttpClientModule module =
        HttpClientModule.builder()
            .client(
                "billing",
                HttpClientOptions.defaults()
                    .withRequestTimeout(Duration.ofSeconds(5))
                    .withRetry(RetryPolicy.defaults().withMaxAttempts(2).withBackoff(Duration.ofMillis(1), Duration.ofMillis(5))))
            .build();
    try (App app =
        start(
            """
            http-clients:
              instances:
                billing:
                  base-url: %s/api
            """
                .formatted(base()),
            Map.of(),
            module)) {
      Context ctx = Context.of(new ClientId("acme")).withCorrelationId(new CorrelationId("corr-7"));
      ServiceClient billing = app.get(ServiceClients.class).get("billing", ctx);
      assertThat(billing.get("/invoices/{id}").pathParam("id", "i1").send().status()).isEqualTo(200);
      assertThat(seen.get(0))
          .containsEntry("path", "/api/invoices/i1")
          .containsEntry("correlation", "corr-7")
          .containsEntry("client", "null"); // the client id is not sent to other services
      assertThat(app.get(ServiceClients.class).get("billing").get("/x").send().status()).isEqualTo(200);
      assertThat(seen.get(1)).containsEntry("correlation", "null");

      double count = 0;
      for (MetricsSnapshot.Family f : app.get(Metrics.class).snapshot().families()) {
        if (f.name().equals("fw_http_client_requests_total")) {
          for (MetricsSnapshot.Sample s : f.samples()) {
            if (s.labelValues().equals(List.of("billing", "GET", "200"))) {
              count = s.value();
            }
          }
        }
      }
      assertThat(count).isEqualTo(2.0);
    }
  }

  @Test
  void clientCredentialsComeFromConfigurationWithTheSecretFromTheEnvironment() throws IOException {
    HttpClientModule module =
        HttpClientModule.builder()
            .client("platform", HttpClientOptions.defaults().withAuth(ClientAuth.clientCredentials().withScope("api")))
            .build();
    try (App app =
        start(
            """
            http-clients:
              instances:
                platform:
                  base-url: %1$s
                  auth:
                    token-url: %1$s/oauth/token
                    client-id: orders-service
            """
                .formatted(base()),
            Map.of("HTTP_CLIENTS_INSTANCES_PLATFORM_AUTH_CLIENT_SECRET", "env-secret"),
            module)) {
      app.get(ServiceClients.class).get("platform").get("/api/me").send();
      assertThat(seen.get(0)).containsEntry("path", "/oauth/token");
      assertThat(seen.get(0).get("body")).contains("client_secret=env-secret").contains("scope=api");
      assertThat(seen.get(1)).containsEntry("authorization", "Bearer tok");
    }
  }

  @Test
  void eachAuthStrategyReadsItsCredentialsFromItsSection() throws IOException {
    HttpClientModule module =
        HttpClientModule.builder()
            .client("b", HttpClientOptions.defaults().withAuth(ClientAuth.bearer()))
            .client("u", HttpClientOptions.defaults().withAuth(ClientAuth.basic()))
            .client("h", HttpClientOptions.defaults().withAuth(ClientAuth.header("X-Api-Key")))
            .build();
    String yaml =
        """
        http-clients:
          instances:
            b: { base-url: %1$s, auth: { token: t-1 } }
            u: { base-url: %1$s, auth: { username: ann } }
            h: { base-url: %1$s }
        """
            .formatted(base());
    try (App app =
        start(yaml, Map.of("HTTP_CLIENTS_INSTANCES_U_AUTH_PASSWORD", "pw", "HTTP_CLIENTS_INSTANCES_H_AUTH_VALUE", "k-1"), module)) {
      ServiceClients clients = app.get(ServiceClients.class);
      clients.get("b").get("/1").send();
      clients.get("u").get("/2").send();
      assertThat(seen.get(0)).containsEntry("authorization", "Bearer t-1");
      assertThat(seen.get(1).get("authorization")).startsWith("Basic ");
    }
    // a strategy's credential missing fails start-up naming the key
    StartupException e = failure(yaml, Map.of("HTTP_CLIENTS_INSTANCES_U_AUTH_PASSWORD", "pw"), module);
    assertThat(e.getMessage()).contains("http-clients.instances.h.auth.value");
  }

  @Test
  void optionDefaultsAndRanges() {
    HttpClientOptions d = HttpClientOptions.defaults();
    assertThat(d)
        .isEqualTo(
            new HttpClientOptions(
                Duration.ofSeconds(5), Duration.ofSeconds(30), 10L * 1024 * 1024, RetryPolicy.defaults(), ClientAuth.none()));
    assertThat(d.withMaxResponseBytes(1).maxResponseBytes()).isEqualTo(1);
    assertThat(d.withMaxResponseBytes(1L << 30).maxResponseBytes()).isEqualTo(1L << 30);
    assertThat(d.withConnectTimeout(Duration.ofHours(1)).connectTimeout()).isEqualTo(Duration.ofHours(1));
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMaxResponseBytes(0)).withMessageContaining("maxResponseBytes");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> d.withMaxResponseBytes((1L << 30) + 1))
        .withMessageContaining("maxResponseBytes");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withConnectTimeout(Duration.ZERO)).withMessageContaining("connectTimeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> d.withRequestTimeout(Duration.ofHours(1).plusNanos(1)))
        .withMessageContaining("requestTimeout");
    assertThatIllegalArgumentException().isThrownBy(() -> ClientAuth.header("Bad Header")).withMessageContaining("header name");
    assertThatIllegalArgumentException().isThrownBy(() -> ClientAuth.clientCredentials().withScope(" "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HttpClientModule.builder().client("a", d).client("a", d))
        .withMessageContaining("already");
  }

  @Test
  void theRequestTimeoutFromCodeApplies() throws IOException {
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    HttpClientModule module =
        HttpClientModule.builder()
            .client("s", HttpClientOptions.defaults().withRequestTimeout(Duration.ofMillis(200)).withRetry(RetryPolicy.none()))
            .build();
    try (App app = start("http-clients:\n  instances:\n    s: { base-url: " + base() + " }\n", Map.of(), module)) {
      long t0 = System.nanoTime();
      Throwable t = catchThrowable(() -> app.get(ServiceClients.class).get("s").get("/slow").send());
      assertThat(t).isNotNull();
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2000));
    }
  }

  @Test
  void theRetryPolicyFromCodeDecidesTheAttempts() throws IOException {
    java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/busy",
        exchange -> {
          hits.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    HttpClientModule module =
        HttpClientModule.builder()
            .client("once", HttpClientOptions.defaults().withRetry(RetryPolicy.none()))
            .client(
                "twice",
                HttpClientOptions.defaults()
                    .withRetry(RetryPolicy.defaults().withMaxAttempts(2).withBackoff(Duration.ofMillis(1), Duration.ofMillis(2))))
            .build();
    try (App app =
        start(
            "http-clients:\n  instances:\n    once: { base-url: " + base() + " }\n    twice: { base-url: " + base() + " }\n",
            Map.of(),
            module)) {
      assertThat(app.get(ServiceClients.class).get("once").get("/busy").send().status()).isEqualTo(503);
      assertThat(hits).hasValue(1);
      hits.set(0);
      assertThat(app.get(ServiceClients.class).get("twice").get("/busy").send().status()).isEqualTo(503);
      assertThat(hits).hasValue(2);
    }
  }

  static final Setting<Duration> TIMEOUT = Setting.duration("demo.billing-timeout").withDefault(Duration.ofSeconds(30));

  @Test
  void optionsMayComeFromAnApplicationSetting() throws IOException {
    java.util.concurrent.atomic.AtomicReference<Duration> used = new java.util.concurrent.atomic.AtomicReference<>();
    io.flowcatalyst.framework.core.Module demo =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "demo";
          }

          @Override
          public void register(io.flowcatalyst.framework.core.AppBuilder app) {
            app.declare(TIMEOUT);
          }
        };
    HttpClientModule module =
        HttpClientModule.builder()
            .client(
                "billing",
                config -> {
                  used.set(config.get(TIMEOUT));
                  return HttpClientOptions.defaults().withRequestTimeout(config.get(TIMEOUT));
                })
            .build();
    Files.writeString(dir.resolve("application.yaml"), "http-clients:\n  instances:\n    billing: { base-url: " + base() + " }\n");
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("DEMO_BILLING_TIMEOUT", "7s"))
            .module(module)
            .module(demo)
            .start()) {
      assertThat(app.get(ServiceClients.class).get("billing")).isNotNull();
      assertThat(used.get()).isEqualTo(Duration.ofSeconds(7));
    }
  }

  @Test
  void unknownKeysUnnamedClientsAndBadValuesFailStartUp() {
    HttpClientModule one = HttpClientModule.builder().client("x", HttpClientOptions.defaults()).build();
    // a key no strategy reads
    assertThat(failure("http-clients:\n  instances:\n    x: { base-url: " + base() + ", timeout: 5s }\n", Map.of(), one).getMessage())
        .contains("http-clients.instances.x.timeout");
    // credentials for a strategy the code does not use
    assertThat(failure("http-clients:\n  instances:\n    x: { base-url: " + base() + ", auth: { token: t } }\n", Map.of(), one).getMessage())
        .contains("http-clients.instances.x.auth.token");
    // a section with no client of that name in code
    assertThat(
            failure(
                    "http-clients:\n  instances:\n    x: { base-url: " + base() + " }\n    legacy: { base-url: " + base() + " }\n",
                    Map.of(),
                    one)
                .getMessage())
        .contains("no client named 'legacy' in code");
    // a client named in code with no section: its base URL is missing
    assertThat(failure("", Map.of(), one).getMessage()).contains("http-clients.instances.x.base-url");
    // a bad base URL, and a token URL over plain http to a non-loopback host
    assertThat(failure("http-clients:\n  instances:\n    x: { base-url: ftp://x }\n", Map.of(), one).getMessage())
        .contains("client 'x'");
    HttpClientModule cc =
        HttpClientModule.builder().client("x", HttpClientOptions.defaults().withAuth(ClientAuth.clientCredentials())).build();
    assertThat(
            failure(
                    "http-clients:\n  instances:\n    x: { base-url: " + base() + ", auth: { token-url: http://login.example.com/t, client-id: c, client-secret: s } }\n",
                    Map.of(),
                    cc)
                .getMessage())
        .contains("client 'x'");
  }

  @Test
  void theEngineSettingsAreConfigurationWithTheDefaultsOfBefore() throws IOException {
    try (App app = start("", Map.of())) {
      assertThat(app.config().get(HttpClientModule.EVENT_LOOPS)).isEqualTo(2);
      assertThat(app.config().get(HttpClientModule.MAX_CONNECTIONS_PER_HOST)).isEqualTo(50);
      assertThat(HttpClientModule.EVENT_LOOPS.key()).isEqualTo("http-client.event-loops");
      assertThat(HttpClientModule.MAX_CONNECTIONS_PER_HOST.key()).isEqualTo("http-client.max-connections-per-host");
      assertThat(HttpClientModule.engine(app.config())).isEqualTo(new HttpClientModule.Engine(2, 50));
    }
  }

  @Test
  void theEngineSettingsComeFromAFileAndTheEnvironmentOverridesIt() throws IOException {
    String yaml = "http-client:\n  event-loops: 4\n  max-connections-per-host: 7\n";
    try (App app = start(yaml, Map.of())) {
      assertThat(HttpClientModule.engine(app.config())).isEqualTo(new HttpClientModule.Engine(4, 7));
    }
    try (App app = start(yaml, Map.of("HTTP_CLIENT_EVENT_LOOPS", "8", "HTTP_CLIENT_MAX_CONNECTIONS_PER_HOST", "9"))) {
      assertThat(HttpClientModule.engine(app.config())).isEqualTo(new HttpClientModule.Engine(8, 9));
    }
  }

  @Test
  void theEngineSettingsAcceptTheirLimits() throws IOException {
    try (App app = start("http-client: { event-loops: 1, max-connections-per-host: 1 }\n", Map.of())) {
      assertThat(HttpClientModule.engine(app.config())).isEqualTo(new HttpClientModule.Engine(1, 1));
    }
    try (App app = start("http-client: { event-loops: 64, max-connections-per-host: 1000 }\n", Map.of())) {
      assertThat(HttpClientModule.engine(app.config())).isEqualTo(new HttpClientModule.Engine(64, 1000));
    }
  }

  @Test
  void anEngineSettingOutOfRangeFailsStartUpNamingTheKeyAndWhereItCameFrom() {
    for (String bad : new String[] {"0", "65", "-1"}) {
      StartupException e = failure("", Map.of("HTTP_CLIENT_EVENT_LOOPS", bad), new HttpClientModule());
      assertThat(e.problems()).singleElement().satisfies(p -> {
        assertThat(p.key()).isEqualTo("http-client.event-loops");
        assertThat(p.source()).isEqualTo("environment variable HTTP_CLIENT_EVENT_LOOPS");
        assertThat(p.message()).contains("1 to 64").contains(bad);
      });
    }
    for (String bad : new String[] {"0", "1001"}) {
      StartupException e = failure("http-client:\n  max-connections-per-host: " + bad + "\n", Map.of(), new HttpClientModule());
      assertThat(e.problems()).singleElement().satisfies(p -> {
        assertThat(p.key()).isEqualTo("http-client.max-connections-per-host");
        assertThat(p.message()).contains("1 to 1000").contains(bad);
      });
    }
    // both wrong: both reported
    assertThat(
            failure("http-client: { event-loops: 0, max-connections-per-host: 0 }\n", Map.of(), new HttpClientModule())
                .problems())
        .hasSize(2);
  }

  @Test
  void aMisspeltHttpClientKeyStillFailsAndTheSettingsAreNoLongerRemovedKeys() {
    StartupException e = failure("http-client:\n  event-loop: 3\n", Map.of(), new HttpClientModule());
    assertThat(e.problems()).extracting(io.flowcatalyst.framework.core.Problem::key).containsExactly("http-client.event-loop");
    assertThat(e.getMessage()).doesNotContain("moved to code");
  }

  @Test
  void theRemovedClientKeysFailStartUpNamingTheCode() {
    HttpClientModule one = HttpClientModule.builder().client("x", HttpClientOptions.defaults()).build();
    StartupException e =
        failure(
            """
            http-clients:
              default: x
              instances:
                x:
                  base-url: %s
                  driver: http
                  connect-timeout: 1s
                  request-timeout: 5s
                  max-response-bytes: 100
                  retry: { max-attempts: 2 }
                  auth: { type: bearer, method: post, scope: s, audience: a, name: X-K }
            """
                .formatted(base()),
            Map.of(),
            one);
    assertThat(e.problems())
        .extracting(io.flowcatalyst.framework.core.Problem::key)
        .containsExactlyInAnyOrder(
            "http-clients.default",
            "http-clients.instances.x.driver",
            "http-clients.instances.x.connect-timeout",
            "http-clients.instances.x.request-timeout",
            "http-clients.instances.x.max-response-bytes",
            "http-clients.instances.x.retry.max-attempts",
            "http-clients.instances.x.auth.type",
            "http-clients.instances.x.auth.method",
            "http-clients.instances.x.auth.scope",
            "http-clients.instances.x.auth.audience",
            "http-clients.instances.x.auth.name");
    assertThat(e.getMessage()).contains("HttpClientOptions.withRetry(RetryPolicy)").contains("HttpClientOptions.withAuth(ClientAuth)");
  }

  @Test
  void clientsBuiltInCodeCanBeRegisteredAndAreMeasuredToo() throws IOException {
    try (App app = start("", Map.of())) {
      ServiceClients clients = app.get(ServiceClients.class);
      ServiceClient own =
          clients.register(
              ServiceOptions.builder("own", URI.create(base())).auth(Auth.bearer(() -> "platform-token")).build());
      own.get("/ping").send();
      assertThat(seen.get(0)).containsEntry("authorization", "Bearer platform-token");
      assertThat(clients.names()).containsExactly("own");
      assertThat(clients.get("own")).isSameAs(own);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> clients.register(ServiceOptions.builder("own", URI.create(base())).build()));
      assertThatIllegalArgumentException().isThrownBy(() -> clients.get("missing"));
    }
  }

  /** The service label values that have a series in the client metrics. */
  static java.util.Set<String> measuredServices(App app) {
    java.util.Set<String> out = new java.util.TreeSet<>();
    for (MetricsSnapshot.Family f : app.get(Metrics.class).snapshot().families()) {
      if (f.name().startsWith("fw_http_client_")) {
        f.samples().forEach(s -> out.add(s.labelValues().get(0)));
      }
    }
    return out;
  }

  static ServiceOptions opts(String name, String base) {
    return ServiceOptions.builder(name, URI.create(base)).build();
  }

  @Test
  void aRegisteredClientCanBeRemovedWithItsMetrics() throws IOException {
    HttpClientModule module = HttpClientModule.builder().client("fixed", HttpClientOptions.defaults()).build();
    try (App app = start("http-clients:\n  instances:\n    fixed: { base-url: " + base() + " }\n", Map.of(), module)) {
      ServiceClients clients = app.get(ServiceClients.class);
      clients.get("fixed").get("/x").send();
      ServiceClient route = clients.register(opts("route-v1", base()));
      route.get("/x").send();
      assertThat(measuredServices(app)).containsExactly("fixed", "route-v1");

      assertThat(clients.unregister("route-v1")).isTrue();
      assertThat(clients.unregister("route-v1")).isFalse();
      assertThat(clients.names()).containsExactly("fixed");
      assertThatIllegalArgumentException().isThrownBy(() -> clients.get("route-v1"));
      assertThat(measuredServices(app)).containsExactly("fixed");
      // a held reference still works, is no longer measured, and leaves no series behind
      assertThat(route.get("/x").send().status()).isEqualTo(200);
      assertThat(measuredServices(app)).containsExactly("fixed");
      // the name is free again
      clients.register(opts("route-v1", base())).get("/x").send();
      assertThat(measuredServices(app)).containsExactly("fixed", "route-v1");
      // configured clients are not removable or replaceable
      assertThatIllegalArgumentException().isThrownBy(() -> clients.unregister("fixed"));
      assertThatIllegalArgumentException().isThrownBy(() -> clients.replace(opts("fixed", base())));
    }
  }

  @Test
  void replaceSwapsTheClientAndStartsFreshMetrics() throws IOException {
    try (App app = start("", Map.of())) {
      ServiceClients clients = app.get(ServiceClients.class);
      ServiceClient first = clients.replace(opts("route", base()));
      first.get("/x").send();
      ServiceClient second = clients.replace(opts("route", base()));
      assertThat(second).isNotSameAs(first);
      assertThat(clients.get("route")).isSameAs(second);
      assertThat(clients.names()).containsExactly("route");
      assertThat(measuredServices(app)).isEmpty();
      first.get("/x").send(); // the replaced client is no longer measured
      assertThat(measuredServices(app)).isEmpty();
      second.get("/x").send();
      assertThat(measuredServices(app)).containsExactly("route");
    }
  }

  @Test
  void aRegistryBuiltWithoutAnAppRegistersMeasuresAndUnregisters() throws IOException {
    Metrics metrics = Metrics.memory(1000, MonotonicClock.system());
    ServiceClients clients = ServiceClients.create(HttpClients.create(), metrics);
    try (ServiceClients.Registration r = clients.registerScoped(opts("tmp", base()))) {
      r.client().get("/x").send();
      assertThat(clients.names()).containsExactly("tmp");
      assertThat(metrics.snapshot().families().stream().anyMatch(f -> f.name().startsWith("fw_http_client_")))
          .isTrue();
    }
    assertThat(clients.names()).isEmpty();
    assertThat(metrics.snapshot().families().stream().flatMap(f -> f.samples().stream())).isEmpty();
  }

  @Test
  void aScopedRegistrationUnregistersOnCloseOnlyWhileItIsStillTheCurrentClient() throws IOException {
    try (App app = start("", Map.of())) {
      ServiceClients clients = app.get(ServiceClients.class);
      try (ServiceClients.Registration r = clients.registerScoped(opts("tmp", base()))) {
        r.client().get("/x").send();
        assertThat(clients.names()).containsExactly("tmp");
        assertThatIllegalArgumentException().isThrownBy(() -> clients.registerScoped(opts("tmp", base())));
      }
      assertThat(clients.names()).isEmpty();
      assertThat(measuredServices(app)).isEmpty();

      ServiceClients.Registration old = clients.registerScoped(opts("tmp", base()));
      ServiceClient newer = clients.replace(opts("tmp", base()));
      old.close(); // must not remove the replacement
      assertThat(clients.get("tmp")).isSameAs(newer);
      old.close();
    }
  }

  @Test
  void concurrentRegisterUseAndRemoveLeaveNoClientsAndNoMeters() throws Exception {
    try (App app = start("", Map.of())) {
      ServiceClients clients = app.get(ServiceClients.class);
      int threads = 8;
      int rounds = 25;
      CountDownLatch go = new CountDownLatch(1);
      AtomicInteger failures = new AtomicInteger();
      List<Thread> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        String name = "route-" + (t % 2); // pairs of threads fight over the same name
        workers.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        go.await();
                        for (int i = 0; i < rounds; i++) {
                          ServiceClient c = clients.replace(opts(name, base()));
                          c.get("/x").send();
                          clients.unregister(name);
                          c.get("/x").send(); // a removed client still in use
                        }
                      } catch (Exception e) {
                        failures.incrementAndGet();
                      }
                    }));
      }
      go.countDown();
      for (Thread w : workers) {
        w.join();
      }
      assertThat(failures).hasValue(0);
      clients.unregister("route-0");
      clients.unregister("route-1");
      assertThat(clients.names()).isEmpty();
      assertThat(measuredServices(app)).isEmpty();
    }
  }

  @Test
  void theEngineIsClosedAtShutdown() throws IOException {
    ServiceClient client;
    HttpClientModule module = HttpClientModule.builder().client("a", HttpClientOptions.defaults()).build();
    try (App app = start("http-clients:\n  instances:\n    a: { base-url: " + base() + " }\n", Map.of(), module)) {
      client = app.get(ServiceClients.class).get("a");
      assertThat(client.get("/x").send().status()).isEqualTo(200);
    }
    Throwable t = catchThrowable(() -> client.get("/x").send());
    assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage("the HTTP client engine is closed");
  }
}
