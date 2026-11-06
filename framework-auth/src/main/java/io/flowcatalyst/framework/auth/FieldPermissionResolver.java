package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.Request;
import java.util.Collection;

/**
 * Finds a field worker's permissions where the application holds them: its database, a cache, a
 * role table (contract section 15.5). The sibling of {@link FieldPermissions} for an application
 * whose answer needs a lookup. Called on every authenticated field request, on the request's own
 * thread, so the {@link Request} (its headers, attributes and the application's request-scoped
 * state) is at hand.
 *
 * <p>An empty (or null) result grants nothing: a route that requires a permission refuses. A thrown
 * exception is not "no permissions": the request answers {@code 503 UNAVAILABLE}, so the device
 * retries instead of being told it may not. Throw only when the answer could not be found.
 *
 * <pre>{@code
 * FieldOptions.builder()
 *     .permissionResolver((request, worker) -> roles.permissionsOf(worker.workerId(), worker.clientIdentifier()))
 *     .build()
 * }</pre>
 */
@FunctionalInterface
public interface FieldPermissionResolver {

  /**
   * The permissions of a field worker.
   *
   * @param request the request being authenticated
   * @param worker the verified worker
   * @return the permissions; empty or null for none
   * @throws Exception when the permissions could not be found; the request answers 503
   */
  Collection<String> permissions(Request request, FieldPrincipal worker) throws Exception;

  /**
   * Adapts a {@link FieldPermissions} mapping, which never looks anything up.
   *
   * @param mapping the mapping
   * @return the resolver
   */
  static FieldPermissionResolver of(FieldPermissions mapping) {
    return (request, worker) -> mapping.permissions(worker);
  }
}
