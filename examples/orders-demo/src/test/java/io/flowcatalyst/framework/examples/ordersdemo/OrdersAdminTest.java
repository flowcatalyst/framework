package io.flowcatalyst.framework.examples.ordersdemo;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The admin area of the web application with the real sessions, roles and permission mapping
 * (OrdersWeb): two test principals, an administrator (role {@code orders-demo:admin}) and a clerk
 * (role {@code orders-demo:clerk}), against real PostgreSQL. Admin contract sections 3 and 4.
 */
class OrdersAdminTest {

  static PostgresFixture pg;
  static TestOidcProvider provider;
  static FakePlatform platform;

  App app;
  TestClient client;
  AuthTesting auth;

  @BeforeAll
  static void startServices() {
    pg = PostgresFixture.start();
    platform = DemoPlatform.start();
    provider = TestOidcProvider.start(Instant::now);
    provider.client("orders-web", "", "http://127.0.0.1:4400/auth/callback");
  }

  @AfterAll
  static void stopServices() {
    provider.close();
    platform.close();
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    Map<String, String> env = new HashMap<>(DemoPlatform.env(platform));
    env.putAll(
        Map.of(
            "APP_ENV", "test",
            "HTTP_HOST", "127.0.0.1",
            "HTTP_PORT", "0",
            "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
            "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
            "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password(),
            "AUTH_ISSUER", provider.issuer().toString(),
            "AUTH_CLIENT_ID", "orders-web",
            "AUTH_PUBLIC_BASE_URL", "http://127.0.0.1:4400"));
    app = OrdersWeb.builder().configDirectory(Path.of("config")).environment(env).start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
    auth = AuthTesting.of(app);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  String admin() {
    return auth.cookie(AuthTesting.user("prn_admin", "orders-demo:admin"));
  }

  String clerk() {
    return auth.cookie(AuthTesting.user("prn_clerk", "orders-demo:clerk"));
  }

  TestResponse get(String path, String cookie) {
    return client.get(path).header("Cookie", cookie).header("Accept", "application/json").header("Sec-Fetch-Mode", "cors").send();
  }

  static List<String> ids(TestResponse r) {
    List<String> out = new ArrayList<>();
    for (JsonNode s : r.tree().get("sections")) {
      out.add(s.get("id").asString());
    }
    return out;
  }

  @Test
  void anAdministratorSeesTheSkeletonAndTheDemosOwnSection() {
    TestResponse nav = get("/api/admin/navigation", admin());

    assertThat(nav.status()).isEqualTo(200);
    assertThat(ids(nav)).containsExactly("about", "event-types", "roles", "users", "activity", "config", "orders");
    assertThat(nav.tree().get("sections").get(6).get("path").asString()).isEqualTo("/admin/orders");
    TestResponse about = get("/api/admin/about", admin());
    assertThat(about.status()).isEqualTo(200);
    assertThat(about.tree().get("application").asString()).isEqualTo("orders-demo");
    assertThat(about.tree().get("ready").asBoolean()).isTrue();
    assertThat(get("/api/admin/orders/overview", admin()).tree().toString())
        .isEqualTo("{\"open\":0,\"cancelled\":0,\"openTotalCents\":0}");
  }

  @Test
  void aClerkIsRefusedEveryAdminOperationWithTheForbiddenEnvelope() {
    for (String path :
        List.of(
            "/api/admin/navigation",
            "/api/admin/about",
            "/api/admin/orders/overview",
            "/api/admin/event-types",
            "/api/admin/event-types/et_3",
            "/api/admin/roles",
            "/api/admin/roles/orders-demo:clerk",
            "/api/admin/users",
            "/api/admin/users/prn_ada",
            "/api/admin/invitations",
            "/api/admin/events",
            "/api/admin/events/ev_01",
            "/api/admin/dispatch-jobs",
            "/api/admin/dispatch-jobs/dj_01",
            "/api/admin/config",
            "/api/admin/config/flowcatalyst.client-secret")) {
      TestResponse r = get(path, clerk());
      assertThat(r.status()).as(path).isEqualTo(403);
      assertThat(r.tree().get("error").asString()).as(path).isEqualTo("FORBIDDEN");
      assertThat(r.tree().get("message").asString()).as(path).startsWith("this needs the permission orders-demo:admin:");
    }
  }

  @Test
  void anAdministratorReadsTheApplicationsEventTypesAndRolesFromThePlatform() {
    TestResponse et = get("/api/admin/event-types", admin());
    assertThat(et.status()).isEqualTo(200);
    assertThat(field(et, "code")).hasSize(9).contains("orders-demo:sales:order:placed").noneMatch(c -> c.startsWith("billing"));
    TestResponse roles = get("/api/admin/roles", admin());
    assertThat(field(roles, "name")).containsExactly("orders-demo:admin", "orders-demo:clerk");
    assertThat(roles.body()).doesNotContain("billing");
    assertThat(get("/api/admin/roles/orders-demo:clerk", admin()).tree().get("permissions")).hasSize(2);
  }

  TestResponse send(String method, String path, String cookie, String body) {
    TestClient.Call call =
        client.request(io.flowcatalyst.framework.http.Method.valueOf(method), path).header("Cookie", cookie).header("Accept", "application/json").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "same-origin");
    if (body != null) {
      call.jsonBody(body);
    }
    return call.send();
  }

