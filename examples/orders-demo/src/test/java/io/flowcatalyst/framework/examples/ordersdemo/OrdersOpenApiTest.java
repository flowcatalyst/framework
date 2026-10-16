package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.OpenApiDocument;
import io.flowcatalyst.framework.http.openapi.OpenApiTesting;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The committed document is current (OpenAPI contract section 9): regenerated from the web
 * application's modules with no database and no configuration. Run with {@code
 * -Dopenapi.update=true} to rewrite {@code web/openapi.json} after changing an operation. The same
 * bytes are the sample application vector {@code conformance/openapi/orders-demo.json}.
 */
class OrdersOpenApiTest {

  static final Path DOCUMENT = Path.of("web/openapi.json");

  @Test
  void theCommittedDocumentIsCurrent() throws Exception {
    OpenApiTesting.assertCommitted(OrdersWeb.builder(), DOCUMENT);
    // The same bytes with the admin area: the vector the Go framework compares once it builds admin (G6).
    Conformance.check("openapi/orders-demo-admin.json", Files.readString(DOCUMENT, StandardCharsets.UTF_8));
  }

  /** The orders-only application (no admin area) is the vector the Go framework passes today. */
  @Test
  void theApplicationWithoutItsAdminAreaIsTheSharedVector() {
    String json =
        switch (OpenApi.document(OrdersWeb.withoutAdmin().declarations())) {
          case OpenApiDocument.Written written -> written.json();
          case OpenApiDocument.Refused refused -> throw new AssertionError(refused.problems());
        };
    Conformance.check("openapi/orders-demo.json", json);
  }
}
