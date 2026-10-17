package io.flowcatalyst.framework.examples.ticketstrial;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket service for the browser end-to-end suite ({@code ui/apps/tickets/e2e}): the real {@link
 * TicketsWeb} application, serving the built SPA from the classpath, against a real PostgreSQL
 * (Testcontainers, a random port), a {@link TestOidcProvider} standing in for the platform's sign-in,
 * a {@link FakePlatform} for the service account and the job-completion calls, and a fake
 * notification service. A small control server on its own port lets the suite change who signs in,
 * empty the data, end every session, move the clock and play the platform's part: fire the SLA sweep
 * and deliver the assignment events. Nothing here touches a developer's database or port 8080.
 *
 * <p>{@code java -cp ... TicketsE2e <app-port> <control-port> [<public-origin>]}, from {@code
 * examples/tickets-trial} (it reads {@code config/application.yaml}). It prints {@code READY} when
 * both servers answer.
 *
 * <p>Control endpoints (all {@code POST} except {@code GET /control/ready} and {@code GET
 * /control/notifications}):
 *
 * <ul>
 *   <li>{@code /control/sign-in-as?roles=tickets-trial:agent,...&client=acme&sub=prn_ada&name=...} the next
 *       sign-in: a person of that client holding those roles
 *   <li>{@code /control/reset} deletes every ticket, policy and outbox row, puts the clock back, forgets the
 *       notifications (sessions stay)
 *   <li>{@code /control/end-sessions} deletes every session, as an expiry would
 *   <li>{@code /control/advance?minutes=61} moves the application's clock forward (the sign-in provider follows it)
 *   <li>{@code /control/sweep} the platform fires the SLA sweep; answers when it completed, with its result
 *   <li>{@code /control/deliver-assignments} the platform delivers every assignment event not delivered yet
 *   <li>{@code /control/notifications} (GET) what the notification service received
 *   <li>{@code /control/notifier-status?value=500} what the notification service answers (empty: 202)
 * </ul>
 */
public final class TicketsE2e {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private TicketsE2e() {}

  /**
   * Starts and waits.
   *
   * @param args the application's port and the control server's port, and optionally the origin
   * @throws Exception when something does not start
   */
  public static void main(String[] args) throws Exception {
    int appPort = Integer.parseInt(args[0]);
    int controlPort = Integer.parseInt(args[1]);
    String origin = args.length > 2 ? args[2] : "http://127.0.0.1:" + appPort;

    AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    java.time.InstantSource clock = () -> Instant.now().plus(offset.get());

    PostgresFixture pg = PostgresFixture.start();
    FakePlatform platform = FakePlatform.start();
    NotifierFake notifier = NotifierFake.start();
    TestOidcProvider provider = TestOidcProvider.start(clock);
    provider.client("tickets-web", "", origin + "/auth/callback");
    provider.discoveryChanges(Map.of("end_session_endpoint", origin + "/signed-out"));
    provider.signInAs(user("tickets-trial:agent", "Ada Lovelace", "acme", "prn_ada"));

    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("HTTP_HOST", "127.0.0.1");
    env.put("HTTP_PORT", String.valueOf(appPort));
    env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
    env.put("DATABASE_INSTANCES_MAIN_USERNAME", pg.username());
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password());
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", "tickets-web");
    env.put("AUTH_PUBLIC_BASE_URL", origin);
    env.put("FLOWCATALYST_ENABLED", "true");
    env.put("FLOWCATALYST_BASE_URL", platform.baseUrl());
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    env.put("FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL", "http://127.0.0.1:" + appPort);
    env.put("FLOWCATALYST_RECEIVERS_SIGNING_SECRET", Deliveries.SECRET);
    env.put("HTTP_CLIENTS_INSTANCES_NOTIFIER_BASE_URL", notifier.baseUrl());
    env.put("TICKETS_NOTIFY_ACME__NOTIFY_KEY", "key-acme-e2e-only");
    env.put("TICKETS_NOTIFY_GLOBEX__NOTIFY_KEY", "key-globex-e2e-only");
    // The clock moves in the suite: sessions must outlive the hours it skips, and a test run signs in
    // far more than 20 times a minute from one address.
    App app =
        TicketsWeb.builder(
                auth -> auth.noRateLimit().idleTimeout(Duration.ofDays(30)).absoluteTimeout(Duration.ofDays(30)),
                TicketsWeb.Tuning.defaults())
            .configDirectory(Path.of("config"))
            .environment(env)
            .instantSource(clock)
            .start();

    HttpClient http = HttpClient.newHttpClient();
    List<String> delivered = new ArrayList<>();
    int[] sweeps = {0};

    HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 0);
    control.createContext("/control/ready", e -> reply(e, 200, "ready"));
    control.createContext(
        "/control/sign-in-as",
        e -> {
          Map<String, String> q = query(e.getRequestURI());
          provider.signInAs(
              user(q.getOrDefault("roles", ""), q.getOrDefault("name", "Ada Lovelace"), q.getOrDefault("client", "acme"), q.getOrDefault("sub", "prn_ada")));
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/reset",
        e -> {
          pg.execute("TRUNCATE ticket_comments, tickets, sla_policies, outbox_messages CASCADE");
          offset.set(Duration.ZERO);
          notifier.received().clear();
          notifier.answer(202);
          delivered.clear();
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/end-sessions",
        e -> {
          pg.execute("DELETE FROM fw_session");
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/advance",
        e -> {
          long minutes = Long.parseLong(query(e.getRequestURI()).getOrDefault("minutes", "0"));
          offset.updateAndGet(d -> d.plusMinutes(minutes));
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/sweep",
        e -> {
          Instant now = clock.instant();
          String body = Deliveries.firingBody(now, TicketsDefinitionsModule.SLA_SWEEP, "si_e2e_" + (++sweeps[0]));
          String ts = String.valueOf(now.getEpochSecond());
          HttpResponse<String> response = post(http, appPort, "/fc/scheduled-jobs", body, ts, Map.of());
          if (response.statusCode() != 202) {
            reply(e, 500, "the service answered " + response.statusCode() + " " + response.body());
            return;
          }
          try {
            List<FakePlatform.Recorded> calls = platform.awaitInstanceCalls("complete", sweeps[0], 15_000);
            reply(e, 200, calls.get(sweeps[0] - 1).json().toString());
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            reply(e, 500, "interrupted");
          }
        });
    control.createContext(
        "/control/deliver-assignments",
        e -> {
          Instant now = clock.instant();
          String rows = pg.query("SELECT coalesce(json_agg(payload::jsonb ORDER BY created_at, id)::text, '[]') FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:assigned'");
          int count = 0;
          for (JsonNode event : JSON.readTree(rows)) {
            String jobId = "dj_" + event.get("deduplicationId").asString();
            if (delivered.contains(jobId)) {
              continue;
            }
            String client = event.get("clientCode").asString();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", jobId);
            envelope.put("type", event.get("type").asString());
            envelope.put("attemptNumber", 1);
            envelope.put("source", event.get("source").asString());
            envelope.put("subject", event.get("subject").asString());
            envelope.put("correlationId", event.get("correlationId").asString());
            envelope.put("messageGroup", event.get("messageGroup").asString());
            envelope.put("clientId", "clt_" + client);
            envelope.put("clientCode", client);
            envelope.put("data", JSON.readTree(event.get("data").asString()));
            String body = JSON.writeValueAsString(envelope);
            String ts = String.valueOf(now.getEpochSecond());
            HttpResponse<String> response =
                post(http, appPort, "/fc/dispatch", body, ts, Map.of("X-Dispatch-Job-Id", jobId, "X-Event-Type", event.get("type").asString(), "X-FlowCatalyst-Client", "clt_" + client + ":" + client));
            if (response.statusCode() == 200) {
              delivered.add(jobId);
              count++;
            }
          }
          reply(e, 200, String.valueOf(count));
        });
    control.createContext(
        "/control/notifications",
        e -> {
          List<Map<String, Object>> out = new ArrayList<>();
          for (NotifierFake.Received r : notifier.received()) {
            JsonNode body = JSON.readTree(r.body());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("to", body.get("to").asString());
            one.put("ticketId", body.get("ticketId").asString());
            one.put("authorization", r.headers().get("Authorization"));
            out.add(one);
          }
          reply(e, 200, JSON.writeValueAsString(out));
        });
    control.createContext(
        "/control/notifier-status",
        e -> {
          String value = query(e.getRequestURI()).getOrDefault("value", "");
          notifier.answer(value.isEmpty() ? 202 : Integer.parseInt(value));
          reply(e, 200, "ok");
        });
    control.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  control.stop(0);
                  app.close();
                  provider.close();
                  notifier.close();
                  platform.close();
                  pg.close();
                }));
    System.out.println("READY app=http://127.0.0.1:" + appPort + " public=" + origin + " control=http://127.0.0.1:" + controlPort);
    new CountDownLatch(1).await();
  }

  private static HttpResponse<String> post(HttpClient http, int appPort, String path, String body, String ts, Map<String, String> extra) throws IOException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
            .header("Content-Type", "application/json")
            .header("X-FlowCatalyst-Signature", Deliveries.sign(ts, body))
            .header("X-FlowCatalyst-Timestamp", ts)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    extra.forEach(request::header);
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  /** A person of one client: the sign-in names the client and the roles. */
  private static Map<String, Object> user(String roles, String name, String client, String sub) {
    List<String> list = roles.isBlank() ? List.of() : List.of(roles.split(","));
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("sub", sub);
    u.put("type", "USER");
    u.put("tier", "CLIENT");
    u.put("name", name);
    u.put("email", sub + "@example.test");
    u.put("clients", List.of("clt_" + client + ":" + client));
    u.put("roles", list);
    return u;
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> out = new HashMap<>();
    String raw = uri.getRawQuery();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          out.put(
              URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
              URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
    }
    return out;
  }

  private static void reply(HttpExchange e, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
