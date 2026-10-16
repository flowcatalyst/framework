package io.flowcatalyst.framework.examples.ordersdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.SpaTesting;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The jar serves its own SPA build (static-assets contract section 8) under the strict policy. The
 * build is {@code ui/apps/orders}: {@code pnpm --filter @flowcatalyst/orders-web build} writes {@code
 * web/dist}, which this module's pom copies under {@code fw-spa}. Without it these tests are skipped
 * (a Java-only build), never passed.
 */
class OrdersSpaTest {

  @BeforeAll
  static void built() {
    assumeTrue(
        OrdersSpaTest.class.getClassLoader().getResource("fw-spa/index.html") != null,
        "web/dist is not built: run `pnpm --filter @flowcatalyst/orders-web build` in ui/, then Maven");
  }

  private static TestClient client() {
    Routes routes = new Routes("test");
    routes.forModule("api").get("/api/orders", r -> Response.noContent());
    routes.forModule(OrdersSpaModule.ID).spa(OrdersSpaModule.spa());
    return TestClient.of(routes);
  }

  @Test
  void theBuildIsServedWithTheContractsHeaders() {
    SpaTesting.assertServesBuild(client());
  }

  @Test
  void theStrictPolicyAllowsExactlyTheColourModeSnippetTheBuildContains() throws IOException, NoSuchAlgorithmException {
    TestResponse index = client().get("/").send();
    String csp = index.header("Content-Security-Policy");
    assertThat(csp).startsWith("default-src 'self'; script-src 'self' 'sha256-");
    assertThat(csp).contains("style-src 'self'").doesNotContain("unsafe-inline");
    assertThat(index.header("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");

    // Every inline script in index.html is hashed in the policy, which Spa took from the build's
    // csp-hashes.json (a changed snippet in @flowcatalyst/ui changes both together); the file itself
    // is not served.
    assertThat(client().get("/csp-hashes.json").send().status()).isEqualTo(404);
    Matcher inline = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(index.body());
    int found = 0;
    while (inline.find()) {
      found++;
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(inline.group(1).getBytes(StandardCharsets.UTF_8));
      assertThat(csp).contains("'sha256-" + Base64.getEncoder().encodeToString(digest) + "'");
    }
    assertThat(found).as("inline scripts in index.html").isEqualTo(1);
    try (InputStream in = OrdersSpaTest.class.getClassLoader().getResourceAsStream("fw-spa/index.html")) {
      assertThat(in).isNotNull();
    }
  }

  @Test
  void aClientRouteOfTheAppIsTheShellAndTheApiKeepsItsJson404() {
    TestClient client = client();
    assertThat(client.get("/orders/ord_123").send().body()).isEqualTo(client.get("/").send().body());
    assertThat(client.get("/api/nothing").send().status()).isEqualTo(404);
    assertThat(client.get("/auth/nothing").send().status()).isEqualTo(404);
  }
}
