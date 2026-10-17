package io.flowcatalyst.framework.examples.ticketstrial;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.metrics.Metrics;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the platform calls into or is told: the definition sync, the dispatch receiver that
 * notifies an assignee (through a fake notification service, with a client's own key), the
 * scheduled-job receiver that runs the SLA sweep, and the gauges. The platform is {@link
 * FakePlatform}, the notification service a local server; nothing leaves the machine.
 */
class TicketsPlatformTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String ACME_KEY = "key-acme-test-only";

  static PostgresFixture pg;
  static TestOidcProvider provider;

  final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

  FakePlatform platform;
  NotifierFake notifier;
  App app;
  AuthTesting auth;
  TestClient client;

  @BeforeAll
  static void startServices() {
    pg = PostgresFixture.start();
    provider = TestOidcProvider.start(Instant::now);
    provider.client("tickets-web", "", "http://127.0.0.1:4411/auth/callback");
  }

  @AfterAll
  static void stopServices() {
    provider.close();
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    now.set(Instant.now());
    platform = FakePlatform.start();
    notifier = NotifierFake.start();
    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("HTTP_HOST", "127.0.0.1");
    env.put("HTTP_PORT", "0");
    env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
    env.put("DATABASE_INSTANCES_MAIN_USERNAME", pg.username());
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password());
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", "tickets-web");
    env.put("AUTH_PUBLIC_BASE_URL", "http://127.0.0.1:4411");
    env.put("FLOWCATALYST_ENABLED", "true");
    env.put("FLOWCATALYST_BASE_URL", platform.baseUrl());
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    env.put("FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL", "http://tickets.test");
    env.put("FLOWCATALYST_RECEIVERS_SIGNING_SECRET", Deliveries.SECRET);
    env.put("HTTP_CLIENTS_INSTANCES_NOTIFIER_BASE_URL", notifier.baseUrl());
    env.put("TICKETS_NOTIFY_ACME__NOTIFY_KEY", ACME_KEY); // globex has none
    app =
        TicketsWeb.builder(a -> a, new TicketsWeb.Tuning(TicketsApiModule.COMMENT_LIMIT, Duration.ofMillis(50)))
            .configDirectory(Path.of("config"))
            .environment(env)
            .instantSource(now::get)
            .start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
    auth = AuthTesting.of(app);
  }

  @AfterEach
  void stop() {
    app.close();
    notifier.close();
    platform.close();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  String person(String clientCode, String id, String role) {
    return auth.cookie(AuthTesting.clientUser(id, "clt_" + clientCode, clientCode, "tickets-trial:" + role));
  }

  String open(String cookie, String subject, String priority) {
    TestResponse r =
        client.post("/api/tickets").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin")
            .jsonBody("{\"subject\":\"" + subject + "\",\"description\":\"d\",\"priority\":\"" + priority + "\"}").send();
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return r.tree().get("id").asString();
  }

  void assign(String cookie, String ticketId, String assignee) {
    TestResponse r =
        client.post("/api/tickets/" + ticketId + "/assign").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin")
            .jsonBody("{\"assigneeId\":\"" + assignee + "\"}").send();
    assertThat(r.status()).as(r.body()).isEqualTo(200);
  }

  /** The outbox row of the latest event of a type, as the platform's processor would read it. */
  JsonNode eventRow(String type) {
    return JSON.readTree(
        pg.query(
            "SELECT payload FROM outbox_messages WHERE type = 'EVENT' AND payload::jsonb ->> 'type' = '"
                + type
                + "' ORDER BY created_at DESC, id DESC LIMIT 1"));
  }

  /** What the platform delivers to a subscription for an event: the envelope around the row's data. */
  String envelopeOf(JsonNode event, String jobId, String clientCode) {
    Map<String, Object> envelope = new java.util.LinkedHashMap<>();
    envelope.put("id", jobId);
    envelope.put("type", event.get("type").asString());
    envelope.put("attemptNumber", 1);
    envelope.put("source", event.get("source").asString());
    envelope.put("subject", event.get("subject").asString());
    envelope.put("correlationId", event.get("correlationId").asString());
    envelope.put("messageGroup", event.get("messageGroup").asString());
    envelope.put("clientId", "clt_" + clientCode);
    envelope.put("clientCode", clientCode);
    envelope.put("data", JSON.readTree(event.get("data").asString()));
    return JSON.writeValueAsString(envelope);
  }

  TestResponse deliverAssignment(JsonNode event, String jobId, String clientCode) {
    return Deliveries.dispatch(
        client, now.get(), jobId, event.get("type").asString(), "clt_" + clientCode + ":" + clientCode, envelopeOf(event, jobId, clientCode));
  }

  // ---- the definition sync ---------------------------------------------------------------------

  @Test
  void theSyncSendsTheEventTypesRolesConnectionSubscriptionAndJobInTheOrderTheyDependOn() throws Exception {
    app.get(DefinitionSync.class).run(DefinitionSync.Mode.APPLY);

    assertThat(platform.syncedResources()).contains("roles", "event-types", "connections", "subscriptions", "scheduled-jobs");
    JsonNode types = syncOf("event-types");
    assertThat(types.toString())
        .contains("tickets-trial:support:ticket:opened")
        .contains("tickets-trial:support:ticket:assigned")
        .contains("tickets-trial:support:ticket:status-changed")
        .contains("tickets-trial:support:ticket:commented")
        .contains("tickets-trial:support:ticket:sla-breached")
        .contains("tickets-trial:support:sla-policy:changed");
    JsonNode roles = syncOf("roles");
    assertThat(roles.toString()).contains("\"name\":\"agent\"").contains("\"name\":\"lead\"").contains("\"name\":\"requester\"");
    assertThat(roles.toString()).contains("tickets-trial:support:sla:manage");
    // the framework added the administrator's role itself, from the admin sections the service registered
    assertThat(roles.toString()).contains("\"name\":\"admin\"").contains("tickets-trial:admin:tickets:view");
    assertThat(syncOf("subscriptions").toString()).contains("http://tickets.test/fc/dispatch").contains("notify-assignee");
    assertThat(syncOf("scheduled-jobs").toString()).contains("sla-sweep").contains("0 * * * * *");
  }

  private JsonNode syncOf(String resource) {
    return platform.syncRequests().stream().filter(r -> r.isSyncOf(resource)).findFirst().orElseThrow().json();
  }

  @Test
  void aDryRunNamesWhatWouldBeSyncedAndSendsNothing() throws Exception {
    platform.clearRequests();
    var report = app.get(DefinitionSync.class).run(DefinitionSync.Mode.DRY_RUN);

    assertThat(report.toString()).contains("sla-sweep").contains("notify-assignee");
    assertThat(platform.syncRequests()).isEmpty();
  }

  // ---- the dispatch receiver: notify the assignee ----------------------------------------------

  @Test
  void anAssignmentTheUseCaseWroteNotifiesTheAssigneeWithTheClientsOwnKey() {
    String agent = person("acme", "agent_1", "agent");
    String id = open(person("acme", "prn_ada", "requester"), "Printer on fire", "HIGH");
    assign(agent, id, "agent_7");

    TestResponse r = deliverAssignment(eventRow("tickets-trial:support:ticket:assigned"), "dj_0HZXEQ5Y8JY5Z", "acme");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"ack\":true}");
    assertThat(notifier.received()).hasSize(1);
    NotifierFake.Received call = notifier.received().getFirst();
    assertThat(call.path()).isEqualTo("/notifications");
    assertThat(call.headers().get("Authorization")).isEqualTo("Bearer " + ACME_KEY);
    assertThat(call.headers().get("Idempotency-Key")).isEqualTo("dj_0HZXEQ5Y8JY5Z");
    JsonNode body = JSON.readTree(call.body());
    assertThat(body.get("to").asString()).isEqualTo("agent_7");
    assertThat(body.get("ticketId").asString()).isEqualTo(id);
    assertThat(body.get("subject").asString()).isEqualTo("Printer on fire");
    assertThat(body.get("assignedBy").asString()).isEqualTo("agent_1");
  }

  @Test
  void assigningATicketToYourselfNotifiesNobody() {
    String id = open(person("acme", "prn_ada", "requester"), "Mine", "LOW");
    assign(person("acme", "agent_1", "agent"), id, "agent_1");

    TestResponse r = deliverAssignment(eventRow("tickets-trial:support:ticket:assigned"), "dj_1", "acme");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"ack\":true}");
    assertThat(notifier.received()).isEmpty();
  }

  @Test
  void aClientWithoutAKeyIsRejectedTerminallyAndTheSecretIsNeverSent() {
    String id = open(person("globex", "prn_gus", "requester"), "Globex printer", "LOW");
    assign(person("globex", "agent_g", "agent"), id, "agent_g2");

    TestResponse r = deliverAssignment(eventRow("tickets-trial:support:ticket:assigned"), "dj_2", "globex");

    assertThat(r.status()).isEqualTo(422);
    assertThat(r.header("FlowCatalyst-Outcome")).isEqualTo("reject");
    assertThat(r.body()).doesNotContain(ACME_KEY);
    assertThat(notifier.received()).isEmpty();
  }

  @Test
  void aTroubledNotificationServiceIsTransientAndARefusalIsTerminal() {
    String id = open(person("acme", "prn_ada", "requester"), "Printer", "LOW");
    assign(person("acme", "agent_1", "agent"), id, "agent_7");
    JsonNode event = eventRow("tickets-trial:support:ticket:assigned");

    notifier.answer(500);
    assertThat(deliverAssignment(event, "dj_3", "acme").status()).isEqualTo(503); // the platform retries it
    notifier.answer(400);
    TestResponse refused = deliverAssignment(event, "dj_4", "acme");
    assertThat(refused.status()).isEqualTo(422);
    assertThat(refused.body()).contains("400").doesNotContain(ACME_KEY);
    notifier.answer(202);
    assertThat(deliverAssignment(event, "dj_5", "acme").status()).isEqualTo(200);
  }

  @Test
  void aDeliveryWithoutTheSignatureIsRefusedBeforeAnyHandlerRuns() {
    TestResponse r =
        client.post("/fc/dispatch").header("Content-Type", "application/json")
            .header("X-Dispatch-Job-Id", "dj_9").header("X-Event-Type", "tickets-trial:support:ticket:assigned").body("{}").send();

    assertThat(r.status()).isEqualTo(401);
    assertThat(notifier.received()).isEmpty();
  }

  // ---- the scheduled-job receiver: the SLA sweep ------------------------------------------------

  @Test
  void theSweepFlagsEveryClientsOverdueTicketsAsTheServiceAndReportsWhatItDid() throws Exception {
    String acmeUrgent = open(person("acme", "prn_ada", "requester"), "Acme urgent", "URGENT"); // due in 60 minutes
    String globexUrgent = open(person("globex", "prn_gus", "requester"), "Globex urgent", "URGENT");
    String acmeLow = open(person("acme", "prn_ada", "requester"), "Acme low", "LOW"); // due in two days

    now.set(now.get().plus(Duration.ofMinutes(61)));
    TestResponse fired = Deliveries.fire(client, now.get(), TicketsDefinitionsModule.SLA_SWEEP, "si_1");
    assertThat(fired.status()).isEqualTo(202);

    JsonNode completion = platform.awaitInstanceCalls("complete", 1, 10_000).getFirst().json();
    assertThat(completion.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(completion.toString()).contains("\"flagged\":2");
    assertThat(pg.query("SELECT string_agg(id, ',' ORDER BY id) FROM tickets WHERE breached_at IS NOT NULL")).isEqualTo(acmeUrgent + "," + globexUrgent);
    assertThat(pg.query("SELECT breached_at IS NULL FROM tickets WHERE id = '" + acmeLow + "'")).isEqualTo("t");

    // Each flag is an event in the ticket's own client, written by the service principal, caused by the firing.
    List<String> clients = List.of(pg.query("SELECT string_agg(payload::jsonb ->> 'clientCode', ',' ORDER BY payload::jsonb ->> 'clientCode') FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'").split(","));
    assertThat(clients).containsExactly("acme", "globex");
    JsonNode breach = eventRow("tickets-trial:support:ticket:sla-breached");
    assertThat(breach.get("causationId").asString()).isEqualTo("si_1");
    assertThat(breach.toString()).contains(FakePlatform.SERVICE_PRINCIPAL);

    // A second firing finds nothing left to do.
    assertThat(Deliveries.fire(client, now.get(), TicketsDefinitionsModule.SLA_SWEEP, "si_2").status()).isEqualTo(202);
    JsonNode second = platform.awaitInstanceCalls("complete", 2, 10_000).get(1).json();
    assertThat(second.toString()).contains("\"flagged\":0");
    assertThat(pg.query("SELECT count(*) FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'")).isEqualTo("2");
  }

  @Test
  void theSweepHoldsTheOverlapLockOfItsJobCode() throws Exception {
    // The framework takes scheduled-job:<code> from the default lock store around the run: a
    // second replica (here, the same store) cannot run the sweep while the first holds it.
    var store = app.get(io.flowcatalyst.framework.locks.Locks.class).defaultInstance();
    var held = store.tryAcquire(io.flowcatalyst.framework.locks.LockKey.service("scheduled-job:sla-sweep"), Duration.ofMinutes(1));
    assertThat(held).isInstanceOf(io.flowcatalyst.framework.locks.AcquireResult.Acquired.class);

    assertThat(Deliveries.fire(client, now.get(), TicketsDefinitionsModule.SLA_SWEEP, "si_locked").status()).isEqualTo(202);

    JsonNode completion = platform.awaitInstanceCalls("complete", 1, 10_000).getFirst().json();
    assertThat(completion.toString()).contains("lock-held");
  }

  // ---- the gauges ------------------------------------------------------------------------------

  @Test
  void theGaugesFollowTheTicketsAcrossClients() throws Exception {
    open(person("acme", "prn_ada", "requester"), "A", "URGENT");
    open(person("globex", "prn_gus", "requester"), "B", "LOW");
    now.set(now.get().plus(Duration.ofMinutes(61)));
    Deliveries.fire(client, now.get(), TicketsDefinitionsModule.SLA_SWEEP, "si_g");
    platform.awaitInstanceCalls("complete", 1, 10_000);

    Metrics metrics = app.get(Metrics.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    String text = "";
    while (System.nanoTime() < deadline) {
      text = io.flowcatalyst.framework.metrics.PrometheusText.write(metrics.snapshot());
      if (text.contains("tickets_active_count 2\n") && text.contains("tickets_overdue_count 1\n")) {
        break;
      }
      Thread.sleep(25);
    }
    assertThat(text).contains("tickets_active_count 2\n").contains("tickets_overdue_count 1\n");
  }
}
