package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.PrincipalId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who signed in or presented a token, as the platform described them.
 *
 * @param id the principal id ({@code sub}), for example {@code prn_0HZXEQ5Y8JY5Z}
 * @param kind a person or a service account ({@code type})
 * @param name the display name, or null
 * @param email the email address, or null
 * @param tier {@code ANCHOR}, {@code PARTNER} or {@code CLIENT}, or null when the token had none
 * @param homeClient the platform id of the principal's own client ({@code client_id}), or null
 * @param clients the clients it may act for: {@code *}, or {@code <id>:<identifier>} or {@code
 *     <id>} entries
 * @param applications {@code *}, or {@code <id>:<code>} entries
 * @param roles role names, {@code <application>:<role>}
 * @param scopes the permissions a bearer access token carries ({@code scope}); empty for a
 *     browser session, whose id_token carries roles, not permissions
 */
public record Principal(
    PrincipalId id,
    PrincipalKind kind,
    @Nullable String name,
    @Nullable String email,
    @Nullable String tier,
    @Nullable String homeClient,
    List<String> clients,
    List<String> applications,
    List<String> roles,
    Set<String> scopes) {

  /** Copies the collections. */
  public Principal {
    clients = List.copyOf(clients);
    applications = List.copyOf(applications);
    roles = List.copyOf(roles);
    scopes = Set.copyOf(scopes);
  }

  /**
   * Whether the principal may act for a client: a {@code clients} entry that is {@code *}, equals
   * the value, starts with {@code <value>:} (the value is the platform id) or ends with {@code
   * :<value>} (the value is the client's identifier, which the framework's {@link ClientId}
   * holds).
   *
   * @param client the platform id or the identifier of the client
   * @return true when allowed
   */
  public boolean mayActFor(String client) {
    if (client.isEmpty()) {
      return false;
    }
    for (String entry : clients) {
      if (entry.equals("*") || entry.equals(client) || entry.startsWith(client + ":")) {
        return true;
      }
      int colon = entry.indexOf(':');
      if (colon >= 0 && entry.substring(colon + 1).equals(client)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the principal may act for a client.
   *
   * @param client the client
   * @return true when allowed
   * @see #mayActFor(String)
   */
  public boolean mayActFor(ClientId client) {
    return mayActFor(client.value());
  }

  /**
   * The identifier of the home client, from the {@code clients} entry {@code <homeClient>:<identifier>}.
   *
   * @return the identifier, or empty when there is no home client or the token did not name its
   *     identifier
   */
  public Optional<String> homeClientIdentifier() {
    String home = homeClient;
    if (home == null) {
      return Optional.empty();
    }
    for (String entry : clients) {
      if (entry.startsWith(home + ":") && entry.length() > home.length() + 1) {
        return Optional.of(entry.substring(home.length() + 1));
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the principal holds a role.
   *
   * @param role the full name, {@code <application>:<role>}
   * @return true when held
   */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * Whether a bearer token carried a permission, by exact name.
   *
   * @param scope the permission
   * @return true when carried
   */
  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }
}
