package io.flowcatalyst.framework.examples.ticketstrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.SpaTesting;
import io.flowcatalyst.framework.http.TestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The jar serves its own SPA build (static-assets contract section 8) under the strict policy. The
 * build is {@code ui/apps/tickets}: {@code pnpm --filter @flowcatalyst/tickets-web build} writes
 * {@code web/dist}, which this module's pom copies under {@code fw-spa}. Without it these tests are
 * skipped (a Java-only build), never passed.
 */
class TicketsSpaTest {

  @BeforeAll
  static void built() {
    assumeTrue(
        TicketsSpaTest.class.getClassLoader().getResource("fw-spa/index.html") != null,
        "web/dist is not built: run `pnpm --filter @flowcatalyst/tickets-web build` in ui/, then Maven");
  }

  private static TestClient client() {
    Routes routes = new Routes("test");
    routes.forModule("api").get("/api/tickets", r -> Response.noContent());
    routes.forModule(TicketsSpaModule.ID).spa(TicketsSpaModule.spa());
    return TestClient.of(routes);
  }

  @Test
  void theBuildIsServedWithTheContractsHeaders() {
    SpaTesting.assertServesBuild(client());
  }

  @Test
  void aClientRouteOfTheAppIsTheShellAndTheApiKeepsItsJson404() {
    TestClient client = client();
    assertThat(client.get("/tickets/tkt_123").send().body()).isEqualTo(client.get("/").send().body());
    assertThat(client.get("/api/nothing").send().status()).isEqualTo(404);
    assertThat(client.get("/auth/nothing").send().status()).isEqualTo(404);
  }
}
