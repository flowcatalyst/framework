package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.PrincipalId;
import java.util.Base64;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the application's service principal from its client-credentials access token
 * (flowcatalyst contract section 4b): the token's {@code sub}, when its {@code type} is
 * {@code SERVICE}.
 *
 * <p>The token is the one the module received directly from the platform's token endpoint in
 * answer to its own credentials, so the connection vouches for it and the signature is not
 * checked. Messages never contain the token.
 */
final class ServiceTokens {

  /** What a principal id the receivers accept looks like (the platform's are {@code prn_...}). */
  static final Pattern SUB = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private static final String SERVICE = "SERVICE";

  private ServiceTokens() {}

  /**
   * The service principal a client-credentials token identifies.
   *
   * @param token the access token as the platform issued it
   * @return the principal id ({@code sub})
   * @throws PlatformException when the token is not a JWT or does not identify a service principal
   */
  static PrincipalId servicePrincipal(String token) {
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
      throw refused("it is not a JWT (three non-empty dot-separated segments)");
    }
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      throw refused("its payload is not base64url");
    }
    JsonNode claims = Envelopes.readTree(payload, true);
    if (claims == null || !claims.isObject()) {
      throw refused("its payload is not one JSON object with unique keys");
    }
    JsonNode sub = claims.get("sub");
    if (sub == null || !sub.isString() || !SUB.matcher(sub.asString()).matches()) {
      throw refused("its sub claim is missing or is not a principal id ([A-Za-z0-9._:-]{1,128})");
    }
    JsonNode type = claims.get("type");
    if (type == null || !type.isString() || !SERVICE.equals(type.asString())) {
      throw refused(
          "its type claim is not SERVICE: the configured credentials (flowcatalyst.client-id) must"
              + " be the application's service account, not a user's");
    }
    return new PrincipalId(sub.asString());
  }

  private static PlatformException refused(String reason) {
    return new PlatformException(
        "the platform token does not identify the application's service principal: " + reason);
  }
}
