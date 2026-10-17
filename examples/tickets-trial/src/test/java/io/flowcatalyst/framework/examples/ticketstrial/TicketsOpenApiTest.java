package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.http.openapi.OpenApiTesting;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The committed document is current (OpenAPI contract section 9): regenerated from the web
 * application's modules with no database and no configuration. Run with {@code
 * -Dopenapi.update=true} to rewrite {@code web/openapi.json} after changing an operation; the SPA's
 * client in {@code ui/apps/tickets} is generated from that file.
 */
class TicketsOpenApiTest {

  static final Path DOCUMENT = Path.of("web/openapi.json");

  @Test
  void theCommittedDocumentIsCurrent() {
    OpenApiTesting.assertCommitted(TicketsWeb.builder(), DOCUMENT);
  }
}
