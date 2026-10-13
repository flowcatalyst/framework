package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.config.Secret;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The OIDC relying party (contract sections 4.1, 5.4 and 5.5): the authorize URL, the code
 * exchange, and the id_token checks. A public client sends no secret; a confidential one uses HTTP
 * Basic.
 */
final class RelyingParty {

  private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");

  /** The token endpoint's answer to a code. */
  sealed interface Exchange {
    record Tokens(String idToken) implements Exchange {}

    record Refused(String error) implements Exchange {}
  }

  /** The id_token checks' outcome. */
  sealed interface IdToken {
    record Ok(Principal principal, Instant authTime) implements IdToken {}

    record Refused(String reason) implements IdToken {}
  }

  private final Platform platform;
  private final String clientId;
  private final @Nullable Secret clientSecret;
  private final String redirectUri;
  private final String scope;
  private final @Nullable String theme;
  private final InstantSource clock;

  RelyingParty(
      Platform platform,
      String clientId,
      @Nullable Secret clientSecret,
      String redirectUri,
      String scope,
      @Nullable String theme,
      InstantSource clock) {
    this.platform = platform;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.scope = scope;
    this.theme = theme;
    this.clock = clock;
  }

  String clientId() {
    return clientId;
  }

  /** The PKCE challenge of a verifier: base64url of SHA-256 of its ASCII bytes. */
  static String challenge(String verifier) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(SessionHandles.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  String authorizeUrl(SessionManager.LoginData login) throws PlatformUnavailableException {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("response_type", "code");
    params.put("client_id", clientId);
    params.put("redirect_uri", redirectUri);
    params.put("scope", scope);
    params.put("state", login.state());
    params.put("nonce", login.nonce());
    params.put("code_challenge", challenge(login.codeVerifier()));
    params.put("code_challenge_method", "S256");
    if (theme != null) {
      params.put("client", theme);
    }
    return withQuery(platform.discovery().authorizationEndpoint().toString(), params);
  }

  Exchange exchange(String code, String verifier) throws PlatformUnavailableException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("client_id", clientId);
    form.put("code_verifier", verifier);
    String authorization = null;
    Secret secret = clientSecret;
    if (secret != null) {
      String pair = Platform.formEncode(clientId) + ":" + Platform.formEncode(secret.reveal());
      authorization = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
    Platform.Answer answer = platform.postForm(platform.discovery().tokenEndpoint(), form, authorization);
    if (answer.status() >= 500) {
      throw new PlatformUnavailableException("the token endpoint answered " + answer.status());
    }
    JsonNode json = answer.json();
    if (answer.status() >= 400) {
      String error = json == null ? "" : json.path("error").asString("");
      return new Exchange.Refused(ERROR_CODE.matcher(error).matches() ? error : "invalid");
    }
    if (answer.status() != 200 || json == null) {
      throw new PlatformUnavailableException("the token endpoint answered " + answer.status() + " without JSON");
    }
    String idToken = json.path("id_token").asString("");
    if (idToken.isEmpty()) {
      return new Exchange.Refused("no_id_token");
    }
    return new Exchange.Tokens(idToken);
  }

  IdToken verify(String idToken, String nonce) throws PlatformUnavailableException {
    return switch (Jwt.verify(idToken, platform)) {
      case Jwt.Checked.Refused r -> new IdToken.Refused(r.reason());
      case Jwt.Checked.Ok ok -> {
        Instant now = clock.instant();
        Optional<String> refused = Claims.idToken(ok.claims(), platform.issuer(), clientId, nonce, now);
        if (refused.isPresent()) {
          yield new IdToken.Refused(refused.get());
        }
        JsonNode authTime = ok.claims().get("auth_time");
        Instant at = authTime != null && authTime.isNumber() ? Jwt.seconds(authTime) : now;
        yield new IdToken.Ok(Claims.principal(ok.claims(), false), at);
      }
    };
  }

  /** The platform's end-session URL for this client, or null when it has none or is unreachable. */
  @Nullable String endSessionUrl(@Nullable String postLogoutRedirectUri) {
    URI end;
    try {
      end = platform.discovery().endSessionEndpoint();
    } catch (PlatformUnavailableException e) {
      return null;
    }
    if (end == null) {
      return null;
    }
    Map<String, String> params = new LinkedHashMap<>();
    params.put("client_id", clientId);
    if (postLogoutRedirectUri != null) {
      params.put("post_logout_redirect_uri", postLogoutRedirectUri);
    }
    return withQuery(end.toString(), params);
  }

  private static String withQuery(String base, Map<String, String> params) {
    StringJoiner query = new StringJoiner("&");
    params.forEach((k, v) -> query.add(Platform.formEncode(k) + "=" + Platform.formEncode(v)));
    return base + (base.contains("?") ? "&" : "?") + query;
  }
}
