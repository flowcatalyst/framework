package io.flowcatalyst.framework.auth;

/** What {@link PlatformTokens#verify} found: valid, invalid, or not checkable right now. */
public sealed interface TokenResult {

  /**
   * A platform access token that passed every check.
   *
   * @param token what it says
   */
  record Valid(AccessToken token) implements TokenResult {}

  /**
   * Not to be trusted: answer {@code 401}.
   *
   * @param reason the contract's reason name (section 5.5), safe to log, never the token
   */
  record Invalid(String reason) implements TokenResult {}

  /**
   * The platform's keys cannot be read, so the token cannot be checked: answer {@code 503}, not
   * {@code 401}.
   *
   * @param reason what failed, safe to log
   */
  record Unavailable(String reason) implements TokenResult {}
}
