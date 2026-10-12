package io.flowcatalyst.framework.conformance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads and writes JSON vectors with one fixed layout, so a rewritten file only changes where the values did. */
public final class JsonVectors {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private JsonVectors() {}

  /**
   * The mapper the vector tests use.
   *
   * @return the mapper
   */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /**
   * Reads a vector file.
   *
   * @param relative the path below {@code conformance/}
   * @return its root object
   */
  public static ObjectNode read(String relative) {
    return (ObjectNode) MAPPER.readTree(Conformance.read(relative));
  }

  /**
   * Compares (or, in update mode, writes) a vector file holding {@code doc}.
   *
   * @param relative the path below {@code conformance/}
   * @param doc the document, inputs and expected outputs
   */
  public static void check(String relative, JsonNode doc) {
    Conformance.check(relative, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n");
  }
}
