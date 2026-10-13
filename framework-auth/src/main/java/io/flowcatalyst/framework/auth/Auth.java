package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.util.Optional;

/** Reading who a request is authenticated as, and requiring a permission on a route. */
public final class Auth {

  private Auth() {}

  /**
   * Who the request is authenticated as, once the context resolver ran (on a route declared with
   * {@code RouteOptions.requiresContext()}).
   *
   * @param request the request
   * @return the authenticated principal, or empty before resolution or on other routes (use {@link
   *     Sessions#current} there)
   */
  public static Optional<Authenticated> current(Request request) {
    return Optional.ofNullable(request.attribute(RequestState.AUTHENTICATED));
  }

  /**
   * Route middleware that answers {@code 403 FORBIDDEN} unless the context holds the permission
   * ({@code Context.hasPermission}). Use it on a route that requires a context.
   *
   * <pre>{@code
   * routes.post("/api/v1/ingestions", admin::create,
   *     RouteOptions.requiresContext().with(Auth.require("ingestor:ingestion:manage")));
   * }</pre>
   *
   * @param permission the permission needed
   * @return the middleware
   */
  public static Middleware<Request, Response> require(String permission) {
    if (permission.isBlank()) {
      throw new IllegalArgumentException("a required permission must not be blank");
    }
    return (request, next) ->
        request.context().hasPermission(permission)
            ? next.handle(request)
            : HttpErrors.forbidden("FORBIDDEN", "this needs the permission " + permission);
  }
}
