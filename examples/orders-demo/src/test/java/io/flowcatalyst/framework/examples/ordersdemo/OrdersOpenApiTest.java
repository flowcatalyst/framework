package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.conformance.Conformance;
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
    Conformance.check("openapi/orders-demo.json", Files.readString(DOCUMENT, StandardCharsets.UTF_8));
  }
}
