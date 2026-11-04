package io.flowcatalyst.framework.ui.i18n;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLDR cardinal plural categories for the shipped languages: {@code en} (one: i = 1 and v = 0),
 * {@code af} (one: n = 1), {@code zu} (one: i = 0 or n = 1); any other language uses {@code en}'s.
 */
public final class Plurals {

  private Plurals() {}

  private static final Pattern DECIMAL = Pattern.compile("[+-]?([0-9]+)(?:\\.([0-9]+))?");

  /**
   * The category of a number.
   *
   * @param lang the language subtag
   * @param count the number as plain decimal text
   * @return {@code one} or {@code other}; {@code other} for text that is not a decimal number
   */
  public static String category(String lang, String count) {
    Matcher m = DECIMAL.matcher(count.strip());
    if (!m.matches()) {
      return "other";
    }
    String integer = m.group(1).replaceFirst("^0+", "");
    if (integer.isEmpty()) {
      integer = "0";
    }
    String frac = m.group(2) == null ? "" : m.group(2);
    boolean isOne = integer.equals("1") && frac.replace("0", "").isEmpty(); // n = 1
    return switch (lang.toLowerCase(Locale.ROOT)) {
      case "af" -> isOne ? "one" : "other";
      case "zu" -> integer.equals("0") || isOne ? "one" : "other";
      default -> integer.equals("1") && frac.isEmpty() ? "one" : "other";
    };
  }
}
