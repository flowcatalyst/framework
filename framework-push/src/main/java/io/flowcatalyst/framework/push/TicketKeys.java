package io.flowcatalyst.framework.push;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket keys ({@code push.ticket.key}, push contract 6.4): Ed25519, the signing key first and
 * the previous one second (verification only). Tickets are JWS compact, {@code alg} {@code EdDSA}.
 */
final class TicketKeys {

  /** Every ticket's {@code aud}. */
  static final String AUDIENCE = "fw-push";

  /** How long a minted ticket may be used to subscribe ({@code exp - iat}). */
  static final long TTL_SECONDS = 120;

  /** The longest {@code exp - iat} a ticket may have. */
  static final long MAX_TTL_SECONDS = 300;

  /** The most patterns one ticket carries. */
  static final int MAX_TOPICS = 100;

  // The reasons a ticket grants nothing (conformance/push/tickets.json); its topics are denied with
  // TICKET_INVALID.
  static final String REASON_ALG = "alg";
  static final String REASON_KID = "kid";
  static final String REASON_SIGNATURE = "signature";
  static final String REASON_ISS = "iss";
  static final String REASON_AUD = "aud";
  static final String REASON_TIMES = "times";
  static final String REASON_EXPIRED = "expired";
  static final String REASON_CLIENT = "client";
  static final String REASON_TOPICS = "topics";
  static final String REASON_OUTSIDE_ISS = "topic-outside-iss";
  static final String REASON_CNF = "cnf";
  static final String REASON_STREAM_EXP = "stream-exp";
  static final String REASON_CLIENT_MISMATCH = "client-mismatch";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  /** A ticket that is not a JWS compact with a JSON header and claims: 400. */
  static final class MalformedTicketException extends Exception {
    private static final long serialVersionUID = 1L;

    MalformedTicketException() {
      super("a ticket is malformed", null, false, false);
    }
  }

  private record Key(String kid, PrivateKey privateKey, PublicKey publicKey, byte[] publicBytes) {}

  private final List<Key> keys;

  private TicketKeys(List<Key> keys) {
    this.keys = keys;
  }

  /**
   * Reads {@code <kid>:<base64url of a 32-byte Ed25519 seed>}, comma-separated, the signing key
   * first, at most two, kids {@code [A-Za-z0-9._-]{1,64}} and distinct.
   *
   * @throws IllegalArgumentException when the text is not that
   */
  static TicketKeys parse(String text) {
    List<Key> out = new ArrayList<>();
    for (String raw : text.split(",", -1)) {
      String entry = raw.strip();
      int colon = entry.indexOf(':');
      if (colon < 0 || !validKid(entry.substring(0, colon))) {
        throw new IllegalArgumentException(
            "each key is <kid>:<base64url of a 32-byte Ed25519 seed>, the kid [A-Za-z0-9._-]{1,64}");
      }
      String kid = entry.substring(0, colon);
      byte[] seed;
      try {
        seed = B64D.decode(entry.substring(colon + 1));
      } catch (IllegalArgumentException e) {
        seed = new byte[0];
      }
      if (seed.length != 32) {
        throw new IllegalArgumentException("key '" + kid + "' is not the base64url of a 32-byte Ed25519 seed");
      }
      for (Key k : out) {
        if (k.kid().equals(kid)) {
          throw new IllegalArgumentException("the kid '" + kid + "' is given twice");
        }
      }
      KeyPair pair = fromSeed(seed);
      out.add(new Key(kid, pair.getPrivate(), pair.getPublic(), rawPublic((EdECPublicKey) pair.getPublic())));
    }
    if (out.size() > 2) {
      throw new IllegalArgumentException("at most two keys: the current one and the previous one");
    }
    return new TicketKeys(List.copyOf(out));
  }