  @Test
  void anAdministratorWorksWithTheApplicationsUsersAndInvitationsThroughTheServiceAccount() {
    platform.clearRequests();
    DemoPlatform.resetDirectory(platform);

    TestResponse users = get("/api/admin/users?limit=3", admin());
    assertThat(users.status()).isEqualTo(200);
    assertThat(field(users, "email")).containsExactly("ada@example.test", "grace@example.test", "linus@example.test");
    assertThat(users.tree().get("items").get(0).get("roles")).extracting(JsonNode::asString).containsExactly("orders-demo:admin");
    assertThat(users.body()).doesNotContain("billing");
    assertThat(field(get("/api/admin/invitations", admin()), "userId")).containsExactly("prn_linus", "prn_margaret");

    String invite = "{\"email\":\"new@example.test\",\"name\":\"New Person\",\"clientId\":\"acme\",\"roles\":[\"orders-demo:clerk\"],\"returnInviteLink\":true}";
    TestResponse made = send("POST", "/api/admin/users", admin(), invite);
    assertThat(made.status()).isEqualTo(201);
    assertThat(made.tree().get("inviteLink").asString()).contains("set-password");
    assertThat(made.header("Cache-Control")).contains("no-store");
    assertThat(field(get("/api/admin/invitations", admin()), "email")).contains("new@example.test");
    assertThat(get("/api/admin/invitations", admin()).body()).doesNotContain("set-password");

    TestResponse again = send("POST", "/api/admin/users", admin(), invite);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.tree().get("error").asString()).isEqualTo("EMAIL_EXISTS");
    assertThat(again.tree().get("message").asString()).isEqualTo("a user with this email already exists");

