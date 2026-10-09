package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON for the receivers: one strict mapper, and small readers that never throw for bad input. */
final class Envelopes {

  /**
   * Duplicate keys are an error: a body with two {@code type} or {@code jobCode} fields is
   * ambiguous, and different parsers would disagree about it.
   */
  static final JsonMapper STRICT =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  /** Tolerates duplicate keys (the last wins): only to recognise an envelope's shape. */
  private static final JsonMapper LENIENT = JsonMapper.builder().build();

  /** For writing completion results. */
  static final JsonMapper JSON = STRICT;

  private Envelopes() {}

  /**
   * Parses bytes as JSON.
   *
   * @param body the raw body
   * @return the tree, or null when the body is empty or not valid JSON
   */
  static @Nullable JsonNode readTree(byte[] body) {
    return readTree(body, true);
  }

  /**
   * Parses bytes as JSON.
   *
   * @param body the raw body
   * @param strict whether duplicate keys make the body invalid
   * @return the tree, or null when the body is empty or not valid JSON
   */
  static @Nullable JsonNode readTree(byte[] body, boolean strict) {
    if (body.length == 0) {
      return null;
    }
    try {
      JsonNode node = (strict ? STRICT : LENIENT).readTree(body);
      return node == null || node.isMissingNode() ? null : node;
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * A string field.
   *
   * @param node an object
   * @param field the field name
   * @return its value when it is a string, else null
   */
  static @Nullable String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }
}
