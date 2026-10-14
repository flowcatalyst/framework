package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.openapi.Docs.compact;
import static io.flowcatalyst.framework.http.openapi.Docs.doc;
import static io.flowcatalyst.framework.http.openapi.Docs.problems;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The document (contract sections 4, 6, 7): structure, implied statuses, security, determinism. */
class DocumentTest {

  record View(String id) {}

  record Body(String a) {
    static final Validator<Body> V = Validator.of(Body.class);
  }

  static final JsonNode FIXTURE = Json.mapper().readTree(OpenApiFixture.fixture());

  static JsonNode op(String path, String method) {
    return FIXTURE.path("paths").path(path).path(method);
  }

  static List<String> statuses(JsonNode op) {
    return op.path("responses").propertyNames().stream().toList();
  }

  @Test
  void theTopLevelIsInTheContractsOrder() {
    assertThat(FIXTURE.propertyNames()).containsExactly("openapi", "info", "paths", "components", "tags");
    assertThat(FIXTURE.get("openapi").asString()).isEqualTo("3.1.0");
    assertThat(compact(FIXTURE.get("info"))).isEqualTo("{\"title\":\"fixture\",\"version\":\"1\"}");
    assertThat(FIXTURE.get("components").propertyNames()).containsExactly("schemas", "responses", "securitySchemes");
    assertThat(compact(FIXTURE.get("tags"))).isEqualTo("[{\"name\":\"shapes\"},{\"name\":\"widgets\"}]");
  }

  @Test
  void pathsAreSortedByBytesAndMethodsInTheirFixedOrder() {
    assertThat(FIXTURE.get("paths").propertyNames())
        .containsExactly(
            "/api/colours/{colour}/{version}", "/api/ping", "/api/widgets", "/api/widgets/{id}",
            "/api/widgets/{id}/note", "/api/widgets/{id}/shapes/{index}");
    assertThat(FIXTURE.path("paths").path("/api/widgets/{id}").propertyNames()).containsExactly("get", "put", "delete");

    Operation<NoBody, View> get = Operation.get("/é", View.class).id("a").build();
    Operation<NoBody, View> post = Operation.post("/Z", View.class).id("b").build();
    Operation<NoBody, View> other = Operation.get("/a", View.class).id("c").build();
    Operation<Body, View> patch = Operation.patch("/a", Body.V, View.class).id("d").build();
    Operation<NoBody, NoBody> delete = Operation.delete("/a", NoBody.class).id("e").build();
    Operation<Body, View> put = Operation.put("/a", Body.V, View.class).id("f").build();
    JsonNode doc = doc(get, post, other, patch, delete, put);
    assertThat(doc.get("paths").propertyNames()).containsExactly("/Z", "/a", "/é");
    assertThat(doc.path("paths").path("/a").propertyNames()).containsExactly("get", "put", "delete", "patch");
  }

  @Test
  void anOperationObjectIsInTheContractsOrder() {
    assertThat(op("/api/widgets", "post").propertyNames())
        .containsExactly("operationId", "summary", "tags", "requestBody", "responses", "security", "x-fw-permission");
    assertThat(op("/api/ping", "post").propertyNames()).containsExactly("operationId", "summary", "description", "responses");
    assertThat(compact(op("/api/widgets", "post").get("requestBody")))
        .isEqualTo("{\"required\":true,\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/WidgetBody\"}}}}");
  }

  @Test
  void parametersArePathInPatternOrderThenQueryInDeclarationOrder() {
    JsonNode params = op("/api/colours/{colour}/{version}", "get").get("parameters");
    assertThat(params.findValuesAsString("name")).containsExactly("colour", "version");
    assertThat(compact(op("/api/widgets", "get").get("parameters")))
        .isEqualTo(
            "[{\"name\":\"colour\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]}},"
                + "{\"name\":\"limit\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"integer\",\"format\":\"int32\",\"default\":20}},"
                + "{\"name\":\"cursor\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"string\"}},"
                + "{\"name\":\"status\",\"in\":\"query\",\"required\":false,\"style\":\"form\",\"explode\":true,"
                + "\"schema\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]}}},"
                + "{\"name\":\"includeArchived\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"boolean\",\"default\":false}}]");
    assertThat(compact(op("/api/widgets/{id}/shapes/{index}", "get").get("parameters").get(2)))
        .isEqualTo("{\"name\":\"after\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"integer\",\"format\":\"int64\"}}");
  }

  // ---- statuses ----------------------------------------------------------------------------------

