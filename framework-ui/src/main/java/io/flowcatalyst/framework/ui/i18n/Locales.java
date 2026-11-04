package io.flowcatalyst.framework.ui.i18n;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** BCP 47 tag handling shared by the bundle and the resolver (contract sections 1 and 3). */
public final class Locales {

  private Locales() {}

  /** The source language, the default and the fallback of every message. */
  public static final String DEFAULT = "en";

  private static final Pattern SUBTAG = Pattern.compile("[A-Za-z0-9]{1,8}");

  /**
   * A tag in canonical case (language lower, script title, region upper); {@code _} is read as
   * {@code -}.
   *
   * @param tag the tag, or null
   * @return the canonical tag, or the empty string when it is not {@code language(-subtag)*}
   */
  public static String normalize(@Nullable String tag) {
    if (tag == null) {
      return "";
    }
    String t = tag.strip().replace('_', '-');
    if (t.isEmpty()) {
      return "";
    }
    String[] parts = t.split("-", -1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String p = parts[i];
      if (!SUBTAG.matcher(p).matches()) {
        return "";
      }
      boolean letters = p.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
      String q;
      if (i == 0) {
        if (!letters || p.length() < 2 || p.length() > 3) {
          return "";
        }
        q = p.toLowerCase(Locale.ROOT);
      } else if (p.length() == 2 && letters) {
        q = p.toUpperCase(Locale.ROOT);
      } else if (p.length() == 4 && letters) {
        q = p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1).toLowerCase(Locale.ROOT);
      } else {
        q = p.toLowerCase(Locale.ROOT);
      }
      if (i > 0) {
        out.append('-');
      }
      out.append(q);
    }
    return out.toString();
  }

  /**
   * The language subtag.
   *
   * @param tag a tag
   * @return {@code af} for {@code af-ZA}
   */
  public static String language(String tag) {
    int i = tag.indexOf('-');
    return i < 0 ? tag : tag.substring(0, i);
  }

  /**
   * The tags of an {@code Accept-Language} header in quality order (ties keep their order); {@code
   * q=0} entries, {@code *} and malformed tags are left out.
   *
   * @param header the header value, or null
   * @return the canonical tags
   */
  public static List<String> parseAcceptLanguage(@Nullable String header) {
    record Entry(String tag, double q) {}
    List<Entry> entries = new ArrayList<>();
    if (header == null) {
      return List.of();
    }
    for (String part : header.split(",")) {
      String[] segs = part.strip().split(";");
      String tag = segs[0].strip();
      if (tag.isEmpty() || tag.equals("*")) {
        continue;
      }
      double q = 1.0;
      for (int i = 1; i < segs.length; i++) {
        String s = segs[i].strip();
        int eq = s.indexOf('=');
        if (eq > 0 && s.substring(0, eq).strip().equalsIgnoreCase("q")) {
          try {
            q = Double.parseDouble(s.substring(eq + 1).strip());
            if (q < 0 || q > 1 || Double.isNaN(q)) {
              q = -1;
            }
          } catch (NumberFormatException e) {
            q = -1;
          }
        }
      }
      String n = normalize(tag);
      if (!n.isEmpty() && q > 0) {
        entries.add(new Entry(n, q));
      }
    }
    entries.sort((a, b) -> Double.compare(b.q(), a.q())); // stable
    return entries.stream().map(Entry::tag).toList();
  }

  /**
   * The text of a message argument: integers and decimals as plain decimal text, anything else as
   * its {@code toString}.
   *
   * @param v the argument
   * @return the text
   */
  static String argText(@Nullable Object v) {
    return switch (v) {
      case null -> "";
      case Double d -> plain(BigDecimal.valueOf(d));
      case Float f -> plain(new BigDecimal(Float.toString(f)));
      case BigDecimal b -> b.toPlainString();
      default -> v.toString();
    };
  }

  private static String plain(BigDecimal b) {
    BigDecimal s = b.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
  }
}
