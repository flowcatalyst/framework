package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.usecase.ErrorKind;
import io.flowcatalyst.framework.usecase.UseCaseError;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

class ErrorEnvelopeTest {

  record Body(String name) {
    static final Validator<Body> V =
        Validator.of(Body.class).field("name", Body::name, Rules.notBlank());
  }

  private static TestClient client() {
    Routes routes = new Routes();
    routes.post("/body", r -> Response.ok(Map.of("name", r.json(Body.V).name())));
    routes.get("/boom", r -> {
      throw new IllegalStateException("secret internals: password=hunter2");
    });
    routes.get("/http", r -> {
      throw new HttpException(409, "TAKEN", "already taken", Map.of("k", "v"));
    });
    routes.get("/forbidden", r -> HttpErrors.forbidden("NOT_ALLOWED", "no"));
    routes.get("/int/{n}", r -> Response.ok(Map.of("n", r.pathInt("n"))));
    routes.get("/q", r -> Response.ok(Map.of("n", r.queryInt("n", 1))));
    routes.get("/usecase/{kind}", r -> HttpErrors.of(
        new UseCaseError(ErrorKind.valueOf(r.pathParam("kind")), "SOME_CODE", "some message", Map.of("a", "b"))));
    routes.get("/usecase-nodetails", r -> HttpErrors.of(UseCaseError.notFound("GONE", "it is gone")));
    routes.get("/ok", r -> Response.ok(Map.of("fine", true)));
    return TestClient.of(
        routes,
        r -> {
          throw new HttpException(401, "UNAUTHENTICATED", "no credentials");
        });
  }

  private static TestClient clientWithAuthRoute() {
    Routes routes = new Routes();
    routes.get("/secure", r -> Response.ok(Map.of()), RouteOptions.requiresContext());
    return TestClient.of(
        routes,
        r -> {
          throw new HttpException(401, "UNAUTHENTICATED", "no credentials");
        });
  }

  private static void assertEnvelope(TestResponse r, int status, String code) {
    assertThat(r.status()).isEqualTo(status);
    assertThat(r.header("Content-Type")).isEqualTo("application/json");
    JsonNode tree = r.tree();
    assertThat(tree.get("error").asString()).isEqualTo(code);
    assertThat(tree.get("message").asString()).isNotBlank();
  }

  @Test
  void malformedJsonIs400Validation() {
    TestResponse r = client().post("/body").jsonBody("{not json").send();
    assertEnvelope(r, 400, "VALIDATION");
    assertThat(r.tree().get("message").asString()).isEqualTo("request is invalid");
  }

  @Test
  void failedValidationIs400() {
    TestResponse r = client().post("/body").jsonBody("{\"name\":\"  \"}").send();
    assertEnvelope(r, 400, "VALIDATION");
    assertThat(r.tree().get("details").get("name").asString()).startsWith("NOT_BLANK: ");
  }

  @Test
  void aBadParameterIs400() {
    TestResponse r = client().get("/int/abc").send();
    assertEnvelope(r, 400, "VALIDATION");
    assertThat(r.tree().get("details").get("n").asString()).contains("INVALID");
    assertEnvelope(client().get("/q?n=x").send(), 400, "VALIDATION");
  }

  @Test
  void aResolverFailureIs401() {
    assertEnvelope(clientWithAuthRoute().get("/secure").send(), 401, "UNAUTHENTICATED");
  }

  @Test
  void aForbiddenResponseCarriesTheUseCaseCode() {
    TestResponse r = client().get("/forbidden").send();
    assertEnvelope(r, 403, "NOT_ALLOWED");
  }

  @Test
  void noRouteIs404() {
    assertEnvelope(client().get("/missing").send(), 404, "NOT_FOUND");
  }

  @Test
  void methodNotAllowedIs405() {
    assertEnvelope(client().post("/ok").send(), 405, "METHOD_NOT_ALLOWED");
  }

  @Test
  void aBodyOverTheLimitIs413() {
    TestResponse r = client().withMaxBodyBytes(10).post("/body").jsonBody("{\"name\":\"aaaaaaaaaaaaaaaa\"}").send();
    assertEnvelope(r, 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void aDeclaredLengthOverTheLimitIs413EvenIfTheBodyIsNeverRead() {
    TestResponse r = client().withMaxBodyBytes(10).get("/ok").declaredLength(11).send();
    assertEnvelope(r, 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void aWrongContentTypeIs415() {
    TestResponse r = client().post("/body").header("Content-Type", "text/plain").body("{\"name\":\"x\"}").send();
    assertEnvelope(r, 415, "UNSUPPORTED_MEDIA_TYPE");
    assertEnvelope(client().post("/body").body("{\"name\":\"x\"}").send(), 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void contentTypeParametersAndCaseAreAccepted() {
    TestResponse r =
        client().post("/body").header("Content-Type", "Application/JSON; charset=utf-8").body("{\"name\":\"x\"}").send();
    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void anUncaughtExceptionIs500WithoutTheCause() {
    TestResponse r = client().get("/boom").send();
    assertEnvelope(r, 500, "INTERNAL");
    assertThat(r.tree().get("message").asString()).isEqualTo("internal error");
    assertThat(r.body()).doesNotContain("hunter2").doesNotContain("IllegalState");
  }

  @Test
  void anHttpExceptionBecomesItsEnvelope() {
    TestResponse r = client().get("/http").send();
    assertEnvelope(r, 409, "TAKEN");
    assertThat(r.tree().get("details").get("k").asString()).isEqualTo("v");
  }

  @Test
  void detailsAreOmittedWhenEmpty() {
    TestResponse r = client().get("/usecase-nodetails").send();
    assertEnvelope(r, 404, "GONE");
    assertThat(r.tree().has("details")).isFalse();
    assertThat(r.body()).isEqualTo("{\"error\":\"GONE\",\"message\":\"it is gone\"}");
  }

  @ParameterizedTest
  @EnumSource(ErrorKind.class)
  void everyErrorKindMapsByItsStatus(ErrorKind kind) {
    TestResponse r = client().get("/usecase/" + kind.name()).send();
    assertEnvelope(r, kind.httpStatus(), "SOME_CODE");
    assertThat(r.tree().get("message").asString()).isEqualTo("some message");
    assertThat(r.tree().get("details").get("a").asString()).isEqualTo("b");
  }

  @Test
  void theStatusTableMatchesTheContract() {
    assertThat(ErrorKind.VALIDATION.httpStatus()).isEqualTo(400);
    assertThat(ErrorKind.AUTHORIZATION.httpStatus()).isEqualTo(403);
    assertThat(ErrorKind.NOT_FOUND.httpStatus()).isEqualTo(404);
    assertThat(ErrorKind.CONFLICT.httpStatus()).isEqualTo(409);
    assertThat(ErrorKind.BUSINESS_RULE.httpStatus()).isEqualTo(409);
    assertThat(ErrorKind.INTERNAL.httpStatus()).isEqualTo(500);
  }

  @Test
  void errorsCarryTheCorrelationId() {
    TestResponse r = client().get("/missing").header("X-Correlation-Id", "abc-123").send();
    assertThat(r.header("X-Correlation-Id")).isEqualTo("abc-123");
  }

  @Test
  void aNullResolverResultIsA500() {
    Routes routes = new Routes();
    routes.get("/s", r -> Response.ok(Map.of()), RouteOptions.requiresContext());
    @SuppressWarnings("NullAway")
    ContextResolver bad = r -> null;
    assertEnvelope(TestClient.of(routes, bad).get("/s").send(), 500, "INTERNAL");
  }
}
