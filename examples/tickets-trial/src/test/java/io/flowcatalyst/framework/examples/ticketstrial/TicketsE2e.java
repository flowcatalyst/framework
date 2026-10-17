package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.auth.E2eLauncher;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket service for the browser end-to-end suite ({@code ui/apps/tickets/e2e}): the real {@link
 * TicketsWeb} application, serving the built SPA from the classpath, against a real PostgreSQL
 * (Testcontainers, a random port), a {@link TestOidcProvider} standing in for the platform's sign-in,
 * a {@link FakePlatform} for the service account and the job-completion calls, and a fake
 * notification service. The shared {@link E2eLauncher} (docs/guide/e2e-kit.md) provides the provider
 * and the standard control endpoints; this class adds the service's own: empty the data, move the clock
 * and play the platform's part (fire the SLA sweep, deliver the assignment events). Nothing here
 * touches a developer's database or port 8080.
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
    AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    java.time.InstantSource clock = () -> Instant.now().plus(offset.get());

    E2eLauncher e2e = E2eLauncher.start(args, "tickets-web", clock);
    int appPort = e2e.appPort();
    PostgresFixture pg = e2e.own(PostgresFixture.start());
    FakePlatform platform = e2e.own(FakePlatform.start());
    NotifierFake notifier = e2e.own(NotifierFake.start());
    e2e.signInAs(E2eLauncher.person("tickets-trial:agent", "Ada Lovelace", "acme", "prn_ada"));

    Map<String, String> env = e2e.environment();
    env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
    env.put("DATABASE_INSTANCES_MAIN_USERNAME", pg.username());
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password());
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
    e2e.own(
        TicketsWeb.builder(
                auth -> auth.noRateLimit().idleTimeout(Duration.ofDays(30)).absoluteTimeout(Duration.ofDays(30)),
                TicketsWeb.Tuning.defaults())
            .configDirectory(Path.of("config"))
            .environment(env)
            .instantSource(clock)
            .start());

    HttpClient http = HttpClient.newHttpClient();
    List<String> delivered = new ArrayList<>();
    int[] sweeps = {0};

    e2e.endSessions(() -> pg.execute("DELETE FROM fw_session"))
        .control(
            "/control/reset",
            q -> {
              pg.execute("TRUNCATE ticket_comments, tickets, sla_policies, outbox_messages CASCADE");
              offset.set(Duration.ZERO);
              notifier.received().clear();
              notifier.answer(202);
              delivered.clear();
              return "ok";
            })
        .control(
            "/control/advance",
            q -> {
              long minutes = Long.parseLong(q.getOrDefault("minutes", "0"));
              offset.updateAndGet(d -> d.plusMinutes(minutes));
              return "ok";
            })
        .control(
            "/control/sweep",
            q -> {
              Instant now = clock.instant();
              String body = Deliveries.firingBody(now, TicketsDefinitionsModule.SLA_SWEEP, "si_e2e_" + (++sweeps[0]));
              String ts = String.valueOf(now.getEpochSecond());
              HttpResponse<String> response = post(http, appPort, "/fc/scheduled-jobs", body, ts, Map.of());
              if (response.statusCode() != 202) {
                throw new IllegalStateException("the service answered " + response.statusCode() + " " + response.body());
              }
              List<FakePlatform.Recorded> calls = platform.awaitInstanceCalls("complete", sweeps[0], 15_000);
              return calls.get(sweeps[0] - 1).json().toString();
            })
        .control(
            "/control/deliver-assignments",
            q -> {
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
              return String.valueOf(count);
            })
        .control(
            "/control/notifications",
            q -> {
              List<Map<String, Object>> out = new ArrayList<>();
              for (NotifierFake.Received r : notifier.received()) {
                JsonNode body = JSON.readTree(r.body());
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("to", body.get("to").asString());
                one.put("ticketId", body.get("ticketId").asString());
                one.put("authorization", r.headers().get("Authorization"));
                out.add(one);
              }
              return JSON.writeValueAsString(out);
            })
        .control(
            "/control/notifier-status",
            q -> {
              String value = q.getOrDefault("value", "");
              notifier.answer(value.isEmpty() ? 202 : Integer.parseInt(value));
              return "ok";
            })
        .serve();
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
}
