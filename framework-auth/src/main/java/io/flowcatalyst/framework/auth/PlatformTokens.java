package io.flowcatalyst.framework.auth;

import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import tools.jackson.databind.JsonNode;

/**
 * Verifies platform access tokens (contract section 5.6) without sessions: RS256 against the
 * platform's published keys, issuer and audience equal to the platform's base URL, the time
 * claims with 60 seconds of allowance, {@code token_use} {@code api}. An identity token from a
 * default login client is refused.
 *
 * <pre>{@code
 * PlatformTokens tokens = PlatformTokens.create(URI.create("https://platform.example.com"),
 *     Duration.ofSeconds(10), app.get(InstantSource.class));
 * switch (tokens.verify(bearer)) {
 *   case TokenResult.Valid v when v.token().mayActFor(clientId) -> ...;
 *   case TokenResult.Valid v -> forbidden;
 *   case TokenResult.Invalid i -> unauthenticated;
 *   case TokenResult.Unavailable u -> unavailable (503);
 * }
 * }</pre>
 *
 * <p>Thread-safe; build one per platform and keep it, so the keys are cached. {@link AuthModule}
 * provides one.
 */
public final class PlatformTokens {

  private final Platform platform;
  private final InstantSource clock;

  PlatformTokens(Platform platform, InstantSource clock) {
    this.platform = platform;
    this.clock = clock;
  }

  /**
   * Creates a verifier.
   *
   * @param issuer the platform's base URL, which is also its tokens' issuer and audience
   * @param timeout how long to wait for the platform's discovery document and keys
   * @param clock the clock that decides whether a token is still valid
   * @return the verifier
   */
  public static PlatformTokens create(URI issuer, Duration timeout, InstantSource clock) {
    return new PlatformTokens(new Platform(issuer, timeout, clock), clock);
  }

  /**
   * Verifies an access token.
   *
   * @param token the compact token, without the {@code Bearer} prefix
   * @return valid, invalid with a reason, or unavailable
   */
  public TokenResult verify(String token) {
    try {
      return switch (Jwt.verify(token, platform)) {
        case Jwt.Checked.Refused r -> new TokenResult.Invalid(r.reason());
        case Jwt.Checked.Ok ok -> claims(ok.claims());
      };
    } catch (PlatformUnavailableException e) {
      return new TokenResult.Unavailable(e.getMessage() == null ? "the platform cannot be reached" : e.getMessage());
    }
  }

  private TokenResult claims(JsonNode claims) {
    var refused = Claims.accessToken(claims, platform.issuer(), clock.instant());
    if (refused.isPresent()) {
      return new TokenResult.Invalid(refused.get());
    }
    return new TokenResult.Valid(
        new AccessToken(Claims.principal(claims, true), Jwt.text(claims, "azp"), Jwt.seconds(claims.get("exp"))));
  }

  Platform platform() {
    return platform;
  }
}
