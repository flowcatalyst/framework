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
 * <p>An {@code http://} issuer is refused ({@link IllegalArgumentException}) unless the verifier is
 * built with {@link #forEnvironment} (which allows it only for {@code APP_ENV} local and test) or
 * {@link #createAllowingPlainHttp}.
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
    return create(issuer, timeout, clock, false);
  }

  /**
   * Creates a verifier for a platform reached over plain {@code http}. For {@code APP_ENV} local and
   * test only: keys and tokens would cross the network unprotected.
   *
   * @param issuer the platform's base URL ({@code http} or {@code https})
   * @param timeout how long to wait for the platform's discovery document and keys
   * @param clock the clock that decides whether a token is still valid
   * @return the verifier
   */
  public static PlatformTokens createAllowingPlainHttp(URI issuer, Duration timeout, InstantSource clock) {
    return create(issuer, timeout, clock, true);
  }

  /**
   * Creates a verifier, allowing a plain {@code http} issuer only when {@code environment} is
   * {@code local} or {@code test}. This is the check {@link AuthModule} makes, so an application
   * passes {@code config.environment()} instead of writing it.
   *
   * @param environment the application's environment ({@code APP_ENV})
   * @param issuer the platform's base URL, which is also its tokens' issuer and audience
   * @param timeout how long to wait for the platform's discovery document and keys
   * @param clock the clock that decides whether a token is still valid
   * @return the verifier
   */
  public static PlatformTokens forEnvironment(String environment, URI issuer, Duration timeout, InstantSource clock) {
    return create(issuer, timeout, clock, "local".equals(environment) || "test".equals(environment));
  }

  private static PlatformTokens create(URI issuer, Duration timeout, InstantSource clock, boolean allowPlainHttp) {
    String scheme = issuer.getScheme();
    boolean https = "https".equalsIgnoreCase(scheme);
    boolean http = "http".equalsIgnoreCase(scheme);
    if (issuer.getHost() == null || !(https || (http && allowPlainHttp))) {
      throw new IllegalArgumentException(
          "the issuer "
              + issuer
              + (http
                  ? " is plain http. create never allows it: use PlatformTokens.forEnvironment(config.environment(), ...), which allows it only when APP_ENV is local or test, or createAllowingPlainHttp"
                  : " must be an absolute https URL"));
    }
    return new PlatformTokens(new Platform(issuer, timeout, clock, allowPlainHttp), clock);
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
