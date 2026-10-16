package io.flowcatalyst.framework.examples.ordersdemo;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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

  App app;
  TestClient client;
  AuthTesting auth;

  @BeforeAll
  static void startServices() {
    pg = PostgresFixture.start();
    provider = TestOidcProvider.start(Instant::now);
    provider.client("orders-web", "", "http://127.0.0.1:4400/auth/callback");
  }

  @AfterAll
  static void stopServices() {
    provider.close();
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    app =
        OrdersWeb.builder()
            .configDirectory(Path.of("config"))
            .environment(
                Map.of(
                    "APP_ENV", "test",
                    "HTTP_HOST", "127.0.0.1",
                    "HTTP_PORT", "0",
                    "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
                    "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
                    "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password(),
                    "AUTH_ISSUER", provider.issuer().toString(),
                    "AUTH_CLIENT_ID", "orders-web",
                    "AUTH_PUBLIC_BASE_URL", "http://127.0.0.1:4400"))
            .start();
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
    assertThat(ids(nav)).containsExactly("about", "orders");
    assertThat(nav.tree().get("sections").get(1).get("path").asString()).isEqualTo("/admin/orders");
    TestResponse about = get("/api/admin/about", admin());
    assertThat(about.status()).isEqualTo(200);
    assertThat(about.tree().get("application").asString()).isEqualTo("orders-demo");
    assertThat(about.tree().get("ready").asBoolean()).isTrue();
    assertThat(get("/api/admin/orders/overview", admin()).tree().toString())
        .isEqualTo("{\"open\":0,\"cancelled\":0,\"openTotalCents\":0}");
  }

  @Test
  void aClerkIsRefusedEveryAdminOperationWithTheForbiddenEnvelope() {
    for (String path : List.of("/api/admin/navigation", "/api/admin/about", "/api/admin/orders/overview")) {
      TestResponse r = get(path, clerk());
      assertThat(r.status()).as(path).isEqualTo(403);
      assertThat(r.tree().get("error").asString()).as(path).isEqualTo("FORBIDDEN");
      assertThat(r.tree().get("message").asString()).as(path).startsWith("this needs the permission orders-demo:admin:");
    }
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
