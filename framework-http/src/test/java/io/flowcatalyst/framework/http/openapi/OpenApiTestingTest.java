package io.flowcatalyst.framework.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stale-document detection in the Maven build (contract section 9). */
class OpenApiTestingTest {

  @TempDir Path dir;

  record Unwritable(String text, java.util.Optional<String> note) {}

  @Test
  void assertBuildsPassesForADocumentThatCanBeWrittenWithNoFile() {
    OpenApiTesting.assertBuilds(OpenApiFixture.app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)));
  }

  @Test
  void assertBuildsFailsWithEveryProblem() {
    Operation<NoBody, Unwritable> bad = Operation.get("/unwritable", Unwritable.class).id("unwritable").build();

    Throwable t =
        catchThrowable(
            () ->
                OpenApiTesting.assertBuilds(
                    OpenApiFixture.app(a -> OpenApi.declare(a, bad)))); // no OpenApi.info either

    assertThat(t)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("the OpenAPI document cannot be written")
        .hasMessageContaining("Optional is refused")
        .hasMessageContaining("no info");
  }

  @Test
  void aCurrentDocumentPasses() throws Exception {
    Path file = dir.resolve("openapi.json");
    Files.writeString(file, OpenApiFixture.fixture());

    OpenApiTesting.assertCommitted(OpenApiFixture.app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)), file);
  }

  @Test
  void aStaleDocumentFailsWithADiff() throws Exception {
    Path file = dir.resolve("openapi.json");
    Files.writeString(file, OpenApiFixture.fixture());
    List<Operation<?, ?>> fewer = OpenApiFixture.ALL.stream().filter(o -> o != OpenApiFixture.PING).toList();

    Throwable t =
        catchThrowable(() -> OpenApiTesting.assertCommitted(OpenApiFixture.app(a -> OpenApiFixture.register(a, fewer)), file));

    assertThat(t).isInstanceOf(AssertionError.class).hasMessageContaining(file + " is stale");
    assertThat(t.getMessage()).contains("-").contains("\"operationId\": \"ping\"");
    assertThat(t.getMessage().lines().filter(l -> l.startsWith("+"))).isEmpty();
  }

  @Test
  void aChangedTypeShowsBothSides() throws Exception {
    Path file = dir.resolve("openapi.json");
    Files.writeString(file, OpenApiFixture.fixture().replace("\"title\": \"fixture\"", "\"title\": \"old\""));

    Throwable t =
        catchThrowable(
            () -> OpenApiTesting.assertCommitted(OpenApiFixture.app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)), file));

    assertThat(t.getMessage()).contains("-4:     \"title\": \"old\",").contains("+4:     \"title\": \"fixture\",");
  }

  @Test
  void aMissingFileFailsAndSaysHowToWriteIt() {
    Path file = dir.resolve("web/openapi.json");

    Throwable t =
        catchThrowable(
            () -> OpenApiTesting.assertCommitted(OpenApiFixture.app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)), file));

    assertThat(t).isInstanceOf(AssertionError.class).hasMessageContaining("does not exist").hasMessageContaining("openapi --out");
  }

  @Test
  void problemsFailTheAssertion() {
    record Bad(Object x) {}
    Operation<NoBody, Bad> op = Operation.get("/x", Bad.class).id("getX").build();

    Throwable t =
        catchThrowable(() -> OpenApiTesting.assertCommitted(OpenApiFixture.app(a -> OpenApi.declare(a, op)), dir.resolve("x.json")));

    assertThat(t).isInstanceOf(AssertionError.class).hasMessageContaining("cannot be written");
  }
}
