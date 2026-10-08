package io.flowcatalyst.framework.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A value that must never be printed, logged or serialised by accident.
 *
 * <p>{@link #toString()} is redacted, the class is not {@link java.io.Serializable}, and the raw
 * text is available only through the explicit {@link #reveal()} call.
 */
public final class Secret {

  private final String value;

  private Secret(String value) {
    this.value = value;
  }

  /**
   * Wraps raw text.
   *
   * @param value the raw secret
   * @return the wrapped secret
   */
  public static Secret of(String value) {
    return new Secret(value);
  }

  /**
   * Returns the raw text. Callers must not log or print it.
   *
   * @return the raw secret
   */
  public String reveal() {
    return value;
  }

  @Override
  public String toString() {
    return "Secret[****]";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Secret s
        && MessageDigest.isEqual(
            value.getBytes(StandardCharsets.UTF_8), s.value.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public int hashCode() {
    // Constant on purpose: a hash of the content would leak information about it.
    return 0x5ec7e7;
  }
}
