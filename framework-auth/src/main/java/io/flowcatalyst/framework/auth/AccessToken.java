package io.flowcatalyst.framework.auth;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A verified platform access token ({@code token_use=api}).
 *
 * @param principal who it is for, with the token's {@code scope} as {@link Principal#scopes()}
 * @param authorizedParty the OAuth client it was issued to ({@code azp}), or null
 * @param expiresAt its {@code exp}
 */
public record AccessToken(Principal principal, @Nullable String authorizedParty, Instant expiresAt) {

  /**
   * Whether the token carries a permission, by exact name.
   *
   * @param scope the permission
   * @return true when carried
   */
  public boolean hasScope(String scope) {
    return principal.hasScope(scope);
  }

  /**
   * Whether the principal may act for a client.
   *
   * @param client the client's platform id or identifier
   * @return true when allowed
   * @see Principal#mayActFor(String)
   */
  public boolean mayActFor(String client) {
    return principal.mayActFor(client);
  }
}
