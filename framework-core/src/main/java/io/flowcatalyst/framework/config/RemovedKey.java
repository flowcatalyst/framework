package io.flowcatalyst.framework.config;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A configuration key whose behaviour moved to code (config-and-code contract, section 5). Giving
 * it in a file or in the environment fails start-up with {@link #movedTo}.
 *
 * @param pattern dotted segments; {@code *} matches one segment (an instance name). In a file, a key
 *     matches when its leading segments match, so {@code locks} matches every key under it
 * @param module the module the key belonged to, for the problem
 * @param movedTo where the behaviour is now given, for example {@code
 *     HttpOptions.withRequestTimeout(Duration)}
 */
public record RemovedKey(String pattern, String module, String movedTo) {

  private static final Pattern SEGMENT = Pattern.compile("\\*|[a-z][a-z0-9_-]*");

  /** Checks the pattern. */
  public RemovedKey {
    for (String segment : pattern.split("\\.", -1)) {
      if (!SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException("invalid removed-key pattern '" + pattern + "'");
      }
    }
    if (movedTo.isBlank()) {
      throw new IllegalArgumentException("movedTo must not be blank");
    }
  }

  /**
   * The pattern's segments.
   *
   * @return the segments, {@code *} for a wildcard
   */
  public List<String> segments() {
    return List.of(pattern.split("\\."));
  }

  /**
   * Whether a file key is this removed key or lies under it.
   *
   * @param key a dotted key
   * @return true when the key's leading segments match the pattern
   */
  public boolean matches(String key) {
    String[] k = key.split("\\.");
    List<String> p = segments();
    if (k.length < p.size()) {
      return false;
    }
    for (int i = 0; i < p.size(); i++) {
      if (!p.get(i).equals("*") && !p.get(i).equals(k[i])) {
        return false;
      }
    }
    return true;
  }
}
