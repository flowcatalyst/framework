package io.flowcatalyst.framework.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Compact JWS checks shared by id_tokens and access tokens (contract section 5.5, steps 1 to 4):
 * RS256 only, decided before any key is looked at; the key from the platform's own key set by
 * {@code kid}; nothing in the header is fetched.
 */
final class Jwt {

  static final int MAX_LENGTH = 16_384;
  static final Duration SKEW = Duration.ofSeconds(60);

  /** The outcome of the signature checks. */
  sealed interface Checked {
    /** Signed by the platform; the claims are not yet checked. */
    record Ok(JsonNode claims) implements Checked {}

    /** Refused, with the contract's reason name. */
    record Refused(String reason) implements Checked {}
  }

  private Jwt() {}

  static Checked verify(String token, Platform platform) throws PlatformUnavailableException {
    if (token.isEmpty() || token.length() > MAX_LENGTH) {
      return new Checked.Refused("malformed");
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
      return new Checked.Refused("malformed");
    }
    JsonNode header = object(parts[0]);
    if (header == null) {
      return new Checked.Refused("malformed");
    }
    JsonNode alg = header.get("alg");
    if (alg == null || !alg.isString() || !alg.asString().equals("RS256")) {
      return new Checked.Refused("alg");
    }
    if (header.has("crit")) {
      return new Checked.Refused("crit");
    }
    JsonNode kidNode = header.get("kid");
    if (kidNode == null || !kidNode.isString() || kidNode.asString().isEmpty()) {
      return new Checked.Refused("kid");
    }
    Optional<RSAPublicKey> key = platform.key(kidNode.asString());
    if (key.isEmpty()) {
      return new Checked.Refused("unknown_kid");
    }
    byte[] signature = decode(parts[2]);
    if (signature == null || signature.length == 0 || !signatureMatches(key.get(), parts[0] + "." + parts[1], signature)) {
      return new Checked.Refused("signature");
    }
    JsonNode claims = object(parts[1]);
    if (claims == null) {
      return new Checked.Refused("malformed");
    }
    return new Checked.Ok(claims);
  }

  private static boolean signatureMatches(RSAPublicKey key, String signedPart, byte[] signature) {
    try {
      Signature check = Signature.getInstance("SHA256withRSA");
      check.initVerify(key);
      check.update(signedPart.getBytes(StandardCharsets.US_ASCII));
      return check.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /**
   * The time rules: {@code exp} required, now before it plus the allowance; {@code nbf} optional,
   * now at or after it minus the allowance; {@code iat} optional, not after now plus the allowance.
   *
   * @return the refusal reason, or empty
   */
  static Optional<String> times(JsonNode claims, Instant now) {
    JsonNode exp = claims.get("exp");
    if (exp == null || !exp.isNumber() || !now.isBefore(seconds(exp).plus(SKEW))) {
      return Optional.of("expired");
    }
    JsonNode nbf = claims.get("nbf");
    if (nbf != null && (!nbf.isNumber() || now.isBefore(seconds(nbf).minus(SKEW)))) {
      return Optional.of("not_yet_valid");
    }
    JsonNode iat = claims.get("iat");
    if (iat != null && (!iat.isNumber() || seconds(iat).isAfter(now.plus(SKEW)))) {
      return Optional.of("iat");
    }
    return Optional.empty();
  }

  static Instant seconds(JsonNode number) {
    double value = number.asDouble();
    if (Double.isNaN(value) || value > 1e12 || value < -1e12) {
      return value > 0 ? Instant.MAX.minus(Duration.ofDays(365)) : Instant.EPOCH;
    }
    return Instant.ofEpochSecond((long) Math.floor(value));
  }

  /** A claim that is a string or a list of them; other elements are dropped. */
  static List<String> texts(@Nullable JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node == null) {
      return values;
    }
    if (node.isString()) {
      values.add(node.asString());
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        if (element.isString()) {
          values.add(element.asString());
        }
      }
    }
    return values;
  }

  static @Nullable String text(JsonNode claims, String name) {
    JsonNode node = claims.get(name);
    return node != null && node.isString() && !node.asString().isEmpty() ? node.asString() : null;
  }

  private static @Nullable JsonNode object(String part) {
    byte[] bytes = decode(part);
    if (bytes == null) {
      return null;
    }
    try {
      JsonNode node = Records.MAPPER.readTree(bytes);
      return node != null && node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static byte @Nullable [] decode(String part) {
    if (part.indexOf('=') >= 0) {
      return null;
    }
    try {
      return Base64.getUrlDecoder().decode(part);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
