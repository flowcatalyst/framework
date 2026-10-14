package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.core.AppBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stale-document detection for the Maven build (contract section 9): a unit test regenerates the
 * document from the application's modules, with no configuration and no database, and fails with a
 * diff when the committed file differs.
 *
 * <pre>{@code
 * @Test
 * void theCommittedDocumentIsCurrent() {
 *   OpenApiTesting.assertCommitted(MyApp.builder(), Path.of("web/openapi.json"));
 * }
 * }</pre>
 *
 * <p>Run the test with {@code -Dopenapi.update=true} to write the file instead, then review the
 * diff and commit it.
 */
public final class OpenApiTesting {

  private OpenApiTesting() {}

  /**
   * Fails unless the committed document equals the one the builder's modules declare.
   *
   * @param app the application's builder, not started; it is used up
   * @param committed the committed document, for example {@code web/openapi.json}
   * @throws AssertionError with every problem, or with a line diff
   */
  public static void assertCommitted(AppBuilder app, Path committed) {
    String json =
        switch (OpenApi.document(app.declarations())) {
          case OpenApiDocument.Refused refused ->
              throw new AssertionError(
                  "the OpenAPI document cannot be written:\n  - " + String.join("\n  - ", refused.problems()));
          case OpenApiDocument.Written written -> written.json();
        };
    try {
      if (Boolean.getBoolean("openapi.update")) {
        Path parent = committed.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(committed, json, StandardCharsets.UTF_8);
        return;
      }
      if (!Files.exists(committed)) {
        throw new AssertionError(
            committed + " does not exist; write it with the openapi command (java -jar app.jar openapi --out "
                + committed + ") or run this test with -Dopenapi.update=true, then commit it");
      }
      String current = Files.readString(committed, StandardCharsets.UTF_8);
      if (!current.equals(json)) {
        throw new AssertionError(
            committed + " is stale: the operations changed and the document was not regenerated (run with"
                + " -Dopenapi.update=true or the openapi command, then commit)\n" + diff(current, json));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A line diff of the committed text against the generated one ({@code -} committed, {@code +} generated). */
  static String diff(String committed, String generated) {
    String[] a = committed.split("\n", -1);
    String[] b = generated.split("\n", -1);
    int[][] lcs = new int[a.length + 1][b.length + 1];
    for (int i = a.length - 1; i >= 0; i--) {
      for (int j = b.length - 1; j >= 0; j--) {
        lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    List<String> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i].equals(b[j])) {
        i++;
        j++;
      } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
        out.add("+" + (j + 1) + ": " + b[j]);
        j++;
      } else {
        out.add("-" + (i + 1) + ": " + a[i]);
        i++;
      }
      if (out.size() >= 200) {
        out.add("... (diff cut at 200 lines)");
        break;
      }
    }
    return String.join("\n", out);
  }
}
