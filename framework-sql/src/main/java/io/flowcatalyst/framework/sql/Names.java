package io.flowcatalyst.framework.sql;

import java.util.Locale;
import java.util.Set;

/** Java naming helpers. */
final class Names {

  private static final Set<String> KEYWORDS =
      Set.of(
          "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
          "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
          "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
          "interface", "long", "native", "new", "package", "private", "protected", "public",
          "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
          "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
          "null", "_", "var", "yield", "record", "sealed", "permits");

  /** Names the generated method bodies use for their own locals. */
  private static final Set<String> LOCALS = Set.of("connection", "ps", "rs", "row", "list");

  private Names() {}

  static boolean isReserved(String name) {
    return KEYWORDS.contains(name);
  }

  static boolean isReservedLocal(String name) {
    return KEYWORDS.contains(name) || LOCALS.contains(name);
  }

  /** {@code client_id} to {@code clientId}; a name without underscores is kept as written. */
  static String lowerCamel(String snake) {
    StringBuilder b = new StringBuilder();
    boolean upper = false;
    for (String part : snake.split("_")) {
      if (part.isEmpty()) {
        continue;
      }
      if (b.length() == 0) {
        b.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
      } else {
        b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
      }
      upper = true;
    }
    return upper ? b.toString() : snake;
  }

  /** {@code findOrder} to {@code FindOrder}. */
  static String upperCamel(String lowerCamel) {
    return Character.toUpperCase(lowerCamel.charAt(0)) + lowerCamel.substring(1);
  }

  /** {@code findOrder} to {@code FIND_ORDER}. */
  static String constant(String lowerCamel) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < lowerCamel.length(); i++) {
      char c = lowerCamel.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        b.append('_');
      }
      b.append(Character.toUpperCase(c));
    }
    return b.toString().toUpperCase(Locale.ROOT);
  }
}