  @Test
  void theStatusesFollowTheOperationsShape() {
    assertThat(statuses(op("/api/ping", "post"))).as("public, no parameters").containsExactly("200", "default");
    assertThat(statuses(op("/api/widgets", "get"))).as("query parameters").containsExactly("200", "400", "default");
    assertThat(statuses(op("/api/widgets/{id}", "get"))).as("context, path, declared 404")
        .containsExactly("200", "400", "401", "403", "404", "default");
    assertThat(statuses(op("/api/widgets", "post"))).as("body, permission, 201, declared 409")
        .containsExactly("201", "400", "401", "403", "409", "413", "415", "default");
    assertThat(statuses(op("/api/widgets/{id}/note", "patch"))).as("202").containsExactly("202", "400", "401", "403", "413", "415", "default");
    assertThat(statuses(op("/api/widgets/{id}", "delete"))).as("204").containsExactly("204", "400", "401", "403", "default");
  }

  @Test
  void impliedStatusesShareOneErrorResponseAndDeclaredOnesListTheirCodes() {
    JsonNode responses = op("/api/widgets", "post").get("responses");
    assertThat(compact(responses.get("400"))).isEqualTo("{\"$ref\":\"#/components/responses/Error\"}");
    assertThat(compact(responses.get("default"))).isEqualTo("{\"$ref\":\"#/components/responses/Error\"}");
    assertThat(compact(responses.get("409")))
        .isEqualTo(
            "{\"description\":\"CODE_TAKEN: a widget has this code\\nNAME_TAKEN: a widget has this name\","
                + "\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Error\"}}}}");
    assertThat(compact(op("/api/widgets/{id}", "delete").path("responses").path("204"))).isEqualTo("{\"description\":\"No Content\"}");
    assertThat(op("/api/widgets", "post").path("responses").path("201").path("description").asString()).isEqualTo("Created");
    assertThat(op("/api/widgets/{id}/note", "patch").path("responses").path("202").path("description").asString()).isEqualTo("Accepted");
  }

  @Test
  void theErrorEnvelopeIsOneSharedComponentAndResponse() {
    assertThat(compact(FIXTURE.path("components").path("schemas").path("Error")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"error\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"},"
                + "\"details\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},"
                + "\"required\":[\"error\",\"message\"],\"additionalProperties\":false}");
    assertThat(compact(FIXTURE.path("components").path("responses")))
        .isEqualTo("{\"Error\":{\"description\":\"Error\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Error\"}}}}}");
  }

  // ---- security ----------------------------------------------------------------------------------

