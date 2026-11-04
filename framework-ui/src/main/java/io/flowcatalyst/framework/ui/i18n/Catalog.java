package io.flowcatalyst.framework.ui.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One language's messages: key to {@link Message}. A catalog file is a JSON object whose values are
 * strings or plural objects (contract section 2).
 *
 * @param messages key to message
 */
public record Catalog(Map<String, Message> messages) {

  static final List<String> CATEGORIES = List.of("zero", "one", "two", "few", "many", "other");

  /** The shape of a message key: a namespace first, then at least one more segment. */
  public static final Pattern KEY = Pattern.compile("[a-z0-9]+(\\.[A-Za-z0-9_-]+)+");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Copies the messages.
   *
   * @param messages key to message
   */
  public Catalog {
    messages = Map.copyOf(messages);
  }

  /**
   * Reads a catalog file.
   *
   * @param json a JSON object of key to message
   * @return the catalog
   * @throws IllegalArgumentException for malformed JSON, a key that is not {@link #KEY}, a value
   *     that is neither a string nor a plural object of strings, an unknown plural category or a
   *     plural object without {@code other}
   */
  public static Catalog parse(String json) {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("catalog is not JSON: " + e.getMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("catalog is not a JSON object");
    }
    Map<String, Message> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : root.properties()) {
      String key = e.getKey();
      if (!KEY.matcher(key).matches()) {
        throw new IllegalArgumentException("invalid message key \"" + key + "\"");
      }
      JsonNode v = e.getValue();
      if (v.isString()) {
        out.put(key, new Message.Text(v.asString()));
      } else if (v.isObject()) {
        Map<String, String> forms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> f : v.properties()) {
          if (!f.getValue().isString()) {
            throw new IllegalArgumentException("message \"" + key + "\" must be a string or a plural object of strings");
          }
          forms.put(f.getKey(), f.getValue().asString());
        }
        try {
          out.put(key, new Message.Plural(forms));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException("message \"" + key + "\": " + ex.getMessage(), ex);
        }
      } else {
        throw new IllegalArgumentException("message \"" + key + "\" must be a string or a plural object of strings");
      }
    }
    return new Catalog(out);
  }
}
