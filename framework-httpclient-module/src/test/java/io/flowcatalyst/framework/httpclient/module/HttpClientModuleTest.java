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
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
    Files.writeString(dir.resolve("application.yaml"), yaml);
    return App.builder().configDirectory(dir).environment(env).module(new HttpClientModule()).start();
  }

  @Test
  void aConfiguredClientSendsTheCorrelationIdAndIsMeasured() throws IOException {
    try (App app =
        start(
            """
            http-clients:
              instances:
                billing:
                  base-url: %s/api
                  request-timeout: 5s
                  retry: { max-attempts: 2, initial-backoff: 1ms }
            """
                .formatted(base()),
            Map.of())) {
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
    try (App app =
        start(
            """
            http-clients:
              instances:
                platform:
                  base-url: %1$s
                  auth:
                    type: client-credentials
                    token-url: %1$s/oauth/token
                    client-id: orders-service
                    scope: api
            """
                .formatted(base()),
            Map.of("HTTP_CLIENTS_INSTANCES_PLATFORM_AUTH_CLIENT_SECRET", "env-secret"))) {
      app.get(ServiceClients.class).get("platform").get("/api/me").send();
      assertThat(seen.get(0)).containsEntry("path", "/oauth/token");
      assertThat(seen.get(0).get("body")).contains("client_secret=env-secret").contains("scope=api");
      assertThat(seen.get(1)).containsEntry("authorization", "Bearer tok");
    }
  }

  @Test
  void unknownKeysAndBadValuesFailStartUp() throws IOException {
    for (String instance :
        new String[] {
          "{ base-url: %s, timeout: 5s }",
          "{ base-url: %s, auth: { type: magic } }",
          "{ base-url: %s, auth: { type: none, token: x } }",
          "{ base-url: ftp://x }",
          "{ base-url: %s, retry: { max-attempts: 0 } }",
          "{ base-url: %s, auth: { type: client-credentials, token-url: http://login.example.com/t, client-id: c, client-secret: s } }"
        }) {
      Throwable t =
          catchThrowable(
              () ->
                  start(
                          "http-clients:\n  instances:\n    x: " + instance.formatted(base()) + "\n",
                          Map.of())
                      .close());
      assertThat(t).as(instance).isInstanceOf(StartupException.class);
    }
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

  @Test
  void theEngineIsClosedAtShutdown() throws IOException {
    ServiceClient client;
    try (App app = start("http-clients:\n  instances:\n    a: { base-url: " + base() + " }\n", Map.of())) {
      client = app.get(ServiceClients.class).get("a");
      assertThat(client.get("/x").send().status()).isEqualTo(200);
    }
    Throwable t = catchThrowable(() -> client.get("/x").send());
    assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage("the HTTP client engine is closed");
  }
}
