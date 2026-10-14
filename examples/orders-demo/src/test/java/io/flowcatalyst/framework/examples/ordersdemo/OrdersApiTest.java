package io.flowcatalyst.framework.examples.ordersdemo;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The typed orders API served in process against real PostgreSQL: the operations, the use cases'
 * failures as the error envelope, permissions. The context comes from a test resolver (the web
 * application resolves it with framework-auth).
 */
class OrdersApiTest {

  static PostgresFixture pg;

  App app;
  TestClient client;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  /** Routes that know the declared operations, and a context from X-Client / X-Perms headers. */
  static Module http() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder app) {
        app.provide(Routes.class, lookup -> {
          Routes routes = new Routes();
          routes.declare(OpenApi.operations(lookup));
          return routes;
        });
        app.provide(
            ContextResolver.class,
            lookup ->
                request -> {
                  String perms = request.header("X-Perms");
                  if (perms == null) {
                    throw new HttpException(401, "UNAUTHENTICATED", "sign in");
                  }
                  return Context.of(new ClientId(request.header("X-Client") == null ? "acme" : request.header("X-Client")))
                      .withPrincipal(new PrincipalId("usr_1"))
                      .withPermissions(Set.of(perms.split(",")));
                });
      }
    };
  }

  @BeforeEach
  void start() {
    pg.reset();
    app =
        OrdersDemo.builder()
            .module(http())
            .module(new OrdersApiModule())
            .configDirectory(Path.of("config"))
            .environment(
                Map.of(
                    "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
                    "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
                    "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password()))
            .instantSource(() -> Instant.parse("2026-10-14T10:00:00Z"))
            .start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  @AfterEach
  void stop() {
    app.close();
  }

  static final String MANAGE = "orders:order:*";

  TestResponse place(String customer) {
    return client.post("/api/orders").header("X-Perms", MANAGE).jsonBody("{\"customer\":\"" + customer + "\"}").send();
  }

  @Test
  void anOrderIsPlacedLinedReadListedAndCancelled() {
    TestResponse placed = place("Ada Lovelace");
    assertThat(placed.status()).isEqualTo(201);
    String id = placed.tree().get("id").asString();
    assertThat(placed.header("Location")).isEqualTo("/api/orders/" + id);
    assertThat(placed.tree().get("lines").size()).isZero();

    TestResponse lined =
        client.post("/api/orders/" + id + "/lines").header("X-Perms", MANAGE)
            .jsonBody("{\"sku\":\"PUNCH-CARDS\",\"quantity\":500,\"unitPriceCents\":3}").send();
    assertThat(lined.status()).isEqualTo(200);
    assertThat(lined.tree().get("totalCents").asLong()).isEqualTo(1500);
    assertThat(lined.tree().get("lines").get(0).get("sku").asString()).isEqualTo("PUNCH-CARDS");

    JsonNode read = client.get("/api/orders/" + id).header("X-Perms", "orders:order:read").send().tree();
    assertThat(read.get("status").asString()).isEqualTo("OPEN");

    JsonNode list = client.get("/api/orders?status=OPEN").header("X-Perms", "orders:order:read").send().tree();
    assertThat(list.get("items").size()).isEqualTo(1);
    assertThat(list.has("nextCursor")).isFalse();
    assertThat(list.get("items").get(0).get("firstSku").asString()).isEqualTo("PUNCH-CARDS");

    TestResponse cancelled =
        client.post("/api/orders/" + id + "/cancel").header("X-Perms", MANAGE).jsonBody("{\"reason\":\"changed my mind\"}").send();
    assertThat(cancelled.tree().get("status").asString()).isEqualTo("CANCELLED");
    assertThat(client.get("/api/orders?status=OPEN").header("X-Perms", "orders:order:read").send().tree().get("items").size()).isZero();
  }

  @Test
  void aUseCaseFailureIsItsErrorEnvelope() {
    String id = place("Ada").tree().get("id").asString();
    client.post("/api/orders/" + id + "/cancel").header("X-Perms", MANAGE).jsonBody("{\"reason\":\"r\"}").send();

    TestResponse r =
        client.post("/api/orders/" + id + "/lines").header("X-Perms", MANAGE)
            .jsonBody("{\"sku\":\"X\",\"quantity\":1,\"unitPriceCents\":1}").send();

    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body()).isEqualTo("{\"error\":\"ORDER_NOT_OPEN\",\"message\":\"The order is cancelled and cannot change.\"}");
    TestResponse missing = client.get("/api/orders/ord_nope").header("X-Perms", "orders:order:read").send();
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.tree().get("error").asString()).isEqualTo("ORDER_NOT_FOUND");
  }

  @Test
  void ordersAreTheClientsOwn() {
    String id = place("Ada").tree().get("id").asString();

    TestResponse other = client.get("/api/orders/" + id).header("X-Perms", "orders:order:read").header("X-Client", "globex").send();
    assertThat(other.status()).isEqualTo(404);
  }

  @Test
  void theBodyIsValidatedBeforeTheUseCaseRuns() {
    TestResponse r =
        client.post("/api/orders/x/lines").header("X-Perms", MANAGE).jsonBody("{\"sku\":\"lower\",\"quantity\":0,\"unitPriceCents\":1}").send();

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("sku").asString()).isEqualTo("PATTERN: has an invalid format");
    assertThat(r.tree().get("details").get("quantity").asString()).isEqualTo("OUT_OF_RANGE: must be between 1 and 10000");
  }

  @Test
  void permissionsAreChecked() {
    TestResponse r = client.post("/api/orders").header("X-Perms", "orders:order:read").jsonBody("{\"customer\":\"Ada\"}").send();

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
    assertThat(client.get("/api/orders").send().status()).isEqualTo(401);
  }

  @Test
  void theListIsPagedByCursor() {
    for (int i = 0; i < 3; i++) {
      place("Customer " + i);
    }
    JsonNode first = client.get("/api/orders?limit=2").header("X-Perms", "orders:order:read").send().tree();
    assertThat(first.get("items").size()).isEqualTo(2);
    String next = first.get("nextCursor").asString();
    JsonNode second = client.get("/api/orders?limit=2&after=" + next).header("X-Perms", "orders:order:read").send().tree();
    assertThat(second.get("items").size()).isEqualTo(1);
    assertThat(second.has("nextCursor")).isFalse();
  }
}
