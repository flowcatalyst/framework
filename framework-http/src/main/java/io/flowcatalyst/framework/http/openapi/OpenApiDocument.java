package io.flowcatalyst.framework.http.openapi;

import java.util.List;

/** The result of writing the OpenAPI document: its text, or every problem found. */
public sealed interface OpenApiDocument {

  /**
   * The document.
   *
   * @param json UTF-8 JSON formatted like {@code JSON.stringify(document, null, 2)} with a final
   *     newline (contract section 4.1)
   */
  record Written(String json) implements OpenApiDocument {}

  /**
   * The document could not be written.
   *
   * @param problems every problem found, each one line naming its subject
   */
  record Refused(List<String> problems) implements OpenApiDocument {
    /**
     * Copies the problems.
     *
     * @param problems the problems
     */
    public Refused {
      problems = List.copyOf(problems);
    }
  }
}
