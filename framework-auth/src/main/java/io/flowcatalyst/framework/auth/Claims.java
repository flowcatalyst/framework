package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.PrincipalId;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The claim rules of an id_token (contract section 5.5, steps 5 to 12) and of an access token
 * (section 5.6), and the principal they describe.
 */
final class Claims {

  private Claims() {}

  /** Checks an id_token's claims; returns the refusal reason or empty. */
  static Optional<String> idToken(JsonNode claims, String issuer, String clientId, String nonce, Instant now) {
    if (!issuer.equals(Jwt.text(claims, "iss"))) {
      return Optional.of("iss");
    }
    JsonNode aud = claims.get("aud");
    List<String> audiences = Jwt.texts(aud);
    if (aud == null || (!aud.isString() && !aud.isArray()) || !audiences.contains(clientId)) {
      return Optional.of("aud");
    }
    JsonNode azp = claims.get("azp");
    if (aud.isArray() && audiences.size() > 1 && azp == null) {
      return Optional.of("azp");
    }
    if (azp != null && !(azp.isString() && azp.asString().equals(clientId))) {
      return Optional.of("azp");
    }
    Optional<String> times = Jwt.times(claims, now);
    if (times.isPresent()) {
      return times;
    }
    String presented = Jwt.text(claims, "nonce");
    if (presented == null || !SessionHandles.same(presented, nonce)) {
      return Optional.of("nonce");
    }
    if (Jwt.text(claims, "sub") == null) {
      return Optional.of("sub");
    }
    if (claims.has("token_use")) {
      return Optional.of("token_use");
    }
    return Optional.empty();
  }

  /** Checks an access token's claims; returns the refusal reason or empty. */
  static Optional<String> accessToken(JsonNode claims, String issuer, Instant now) {
    if (!issuer.equals(Jwt.text(claims, "iss"))) {
      return Optional.of("iss");
    }
    JsonNode aud = claims.get("aud");
    if (aud == null || (!aud.isString() && !aud.isArray()) || !Jwt.texts(aud).contains(issuer)) {
      return Optional.of("aud");
    }
    Optional<String> times = Jwt.times(claims, now);
    if (times.isPresent()) {
      return times;
    }
    if (!"api".equals(Jwt.text(claims, "token_use"))) {
      return Optional.of("token_use");
    }
    if (Jwt.text(claims, "sub") == null) {
      return Optional.of("sub");
    }
    return Optional.empty();
  }

  /** The principal a checked token describes; scopes only from an access token. */
  static Principal principal(JsonNode claims, boolean withScopes) {
    String sub = Jwt.text(claims, "sub");
    if (sub == null) {
      throw new IllegalStateException("claims were not checked");
    }
    Set<String> scopes = new LinkedHashSet<>();
    if (withScopes) {
      String scope = Jwt.text(claims, "scope");
      if (scope != null) {
        for (String s : scope.split(" ")) {
          if (!s.isBlank()) {
            scopes.add(s);
          }
        }
      }
    }
    return new Principal(
        new PrincipalId(sub),
        PrincipalKind.of(String.valueOf(Jwt.text(claims, "type"))),
        Jwt.text(claims, "name"),
        Jwt.text(claims, "email"),
        Jwt.text(claims, "tier"),
        Jwt.text(claims, "client_id"),
        Jwt.texts(claims.get("clients")),
        Jwt.texts(claims.get("applications")),
        Jwt.texts(claims.get("roles")),
        scopes);
  }
}
