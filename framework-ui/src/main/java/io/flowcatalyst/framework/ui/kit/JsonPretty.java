package io.flowcatalyst.framework.ui.kit;

import tools.jackson.databind.JsonNode;

/**
 * The json-block's pretty printer (json-block.md): 2-space indent, {@code "key": value}, one member
 * or element per line, {@code {}} and {@code []} when empty, object keys in the order given, and no
 * HTML escaping (the template escapes the whole text afterwards). Jackson's default printer writes
 * {@code "a" : 1} and cannot be told otherwise, so this is a small dedicated one.
 */
public final class JsonPretty {

  private JsonPretty() {}

  /**
   * The formatted text of a JSON value.
   *
   * @param node the value, null is JSON null
   * @return the text
   */
  public static String print(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out, 0);
    return out.toString();
  }

  private static void indent(StringBuilder out, int depth) {
    for (int i = 0; i < depth; i++) {
      out.append("  ");
    }
  }

  private static void write(JsonNode node, StringBuilder out, int depth) {
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      boolean first = true;
      for (var e : node.properties()) {
        if (!first) {
          out.append(",\n");
        }
        first = false;
        indent(out, depth + 1);
        string(e.getKey(), out);
        out.append(": ");
        write(e.getValue(), out, depth + 1);
      }
      out.append('\n');
      indent(out, depth);
      out.append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      boolean first = true;
      for (JsonNode v : node) {
        if (!first) {
          out.append(",\n");
        }
        first = false;
        indent(out, depth + 1);
        write(v, out, depth + 1);
      }
      out.append('\n');
      indent(out, depth);
      out.append(']');
    } else if (node.isString()) {
      string(node.asString(), out);
    } else if (node.isNull() || node.isMissingNode()) {
      out.append("null");
    } else {
      // numbers and booleans: Jackson's own JSON text
      out.append(node.toString());
    }
  }

  private static void string(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