  /** The Ed25519 key pair of a seed: the JDK derives the key from the 32 bytes its generator reads. */
  private static KeyPair fromSeed(byte[] seed) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
      generator.initialize(NamedParameterSpec.ED25519, new FixedRandom(seed));
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 is not available", e);
    }
  }

  /** A random source that returns the bytes it was given: the seed. */
  private static final class FixedRandom extends SecureRandom {
    private static final long serialVersionUID = 1L;
    private final byte[] seed;

    FixedRandom(byte[] seed) {
      this.seed = seed.clone();
    }

    @Override
    public void nextBytes(byte[] bytes) {
      System.arraycopy(seed, 0, bytes, 0, Math.min(bytes.length, seed.length));
    }
  }

  /** The 32 bytes of RFC 8032: y little-endian, the high bit of the last byte the parity of x. */
  private static byte[] rawPublic(EdECPublicKey key) {
    EdECPoint point = key.getPoint();
    byte[] y = point.getY().toByteArray(); // big-endian, may carry a sign byte
    byte[] out = new byte[32];
    for (int i = 0; i < 32 && i < y.length; i++) {
      out[i] = y[y.length - 1 - i];
    }
    if (point.isXOdd()) {
      out[31] |= (byte) 0x80;
    }
    return out;
  }

  private static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
    byte[] be = new byte[32];
    for (int i = 0; i < 32; i++) {
      be[i] = raw[31 - i];
    }
    boolean xOdd = (be[0] & 0x80) != 0;
    be[0] &= 0x7f;
    return KeyFactory.getInstance("Ed25519")
        .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, be))));
  }

  private static boolean validKid(String s) {
    if (s.isEmpty() || s.length() > 64) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  private @Nullable Key byKid(@Nullable String kid) {
    for (Key key : keys) {
      if (key.kid().equals(kid)) {
        return key;
      }
    }
    return null;
  }

  /** The JWKS of the public keys, current first (GET {base}/push/keys). */
  byte[] jwks() {
    StringBuilder out = new StringBuilder("{\"keys\":[");
    for (int i = 0; i < keys.size(); i++) {
      Key k = keys.get(i);
      if (i > 0) {
        out.append(',');
      }
      out.append("{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"kid\":")
          .append(Wire.jsonString(k.kid()))
          .append(",\"x\":\"")
          .append(B64.encodeToString(k.publicBytes()))
          .append("\",\"alg\":\"EdDSA\",\"use\":\"sig\"}");
    }
    return out.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  /** The ticket's claims, in the order they are written. */
  record Claims(
      String iss,
      String client,
      List<String> topics,
      @Nullable String sub,
      @Nullable String jkt,
      long iat,
      long exp,
      long streamExp) {}

  /** Signs the claims as a JWS compact with the signing key's kid. */
  String sign(Claims c) {
    Key key = keys.getFirst();
    StringBuilder claims = new StringBuilder("{\"iss\":").append(Wire.jsonString(c.iss()));
    claims.append(",\"aud\":\"").append(AUDIENCE).append('"');
    claims.append(",\"client\":").append(Wire.jsonString(c.client()));
    claims.append(",\"topics\":[");
    for (int i = 0; i < c.topics().size(); i++) {
      if (i > 0) {
        claims.append(',');
      }
      claims.append(Wire.jsonString(c.topics().get(i)));
    }
    claims.append(']');
    if (c.sub() != null && !c.sub().isEmpty()) {
      claims.append(",\"sub\":").append(Wire.jsonString(c.sub()));
    }
    if (c.jkt() != null && !c.jkt().isEmpty()) {
      claims.append(",\"cnf\":{\"jkt\":").append(Wire.jsonString(c.jkt())).append('}');
    }
    claims.append(",\"iat\":").append(c.iat()).append(",\"exp\":").append(c.exp());
    if (c.streamExp() != 0) {
      claims.append(",\"stream-exp\":").append(c.streamExp());
    }
    claims.append('}');
    String header = "{\"alg\":\"EdDSA\",\"kid\":" + Wire.jsonString(key.kid()) + "}";
    String signed = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key.privateKey());
      signature.update(signed.getBytes(StandardCharsets.US_ASCII));
      return signed + "." + b64(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign a ticket", e);
    }
  }

  private static String b64(byte[] bytes) {
    return B64.encodeToString(bytes);
  }

  /**
   * A checked ticket. An invalid one grants nothing; {@code patterns} then holds the patterns it
   * names (unverified), so its topics are denied TICKET_INVALID rather than TOPIC_FORBIDDEN.
   */
  static final class Ticket {
    boolean valid;
    String reason = "";
    String client = "";
    String sub = "";
    List<TopicPattern> patterns = List.of();
    Instant streamExp = Instant.EPOCH;
  }

  /** What a ticket is checked against. */
  record Check(String application, Instant now, String proofKey, java.time.Duration maxLifetime) {}

  /**
   * Verifies one ticket (push contract 6.4).
   *
   * @throws MalformedTicketException for a ticket that is not a JWS compact with a JSON header and
   *     JSON claims
   */
  Ticket check(String raw, Check c) throws MalformedTicketException {
    String[] parts = raw.split("\\.", -1);
    if (parts.length != 3 || raw.length() > 64 * 1024) {
      throw new MalformedTicketException();
    }
    JsonNode header;
    JsonNode claims;
    byte[] sig;
    try {
      header = MAPPER.readTree(B64D.decode(parts[0]));
      claims = MAPPER.readTree(B64D.decode(parts[1]));
      sig = B64D.decode(parts[2]);
    } catch (RuntimeException e) {
      throw new MalformedTicketException();
    }
    if (header == null || !header.isObject() || claims == null || !claims.isObject()) {
      throw new MalformedTicketException();
    }
    Ticket t = new Ticket();
    t.patterns = namedPatterns(claims.get("topics"));
    if (!"EdDSA".equals(text(header, "alg")) || header.has("crit")) {
      return bad(t, REASON_ALG);
    }
    Key key = byKid(text(header, "kid"));
    if (key == null) {
      return bad(t, REASON_KID);
    }
    if (!verifies(key, parts[0] + "." + parts[1], sig)) {
      return bad(t, REASON_SIGNATURE);
    }
    if (!c.application().equals(text(claims, "iss"))) {
      return bad(t, REASON_ISS);
    }
    if (!AUDIENCE.equals(text(claims, "aud"))) {
      return bad(t, REASON_AUD);
    }
    Instant iat = seconds(claims.get("iat"));
    Instant exp = seconds(claims.get("exp"));
    if (iat == null
        || exp == null
        || !exp.isAfter(iat)
        || exp.getEpochSecond() - iat.getEpochSecond() > MAX_TTL_SECONDS
        || iat.isAfter(c.now().plusSeconds(60))) {
      return bad(t, REASON_TIMES);
    }
    if (!c.now().isBefore(exp)) {
      return bad(t, REASON_EXPIRED);
    }
    String client = text(claims, "client");
    if (client == null || !validClient(client)) {
      return bad(t, REASON_CLIENT);
    }
    JsonNode topics = claims.get("topics");
    if (topics == null || !topics.isArray() || topics.isEmpty() || topics.size() > MAX_TOPICS || t.patterns.size() != topics.size()) {
      return bad(t, REASON_TOPICS);
    }
    for (TopicPattern p : t.patterns) {
      if (!p.application().equals(c.application())) {
        return bad(t, REASON_OUTSIDE_ISS);
      }
    }
    if (claims.has("cnf")) {
      JsonNode cnf = claims.get("cnf");
      String jkt = cnf != null && cnf.isObject() ? text(cnf, "jkt") : null;
      if (jkt == null || jkt.isEmpty() || c.proofKey().isEmpty() || !jkt.equals(c.proofKey())) {
        return bad(t, REASON_CNF);
      }
    }
    t.streamExp = exp.plus(c.maxLifetime());
    if (claims.has("stream-exp")) {
      Instant se = seconds(claims.get("stream-exp"));
      if (se == null || !c.now().isBefore(se)) {
        return bad(t, REASON_STREAM_EXP);
      }
      t.streamExp = se;
    }
    t.client = client;
    String sub = text(claims, "sub");
    t.sub = sub == null ? "" : sub;
    t.valid = true;
    return t;
  }

  private static Ticket bad(Ticket t, String reason) {
    t.valid = false;
    t.reason = reason;
    return t;
  }

  private static boolean verifies(Key key, String signed, byte[] sig) {
    if (sig.length != 64) {
      return false;
    }
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(key.publicKey());
      signature.update(signed.getBytes(StandardCharsets.US_ASCII));
      return signature.verify(sig);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static @Nullable String text(JsonNode node, String name) {
    JsonNode v = node.get(name);
    return v != null && v.isString() ? v.asString() : null;
  }

  /** Reads a NumericDate. */
  private static @Nullable Instant seconds(@Nullable JsonNode v) {
    if (v == null || !v.isNumber()) {
      return null;
    }
    double f = v.doubleValue();
    if (f < 0 || f > 1e11) {
      return null;
    }
    return Instant.ofEpochSecond((long) f);
  }

  /** Reads the topics claim as patterns, leaving out any that is not one. */
  private static List<TopicPattern> namedPatterns(@Nullable JsonNode v) {
    if (v == null || !v.isArray()) {
      return List.of();
    }
    List<TopicPattern> out = new ArrayList<>();
    for (JsonNode item : v) {
      if (item.isString()) {
        try {
          out.add(TopicPattern.parse(item.asString()));
        } catch (IllegalArgumentException e) {
          // not a pattern: left out
        }
      }
    }
    return out;
  }

  /** Checks a client value: {@code [A-Za-z0-9._-]{1,100}}, as {@code ClientId}. */
  private static boolean validClient(String s) {
    return !s.isEmpty() && s.length() <= 100 && s.chars().allMatch(TicketKeys::clientChar);
  }

  private static boolean clientChar(int c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
  }
}
