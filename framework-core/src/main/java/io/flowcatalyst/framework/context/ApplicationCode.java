package io.flowcatalyst.framework.context;

import java.util.regex.Pattern;

/**
 * The code of an application, for example {@code billing}.
 *
 * @param value matches {@code [a-z][a-z0-9-]*}
 */
public record ApplicationCode(String value) {
  private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

  /** Rejects values that do not match the pattern. */
  public ApplicationCode {
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("ApplicationCode must match [a-z][a-z0-9-]*: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
