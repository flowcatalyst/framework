package io.flowcatalyst.framework.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The DPoP proof checks of contract section 15.3 (RFC 9449): header {@code typ: dpop+jwt}, {@code
 * alg: ES256} only, {@code jwk} an EC P-256 public key; the signature, the RFC 7638 thumbprint, and
 * the claims {@code htm}, {@code htu}, {@code ath}, {@code jti} and {@code iat}. The nonce and the
 * replay check are the caller's, because they need state.
 */
final class FieldProofs {

  static final int MAX_LENGTH = 4096;
  static final int MAX_JTI = 256;

  /** The outcome of the checks. */
  sealed interface Checked {
    /**
     * Every stateless check passed.
     *
     * @param jkt the thumbprint of the proof's key
     * @param jti the proof's id
     * @param nonce the proof's {@code nonce}, if it carried a string
     */
    record Ok(String jkt, String jti, @Nullable String nonce) implements Checked {}

    /**
     * Refused, with the contract's reason name.
     *
     * @param reason the reason
     */
    record Refused(String reason) implements Checked {}
  }

  private static final ECParameterSpec P256 = p256();
  private static final BigInteger FIELD_PRIME = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);

  private FieldProofs() {}

  /**
   * Checks a proof.
   *
   * @param compact the {@code DPoP} header value
   * @param accessToken the compact access token, for {@code ath}
   * @param expectedJkt the token's {@code cnf.jkt}
   * @param method the request method
   * @param expectedHtu {@code auth.public-base-url} plus the request path
   * @param now the server clock
   * @param window how far {@code iat} may be from now, either way
   */
  static Checked verify(String compact, String accessToken, String expectedJkt, String method, String expectedHtu, Instant now, Duration window) {
    if (compact.isEmpty() || compact.length() > MAX_LENGTH) {
      return refused("invalid_proof");
    }
    String[] parts = compact.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
      return refused("invalid_proof");
    }
    JsonNode header = object(parts[0]);
    JsonNode claims = object(parts[1]);
    byte[] signature = decode(parts[2]);
    if (header == null || claims == null || signature == null) {
      return refused("invalid_proof");
    }
    if (!"dpop+jwt".equals(Jwt.text(header, "typ")) || !"ES256".equals(Jwt.text(header, "alg")) || header.has("crit")) {
      return refused("invalid_proof");
    }
    JsonNode jwk = header.get("jwk");
    if (jwk == null || !jwk.isObject() || !"EC".equals(Jwt.text(jwk, "kty")) || !"P-256".equals(Jwt.text(jwk, "crv")) || jwk.has("d")) {
      return refused("invalid_proof");
    }
    byte[] x = decode(Jwt.text(jwk, "x"));
    byte[] y = decode(Jwt.text(jwk, "y"));
    if (x == null || y == null || x.length != 32 || y.length != 32) {
      return refused("invalid_proof");
    }
    ECPublicKey key = publicKey(x, y);
    if (key == null || signature.length != 64 || !signatureMatches(key, parts[0] + "." + parts[1], signature)) {
      return refused("invalid_proof");
    }
    String jkt = thumbprint(x, y);
    if (!MessageDigest.isEqual(jkt.getBytes(StandardCharsets.US_ASCII), expectedJkt.getBytes(StandardCharsets.US_ASCII))) {
      return refused("jkt_mismatch");
    }
    if (!method.equals(Jwt.text(claims, "htm"))) {
      return refused("htm_mismatch");
    }
    if (!sameUrl(Jwt.text(claims, "htu"), expectedHtu)) {
      return refused("htu_mismatch");
    }
    String ath = Jwt.text(claims, "ath");
    if (ath == null || !MessageDigest.isEqual(ath.getBytes(StandardCharsets.US_ASCII), athOf(accessToken).getBytes(StandardCharsets.US_ASCII))) {
      return refused("ath_mismatch");
    }
    String jti = Jwt.text(claims, "jti");
    JsonNode iat = claims.get("iat");
    if (jti == null || jti.length() > MAX_JTI || iat == null || !iat.isNumber()) {
      return refused("invalid_proof");
    }
    Instant issued = Jwt.seconds(iat);
    if (issued.isBefore(now.minus(window)) || issued.isAfter(now.plus(window))) {
      return refused("stale_proof");
    }
    return new Checked.Ok(jkt, jti, Jwt.text(claims, "nonce"));
  }

  private static Checked refused(String reason) {
    return new Checked.Refused(reason);
  }

  /** {@code htu} equals the expected URL exactly: the configured origin plus the escaped path as received. */
  static boolean sameUrl(@Nullable String presented, String expected) {
    return presented != null && presented.equals(expected);
  }

  static String athOf(String accessToken) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** The RFC 7638 thumbprint of an EC P-256 key given its coordinates. */
  static String thumbprint(byte[] x, byte[] y) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + b64.encodeToString(x) + "\",\"y\":\"" + b64.encodeToString(y) + "\"}";
    try {
      return b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static @Nullable ECPublicKey publicKey(byte[] x, byte[] y) {
    BigInteger px = new BigInteger(1, x);
    BigInteger py = new BigInteger(1, y);
    // the point must be on the curve: y^2 = x^3 + ax + b (mod p)
    BigInteger a = P256.getCurve().getA();
    BigInteger b = P256.getCurve().getB();
    BigInteger left = py.multiply(py).mod(FIELD_PRIME);
    BigInteger right = px.pow(3).add(a.multiply(px)).add(b).mod(FIELD_PRIME);
    if (px.compareTo(FIELD_PRIME) >= 0 || py.compareTo(FIELD_PRIME) >= 0 || !left.equals(right)) {
      return null;
    }
    try {
      return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(px, py), P256));
    } catch (GeneralSecurityException e) {
      return null;
    }
  }

  private static boolean signatureMatches(ECPublicKey key, String signedPart, byte[] raw) {
    try {
      Signature check = Signature.getInstance("SHA256withECDSA");
      check.initVerify(key);
      check.update(signedPart.getBytes(StandardCharsets.US_ASCII));
      return check.verify(toDer(raw));
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** The JWS signature is r ‖ s, 32 bytes each; the JDK wants the DER {@code SEQUENCE { r, s }}. */
  static byte[] toDer(byte[] raw) {
    byte[] r = derInteger(raw, 0);
    byte[] s = derInteger(raw, 32);
    int length = r.length + s.length;
    byte[] sequence = length < 128 ? new byte[] {0x30, (byte) length} : new byte[] {0x30, (byte) 0x81, (byte) length};
    byte[] out = new byte[sequence.length + length];
    System.arraycopy(sequence, 0, out, 0, sequence.length);
    System.arraycopy(r, 0, out, sequence.length, r.length);
    System.arraycopy(s, 0, out, sequence.length + r.length, s.length);
    return out;
  }

  private static byte[] derInteger(byte[] raw, int offset) {
    int start = offset;
    int end = offset + 32;
    while (start < end - 1 && raw[start] == 0) {
      start++;
    }
    boolean pad = (raw[start] & 0x80) != 0;
    int length = end - start + (pad ? 1 : 0);
    byte[] out = new byte[2 + length];
    out[0] = 0x02;
    out[1] = (byte) length;
    System.arraycopy(raw, start, out, pad ? 3 : 2, end - start);
    return out;
  }

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new ExceptionInInitializerError(e);
    }
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

  private static byte @Nullable [] decode(@Nullable String part) {
    if (part == null || part.indexOf('=') >= 0) {
      return null;
    }
    try {
      return Base64.getUrlDecoder().decode(part);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
