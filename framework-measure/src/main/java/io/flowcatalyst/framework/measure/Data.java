package io.flowcatalyst.framework.measure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads the embedded data tables ({@code iso4217.json}, {@code units.json}) with a small JSON
 * reader, so the module needs no JSON library. Internal to the framework: not an API.
 *
 * <p>A JSON object is a {@code Map}, an array a {@code List}, a string a {@code String}, a number a
 * {@link BigDecimal}, {@code true} and {@code false} a {@code Boolean}, {@code null} is {@code null}.
 */
public final class Data {

  private final String text;
  private int pos;

  private Data(String text) {
    this.text = text;
  }

  /**
   * Reads a resource of this package as JSON.
   *
   * @param name the file name, for example {@code units.json}
   * @return the parsed document
   */
  public static @Nullable Object read(String name) {
    try (InputStream in = Data.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + name);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses a JSON document.
   *
   * @param json the text
   * @return the parsed value
   */
  public static @Nullable Object parse(String json) {
    Data d = new Data(json);
    d.skip();
    Object v = d.value();
    d.skip();
    if (d.pos != json.length()) {
      throw d.fail("trailing content");
    }
    return v;
  }

  /**
   * An object member that must be an object.
   *
   * @param value a parsed value
   * @return the value as a map
   */
  public static Map<?, ?> object(@Nullable Object value) {
    if (value instanceof Map<?, ?> m) {
      return m;
    }
    throw new IllegalStateException("data table: an object was expected");
  }

  /**
   * A parsed value that must be an array.
   *
   * @param value a parsed value
   * @return the value as a list
   */
  public static List<?> array(@Nullable Object value) {
    if (value instanceof List<?> l) {
      return l;
    }
    throw new IllegalStateException("data table: an array was expected");
  }

  /**
   * A member that must be a string.
   *
   * @param object the object
   * @param key the member name
   * @return the string
   */
  public static String string(Map<?, ?> object, String key) {
    if (object.get(key) instanceof String s) {
      return s;
    }
    throw new IllegalStateException("data table: " + key + " must be a string");
  }

  /**
   * A member that must be a whole number.
   *
   * @param object the object
   * @param key the member name
   * @return the number
   */
  public static int integer(Map<?, ?> object, String key) {
    if (object.get(key) instanceof BigDecimal d) {
      return d.intValueExact();
    }
    throw new IllegalStateException("data table: " + key + " must be a number");
  }

  private IllegalStateException fail(String why) {
    return new IllegalStateException("data table JSON: " + why + " at " + pos);
  }

  private void skip() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private @Nullable Object value() {
    if (pos >= text.length()) {
      throw fail("unexpected end");
    }
    char c = text.charAt(pos);
    switch (c) {
      case '{' -> {
        return object();
      }
      case '[' -> {
        return array();
      }
      case '"' -> {
        return string();
      }
      default -> {
        return literal();
      }
    }
  }

  private Map<String, @Nullable Object> object() {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    pos++;
    skip();
    if (text.charAt(pos) == '}') {
      pos++;
      return m;
    }
    while (true) {
      skip();
      String key = string();
      skip();
      expect(':');
      skip();
      m.put(key, value());
      skip();
      if (text.charAt(pos) == ',') {
        pos++;
      } else {
        expect('}');
        return m;
      }
    }
  }

  private List<@Nullable Object> array() {
    List<@Nullable Object> l = new ArrayList<>();
    pos++;
    skip();
    if (text.charAt(pos) == ']') {
      pos++;
      return l;
    }
    while (true) {
      skip();
      l.add(value());
      skip();
      if (text.charAt(pos) == ',') {
        pos++;
      } else {
        expect(']');
        return l;
      }
    }
  }

  private void expect(char c) {
    if (pos >= text.length() || text.charAt(pos) != c) {
      throw fail("expected " + c);
    }
    pos++;
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= text.length()) {
        throw fail("unterminated string");
      }
      char c = text.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char e = text.charAt(pos++);
      switch (e) {
        case 'n' -> sb.append('\n');
        case 't' -> sb.append('\t');
        case 'r' -> sb.append('\r');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'u' -> {
          sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> sb.append(e);
      }
    }
  }

  private @Nullable Object literal() {
    int start = pos;
    while (pos < text.length() && ",]} \n\r\t".indexOf(text.charAt(pos)) < 0) {
      pos++;
    }
    String word = text.substring(start, pos);
    return switch (word) {
      case "null" -> null;
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      default -> {
        try {
          yield new BigDecimal(word);
        } catch (NumberFormatException e) {
          throw fail("bad literal " + word);
        }
      }
    };
  }
}
