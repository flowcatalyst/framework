package io.flowcatalyst.framework.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * An enrolled field device for tests (contract section 15.8): an ES256 key, its RFC 7638 thumbprint
 * ({@link #jkt()}), and DPoP proofs that are valid unless a {@link Flaw} breaks one rule. Test
 * support only; it signs for whoever asks.
 *
 * <pre>{@code
 * TestFieldDevice device = TestFieldDevice.create();
 * String token = provider.fieldToken("orders", device.jkt(), Map.of());
 * String proof = device.proof("POST", "https://app.example/api/orders", nonce, token);
 * client.post("/api/orders").header("Authorization", "DPoP " + token).header("DPoP", proof)...
 * }</pre>
 *
 * <p>To get a nonce the way a real client does, send the request without one and read the {@code
 * DPoP-Nonce} header of the {@code 401 USE_DPOP_NONCE} answer.
 */
public final class TestFieldDevice {

  /** One rule a proof can break. */
  public enum Flaw {
    /** the header carries a different key, so {@code jkt} is not the token's ({@code jkt_mismatch}) */
    OTHER_DEVICE_KEY,
    /** signed by another key than the header names ({@code invalid_proof}) */
    BAD_SIGNATURE,
    /** {@code htm} is not the request method */
    WRONG_METHOD,
    /** {@code htu} names another path */
    WRONG_URL,
    /** {@code ath} is not the hash of the token */
    WRONG_ATH,
    /** {@code iat} two days ago */
    STALE,
    /** no {@code jti} */
    NO_JTI,
    /** no {@code nonce} claim even if one was given */
    NO_NONCE,
    /** {@code typ} is not {@code dpop+jwt} */
    WRONG_TYP,
    /** {@code alg} says {@code RS256} */
    WRONG_ALG,
    /** the {@code jwk} carries a private member */
    PRIVATE_MEMBER
  }

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final KeyPair keys;
  private final InstantSource clock;

  private TestFieldDevice(KeyPair keys, InstantSource clock) {
    this.keys = keys;
    this.clock = clock;
  }

  /**
   * A new device with a new key, whose proofs are issued at the system time.
   *
   * @return the device
   */
  public static TestFieldDevice create() {
    return create(InstantSource.system());
  }

  /**
   * A new device whose proofs are issued by the given clock; share the application's.
   *
   * @param clock the clock for {@code iat}
   * @return the device
   */
  public static TestFieldDevice create(InstantSource clock) {
    return new TestFieldDevice(newKey(), clock);
  }

  /**
   * The thumbprint of the device key: what the access token's {@code cnf.jkt} must hold.
   *
   * @return the RFC 7638 SHA-256 thumbprint, base64url
   */
  public String jkt() {
    return thumbprint(keys);
  }

  /**
   * A proof for a request.
   *
   * @param method the request method
   * @param url {@code auth.public-base-url} plus the path, for example {@code https://app.example/api/orders}
   * @param nonce the application nonce, or null to send none
   * @param accessToken the compact access token the request carries
   * @param flaws rules to break; none gives a valid proof
   * @return the compact proof, a fresh {@code jti} each time
   */
  public String proof(String method, String url, @Nullable String nonce, String accessToken, Flaw... flaws) {
    return proof(method, url, nonce, accessToken, UUID.randomUUID().toString(), flaws);
  }

  /**
   * A proof with a chosen {@code jti}, to present the same proof twice.
   *
   * @param method the request method
   * @param url the full URL without query
   * @param nonce the application nonce, or null to send none
   * @param accessToken the compact access token the request carries
   * @param jti the proof id
   * @param flaws rules to break
   * @return the compact proof
   */
  public String proof(String method, String url, @Nullable String nonce, String accessToken, String jti, Flaw... flaws) {
    Set<Flaw> broken = flaws.length == 0 ? EnumSet.noneOf(Flaw.class) : EnumSet.copyOf(java.util.List.of(flaws));
    KeyPair named = broken.contains(Flaw.OTHER_DEVICE_KEY) ? newKey() : keys;
    KeyPair signer = broken.contains(Flaw.BAD_SIGNATURE) ? newKey() : named;

    Map<String, Object> jwk = new LinkedHashMap<>();
    ECPublicKey pub = (ECPublicKey) named.getPublic();
    jwk.put("crv", "P-256");
    jwk.put("kty", "EC");
    jwk.put("x", B64.encodeToString(coordinate(pub.getW().getAffineX())));
    jwk.put("y", B64.encodeToString(coordinate(pub.getW().getAffineY())));
    if (broken.contains(Flaw.PRIVATE_MEMBER)) {
      jwk.put("d", B64.encodeToString(new byte[32]));
    }
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("typ", broken.contains(Flaw.WRONG_TYP) ? "JWT" : "dpop+jwt");
    header.put("alg", broken.contains(Flaw.WRONG_ALG) ? "RS256" : "ES256");
    header.put("jwk", jwk);

    Instant now = clock.instant();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("htm", broken.contains(Flaw.WRONG_METHOD) ? (method.equals("GET") ? "POST" : "GET") : method);
    claims.put("htu", broken.contains(Flaw.WRONG_URL) ? url + "/other" : url);
    claims.put("iat", (broken.contains(Flaw.STALE) ? now.minus(Duration.ofDays(2)) : now).getEpochSecond());
    if (!broken.contains(Flaw.NO_JTI)) {
      claims.put("jti", jti);
    }
    claims.put("ath", broken.contains(Flaw.WRONG_ATH) ? ath("some other token") : ath(accessToken));
    if (nonce != null && !broken.contains(Flaw.NO_NONCE)) {
      claims.put("nonce", nonce);
    }
    String signed = B64.encodeToString(json(header)) + "." + B64.encodeToString(json(claims));
    return signed + "." + B64.encodeToString(sign(signer, signed));
  }

  /**
   * The nonce a {@code 401 USE_DPOP_NONCE} (or any answer to a {@code DPoP} request) offers.
   *
   * @param dpopNonceHeader the value of the {@code DPoP-Nonce} response header
   * @return the same value, checked to be present
   */
  public static String nonceFrom(@Nullable String dpopNonceHeader) {
    if (dpopNonceHeader == null || dpopNonceHeader.isEmpty()) {
      throw new IllegalStateException("the answer carried no DPoP-Nonce header");
    }
    return dpopNonceHeader;
  }

  private static KeyPair newKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String thumbprint(KeyPair pair) {
    ECPublicKey pub = (ECPublicKey) pair.getPublic();
    return FieldProofs.thumbprint(coordinate(pub.getW().getAffineX()), coordinate(pub.getW().getAffineY()));
  }

  private static byte[] coordinate(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] out = new byte[32];
    int copy = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
    return out;
  }

  private static String ath(String token) {
    try {
      return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] json(Map<String, Object> value) {
    return JSON.writeValueAsBytes(value);
  }

  /** ES256 as a JWS wants it: r ‖ s, 32 bytes each, not the JDK's DER. */
  private static byte[] sign(KeyPair pair, String signed) {
    try {
      Signature signature = Signature.getInstance("SHA256withECDSA");
      signature.initSign(pair.getPrivate());
      signature.update(signed.getBytes(StandardCharsets.US_ASCII));
      byte[] der = signature.sign();
      int offset = 3;
      int rLength = der[offset] & 0xff;
      byte[] r = java.util.Arrays.copyOfRange(der, offset + 1, offset + 1 + rLength);
      offset = offset + 1 + rLength + 1;
      int sLength = der[offset] & 0xff;
      byte[] s = java.util.Arrays.copyOfRange(der, offset + 1, offset + 1 + sLength);
      byte[] out = new byte[64];
      System.arraycopy(coordinate(new BigInteger(1, r)), 0, out, 0, 32);
      System.arraycopy(coordinate(new BigInteger(1, s)), 0, out, 32, 32);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
