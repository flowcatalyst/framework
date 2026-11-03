package io.flowcatalyst.framework.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Stateless application nonces for DPoP proofs (contract section 15.4). A nonce is {@code
 * base64url(bucket ‖ first 16 bytes of HMAC-SHA256(k, "fw-field-nonce" ‖ bucket))}, {@code bucket}
 * being the unix second divided by 300 as 8 bytes big-endian and {@code k} the HKDF-SHA256 (no salt,
 * info {@code fw-field-nonce}, 32 bytes) of a cookie key. A nonce is valid in its own bucket and the
 * next one, under the key derived from any configured cookie key, so rotating the cookie keys does
 * not break devices.
 */
final class FieldNonces {

  static final long BUCKET_SECONDS = 300;
  private static final byte[] INFO = "fw-field-nonce".getBytes(StandardCharsets.US_ASCII);
  private static final int MAC_BYTES = 16;

  private final List<SecretKeySpec> keys = new ArrayList<>();
  private final InstantSource clock;

  FieldNonces(CookieKeys cookieKeys, InstantSource clock) {
    this.clock = clock;
    for (byte[] raw : cookieKeys.rawKeys()) {
      keys.add(new SecretKeySpec(hkdf(raw), "HmacSHA256"));
    }
  }

  /** A nonce valid now: the current bucket under the current (first) cookie key. */
  String issue() {
    long bucket = bucket();
    byte[] bucketBytes = ByteBuffer.allocate(8).putLong(bucket).array();
    byte[] mac = Arrays.copyOf(mac(keys.getFirst(), bucketBytes), MAC_BYTES);
    byte[] out = new byte[8 + MAC_BYTES];
    System.arraycopy(bucketBytes, 0, out, 0, 8);
    System.arraycopy(mac, 0, out, 8, MAC_BYTES);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
  }

  /** Whether a presented nonce is one of ours from the current or the previous bucket. */
  boolean valid(@Nullable String nonce) {
    if (nonce == null || nonce.length() > 64) {
      return false;
    }
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(nonce);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (raw.length != 8 + MAC_BYTES) {
      return false;
    }
    byte[] bucketBytes = Arrays.copyOfRange(raw, 0, 8);
    long presented = ByteBuffer.wrap(bucketBytes).getLong();
    long now = bucket();
    if (presented != now && presented != now - 1) {
      return false;
    }
    byte[] given = Arrays.copyOfRange(raw, 8, raw.length);
    boolean ok = false;
    for (SecretKeySpec key : keys) {
      // every key is tried, so the time taken does not say which one matched
      ok |= MessageDigest.isEqual(Arrays.copyOf(mac(key, bucketBytes), MAC_BYTES), given);
    }
    return ok;
  }

  private long bucket() {
    return Math.floorDiv(clock.instant().getEpochSecond(), BUCKET_SECONDS);
  }

  private static byte[] mac(SecretKeySpec key, byte[] bucketBytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(INFO);
      mac.update(bucketBytes);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /** RFC 5869 with SHA-256, a zero salt, {@link #INFO} and one output block (32 bytes). */
  static byte[] hkdf(byte[] inputKey) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] prk = mac.doFinal(inputKey);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      mac.update(INFO);
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
