package io.flowcatalyst.framework.http.openapi;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Writes a tree of maps, lists, strings, numbers and booleans exactly as JavaScript's {@code
 * JSON.stringify(value, null, 2)} does, plus a final newline (contract section 4.1): two-space
 * indentation, {@code "key": value}, one element per line, empty containers as {@code {}} and
 * {@code []}, only {@code "}, {@code \}, and control characters escaped (non-ASCII, {@code <},
 * {@code >}, {@code &} and {@code /} written as they are), lone surrogates as {@code \\uXXXX}.
 */
final class JsonText {

  private JsonText() {}

  static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    value(sb, value, 0);
    return sb.append('\n').toString();
  }

  private static void value(StringBuilder sb, Object v, int depth) {
    switch (v) {
      case Map<?, ?> m -> object(sb, m, depth);
      case List<?> l -> array(sb, l, depth);
      case String s -> string(sb, s);
      case Boolean b -> sb.append(b);
      case Integer i -> sb.append(i);
      case Long l -> sb.append(l);
      case BigInteger i -> sb.append(i);
      case BigDecimal d -> sb.append(number(d));
      default -> throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
    }
  }

  /** A decimal as JavaScript prints a number of that value (plain notation, no trailing zeros). */
  static String number(BigDecimal d) {
    if (d.signum() == 0) {
      return "0";
    }
    return d.stripTrailingZeros().toPlainString();
  }

  private static void object(StringBuilder sb, Map<?, ?> m, int depth) {
    if (m.isEmpty()) {
      sb.append("{}");
      return;
    }
    sb.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      newline(sb, depth + 1);
      string(sb, (String) e.getKey());
      sb.append(": ");
      value(sb, e.getValue(), depth + 1);
    }
    newline(sb, depth);
    sb.append('}');
  }

  private static void array(StringBuilder sb, List<?> l, int depth) {
    if (l.isEmpty()) {
      sb.append("[]");
      return;
    }
    sb.append('[');
    boolean first = true;
    for (Object item : l) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      newline(sb, depth + 1);
      value(sb, item, depth + 1);
    }
    newline(sb, depth);
    sb.append(']');
  }

  private static void newline(StringBuilder sb, int depth) {
    sb.append('\n');
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
  }

  static void string(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            hex(sb, c);
          } else if (Character.isHighSurrogate(c)) {
            if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
              sb.append(c).append(s.charAt(i + 1));
              i++;
            } else {
              hex(sb, c);
            }
          } else if (Character.isLowSurrogate(c)) {
            hex(sb, c);
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static void hex(StringBuilder sb, char c) {
    sb.append("\\u");
    String h = Integer.toHexString(c);
    for (int k = h.length(); k < 4; k++) {
      sb.append('0');
    }
    sb.append(h);
  }
}
