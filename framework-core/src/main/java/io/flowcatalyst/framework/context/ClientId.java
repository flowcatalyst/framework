package io.flowcatalyst.framework.context;

import java.util.regex.Pattern;

/**
 * Identifies a client (the tenant). Always present in a {@link Context}.
 *
 * <p>The text never contains {@code :}, so a client id can be embedded in composite keys (lock
 * and cache keys) without two different clients producing the same key.
 *
 * @param value the identifier text, matching {@code [A-Za-z0-9._-]{1,100}}
 */
public record ClientId(String value) {
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  /** Rejects values outside {@code [A-Za-z0-9._-]{1,100}}. */
  public ClientId {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("ClientId must match [A-Za-z0-9._-]{1,100}: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
