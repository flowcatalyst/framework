package io.flowcatalyst.framework.objectstore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Whether a string is a media type, read exactly as Go's {@code mime.ParseMediaType} reads one (Go
 * 1.27, {@code mime/mediatype.go}), so the Java and Go object stores accept the same content types.
 * The caller has already refused anything but printable ASCII, so white space here is the space.
 */
final class MediaTypes {

  private static final String TSPECIALS = "()<>@,;:\\\"/[]?=";

  private MediaTypes() {}

  /** A parameter read by {@link #consumeMediaParam}: lower-case name, value and the rest. */
  private record Param(String name, String value, String rest) {}

  /** The token read and the rest. */
  private record Split(String token, String rest) {}

  static boolean valid(String v) {
    int semi = v.indexOf(';');
    String base = semi < 0 ? v : v.substring(0, semi);
    if (!checkMediaTypeDisposition(base.toLowerCase(Locale.ROOT).strip())) {
      return false;
    }
    Map<String, String> params = new HashMap<>();
    Map<String, Map<String, String>> continuation = new HashMap<>();
    String rest = v.substring(base.length());
    while (!rest.isEmpty()) {
      rest = rest.stripLeading();
      if (rest.isEmpty()) {
        break;
      }
      Param p = consumeMediaParam(rest);
      if (p == null) {
        if (rest.strip().equals(";")) {
          break; // a trailing semicolon is ignored
        }
        return false;
      }
      Map<String, String> pmap = params;
      int star = p.name().indexOf('*');
      if (star >= 0) {
        pmap = continuation.computeIfAbsent(p.name().substring(0, star), k -> new HashMap<>());
      }
      if (pmap.containsKey(p.name()) && !p.value().equals(pmap.get(p.name()))) {
        return false; // a parameter twice with different values
      }
      pmap.put(p.name(), p.value());
      rest = p.rest();
    }
    return true;
  }

  /** {@code type} or {@code type/subtype}, both tokens, nothing after. */
  private static boolean checkMediaTypeDisposition(String s) {
    Split type = consumeToken(s);
    if (type.token().isEmpty()) {
      return false;
    }
    if (type.rest().isEmpty()) {
      return true;
    }
    if (!type.rest().startsWith("/")) {
      return false;
    }
    Split subtype = consumeToken(type.rest().substring(1));
    return !subtype.token().isEmpty() && subtype.rest().isEmpty();
  }

  private static boolean isTSpecial(char c) {
    return TSPECIALS.indexOf(c) >= 0;
  }

  private static boolean isTokenChar(char c) {
    return c > 0x20 && c < 0x80 && !isTSpecial(c);
  }

  private static Split consumeToken(String v) {
    int i = 0;
    while (i < v.length() && isTokenChar(v.charAt(i))) {
      i++;
    }
    return new Split(v.substring(0, i), v.substring(i));
  }

  /** A token or a quoted string; an empty token and the input unchanged when there is neither. */
  private static Split consumeValue(String v) {
    if (v.isEmpty()) {
      return new Split("", v);
    }
    if (v.charAt(0) != '"') {
      return consumeToken(v);
    }
    StringBuilder buffer = new StringBuilder();
    for (int i = 1; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '"') {
        return new Split(buffer.toString(), v.substring(i + 1));
      }
      // a backslash escapes a tspecial; before anything else it is a literal backslash (as in Go)
      if (c == '\\' && i + 1 < v.length() && isTSpecial(v.charAt(i + 1))) {
        buffer.append(v.charAt(i + 1));
        i++;
        continue;
      }
      if (c == '\r' || c == '\n') {
        return new Split("", v);
      }
      buffer.append(c);
    }
    return new Split("", v); // no closing quote
  }

  private static @Nullable Param consumeMediaParam(String v) {
    String rest = v.stripLeading();
    if (!rest.startsWith(";")) {
      return null;
    }
    rest = rest.substring(1).stripLeading();
    Split name = consumeToken(rest);
    if (name.token().isEmpty()) {
      return null;
    }
    rest = name.rest().stripLeading();
    if (!rest.startsWith("=")) {
      return null;
    }
    rest = rest.substring(1).stripLeading();
    Split value = consumeValue(rest);
    if (value.token().isEmpty() && value.rest().equals(rest)) {
      return null;
    }
    return new Param(name.token().toLowerCase(Locale.ROOT), value.token(), value.rest());
  }
}