  @Test
  void aContextOperationListsEverySchemeSessionFirstThenBearerThenTheRestByName() {
    assertThat(compact(op("/api/widgets/{id}", "get").get("security")))
        .isEqualTo("[{\"session\":[]},{\"bearer\":[]},{\"basic\":[]}]");
    assertThat(op("/api/ping", "post").has("security")).isFalse();
    assertThat(compact(FIXTURE.path("components").path("securitySchemes")))
        .isEqualTo(
            "{\"session\":{\"type\":\"apiKey\",\"in\":\"cookie\",\"name\":\"__Host-fw_session\"},"
                + "\"bearer\":{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"},"
                + "\"basic\":{\"type\":\"http\",\"scheme\":\"basic\"}}");
    assertThat(Emitter.schemeOrder(java.util.Set.of("zeta", "bearer", "alpha", "session")))
        .containsExactly("session", "bearer", "alpha", "zeta");
  }

  @Test
  void aContextOperationWithoutAnySchemeFailsTheDump() {
    Operation<NoBody, View> op = Operation.get("/x", View.class).id("getX").requiresContext().build();

    assertThat(OpenApiFixture.problems(a -> {
          OpenApi.info(a, "t", "1");
          OpenApi.declare(a, op);
        }))
        .containsExactly(
            "getX: the operation requires a context but no security scheme was contributed; say how callers"
                + " authenticate (framework-auth contributes them, or OpenApi.securityScheme)");
  }

  @Test
  void aPublicOperationNeedsNoScheme() {
    Operation<NoBody, View> op = Operation.get("/x", View.class).id("getX").build();
    String json = OpenApiFixture.json(a -> {
      OpenApi.info(a, "t", "1");
      OpenApi.declare(a, op);
    });

    assertThat(Json.mapper().readTree(json).get("components").propertyNames()).containsExactly("schemas", "responses");
  }

  @Test
  void theSameSchemeTwiceAndABadSchemeNameFail() {
    Operation<NoBody, View> op = Operation.get("/x", View.class).id("getX").build();

    assertThat(problems(a -> OpenApi.securityScheme(a, "session", SecurityScheme.cookie("x")), op))
        .containsExactly("security scheme 'session' is contributed twice (module 'fixture')");
    assertThat(problems(a -> OpenApi.securityScheme(a, "1st", SecurityScheme.header("X-Key")), op))
        .containsExactly("security scheme '1st' must match [A-Za-z][A-Za-z0-9_-]*");
  }

  @Test
  void thePermissionIsDocumented() {
    assertThat(op("/api/widgets", "post").get("x-fw-permission").asString()).isEqualTo("fixture:widget:manage");
    assertThat(op("/api/widgets/{id}", "get").has("x-fw-permission")).isFalse();
  }

  // ---- whole-document problems ---------------------------------------------------------------------

  @Test
  void infoIsGivenExactlyOnce() {
    Operation<NoBody, View> op = Operation.get("/x", View.class).id("getX").build();

    assertThat(OpenApiFixture.problems(a -> OpenApi.declare(a, op)))
        .containsExactly("the document has no info: call OpenApi.info(app, title, version) once, in register");
    assertThat(problems(a -> OpenApi.info(a, "again", "2"), op))
        .containsExactly("OpenApi.info is called 2 times (modules [fixture, fixture]); once per application");
  }

  @Test
  void idsAndShapesAreUniqueAndDescriptorProblemsAreListed() {
    Operation<NoBody, View> a = Operation.get("/a", View.class).id("getX").build();
    Operation<NoBody, View> b = Operation.get("/b", View.class).id("getX").build();
    Operation<NoBody, View> c = Operation.get("/c/{id}", View.class).id("getC").path(io.flowcatalyst.framework.http.PathParam.string("id")).build();
    Operation<NoBody, View> d = Operation.get("/c/{other}", View.class).id("getD").path(io.flowcatalyst.framework.http.PathParam.string("other")).build();
    Operation<NoBody, View> e = Operation.get("/e", View.class).id("Bad").build();

    assertThat(problems(a, b, c, d, e, a))
        .containsExactly(
            "getX: two operations have this id (GET /a and GET /b)",
            "getD: GET /c/{other} collides with getC",
            "Bad: operation id 'Bad' must match [a-z][A-Za-z0-9]*",
            "getX: the operation is declared twice");
  }

  record Bad(java.util.Optional<String> a, Object b) {}

  @Test
  void everyProblemIsCollectedNotJustTheFirst() {
    Operation<NoBody, Bad> op = Operation.get("/x", Bad.class).id("getX").build();

    assertThat(OpenApiFixture.problems(x -> OpenApi.declare(x, op)))
        .containsExactly(
            "the document has no info: call OpenApi.info(app, title, version) once, in register",
            "getX.response.a: Optional is refused (house rule: never a field); make the component @Nullable",
            "getX.response.b: Object is refused; name the type");
  }

  // ---- determinism and formatting ---------------------------------------------------------------

  @Test
  void theSameDeclarationsGiveTheSameBytesInAnyOrder() {
    String first = OpenApiFixture.fixture();
    assertThat(OpenApiFixture.fixture()).isEqualTo(first);
    Random random = new Random(42);
    for (int i = 0; i < 5; i++) {
      List<Operation<?, ?>> shuffled = new ArrayList<>(OpenApiFixture.ALL);
      Collections.shuffle(shuffled, random);
      String json =
          OpenApiFixture.json(a -> {
            OpenApi.securityScheme(a, "session", SecurityScheme.cookie("__Host-fw_session"));
            OpenApi.securityScheme(a, "basic", SecurityScheme.httpBasic());
            OpenApi.schema(a, OpenApiFixture.Money.class,
                Schema.string().pattern("^-?[0-9]+\\.[0-9]{2}$").description("an amount with two decimals"));
            OpenApi.declare(a, shuffled.toArray(Operation<?, ?>[]::new));
            OpenApi.securityScheme(a, "bearer", SecurityScheme.bearer("JWT"));
            OpenApi.info(a, "fixture", "1");
          });
      assertThat(json).isEqualTo(first);
    }
  }

  @Test
  void theDocumentIsFormattedLikeJsonStringifyWithTwoSpaces() {
    String json = OpenApiFixture.fixture();
    assertThat(json).startsWith("{\n  \"openapi\": \"3.1.0\",\n  \"info\": {\n    \"title\": \"fixture\",\n");
    assertThat(json).endsWith("\n}\n").doesNotContain("\r").doesNotContain("null");
    assertThat(json).contains("\"security\": [\n          {\n            \"session\": []\n          },");
  }

  @Test
  void theWriterEscapesOnlyWhatJsonStringifyEscapes() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("s", "q\" b\\ n\n t\t c\u0001 d\u007f é </script>&/ 😀 \ud800x");
    m.put("e", Map.of());
    m.put("l", List.of());
    m.put("n", List.of(1L, new BigDecimal("2.50"), new BigDecimal("1E+3"), new BigDecimal("0.000"), true));

    assertThat(JsonText.write(m))
        .isEqualTo(
            "{\n  \"s\": \"q\\\" b\\\\ n\\n t\\t c\\u0001 d\u007f é </script>&/ 😀 \\ud800x\",\n  \"e\": {},\n  \"l\": [],\n"
                + "  \"n\": [\n    1,\n    2.5,\n    1000,\n    0,\n    true\n  ]\n}\n");
  }
}