    assertThat(send("POST", "/api/admin/invitations/prn_margaret/resend", admin(), "{}").status()).isEqualTo(200);
    assertThat(send("DELETE", "/api/admin/invitations/prn_linus", admin(), null).status()).isEqualTo(204);
    assertThat(field(get("/api/admin/invitations", admin()), "userId")).doesNotContain("prn_linus");
    // every platform call went to this application's paths, as the service account
    assertThat(platform.requests())
        .filteredOn(r -> r.path().startsWith("/api/applications/"))
        .allMatch(r -> r.path().startsWith("/api/applications/orders-demo/") && r.headers().get("authorization").startsWith("Bearer "));
  }

  @Test
  void aClerkCannotInviteResendOrRevokeAndTheServiceAccountIsNotUsed() {
    platform.clearRequests();
    String invite = "{\"email\":\"x@example.test\",\"name\":\"X\",\"clientId\":\"acme\",\"roles\":[\"orders-demo:clerk\"]}";
    assertThat(send("POST", "/api/admin/users", clerk(), invite).status()).isEqualTo(403);
    assertThat(send("POST", "/api/admin/invitations/prn_linus/resend", clerk(), "{}").status()).isEqualTo(403);
    assertThat(send("DELETE", "/api/admin/invitations/prn_linus", clerk(), null).status()).isEqualTo(403);
    assertThat(platform.requests()).noneMatch(r -> r.path().startsWith("/api/applications/"));
  }

  @Test
  void anAuditorReadsUsersAndActivityAndCannotChangeAnything() {
    String auditor = auth.cookie(AuthTesting.user("prn_auditor", "orders-demo:auditor"));
    assertThat(ids(get("/api/admin/navigation", auditor))).containsExactly("about", "users", "activity");
    assertThat(get("/api/admin/users", auditor).status()).isEqualTo(200);
    assertThat(get("/api/admin/invitations", auditor).status()).isEqualTo(200);
    assertThat(get("/api/admin/events", auditor).status()).isEqualTo(200);
    assertThat(get("/api/admin/roles", auditor).status()).isEqualTo(403);
    platform.clearRequests();
    String invite = "{\"email\":\"x@example.test\",\"name\":\"X\",\"clientId\":\"acme\",\"roles\":[\"orders-demo:clerk\"]}";
    assertThat(send("POST", "/api/admin/users", auditor, invite).tree().get("message").asString()).isEqualTo("this needs the permission orders-demo:admin:users:invite");
    assertThat(send("POST", "/api/admin/invitations/prn_linus/resend", auditor, "{}").status()).isEqualTo(403);
    assertThat(send("DELETE", "/api/admin/invitations/prn_linus", auditor, null).status()).isEqualTo(403);
    assertThat(platform.requests()).noneMatch(r -> r.method().equals("POST") || r.method().equals("DELETE"));
  }

  @Test
  void anAdministratorReadsTheApplicationsEventsAndTheJobsThatDeliverThem() {
    TestResponse events = get("/api/admin/events?limit=4", admin());
    assertThat(events.status()).isEqualTo(200);
    assertThat(field(events, "id")).hasSize(4).noneMatch(id -> id.equals("ev_x1"));
    assertThat(events.body()).doesNotContain("billing:invoice");
    TestResponse one = get("/api/admin/events/ev_02", admin());
    assertThat(one.tree().get("dispatchJobs").get(0).get("id").asString()).isEqualTo("dj_02");
    assertThat(one.tree().get("dispatchJobs").get(0).get("targetUrl").asString()).isEqualTo("https://billing.example.test/hooks/orders");
    TestResponse job = get("/api/admin/dispatch-jobs/dj_02", admin());
    assertThat(job.tree().get("attempts")).hasSize(2);
    assertThat(job.tree().get("attempts").get(0).get("responseBody").asString()).isEqualTo("upstream exploded");
    assertThat(get("/api/admin/events/ev_x1", admin()).status()).isEqualTo(404);
    assertThat(field(get("/api/admin/dispatch-jobs?status=FAILED", admin()), "id")).containsExactly("dj_08", "dj_02");
  }

  @Test
  void theConfigPageShowsTheFlowcatalystSettingsAndNeverTheClientSecret() {
    TestResponse r = get("/api/admin/config", admin());
    assertThat(r.status()).isEqualTo(200);
    JsonNode secret = null;
    for (JsonNode s : r.tree().get("settings")) {
      if (s.get("key").asString().equals("flowcatalyst.client-secret")) {
        secret = s;
      }
    }
    assertThat(secret).isNotNull();
    assertThat(secret.get("secret").asBoolean()).isTrue();
    assertThat(secret.get("state").asString()).isEqualTo("SET");
    assertThat(secret.has("value")).isFalse();
    assertThat(r.body()).doesNotContain(FakePlatform.CLIENT_SECRET);
  }

  @Test
  void theAdminRoleAndItsPermissionsAreInTheDefinitionSync() {
    io.flowcatalyst.framework.flowcatalyst.DefinitionSync sync =
        app.get(io.flowcatalyst.framework.flowcatalyst.DefinitionSync.class);
    io.flowcatalyst.framework.flowcatalyst.SyncReport report =
        sync.run(io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Mode.DRY_RUN);
    assertThat(report.of(io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Category.ROLES).codes()).containsExactly("admin");
    assertThat(app.get(io.flowcatalyst.framework.flowcatalyst.DefinitionCatalog.class).roles().get(0).permissions())
        .containsExactly(
            "orders-demo:admin:activity:view",
            "orders-demo:admin:config:view",
            "orders-demo:admin:console:view",
            "orders-demo:admin:event-types:view",
            "orders-demo:admin:orders:view",
            "orders-demo:admin:roles:view",
            "orders-demo:admin:users:invite",
            "orders-demo:admin:users:resend",
            "orders-demo:admin:users:revoke",
            "orders-demo:admin:users:view");
  }

  static List<String> field(TestResponse r, String name) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : r.tree().get("items")) {
      out.add(n.get(name).asString());
    }
    return out;
  }

  @Test
  void anAdministratorHoldsNoOrderPermissionsAndTheClerkNoAdminOnes() {
    assertThat(get("/api/orders", admin()).status()).isEqualTo(403);
    assertThat(get("/api/orders", clerk()).status()).isEqualTo(200);
  }

  @Test
  void aUserWithBothRolesHasBothAreas() {
    String both = auth.cookie(AuthTesting.user("prn_both", "orders-demo:admin", "orders-demo:clerk"));

    assertThat(get("/api/admin/navigation", both).status()).isEqualTo(200);
    assertThat(get("/api/orders", both).status()).isEqualTo(200);
  }

  @Test
  void aViewerRoleAloneGrantsNothingAdmin() {
    String viewer = auth.cookie(AuthTesting.user("prn_viewer", "orders-demo:viewer"));

    assertThat(get("/api/admin/navigation", viewer).status()).isEqualTo(403);
  }

  @Test
  void withoutASessionTheAnswerIsAJson401ForAFetch() {
    TestResponse r = client.get("/api/admin/navigation").header("Accept", "application/json").header("Sec-Fetch-Mode", "cors").send();

    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
  }
}
