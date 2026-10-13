package io.flowcatalyst.framework.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Handles for the server-side session stores (contract section 6.3): 32 random bytes, base64url
 * without padding. Stores keep only {@link #hash(String)} of a handle, so a leaked table holds no
 * usable cookie. Public for the {@code database} and {@code redis} drivers.
 */
public final class SessionHandles {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{43}");

  private SessionHandles() {}

  /**
   * A new handle.
   *
   * @return 43 characters of {@code [A-Za-z0-9_-]}
   */
  public static String newHandle() {
    return random(32);
  }

  /**
   * Whether a value has the shape of a handle. Stores answer "none" for anything else without a
   * store call.
   *
   * @param value the cookie value
   * @return true for 43 characters of {@code [A-Za-z0-9_-]}
   */
  public static boolean isWellFormed(String value) {
    return WELL_FORMED.matcher(value).matches();
  }

  /**
   * What a store keeps in place of the handle.
   *
   * @param handle the handle
   * @return base64url of SHA-256 of the handle's ASCII bytes
   */
  public static String hash(String handle) {
    return B64.encodeToString(sha256(handle.getBytes(StandardCharsets.US_ASCII)));
  }

  /** {@code n} random bytes, base64url without padding. */
  static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return B64.encodeToString(b);
  }

  static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JDK", e);
    }
  }

  static String base64Url(byte[] bytes) {
    return B64.encodeToString(bytes);
  }

  /** Constant-time comparison of two strings. */
  static boolean same(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
