package io.flowcatalyst.framework.httpclient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request path with {@code {name}} placeholders, checked so a request can never leave its
 * service: no {@code //}, {@code ?}, {@code #}, backslash, or {@code .} / {@code ..} segments; values
 * are percent-encoded as one segment and may not be empty, {@code .} or {@code ..}.
 */
final class PathTemplate {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
  private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9\\-._~!$&'()*+,;=:@]*(%[0-9A-Fa-f]{2}[A-Za-z0-9\\-._~!$&'()*+,;=:@]*)*");

  private final String text;
  private final List<String> segments;
  private final Set<String> names;

  PathTemplate(String text) {
    if (!text.startsWith("/")) {
      throw new IllegalArgumentException("a request path must start with /");
    }
    if (text.contains("//") || text.contains("?") || text.contains("#") || text.contains("\\")) {
      throw new IllegalArgumentException("a request path may not contain //, ?, # or \\ (use query())");
    }
    this.text = text;
    this.segments = List.of(text.substring(1).split("/", -1));
    Set<String> found = new LinkedHashSet<>();
    for (String segment : segments) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("a request path may not contain . or .. segments");
      }
      Matcher m = PLACEHOLDER.matcher(segment);
      while (m.find()) {
        if (!found.add(m.group(1))) {
          throw new IllegalArgumentException("placeholder {" + m.group(1) + "} appears twice");
        }
      }
      String literal = PLACEHOLDER.matcher(segment).replaceAll("");
      if (!LITERAL.matcher(literal).matches()) {
        throw new IllegalArgumentException(
            "a request path may contain only unreserved characters, sub-delimiters, ':', '@' and %XX");
      }
    }
    this.names = Set.copyOf(found);
  }

  String text() {
    return text;
  }

  Set<String> names() {
    return names;
  }

  /** The filled, encoded path. Every placeholder needs a value. */
  String fill(Map<String, String> values) {
    for (String name : values.keySet()) {
      if (!names.contains(name)) {
        throw new IllegalArgumentException("the path " + text + " has no placeholder {" + name + "}");
      }
    }
    List<String> out = new ArrayList<>();
    for (String segment : segments) {
      Matcher m = PLACEHOLDER.matcher(segment);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        String value = values.get(m.group(1));
        if (value == null) {
          throw new IllegalArgumentException("no value for {" + m.group(1) + "} in " + text);
        }
        m.appendReplacement(sb, Matcher.quoteReplacement(encodeSegment(value)));
      }
      m.appendTail(sb);
      out.add(sb.toString());
    }
    return "/" + String.join("/", out);
  }

  static String encodeSegment(String value) {
    if (value.isEmpty() || value.equals(".") || value.equals("..")) {
      throw new IllegalArgumentException("a path parameter may not be empty, . or ..");
    }
    return encode(value);
  }

  /** Percent-encodes everything but RFC 3986 unreserved characters. */
  static String encode(String value) {
    StringBuilder sb = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '.' || c == '_' || c == '~') {
        sb.append((char) c);
      } else {
        sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return sb.toString();
  }
}
