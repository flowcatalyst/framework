package io.flowcatalyst.framework.examples.ordersjte;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads an {@code application/x-www-form-urlencoded} body. */
final class Forms {

  private Forms() {}

  /**
   * The first value of each field.
   *
   * @param body the request body
   * @return field name to value
   */
  static Map<String, String> parse(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : body.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.putIfAbsent(name, value);
    }
    return out;
  }
}
