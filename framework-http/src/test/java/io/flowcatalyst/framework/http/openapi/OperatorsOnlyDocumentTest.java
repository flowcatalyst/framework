package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.openapi.Docs.doc;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** An operators-only operation is documented as {@code x-fw-operators-only: true}, after {@code x-fw-permission}. */
class OperatorsOnlyDocumentTest {

  record View(String id) {}

  @Test
  void theMarkerIsPresentWithTheOptionAndAbsentWithout() {
    Operation<NoBody, View> only =
        Operation.get("/api/vault", View.class).id("getVault").tag("vault").permission("v:vault:read").operatorsOnly().build();
    Operation<NoBody, View> plain =
        Operation.get("/api/plain", View.class).id("getPlain").tag("vault").permission("v:vault:read").build();
    Operation<NoBody, View> open =
        Operation.get("/api/open", View.class).id("getOpen").tag("vault").operatorsOnly().build();
    JsonNode doc = doc(only, plain, open);

    JsonNode marked = doc.path("paths").path("/api/vault").path("get");
    assertThat(marked.get("x-fw-operators-only").asBoolean()).isTrue();
    assertThat(marked.propertyNames()).containsExactly("operationId", "tags", "responses", "security", "x-fw-permission", "x-fw-operators-only");
    assertThat(doc.path("paths").path("/api/plain").path("get").has("x-fw-operators-only")).isFalse();
    // without a permission the marker stands alone, and the operation still lists its security
    JsonNode alone = doc.path("paths").path("/api/open").path("get");
    assertThat(alone.get("x-fw-operators-only").asBoolean()).isTrue();
    assertThat(alone.has("x-fw-permission")).isFalse();
    assertThat(alone.has("security")).isTrue();
  }
}
