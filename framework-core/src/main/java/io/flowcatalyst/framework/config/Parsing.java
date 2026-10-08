package io.flowcatalyst.framework.config;

/** Shared parsing helpers. */
final class Parsing {
  private Parsing() {}

  /** Turns a YAML scalar or environment text into text; lists and maps are not scalars. */
  static String scalarText(Object raw, String expected) throws ValueFormatException {
    return switch (raw) {
      case String s -> s;
      case Boolean b -> b.toString();
      case Number n -> n.toString();
      default -> throw new ValueFormatException(expected);
    };
  }
}
