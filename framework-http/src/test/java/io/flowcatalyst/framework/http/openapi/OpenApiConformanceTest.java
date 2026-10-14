package io.flowcatalyst.framework.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Writes or checks {@code conformance/openapi/fixture.json} (contract section 10): the document of
 * {@link OpenApiFixture}, which the Go emitter must reproduce byte for byte. Every committed vector
 * of the directory ({@code auth.json} from framework-auth, {@code orders-demo.json} from the
 * example) must be a valid OpenAPI 3.1 document.
 */
class OpenApiConformanceTest {

  @Test
  void theFixtureDocument() {
    String json = OpenApiFixture.fixture();
    MetaSchemaTest.assertValidOpenApi(json);
    Conformance.check("openapi/fixture.json", json);
  }

  @Test
  void everyCommittedVectorIsValidOpenApi() throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(Conformance.file("openapi"))) {
      files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
    assertThat(files).extracting(p -> p.getFileName().toString()).contains("fixture.json");
    for (Path file : files) {
      String json = Files.readString(file, StandardCharsets.UTF_8);
      MetaSchemaTest.assertValidOpenApi(json);
      assertThat(json).as(file + " ends with one newline").endsWith("}\n");
    }
  }
}
