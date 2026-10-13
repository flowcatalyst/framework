package io.flowcatalyst.framework.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a principal into the permissions its {@code Context} carries (contract section 7.3).
 * Behaviour in code: the platform's id_token carries roles, not permissions, and only the
 * application knows what its roles mean.
 *
 * <pre>{@code
 * PermissionMapper.scopes().and(PermissionMapper.byRole(Map.of(
 *     "ingestor:operator", Set.of("ingestor:ingestion:view", "ingestor:ingestion:manage"),
 *     "ingestor:viewer", Set.of("ingestor:ingestion:view"))))
 * }</pre>
 */
@FunctionalInterface
public interface PermissionMapper {

  /**
   * The permissions of a principal. Called on every authenticated request, so a change of mapping
   * takes effect at once.
   *
   * @param principal the principal
   * @return the permissions, possibly empty
   */
  Set<String> permissions(Principal principal);

  /**
   * The default: the {@code scope} of a bearer access token. A browser session gets none.
   *
   * @return the mapper
   */
  static PermissionMapper scopes() {
    return Principal::scopes;
  }

  /**
   * Nothing granted.
   *
   * @return the mapper
   */
  static PermissionMapper none() {
    return principal -> Set.of();
  }

  /**
   * A table from role name ({@code <application>:<role>}, as the platform sends it) to
   * permissions.
   *
   * @param table the table; copied
   * @return the mapper
   */
  static PermissionMapper byRole(Map<String, ? extends Collection<String>> table) {
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    table.forEach((role, permissions) -> copy.put(role, Set.copyOf(permissions)));
    Map<String, Set<String>> frozen = Map.copyOf(copy);
    return principal -> {
      Set<String> out = new LinkedHashSet<>();
      for (String role : principal.roles()) {
        Set<String> granted = frozen.get(role);
        if (granted != null) {
          out.addAll(granted);
        }
      }
      return out;
    };
  }

  /**
   * The union with another mapping.
   *
   * @param other the other mapping
   * @return the mapper
   */
  default PermissionMapper and(PermissionMapper other) {
    PermissionMapper self = this;
    return principal -> {
      Set<String> out = new LinkedHashSet<>(self.permissions(principal));
      out.addAll(other.permissions(principal));
      return out;
    };
  }
}
