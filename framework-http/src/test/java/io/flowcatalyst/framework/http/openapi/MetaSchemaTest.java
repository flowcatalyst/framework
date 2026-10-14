package io.flowcatalyst.framework.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Collections;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Colour;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.CursorPage;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Ids;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Line;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Money;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Scalars;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Shape;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.Widget;
import io.flowcatalyst.framework.http.openapi.OpenApiFixture.WidgetBody;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The emitted documents are valid OpenAPI 3.1 (the OpenAPI Initiative's schema), every component
 * is a valid JSON Schema 2020-12 schema, and what the typed-operation writer produces for a sample
 * of every row of the type table validates against the emitted schema (contract section 5.5: the
 * table cannot drift from Jackson).
 */
class MetaSchemaTest {

  static final String OAS = "https://spec.openapis.org/oas/3.1/schema/2022-10-07";
  static final String META = "https://json-schema.org/draft/2020-12/schema";
  static final String DOC = "https://fixture.test/openapi.json";

  static String resource(String name) {
    try (InputStream in = MetaSchemaTest.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static SchemaRegistry registry(String document) {
    return SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
        b -> b.schemas(Map.of(OAS, resource("/openapi/oas-3.1-schema-2022-10-07.json"), DOC, document)));
  }

  /** Validates a document against the OpenAPI 3.1 schema and its components against 2020-12. */
  static void assertValidOpenApi(String document) {
    SchemaRegistry registry = registry(document);
    JsonNode doc = Json.mapper().readTree(document);
    List<Error> errors = registry.getSchema(SchemaLocation.of(OAS)).validate(doc);
    assertThat(errors).as("OpenAPI 3.1 schema").isEmpty();
    var meta = registry.getSchema(SchemaLocation.of(META));
    for (String name : doc.path("components").path("schemas").propertyNames()) {
      assertThat(meta.validate(doc.path("components").path("schemas").path(name))).as(name).isEmpty();
    }
  }

  static List<Error> validate(String document, String component, Object value) {
    JsonNode instance = Json.mapper().readTree(Json.operationMapper().writeValueAsString(value));
    return registry(document)
        .getSchema(SchemaLocation.of(DOC + "#/components/schemas/" + component))
        .validate(instance, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
  }

  @Test
  void theFixtureIsAValidOpenApi31Document() {
    assertValidOpenApi(OpenApiFixture.fixture());
  }

  @Test
  void aBrokenDocumentIsCaughtByTheSchema() {
    String broken = OpenApiFixture.fixture().replace("\"openapi\": \"3.1.0\"", "\"openapi\": \"3.0.3\"");

    List<Error> errors = registry(broken).getSchema(SchemaLocation.of(OAS)).validate(Json.mapper().readTree(broken));
    assertThat(errors).isNotEmpty();
  }

  static Widget widget(String note) {
    Scalars scalars =
        new Scalars(
            "text", true, null, 7, 8, 9_007_199_254_740_993L, null, 0.5, 1.25, new BigDecimal("12.30"),
            Instant.parse("2026-10-14T10:15:30.123Z"), LocalDate.parse("2026-10-14"), Duration.ofSeconds(90),
            UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), new byte[] {1, 2, 3, (byte) 0xff},
            Json.mapper().readTree("{\"any\":[1,\"two\",null]}"), Colour.GREEN, new Money("-4.20"));
    Ids ids = new Ids(new ClientId("acme"), new PrincipalId("prn_1"), new ApplicationCode("orders"), new CorrelationId("c-1"));
    Collections collections =
        new Collections(
            List.of("a", "b"), List.of(1, 2), Set.of(Colour.RED), Map.of("x", 1L), List.of(new Line("SKU", 2)),
            Map.of("g", List.of("h")));
    return new Widget("w1", "Widget", new Shape.Square(2.0, null), scalars, ids, collections, note);
  }

  @Test
  void jacksonsOutputForEveryRowValidatesAgainstTheEmittedSchemas() {
    String fixture = OpenApiFixture.fixture();

    assertThat(validate(fixture, "Widget", widget(null))).isEmpty();
    assertThat(validate(fixture, "Widget", widget("a note"))).isEmpty();
    assertThat(validate(fixture, "CursorPageWidget", new CursorPage<>(List.of(widget(null)), true, "next"))).isEmpty();
    assertThat(validate(fixture, "Shape", new Shape.Circle(1.5))).isEmpty();
    assertThat(validate(fixture, "Shape", new Shape.Square(1.5, "label"))).isEmpty();
    assertThat(validate(fixture, "Pong", new OpenApiFixture.Pong(Instant.EPOCH))).isEmpty();
    WidgetBody body =
        new WidgetBody("name", "AB-1", null, 3, 1.0, List.of("t"), "red", new Shape.Circle(1), new Line("S", 1),
            List.of(new Line("T", 2)), "free");
    assertThat(validate(fixture, "WidgetBody", body)).isEmpty();
  }

  @Test
  void theErrorEnvelopeTheFrameworkWritesValidatesAgainstTheErrorComponent() {
    String fixture = OpenApiFixture.fixture();
    Response withDetails = HttpErrors.error(400, "VALIDATION", "request is invalid", Map.of("a", "NOT_BLANK: must not be blank"));
    Response plain = HttpErrors.error(404, "NOT_FOUND", "no route");

    for (Response r : List.of(withDetails, plain)) {
      byte[] bytes = ((Response.Body.Bytes) r.body()).content();
      JsonNode instance = Json.mapper().readTree(bytes);
      assertThat(
              registry(fixture).getSchema(SchemaLocation.of(DOC + "#/components/schemas/Error")).validate(instance))
          .isEmpty();
    }
  }

  @Test
  void theAgreementCheckCatchesADisagreement() {
    String fixture = OpenApiFixture.fixture();
    // the plain mapper writes BigDecimal as a number and nulls: the emitted schema refuses both
    JsonNode plain = Json.mapper().valueToTree(widget(null));
    List<Error> errors =
        registry(fixture).getSchema(SchemaLocation.of(DOC + "#/components/schemas/Widget")).validate(plain);

    assertThat(errors).isNotEmpty();
    assertThat(validate(fixture, "Widget", Map.of("id", "w1"))).isNotEmpty();
  }
}
