package io.flowcatalyst.framework.auth;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The session answer (component {@code AuthSession}, contract sections 4.3 and 4.5): a {@code oneOf}
 * of the two variants, told apart by {@code authenticated}, which each fixes with {@code const}.
 */
sealed interface AuthSession {

  /**
   * A signed-in browser.
   *
   * @param authenticated always true
   * @param principal who signed in
   * @param tier the principal's tier, absent when the token had none
   * @param client the client the {@code Context} would carry
   * @param clients the principal's clients
   * @param applications the principal's applications
   * @param roles the principal's roles
   * @param permissions the permissions the application's mapping gives, sorted
   * @param authTime when the principal signed in at the platform
   * @param expiresAt the absolute expiry
   * @param idleExpiresAt the idle expiry as of this call
   * @param csrf what unsafe calls need
   */
  record Authenticated(
      boolean authenticated,
      AuthPrincipal principal,
      @Nullable AuthTier tier,
      String client,
      List<String> clients,
      List<String> applications,
      List<String> roles,
      List<String> permissions,
      Instant authTime,
      Instant expiresAt,
      Instant idleExpiresAt,
      AuthCsrf csrf)
      implements AuthSession {}

  /**
   * No session.
   *
   * @param authenticated always false
   * @param loginUrl where to start signing in
   */
  record Anonymous(boolean authenticated, String loginUrl) implements AuthSession {}
}
