package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.config.Secret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keys that seal cookies (contract section 6.5): AES-256-GCM, value {@code
 * v1.<kid>.<base64url(iv ‖ ciphertext ‖ tag)>}, additional data {@code fw:<kind>}. The first key
 * seals, every key opens, so a key can be rotated without signing everyone out.
 */
public final class CookieKeys {

  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,16}");
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String current;
  private final Map<String, SecretKeySpec> keys;

  private CookieKeys(String current, Map<String, SecretKeySpec> keys) {
    this.current = current;
    this.keys = keys;
  }

  /**
   * Parses {@code auth.cookie-keys}: {@code <kid>:<base64url of 32 bytes>[,<kid>:<key>...]}.
   *
   * @param value the secret
   * @return the keys; the first seals
   * @throws IllegalArgumentException when the list is empty or malformed, a key is not 32 bytes, or
   *     a kid repeats; the message never contains key material
   */
  public static CookieKeys parse(Secret value) {
    Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    String first = null;
    int position = 0;
    for (String entry : value.reveal().split(",", -1)) {
      position++;
      String trimmed = entry.trim();
      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException("auth.cookie-keys entry " + position + " is not <kid>:<key>");
      }
      String kid = trimmed.substring(0, colon);
      if (!KID.matcher(kid).matches()) {
        throw new IllegalArgumentException("auth.cookie-keys entry " + position + ": the kid must match [A-Za-z0-9_-]{1,16}");
      }
      byte[] key;
      try {
        key = Base64.getUrlDecoder().decode(trimmed.substring(colon + 1).trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("auth.cookie-keys key '" + kid + "' is not base64url");
      }
      if (key.length != 32) {
        throw new IllegalArgumentException("auth.cookie-keys key '" + kid + "' must be 32 bytes, not " + key.length);
      }
      if (keys.putIfAbsent(kid, new SecretKeySpec(key, "AES")) != null) {
        throw new IllegalArgumentException("auth.cookie-keys lists the kid '" + kid + "' twice");
      }
      if (first == null) {
        first = kid;
      }
    }
    if (first == null) {
      throw new IllegalArgumentException("auth.cookie-keys is empty");
    }
    return new CookieKeys(first, Map.copyOf(keys));
  }

  /**
   * A new random key in the list format, for setting up {@code auth.cookie-keys}.
   *
   * @param kid the key id
   * @return {@code <kid>:<base64url of 32 random bytes>}
   */
  public static String generate(String kid) {
    if (!KID.matcher(kid).matches()) {
      throw new IllegalArgumentException("the kid must match [A-Za-z0-9_-]{1,16}");
    }
    return kid + ":" + SessionHandles.random(32);
  }

  /**
   * The ids of the keys, the sealing one first.
   *
   * @return the kids
   */
  public List<String> kids() {
    List<String> out = new ArrayList<>();
    out.add(current);
    keys.keySet().stream().filter(k -> !k.equals(current)).forEach(out::add);
    return out;
  }

  /**
   * Seals bytes with the first key.
   *
   * @param kind the record kind, bound into the additional data
   * @param plaintext the bytes
   * @return the cookie value
   */
  String seal(String kind, byte[] plaintext) {
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(current), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(kind));
      byte[] sealed = cipher.doFinal(plaintext);
      byte[] out = new byte[IV_BYTES + sealed.length];
      System.arraycopy(iv, 0, out, 0, IV_BYTES);
      System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
      return "v1." + current + "." + SessionHandles.base64Url(out);
    } catch (GeneralSecurityException e) {
      throw new SessionStoreException("cannot seal a cookie", e);
    }
  }

  /**
   * Opens a cookie value sealed by any of the keys for this kind.
   *
   * @param kind the record kind expected
   * @param value the cookie value
   * @return the bytes, or empty when the value is malformed, names an unknown key, was sealed for
   *     another kind, or was changed
   */
  Optional<byte[]> open(String kind, String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 3 || !parts[0].equals("v1")) {
      return Optional.empty();
    }
    SecretKeySpec key = keys.get(parts[1]);
    if (key == null) {
      return Optional.empty();
    }
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (raw.length < IV_BYTES + TAG_BITS / 8) {
      return Optional.empty();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
      cipher.updateAAD(aad(kind));
      return Optional.of(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES));
    } catch (javax.crypto.AEADBadTagException e) {
      return Optional.empty();
    } catch (GeneralSecurityException e) {
      throw new SessionStoreException("cannot open a cookie", e);
    }
  }

  private static byte[] aad(String kind) {
    return ("fw:" + kind).getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public String toString() {
    return "CookieKeys" + kids();
  }
}
