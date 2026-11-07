package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CORS from a {@link CorsPolicy} ({@link Routes#cors}); off unless installed. */
class CorsTest {

  private static final String APP = "https://app.example.com";

  private static TestClient client(CorsPolicy policy) {
    Routes routes = new Routes();
    routes.get("/api/things", r -> Response.text(200, "things"));
    routes.post("/api/things", r -> Response.text(201, "made"));
    routes.get("/api/fail", r -> {
      throw new HttpException(409, "TAKEN", "taken");
    });
    if (policy != null) {
      routes.cors(policy);
    }
    return TestClient.of(routes);
  }

  @Test
  void anAllowedOriginsRequestCarriesTheHeadersEvenOnErrorsAndNotFound() {
    TestClient c = client(CorsPolicy.allowing(APP));
    TestResponse ok = c.get("/api/things").header("Origin", APP).send();
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.header("Access-Control-Allow-Origin")).isEqualTo(APP);
    assertThat(ok.header("Access-Control-Expose-Headers")).isEqualTo("X-Correlation-Id, Idempotency-Replayed");
    assertThat(ok.header("Access-Control-Allow-Credentials")).isNull();
    assertThat(ok.header("Vary")).isEqualTo("Origin");
    for (String path : List.of("/api/fail", "/nowhere")) {
      TestResponse err = c.get(path).header("Origin", APP).send();
      assertThat(err.status()).isGreaterThanOrEqualTo(400);
      assertThat(err.header("Access-Control-Allow-Origin")).as(path).isEqualTo(APP);
    }
  }

  @Test
  void anotherOriginGetsNoAllowHeaderAndARequestWithoutOriginIsUntouched() {
    TestClient c = client(CorsPolicy.allowing(APP));
    TestResponse other = c.get("/api/things").header("Origin", "https://evil.example").send();
    assertThat(other.status()).isEqualTo(200); // the browser, not the server, withholds the answer
    assertThat(other.header("Access-Control-Allow-Origin")).isNull();
    assertThat(other.header("Vary")).isEqualTo("Origin");
    TestResponse plain = c.get("/api/things").send();
    assertThat(plain.header("Access-Control-Allow-Origin")).isNull();
    assertThat(plain.header("Vary")).isNull();
  }

  @Test
  void aPreflightIsAnsweredForAnAllowedOriginMethodAndHeaders() {
    TestClient c = client(CorsPolicy.allowing(APP));
    TestResponse pre =
        c.request(Method.OPTIONS, "/api/things")
            .header("Origin", APP)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type, idempotency-key, dpop")
            .send();
    assertThat(pre.status()).isEqualTo(204);
    assertThat(pre.header("Access-Control-Allow-Origin")).isEqualTo(APP);
    assertThat(pre.header("Access-Control-Allow-Credentials")).as("never credentials").isNull();
    assertThat(pre.header("Access-Control-Allow-Methods")).isEqualTo("GET, HEAD, POST, PUT, PATCH, DELETE");
    assertThat(pre.header("Access-Control-Allow-Headers"))
        .isEqualTo("Content-Type, Authorization, DPoP, X-Correlation-Id, Idempotency-Key, Last-Event-ID");
    assertThat(pre.header("Access-Control-Max-Age")).isEqualTo("600");
    assertThat(pre.body()).isEmpty();
  }

  @Test
  void aPreflightForAnotherOriginMethodOrHeaderIsRefused() {
    TestClient c = client(CorsPolicy.allowing(APP));
    TestResponse origin =
        c.request(Method.OPTIONS, "/api/things").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET").send();
    TestResponse method =
        c.request(Method.OPTIONS, "/api/things").header("Origin", APP).header("Access-Control-Request-Method", "OPTIONS").send();
    TestResponse header =
        c.request(Method.OPTIONS, "/api/things")
            .header("Origin", APP)
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "x-secret")
            .send();
    for (TestResponse r : List.of(origin, method, header)) {
      assertThat(r.status()).isEqualTo(403);
      assertThat(r.body()).contains("CORS_REFUSED");
      assertThat(r.header("Access-Control-Allow-Origin")).isNull();
    }
  }

  @Test
  void aPlainOptionsRequestIsNotAPreflight() {
    TestResponse r = client(CorsPolicy.allowing(APP)).request(Method.OPTIONS, "/api/things").header("Origin", APP).send();
    assertThat(r.status()).isEqualTo(204);
    assertThat(r.header("Allow")).isEqualTo("GET, HEAD, OPTIONS, POST");
    assertThat(r.header("Access-Control-Allow-Methods")).isNull();
  }

  @Test
  void theWildcardAllowsAnyOriginWithoutCredentials() {
    TestResponse r = client(CorsPolicy.allowing("*")).get("/api/things").header("Origin", "https://any.example").send();
    assertThat(r.header("Access-Control-Allow-Origin")).isEqualTo("*");
  }

  @Test
  void withoutAPolicyThereIsNoCors() {
    TestResponse r = client(null).get("/api/things").header("Origin", APP).send();
    assertThat(r.header("Access-Control-Allow-Origin")).isNull();
    assertThat(r.header("Vary")).isNull();
  }

  @Test
  void aPolicyIsCheckedAndInstalledOnce() {
    assertThatIllegalArgumentException().isThrownBy(() -> CorsPolicy.allowing());
    assertThatIllegalArgumentException().isThrownBy(() -> CorsPolicy.allowing("*", APP));
    assertThatIllegalArgumentException().isThrownBy(() -> CorsPolicy.allowing("https://app.example.com/path"));
    assertThatIllegalArgumentException().isThrownBy(() -> CorsPolicy.allowing("app.example.com"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CorsPolicy(List.of(APP), List.of(), List.of("bad header"), List.of(), Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CorsPolicy(List.of(APP), List.of(), List.of(), List.of(), Duration.ofSeconds(-1)));
    Routes routes = new Routes();
    routes.cors(CorsPolicy.allowing(APP));
    assertThatIllegalStateException().isThrownBy(() -> routes.cors(CorsPolicy.allowing(APP)));
  }
}
