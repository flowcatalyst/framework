package io.flowcatalyst.framework.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs and verifies stream tokens with {@code push.stream.secret} (HMAC-SHA256), which every
 * instance of the host shares. A stream token names the granted topics, the stream's client, who
 * holds it (principal, field device and login family, for limits and revocation) and the expiry. It
 * is a capability to listen, nothing else; it is never logged.
 */
final class StreamTokens {

  /** The 401 code of a token that is not ours. */
  static final String CODE_INVALID = "STREAM_TOKEN_INVALID";

  /** The 401 code of a token past its expiry. */
  static final String CODE_EXPIRED = "STREAM_TOKEN_EXPIRED";

  private static final String MAC_CONTEXT = "fw-push-stream:";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  /** The token's claims. */
  record Claims(Subscription subscription, long exp) {}

  /** The outcome of {@link #verify}. */
  sealed interface Verified {}

  record Valid(Claims claims) implements Verified {}

  record Refused(String code) implements Verified {}

  private final byte[] secret;

  StreamTokens(String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] mac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal((MAC_CONTEXT + payload).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /** Returns base64url(claims JSON) "." base64url(HMAC). */
  String sign(Subscription sub, long exp) {
    StringBuilder json = new StringBuilder("{\"v\":1,\"c\":").append(Wire.jsonString(sub.client()));
    if (!sub.principal().isEmpty()) {
      json.append(",\"p\":").append(Wire.jsonString(sub.principal()));
    }
    if (!sub.device().isEmpty()) {
      json.append(",\"d\":").append(Wire.jsonString(sub.device()));
    }
    if (!sub.session().isEmpty()) {
      json.append(",\"s\":").append(Wire.jsonString(sub.session()));
    }
    json.append(",\"t\":[");
    for (int i = 0; i < sub.topics().size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append(Wire.jsonString(sub.topics().get(i)));
    }
    json.append("],\"exp\":").append(exp).append('}');
    String payload = B64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    return payload + "." + B64.encodeToString(mac(payload));
  }

  /** The claims, or the 401 code. */
  Verified verify(String token, Instant now) {
    int dot = token.indexOf('.');
    if (dot < 0 || token.length() > 64 * 1024) {
      return new Refused(CODE_INVALID);
    }
    String payload = token.substring(0, dot);
    byte[] got;
    try {
      got = B64D.decode(token.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return new Refused(CODE_INVALID);
    }
    if (!MessageDigest.isEqual(got, mac(payload))) {
      return new Refused(CODE_INVALID);
    }
    try {
      JsonNode node = MAPPER.readTree(B64D.decode(payload));
      if (node == null
          || !node.isObject()
          || node.path("v").asInt(0) != 1
          || !node.path("t").isArray()
          || node.path("t").isEmpty()
          || !node.path("c").isString()
          || node.path("c").asString().isEmpty()
          || !node.path("exp").isNumber()) {
        return new Refused(CODE_INVALID);
      }
      List<String> topics = new ArrayList<>();
      for (JsonNode t : node.path("t")) {
        if (!t.isString()) {
          return new Refused(CODE_INVALID);
        }
        topics.add(t.asString());
      }
      long exp = node.path("exp").asLong();
      if (!now.isBefore(Instant.ofEpochSecond(exp))) {
        return new Refused(CODE_EXPIRED);
      }
      Subscription sub =
          new Subscription(
              List.copyOf(topics),
              node.path("c").asString(),
              node.path("p").asString(""),
              node.path("d").asString(""),
              node.path("s").asString(""));
      return new Valid(new Claims(sub, exp));
    } catch (RuntimeException e) {
      return new Refused(CODE_INVALID);
    }
  }
}
