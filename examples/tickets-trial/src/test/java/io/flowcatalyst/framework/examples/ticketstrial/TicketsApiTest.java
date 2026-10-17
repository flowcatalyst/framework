package io.flowcatalyst.framework.examples.ticketstrial;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The typed API of the web application with the real sessions, roles and permission mapping
 * ({@link TicketsWeb}) and two clients, against real PostgreSQL. Each person holds a session cookie
 * of the framework's session store; the client of a request is the one in the person's sign-in.
 */
class TicketsApiTest {

  static PostgresFixture pg;
  static TestOidcProvider provider;

  App app;
  TestClient client;
  AuthTesting auth;

  @BeforeAll
  static void startServices() {
    pg = PostgresFixture.start();
    provider = TestOidcProvider.start(Instant::now);
    provider.client("tickets-web", "", "http://127.0.0.1:4410/auth/callback");
  }

  @AfterAll
  static void stopServices() {
    provider.close();
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    Map<String, String> env =
        Map.of(
            "APP_ENV", "test",
            "HTTP_HOST", "127.0.0.1",
            "HTTP_PORT", "0",
            "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
            "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
            "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password(),
            "AUTH_ISSUER", provider.issuer().toString(),
            "AUTH_CLIENT_ID", "tickets-web",
            "AUTH_PUBLIC_BASE_URL", "http://127.0.0.1:4410");
    // Comments are limited to 3 in a burst here, so the test can see the limit.
    app =
        TicketsWeb.builder(a -> a, new TicketsWeb.Tuning(new RateLimitPolicy(3, Duration.ofHours(1), 3), Duration.ofSeconds(30)))
            .configDirectory(Path.of("config"))
            .environment(env)
            .start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
    auth = AuthTesting.of(app);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  // ---- people: a session cookie each, in the client of their sign-in ----------------------------

  String requester(String client, String id) {
    return auth.cookie(AuthTesting.clientUser(id, "clt_" + client, client, "tickets-trial:requester"));
  }

  String agent(String client) {
    return auth.cookie(AuthTesting.clientUser("agent_" + client, "clt_" + client, client, "tickets-trial:agent"));
  }

  String lead(String client) {
    return auth.cookie(AuthTesting.clientUser("lead_" + client, "clt_" + client, client, "tickets-trial:lead"));
  }

  TestResponse get(String path, String cookie) {
    return client.get(path).header("Cookie", cookie).header("Accept", "application/json").header("Sec-Fetch-Mode", "cors").send();
  }

  TestResponse post(String path, String cookie, String json) {
    return client.post(path).header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").jsonBody(json).send();
  }

  TestResponse put(String path, String cookie, String json) {
    return client.put(path).header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").jsonBody(json).send();
  }

  String open(String cookie, String subject, String priority) {
    TestResponse r = post("/api/tickets", cookie, "{\"subject\":\"" + subject + "\",\"description\":\"d\",\"priority\":\"" + priority + "\"}");
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return r.tree().get("id").asString();
  }

  // ---- the ticket's life over HTTP ---------------------------------------------------------------

  @Test
  void aTicketIsOpenedAssignedWorkedCommentedResolvedAndClosed() {
    String ada = requester("acme", "prn_ada");
    TestResponse opened = post("/api/tickets", ada, "{\"subject\":\"Printer on fire\",\"description\":\"smoke\",\"priority\":\"HIGH\"}");
    assertThat(opened.status()).isEqualTo(201);
    String id = opened.tree().get("id").asString();
    assertThat(opened.header("Location")).isEqualTo("/api/tickets/" + id);
    assertThat(opened.tree().get("status").asString()).isEqualTo("OPEN");
    assertThat(opened.tree().get("requesterId").asString()).isEqualTo("prn_ada");
    assertThat(opened.tree().has("assigneeId")).isFalse(); // absent, not null
    assertThat(opened.tree().get("overdue").asBoolean()).isFalse();

    String agent = agent("acme");
    assertThat(post("/api/tickets/" + id + "/assign", agent, "{\"assigneeId\":\"agent_acme\"}").tree().get("assigneeId").asString()).isEqualTo("agent_acme");
    assertThat(post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"START\"}").tree().get("status").asString()).isEqualTo("IN_PROGRESS");
    assertThat(post("/api/tickets/" + id + "/comments", ada, "{\"body\":\"Any news?\"}").tree().get("comments").size()).isEqualTo(1);
    TestResponse resolved = post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"RESOLVE\",\"note\":\"Toner replaced\"}");
    assertThat(resolved.tree().get("resolution").asString()).isEqualTo("Toner replaced");
    assertThat(post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"CLOSE\"}").tree().get("status").asString()).isEqualTo("CLOSED");

    JsonNode read = get("/api/tickets/" + id, ada).tree();
    assertThat(read.get("version").asLong()).isEqualTo(6);
    assertThat(read.get("comments").get(0).get("authorId").asString()).isEqualTo("prn_ada");
  }

  @Test
  void aUseCaseFailureIsItsErrorEnvelopeWithTheStatusItsKindMapsTo() {
    String agent = agent("acme");
    String id = open(requester("acme", "prn_ada"), "Printer", "LOW");

    TestResponse illegal = post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"CLOSE\"}");
    assertThat(illegal.status()).isEqualTo(409);
    assertThat(illegal.tree().get("error").asString()).isEqualTo("ILLEGAL_TRANSITION");
    assertThat(post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"START\"}").tree().get("error").asString()).isEqualTo("ASSIGNEE_REQUIRED");
    assertThat(post("/api/tickets/" + id + "/transition", agent, "{\"action\":\"RESOLVE\"}").status()).isEqualTo(400);
    TestResponse missing = get("/api/tickets/tkt_nope", agent);
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.tree().get("error").asString()).isEqualTo("TICKET_NOT_FOUND");
    // a requester may comment on their own ticket only
    TestResponse notTheirs = post("/api/tickets/" + id + "/comments", requester("acme", "prn_bob"), "{\"body\":\"me too\"}");
    assertThat(notTheirs.status()).isEqualTo(403);
    assertThat(notTheirs.tree().get("error").asString()).isEqualTo("NOT_YOUR_TICKET");
  }

  @Test
  void theBodyIsValidatedBeforeTheUseCaseRuns() {
    TestResponse r = post("/api/tickets", requester("acme", "prn_ada"), "{\"subject\":\" \",\"description\":\"d\",\"priority\":\"HIGH\"}");
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("subject").asString()).isEqualTo("NOT_BLANK: must not be blank");
    assertThat(post("/api/tickets", requester("acme", "prn_ada"), "{\"subject\":\"x\",\"description\":\"d\",\"priority\":\"SOON\"}").status()).isEqualTo(400);
    assertThat(post("/api/tickets/x/transition", agent("acme"), "{\"action\":\"EXPLODE\"}").status()).isEqualTo(400);
  }

  // ---- permissions, sign-in and clients --------------------------------------------------------

  @Test
  void permissionsAreCheckedPerRoleAndAnonymousIsRefused() {
    String ada = requester("acme", "prn_ada");
    String id = open(ada, "Printer", "LOW");

    TestResponse r = post("/api/tickets/" + id + "/assign", ada, "{\"assigneeId\":\"prn_ada\"}");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
    assertThat(put("/api/sla-policy", agent("acme"), "{\"lowMinutes\":4,\"normalMinutes\":3,\"highMinutes\":2,\"urgentMinutes\":1}").status()).isEqualTo(403);
    assertThat(get("/api/admin/tickets/overview", agent("acme")).status()).isEqualTo(403);
    assertThat(client.get("/api/tickets").header("Accept", "application/json").header("Sec-Fetch-Mode", "cors").send().status()).isEqualTo(401);
    // a person with no role of this application can do nothing
    String nobody = auth.cookie(AuthTesting.clientUser("prn_x", "clt_acme", "acme", "other-app:thing"));
    assertThat(get("/api/tickets", nobody).status()).isEqualTo(403);
  }

  @Test
  void ticketsAreTheClientsOwnWhateverTheIdAndTheListSaysSo() {
    String id = open(requester("acme", "prn_ada"), "Acme printer", "LOW");
    open(requester("globex", "prn_gus"), "Globex printer", "LOW");

    assertThat(get("/api/tickets/" + id, agent("globex")).status()).isEqualTo(404);
    assertThat(post("/api/tickets/" + id + "/assign", agent("globex"), "{\"assigneeId\":\"agent_globex\"}").status()).isEqualTo(404);
    assertThat(post("/api/tickets/" + id + "/comments", agent("globex"), "{\"body\":\"mine now\"}").status()).isEqualTo(404);
    JsonNode acme = get("/api/tickets", agent("acme")).tree();
    JsonNode globex = get("/api/tickets", agent("globex")).tree();
    assertThat(acme.get("items").size()).isEqualTo(1);
    assertThat(acme.get("items").get(0).get("subject").asString()).isEqualTo("Acme printer");
    assertThat(globex.get("items").get(0).get("subject").asString()).isEqualTo("Globex printer");
    // and the session says which client the person is in
    assertThat(get("/auth/session", agent("globex")).tree().get("client").asString()).isEqualTo("globex");
  }

  // ---- lists -----------------------------------------------------------------------------------

  @Test
  void theListFiltersAndPagesByCursor() {
    String ada = requester("acme", "prn_ada");
    String agent = agent("acme");
    String a = open(ada, "A", "LOW");
    String b = open(ada, "B", "NORMAL");
    String c = open(requester("acme", "prn_bob"), "C", "HIGH");
    post("/api/tickets/" + b + "/assign", agent, "{\"assigneeId\":\"agent_acme\"}");

    JsonNode first = get("/api/tickets?limit=2", agent).tree();
    assertThat(first.get("items").size()).isEqualTo(2);
    assertThat(first.get("items").get(0).get("id").asString()).isEqualTo(c); // newest first
    JsonNode second = get("/api/tickets?limit=2&after=" + first.get("nextCursor").asString(), agent).tree();
    assertThat(second.get("items").size()).isEqualTo(1);
    assertThat(second.get("items").get(0).get("id").asString()).isEqualTo(a);
    assertThat(second.has("nextCursor")).isFalse();
    assertThat(get("/api/tickets?assignee=agent_acme", agent).tree().get("items").size()).isEqualTo(1);
    assertThat(get("/api/tickets?requester=prn_ada", agent).tree().get("items").size()).isEqualTo(2);
    assertThat(get("/api/tickets?status=IN_PROGRESS", agent).tree().get("items").size()).isZero();
    TestResponse bad = get("/api/tickets?status=NOPE", agent);
    assertThat(bad.status()).isEqualTo(400);
  }

  // ---- the SLA policy --------------------------------------------------------------------------

  @Test
  void theSlaPolicyIsPerClientDefaultsUntilSetAndChecked() {
    String leadAcme = lead("acme");
    JsonNode defaults = get("/api/sla-policy", leadAcme).tree();
    assertThat(defaults.get("customised").asBoolean()).isFalse();
    assertThat(defaults.get("urgentMinutes").asInt()).isEqualTo(60);

    TestResponse set = put("/api/sla-policy", leadAcme, "{\"lowMinutes\":600,\"normalMinutes\":300,\"highMinutes\":120,\"urgentMinutes\":15}");
    assertThat(set.status()).isEqualTo(200);
    assertThat(set.tree().get("customised").asBoolean()).isTrue();
    assertThat(set.tree().get("version").asLong()).isEqualTo(1);
    assertThat(put("/api/sla-policy", leadAcme, "{\"lowMinutes\":600,\"normalMinutes\":300,\"highMinutes\":120,\"urgentMinutes\":20}").tree().get("version").asLong()).isEqualTo(2);
    assertThat(get("/api/sla-policy", lead("globex")).tree().get("customised").asBoolean()).isFalse();

    TestResponse unordered = put("/api/sla-policy", leadAcme, "{\"lowMinutes\":10,\"normalMinutes\":20,\"highMinutes\":30,\"urgentMinutes\":40}");
    assertThat(unordered.status()).isEqualTo(400);
    assertThat(unordered.tree().get("error").asString()).isEqualTo("POLICY_NOT_ORDERED");
    TestResponse range = put("/api/sla-policy", leadAcme, "{\"lowMinutes\":0,\"normalMinutes\":20,\"highMinutes\":30,\"urgentMinutes\":40}");
    assertThat(range.status()).isEqualTo(400);
    assertThat(range.tree().get("details").get("lowMinutes").asString()).startsWith("OUT_OF_RANGE");
    // the deadline of a ticket opened now comes from the client's policy
    String id = open(requester("acme", "prn_ada"), "Urgent", "URGENT");
    JsonNode t = get("/api/tickets/" + id, leadAcme).tree();
    assertThat(Duration.between(Instant.parse(t.get("openedAt").asString()), Instant.parse(t.get("dueAt").asString()))).isEqualTo(Duration.ofMinutes(20));
  }

  // ---- rate limit and admin ----------------------------------------------------------------------

  @Test
  void commentingIsLimitedPerPersonNotPerClient() {
    String ada = requester("acme", "prn_ada");
    String bob = requester("acme", "prn_bob");
    String adas = open(ada, "Ada's", "LOW");
    String bobs = open(bob, "Bob's", "LOW");

    for (int i = 0; i < 3; i++) {
      assertThat(post("/api/tickets/" + adas + "/comments", ada, "{\"body\":\"c" + i + "\"}").status()).isEqualTo(200);
    }
    TestResponse limited = post("/api/tickets/" + adas + "/comments", ada, "{\"body\":\"one too many\"}");
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.tree().get("error").asString()).isEqualTo("RATE_LIMITED");
    assertThat(limited.header("Retry-After")).isNotNull();
    // another person is not affected, and reading is not limited
    assertThat(post("/api/tickets/" + bobs + "/comments", bob, "{\"body\":\"hello\"}").status()).isEqualTo(200);
    assertThat(get("/api/tickets/" + adas, ada).status()).isEqualTo(200);
    assertThat(get("/api/tickets/" + adas, ada).tree().get("comments").size()).isEqualTo(3);
  }

  @Test
  void theAdminOverviewCountsTheClientsTicketsAndTheNavigationListsItsSection() {
    String admin = auth.cookie(AuthTesting.clientUser("prn_admin", "clt_acme", "acme", "tickets-trial:admin"));
    String ada = requester("acme", "prn_ada");
    String done = open(ada, "A", "LOW");
    open(ada, "B", "LOW");
    open(requester("globex", "prn_gus"), "C", "LOW");
    String agent = agent("acme");
    post("/api/tickets/" + done + "/assign", agent, "{\"assigneeId\":\"agent_acme\"}");
    post("/api/tickets/" + done + "/transition", agent, "{\"action\":\"START\"}");

    JsonNode overview = get("/api/admin/tickets/overview", admin).tree();
    assertThat(overview.get("active").asLong()).isEqualTo(2);
    assertThat(overview.get("statuses").toString()).contains("\"status\":\"OPEN\",\"count\":1").contains("\"status\":\"IN_PROGRESS\",\"count\":1");
    JsonNode sections = get("/api/admin/navigation", admin).tree().get("sections");
    assertThat(sections.toString()).contains("\"id\":\"tickets\"").contains("\"id\":\"config\"");
  }
}
