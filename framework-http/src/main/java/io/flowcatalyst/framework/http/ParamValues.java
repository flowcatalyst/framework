package io.flowcatalyst.framework.http;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parses typed path and query parameter values; errors name the parameter, never the value. */
final class ParamValues {

  static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
  private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

  private ParamValues() {}

  static void checkName(String name, boolean path) {
    if (!(path ? NAME : QUERY_NAME).matcher(name).matches()) {
      throw new IllegalArgumentException(
          (path ? "a path parameter name must match [a-z][A-Za-z0-9]*: " : "a query parameter name must match [A-Za-z][A-Za-z0-9_.-]*: ")
              + name);
    }
  }

  /** The value types a parameter may have. */
  static void checkType(Class<?> type) {
    if (type != String.class && type != Integer.class && type != Long.class && type != Boolean.class && !type.isEnum()) {
      throw new IllegalArgumentException("a parameter is a String, Integer, Long, Boolean or an enum, not " + type.getName());
    }
  }

  /**
   * Parses one value.
   *
   * @return the value, or null when it does not parse (the message is added to {@code errors})
   */
  static Object parse(Class<?> type, String name, String raw, List<FieldError> errors) {
    if (type == String.class) {
      return raw;
    }
    if (type == Integer.class) {
      try {
        return Integer.parseInt(raw);
      } catch (NumberFormatException e) {
        errors.add(new FieldError(name, "INVALID", "must be an integer"));
        return Invalid.VALUE;
      }
    }
    if (type == Long.class) {
      try {
        return Long.parseLong(raw);
      } catch (NumberFormatException e) {
        errors.add(new FieldError(name, "INVALID", "must be an integer"));
        return Invalid.VALUE;
      }
    }
    if (type == Boolean.class) {
      return switch (raw) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        default -> {
          errors.add(new FieldError(name, "INVALID", "must be true or false"));
          yield Invalid.VALUE;
        }
      };
    }
    List<String> names = new ArrayList<>();
    for (Object constant : type.getEnumConstants()) {
      String n = ((Enum<?>) constant).name();
      if (n.equals(raw)) {
        return constant;
      }
      names.add(n);
    }
    errors.add(new FieldError(name, "INVALID", "must be one of: " + String.join(", ", names)));
    return Invalid.VALUE;
  }

  /** Marks a value that did not parse; never reaches a handler. */
  enum Invalid {
    VALUE
  }
}
