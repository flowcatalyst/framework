package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.ServiceError;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A use case failure still maps to the HTTP envelope through {@code HttpErrors.of}, although
 * {@code framework-http} no longer depends on this module (MA item 8): {@link UseCaseError} is a
 * {@link ServiceError}. The HTTP module is a test dependency here only.
 */
class UseCaseErrorHttpTest {

  private static TestClient client() {
    Routes routes = new Routes();
    routes.get(
        "/usecase/{kind}",
        r ->
            HttpErrors.of(
                new UseCaseError(
                    ErrorKind.valueOf(r.pathParam("kind")),
                    "SOME_CODE",
                    "some message",
                    Map.of("a", "b"))));
    routes.get("/usecase-nodetails", r -> HttpErrors.of(UseCaseError.notFound("GONE", "it is gone")));
    return TestClient.of(routes);
  }

  @ParameterizedTest
  @EnumSource(ErrorKind.class)
  void everyErrorKindMapsByItsStatus(ErrorKind kind) {
    TestResponse r = client().get("/usecase/" + kind.name()).send();
    assertThat(r.status()).isEqualTo(kind.httpStatus());
    assertThat(r.header("Content-Type")).isEqualTo("application/json");
    assertThat(r.body())
        .isEqualTo("{\"error\":\"SOME_CODE\",\"message\":\"some message\",\"details\":{\"a\":\"b\"}}");
  }

  @Test
  void detailsAreOmittedWhenEmpty() {
    TestResponse r = client().get("/usecase-nodetails").send();
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.body()).isEqualTo("{\"error\":\"GONE\",\"message\":\"it is gone\"}");
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
  void aUseCaseErrorIsAServiceError() {
    ServiceError e = UseCaseError.conflict("TAKEN", "already taken");
    assertThat(e.httpStatus()).isEqualTo(409);
    assertThat(e.code()).isEqualTo("TAKEN");
    assertThat(e.message()).isEqualTo("already taken");
    assertThat(e.details()).isEmpty();
  }
}
