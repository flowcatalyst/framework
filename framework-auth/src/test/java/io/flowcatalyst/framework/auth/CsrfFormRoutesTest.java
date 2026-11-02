package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Form routes (sessions contract 7.2 rule 5, server-rendered UI contract section 8 rule 2): the
 * cases of the Go module's TestCSRFFormRoutes. The guard is used as route middleware, as an
 * application does for its public routes.
 */
class CsrfFormRoutesTest {

  private static final URI BASE = URI.create("https://app.example.com");
  private static final String URLENCODED = "application/x-www-form-urlencoded";
  private static final String MULTIPART = "multipart/form-data; boundary=xyz";

  private static TestClient app(CsrfGuard guard) {
    Routes routes = new Routes("csrf");
    routes.post("/thing", r -> Response.ok(Map.of("ok", "yes")), RouteOptions.none().with(guard.middleware()));
    routes.post("/form", r -> Response.ok(Map.of("ok", "yes")), RouteOptions.acceptForms().with(guard.middleware()));
    return TestClient.of(routes);
  }

  private static TestResponse post(TestClient c, String path, String... headers) {
    TestClient.Call call = c.post(path).body("a=1");
    for (int i = 0; i < headers.length; i += 2) {
      call = call.header(headers[i], headers[i + 1]);
    }
    return call.send();
  }

  private static String code(TestResponse r) {
    return r.status() < 400 ? "" : r.tree().get("error").asString();
  }

  private record Case(String name, String path, String[] headers, int status, String code) {}

  private static Case c(String name, String path, int status, String code, String... headers) {
    return new Case(name, path, headers, status, code);
  }

  @Test
  void aFormBodyIsAcceptedOnlyOnADeclaredFormRouteAndAfterTheProvenanceRules() {
    TestClient app = app(CsrfGuard.forSessions(BASE));
    Case[] cases = {
      c("urlencoded, same-origin", "/form", 200, "", "Sec-Fetch-Site", "same-origin", "Content-Type", URLENCODED),
      c("urlencoded with a charset", "/form", 200, "", "Sec-Fetch-Site", "same-origin", "Content-Type", URLENCODED + "; charset=utf-8"),
      c("multipart, same-origin", "/form", 200, "", "Sec-Fetch-Site", "same-origin", "Content-Type", MULTIPART),
      c("urlencoded, matching Origin", "/form", 200, "", "Origin", "https://app.example.com", "Content-Type", URLENCODED),
      c("json still works on a form route", "/form", 200, "", "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json"),
      c("cross-site is refused first", "/form", 403, "CROSS_ORIGIN_REQUEST", "Sec-Fetch-Site", "cross-site", "Content-Type", URLENCODED),
      c("a foreign Origin is refused first", "/form", 403, "CROSS_ORIGIN_REQUEST", "Origin", "https://evil.example.com", "Content-Type", URLENCODED),
      c("neither header is refused first", "/form", 403, "CROSS_ORIGIN_REQUEST", "Content-Type", URLENCODED),
      c("text/plain is still 415", "/form", 415, "UNSUPPORTED_MEDIA_TYPE", "Sec-Fetch-Site", "same-origin", "Content-Type", "text/plain"),
      c("no content type is still 415", "/form", 415, "UNSUPPORTED_MEDIA_TYPE", "Sec-Fetch-Site", "same-origin"),
      c("an unparseable content type is 415", "/form", 415, "UNSUPPORTED_MEDIA_TYPE", "Sec-Fetch-Site", "same-origin", "Content-Type", "multipart/form-data; boundary"),
      c("a form body on a normal route is 415", "/thing", 415, "UNSUPPORTED_MEDIA_TYPE", "Sec-Fetch-Site", "same-origin", "Content-Type", URLENCODED),
      c("multipart on a normal route is 415", "/thing", 415, "UNSUPPORTED_MEDIA_TYPE", "Sec-Fetch-Site", "same-origin", "Content-Type", MULTIPART),
    };
    for (Case k : cases) {
      TestResponse r = post(app, k.path(), k.headers());
      assertThat(r.status()).as(k.name()).isEqualTo(k.status());
      assertThat(code(r)).as(k.name()).isEqualTo(k.code());
    }
  }

  @Test
  void thePublicGuardIsJsonOnlyForEveryCallerEvenOnAFormRoute() {
    TestClient pub = app(CsrfGuard.publicForm(BASE));
    assertThat(post(pub, "/form", "Sec-Fetch-Site", "same-origin", "Content-Type", URLENCODED).status()).isEqualTo(415);
    assertThat(post(pub, "/form", "Sec-Fetch-Site", "same-origin", "Content-Type", MULTIPART).status()).isEqualTo(415);
    assertThat(post(pub, "/form", "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json").status()).isEqualTo(200);
    // the public guard's own provenance rule is unchanged: a header-less request passes, a cross-site one does not
    assertThat(post(pub, "/form", "Content-Type", "application/json").status()).isEqualTo(200);
    assertThat(post(pub, "/form", "Sec-Fetch-Site", "cross-site", "Content-Type", "application/json").status()).isEqualTo(403);
  }
}
