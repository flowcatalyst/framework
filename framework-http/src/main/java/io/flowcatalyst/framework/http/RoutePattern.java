package io.flowcatalyst.framework.http;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed route pattern: {@code /orders/{id}}. Segments are literals or {@code {name}}
 * parameters; a trailing slash is a trailing empty literal segment.
 *
 * @param text the pattern as written
 * @param segments the raw segments; parameters are written {@code {name}}
 * @param paramNames the parameter names in segment order
 */
record RoutePattern(String text, List<String> segments, List<String> paramNames) {

  private static final java.util.regex.Pattern PARAM = java.util.regex.Pattern.compile("\\{([a-z][A-Za-z0-9]*)}");

  static RoutePattern parse(String text) {
    if (!text.startsWith("/")) {
      throw new IllegalArgumentException("route pattern must start with '/': " + text);
    }
    String[] parts = text.substring(1).split("/", -1);
    List<String> segments = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty() && i != parts.length - 1) {
        throw new IllegalArgumentException("route pattern has an empty segment: " + text);
      }
      if (part.indexOf('{') >= 0 || part.indexOf('}') >= 0) {
        var m = PARAM.matcher(part);
        if (!m.matches()) {
          throw new IllegalArgumentException(
              "route pattern parameter must be a whole segment {name} with name [a-z][A-Za-z0-9]*: " + text);
        }
        if (names.contains(m.group(1))) {
          throw new IllegalArgumentException("route pattern repeats parameter '" + m.group(1) + "': " + text);
        }
        names.add(m.group(1));
      }
      segments.add(part);
    }
    return new RoutePattern(text, List.copyOf(segments), List.copyOf(names));
  }

  boolean isParam(int i) {
    return segments.get(i).startsWith("{");
  }

  /** The shape: parameter names ignored, so {@code /a/{x}} and {@code /a/{y}} are equal. */
  String shape() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
      sb.append('/').append(isParam(i) ? "{}" : segments.get(i));
    }
    return sb.toString();
  }
}
