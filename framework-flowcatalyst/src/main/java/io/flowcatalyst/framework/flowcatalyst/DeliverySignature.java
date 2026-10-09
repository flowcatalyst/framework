package io.flowcatalyst.framework.flowcatalyst;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Verifies a FlowCatalyst-signed delivery (dispatch job or scheduled-job firing). A port of the
 * Java SDK's {@code WebhookSignature}, with the same rules as the Go and TypeScript verifiers:
 *
 * <ul>
 *   <li>{@code X-FlowCatalyst-Signature} is lowercase hex of HMAC-SHA256, keyed with the signing
 *       secret (UTF-8), over the raw {@code X-FlowCatalyst-Timestamp} header string followed by
 *       the raw body bytes; it is lower-cased before the compare;
 *   <li>the timestamp is millisecond ISO-8601 (or bare Unix seconds) and, compared in whole
 *       seconds, may be at most 300 s in the past and 60 s in the future;
 *   <li>an empty secret fails closed;
 *   <li>an optional bearer gate is AND-ed with the signature, checked after it.
 * </ul>
 *
 * <p>Ported rather than reused because the SDK reads the system clock (the framework injects
 * time) and its messages echo the timestamp header. Differences, all stricter: compares are
 * constant-time over digests (so a wrong bearer's length is not revealed either), a Unix-seconds
 * timestamp must be ASCII digits, and over-long header values are rejected without parsing.
 */
final class DeliverySignature {

  /** How far in the past a timestamp may be, in seconds. */
  static final long PAST_TOLERANCE_SECONDS = 300;

  /** How far in the future a timestamp may be, in seconds. */
  static final long FUTURE_TOLERANCE_SECONDS = 60;

  private static final int MAX_TIMESTAMP_LENGTH = 64;
  private static final int SIGNATURE_HEX_LENGTH = 64;
  private static final Pattern UNIX_SECONDS = Pattern.compile("[0-9]{1,18}");
  private static final String BEARER = "Bearer ";

  /** Why a delivery was not accepted. Safe to return to the caller and to log. */
  enum Failure {
    MISSING_SECRET,
    MISSING_SIGNATURE,
    MISSING_TIMESTAMP,
    INVALID_TIMESTAMP,
    TIMESTAMP_EXPIRED,
    TIMESTAMP_IN_FUTURE,
    INVALID_SIGNATURE,
    MISSING_BEARER,
    INVALID_BEARER,
    /** A signature, timestamp or authorization header sent more than once (checked by the gate). */
    DUPLICATE_HEADER
  }

  /** The outcome of a check. */
  sealed interface Verdict {}

  /** The delivery is genuine. */
  record Valid() implements Verdict {}

  /**
   * The delivery was refused.
   *
   * @param failure why
   */
  record Invalid(Failure failure) implements Verdict {}

  /** Supplies the raw body only once the cheap header checks have passed. */
  @FunctionalInterface
  interface Body {
    byte[] bytes();
  }

  private DeliverySignature() {}

  /**
   * Checks a signed delivery.
   *
   * @param body the raw body, exactly as received; read only after the header checks pass
   * @param signature the {@code X-FlowCatalyst-Signature} value, or null
   * @param timestamp the {@code X-FlowCatalyst-Timestamp} value, or null
   * @param secret the signing secret; empty fails closed
   * @param expectedBearer when non-empty, {@code authorization} must be exactly {@code Bearer
   *     <expectedBearer>}
   * @param authorization the {@code Authorization} value, or null
   * @param now the current time
   * @return the verdict
   */
  static Verdict check(
      Body body,
      @Nullable String signature,
      @Nullable String timestamp,
      String secret,
      @Nullable String expectedBearer,
      @Nullable String authorization,
      Instant now) {
    if (secret.isEmpty()) {
      return new Invalid(Failure.MISSING_SECRET);
    }
    if (signature == null || signature.isEmpty()) {
      return new Invalid(Failure.MISSING_SIGNATURE);
    }
    if (timestamp == null || timestamp.isEmpty()) {
      return new Invalid(Failure.MISSING_TIMESTAMP);
    }
    OptionalLong parsed = parseTimestamp(timestamp);
    if (parsed.isEmpty()) {
      return new Invalid(Failure.INVALID_TIMESTAMP);
    }
    long seconds = parsed.getAsLong();
    long nowSeconds = now.getEpochSecond();
    if (seconds < nowSeconds - PAST_TOLERANCE_SECONDS) {
      return new Invalid(Failure.TIMESTAMP_EXPIRED);
    }
    if (seconds > nowSeconds + FUTURE_TOLERANCE_SECONDS) {
      return new Invalid(Failure.TIMESTAMP_IN_FUTURE);
    }
    if (signature.length() != SIGNATURE_HEX_LENGTH) {
      return new Invalid(Failure.INVALID_SIGNATURE);
    }
    byte[] expected = hmacHex(secret, timestamp, body.bytes()).getBytes(StandardCharsets.UTF_8);
    byte[] presented = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, presented)) {
      return new Invalid(Failure.INVALID_SIGNATURE);
    }
    return checkBearer(expectedBearer, authorization);
  }

  /**
   * The bearer gate alone, for unsigned deliveries accepted with {@code require-signature=false}.
   *
   * @param expectedBearer the configured token, or null/empty for no gate
   * @param authorization the {@code Authorization} value, or null
   * @return the verdict
   */
  static Verdict checkBearer(@Nullable String expectedBearer, @Nullable String authorization) {
    if (expectedBearer == null || expectedBearer.isEmpty()) {
      return new Valid();
    }
    if (authorization == null || !authorization.startsWith(BEARER)) {
      return new Invalid(Failure.MISSING_BEARER);
    }
    // Compare digests: constant time, and independent of either token's length.
    byte[] presented = sha256(authorization.substring(BEARER.length()));
    byte[] want = sha256(expectedBearer);
    if (!MessageDigest.isEqual(presented, want)) {
      return new Invalid(Failure.INVALID_BEARER);
    }
    return new Valid();
  }

  /**
   * {@code hex(HMAC-SHA256(secret, timestamp + body))}, lowercase.
   *
   * @param secret the signing secret
   * @param timestamp the raw timestamp header string
   * @param body the raw body
   * @return 64 lowercase hex characters
   */
  static String hmacHex(String secret, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * The timestamp in epoch seconds. The HMAC always covers the raw header string; parsing only
   * feeds the window check.
   */
  static OptionalLong parseTimestamp(String timestamp) {
    if (timestamp.length() > MAX_TIMESTAMP_LENGTH) {
      return OptionalLong.empty();
    }
    if (UNIX_SECONDS.matcher(timestamp).matches()) {
      return OptionalLong.of(Long.parseLong(timestamp));
    }
    try {
      return OptionalLong.of(Instant.parse(timestamp).getEpochSecond());
    } catch (DateTimeParseException e) {
      return OptionalLong.empty();
    }
  }
}
