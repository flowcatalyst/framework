package io.flowcatalyst.framework.auth;

import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Base64url in its one canonical spelling ({@code docs/contracts/token-spelling.md}): the URL-safe
 * alphabet only (no {@code =} padding, no {@code +} or {@code /}, no whitespace or line break), a
 * length that is not 1 mod 4, and the spare bits of the last character zero. Every verifier of a
 * value the framework or the platform issues (tokens, DPoP proofs, nonces, sealed cookies, push
 * tickets and stream tokens) decodes with {@link #decode(String)}, so a value verifies only in the
 * spelling it was issued in.
 *
 * <p>{@link Base64#getUrlDecoder()} alone accepts {@code =} padding and ignores the spare bits, so
 * several strings decode to the same bytes; this class checks the text first.
 */
public final class Base64Url {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private Base64Url() {}

  /**
   * Encodes bytes canonically: the URL-safe alphabet without padding.
   *
   * @param bytes the bytes
   * @return their canonical spelling
   */
  public static String encode(byte[] bytes) {
    return ENCODER.encodeToString(bytes);
  }

  /**
   * Decodes a canonical base64url string; for any string it accepts, {@link #encode(byte[])} of the
   * result gives the string back.
   *
   * @param text the text, as received (never trimmed)
   * @return the bytes, or null when the text is absent or not canonical base64url
   */
  public static byte @Nullable [] decode(@Nullable String text) {
    if (text == null || !isCanonical(text)) {
      return null;
    }
    return DECODER.decode(text);
  }

  /**
   * Whether a string is canonical base64url.
   *
   * @param text the text
   * @return true when it uses the URL-safe alphabet only, its length is not 1 mod 4 and the spare
   *     bits of its last character are zero
   */
  public static boolean isCanonical(String text) {
    int n = text.length();
    for (int i = 0; i < n; i++) {
      if (value(text.charAt(i)) < 0) {
        return false;
      }
    }
    if (n == 0) {
      return true;
    }
    int last = value(text.charAt(n - 1));
    return switch (n % 4) {
      case 1 -> false;
      case 2 -> (last & 0x0F) == 0; // one byte in the last group: four spare bits
      case 3 -> (last & 0x03) == 0; // two bytes: two spare bits
      default -> true;
    };
  }

  /** The six-bit value of a URL-safe base64 character, or -1 for any other character. */
  private static int value(char c) {
    if (c >= 'A' && c <= 'Z') {
      return c - 'A';
    }
    if (c >= 'a' && c <= 'z') {
      return c - 'a' + 26;
    }
    if (c >= '0' && c <= '9') {
      return c - '0' + 52;
    }
    if (c == '-') {
      return 62;
    }
    if (c == '_') {
      return 63;
    }
    return -1;
  }
}
