package io.flowcatalyst.framework.objectstore;

import java.util.regex.Pattern;

/** The rules for keys, the same for every driver so a key that works in a test works when deployed. */
public final class ObjectKeys {

  /** The longest key, in characters. */
  public static final int MAX_LENGTH = 512;

  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private ObjectKeys() {}

  /**
   * Checks a key: 1 to {@value #MAX_LENGTH} characters, segments separated by a single {@code /},
   * each segment starting with a letter or digit and holding only letters, digits, dot, dash and
   * underscore. So no leading or trailing slash, no empty segment, and no {@code .} or {@code ..}
   * (which is what keeps a key inside a folder store).
   *
   * @param key the key
   * @return the same key
   * @throws IllegalArgumentException when the key is not allowed
   */
  public static String check(String key) {
    if (key.isEmpty() || key.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("key must be 1 to " + MAX_LENGTH + " characters, was " + key.length());
    }
    int start = 0;
    while (true) {
      int slash = key.indexOf('/', start);
      String segment = key.substring(start, slash < 0 ? key.length() : slash);
      if (!SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException(
            "key '" + printable(key) + "' has an invalid segment; segments are letters, digits, '.', '-' and '_', starting with a letter or digit, separated by '/'");
      }
      if (slash < 0) {
        return key;
      }
      start = slash + 1;
    }
  }

  private static String printable(String key) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(key.length(), 80); i++) {
      char c = key.charAt(i);
      sb.append(c < 32 || c == 127 ? '?' : c);
    }
    return sb.toString();
  }
}
