package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.OpenApiDocument;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /auth/session} and {@code POST /auth/logout} as typed operations (sessions contract
 * section 4.5): the fixed components in {@code conformance/openapi/auth.json}, the security schemes
 * this module contributes, the {@code endSessionUrl} omission (section 4.4), and the real answers
 * validating against the emitted schemas.
 */
class AuthOpenApiTest {

  @TempDir Path dir;

  AuthApp t;

  @AfterEach
  void close() {
    if (t != null) {
      t.close();
    }
  }

  static Module info() {
    return new Module() {
      @Override
      public String id() {
        return "app";
      }

      @Override
      public void register(AppBuilder app) {
        OpenApi.info(app, "flowcatalyst-auth", "1");
      }
    };
  }

  /** The document of an application with only this module: no configuration is read. */
  static String document(Consumer<AuthModule.Builder> customise) {
    AuthModule.Builder b = AuthModule.builder();
    customise.accept(b);
    AppBuilder app = App.builder().environment(Map.of()).module(info()).module(b.build());
    return switch (OpenApi.document(app.declarations())) {
      case OpenApiDocument.Written w -> w.json();
      case OpenApiDocument.Refused r -> throw new AssertionError(String.join("\n", r.problems()));
    };
  }

  @Test
  void theTwoOperationsAndTheirFixedComponentsAreTheConformanceVector() {
    String json = document(b -> {});
    Conformance.check("openapi/auth.json", json);

    JsonNode doc = Json.mapper().readTree(json);
    assertThat(doc.get("paths").propertyNames()).containsExactly("/auth/logout", "/auth/session");
    assertThat(doc.path("components").path("schemas").propertyNames())
        .containsExactly("AuthCsrf", "AuthLogout", "AuthPrincipal", "AuthSession", "AuthSessionAnonymous", "AuthSessionAuthenticated", "Error");
    JsonNode session = doc.path("paths").path("/auth/session").path("get");
    assertThat(session.get("operationId").asString()).isEqualTo("getAuthSession");
    assertThat(session.get("responses").propertyNames()).containsExactly("200", "default");
    assertThat(session.has("security")).isFalse();
    JsonNode logout = doc.path("paths").path("/auth/logout").path("post");
    assertThat(logout.get("operationId").asString()).isEqualTo("logout");
    assertThat(logout.get("responses").propertyNames()).containsExactly("200", "403", "415", "default");
    assertThat(logout.has("security")).isFalse();
    assertThat(doc.path("components").path("schemas").path("AuthSession").toString())
        .isEqualTo("{\"oneOf\":[{\"$ref\":\"#/components/schemas/AuthSessionAuthenticated\"},{\"$ref\":\"#/components/schemas/AuthSessionAnonymous\"}]}");
    assertThat(doc.path("components").path("schemas").path("AuthSessionAuthenticated").path("properties").propertyNames())
        .containsExactly(
            "authenticated", "principal", "tier", "client", "clients", "applications", "roles", "permissions", "authTime",
            "expiresAt", "idleExpiresAt", "csrf");
    assertThat(doc.path("components").path("schemas").path("AuthSessionAuthenticated").path("properties").path("authenticated").toString())
        .isEqualTo("{\"type\":\"boolean\",\"const\":true}");
    assertThat(doc.path("components").path("schemas").path("AuthSessionAnonymous").path("properties").path("authenticated").toString())
        .isEqualTo("{\"type\":\"boolean\",\"const\":false}");
    assertThat(doc.path("components").path("schemas").path("AuthLogout").toString())
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"loggedOut\":{\"type\":\"boolean\",\"const\":true},"
                + "\"endSessionUrl\":{\"type\":\"string\"}},\"required\":[\"loggedOut\"],\"additionalProperties\":false}");
    assertThat(doc.path("components").path("securitySchemes").toString())
        .isEqualTo(
            "{\"session\":{\"type\":\"apiKey\",\"in\":\"cookie\",\"name\":\"__Host-fw_session\"},"
                + "\"bearer\":{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"}}");
  }

  @Test
  void withoutBearerTokensOnlyTheSessionSchemeIsContributed() {
    JsonNode doc = Json.mapper().readTree(document(b -> b.bearerTokens(false)));

    assertThat(doc.path("components").path("securitySchemes").propertyNames()).containsExactly("session");
  }

  // ---- endSessionUrl (section 4.4, amended) --------------------------------------------------------

  private TestResponse logout() {
    return t.browser().send(t.client.request(Method.POST, "/auth/logout"), null);
  }

  @Test
  void endSessionUrlIsOmittedWhenDiscoveryHasNone() {
    t = AuthApp.start(dir);
    Map<String, Object> changes = new HashMap<>();
    changes.put("end_session_endpoint", null);
    t.provider.discoveryChanges(changes);

    TestResponse r = logout();

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"loggedOut\":true}");
  }

  @Test
  void endSessionUrlIsOmittedWhenThePlatformCannotBeRead() {
    t = AuthApp.start(dir);
    t.provider.down(true);

    assertThat(logout().body()).isEqualTo("{\"loggedOut\":true}");
  }

  // ---- the real answers against the emitted schemas -----------------------------------------------

  static void assertValid(String component, String body) {
    String doc = document(b -> {});
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b.schemas(Map.of("https://auth.test/openapi.json", doc)))
            .getSchema(SchemaLocation.of("https://auth.test/openapi.json#/components/schemas/" + component));
    assertThat(schema.validate(Json.mapper().readTree(body), c -> c.executionConfig(x -> x.formatAssertionsEnabled(true))))
        .as(component + ": " + body)
        .isEmpty();
  }

  @Test
  void whatTheEndpointsAnswerValidatesAgainstTheDocument() {
    t = AuthApp.start(dir);
    Browser anonymous = t.browser();
    assertValid("AuthSession", anonymous.get("/auth/session").body());
    assertValid("AuthLogout", logout().body());

    t.provider.signInAs(Map.of("roles", List.of("orders:clerk")));
    Browser b = t.browser();
    b.signIn("/");
    String session = b.get("/auth/session").body();
    assertThat(session).contains("\"authenticated\":true");
    assertValid("AuthSession", session);
    assertValid("AuthSessionAuthenticated", session);
  }

  @Test
  void anUnknownTierIsLeftOutRatherThanBreakingTheDocument() {
    t = AuthApp.start(dir);
    t.provider.signInAs(Map.of("tier", "GALACTIC"));
    Browser b = t.browser();
    b.signIn("/");

    String session = b.get("/auth/session").body();
    assertThat(session).contains("\"authenticated\":true").doesNotContain("tier");
    assertValid("AuthSession", session);
  }
}
