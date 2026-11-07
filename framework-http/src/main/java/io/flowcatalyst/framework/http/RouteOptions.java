package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.pipeline.Middleware;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Per-route settings: whether a context is required, whether form bodies are accepted, the route's
 * own request body limit, and route middleware. Immutable.
 */
public final class RouteOptions {

  private static final RouteOptions NONE = new RouteOptions(false, false, -1, List.of());

  private final boolean requiresContext;
  private final boolean acceptsForms;
  private final long maxBodyBytes;
  private final List<Middleware<Request, Response>> middleware;

  private RouteOptions(
      boolean requiresContext,
      boolean acceptsForms,
      long maxBodyBytes,
      List<Middleware<Request, Response>> middleware) {
    this.requiresContext = requiresContext;
    this.acceptsForms = acceptsForms;
    this.maxBodyBytes = maxBodyBytes;
    this.middleware = middleware;
  }

  /**
   * No context, no route middleware.
   *
   * @return the default options
   */
  public static RouteOptions none() {
    return NONE;
  }

  /**
   * Options for a route whose handler needs {@link Request#context()}.
   *
   * @return options with a context required
   */
  public static RouteOptions requiresContext() {
    return new RouteOptions(true, false, -1, List.of());
  }

  /**
   * Options for a route that server-rendered pages post to: the sessions CSRF guard also accepts
   * {@code application/x-www-form-urlencoded} and {@code multipart/form-data} bodies on it
   * (sessions contract 7.2 rule 5, server-rendered UI contract section 8 rule 2). The provenance
   * rules still apply first; every other route keeps the JSON-only rule. For plain routes only: a
   * typed operation takes JSON.
   *
   * @return options that accept form bodies
   */
  public static RouteOptions acceptForms() {
    return NONE.acceptingForms();
  }

  /**
   * These options, also declaring the route a form route (see {@link #acceptForms()}).
   *
   * @return a copy that accepts form bodies
   */
  public RouteOptions acceptingForms() {
    return new RouteOptions(requiresContext, true, maxBodyBytes, middleware);
  }

  /**
   * Whether the route is a form route.
   *
   * @return true when the sessions CSRF guard accepts form bodies on it
   */
  public boolean acceptsForms() {
    return acceptsForms;
  }

  /**
   * Adds route middleware, inside the global middleware and the context resolution; the first
   * added is outermost.
   *
   * @param m the middleware
   * @return a copy with the middleware added
   */
  public RouteOptions with(Middleware<Request, Response> m) {
    List<Middleware<Request, Response>> list = new ArrayList<>(middleware);
    list.add(m);
    return new RouteOptions(requiresContext, acceptsForms, maxBodyBytes, List.copyOf(list));
  }

  /**
   * These options with the route's own request body limit, in place of {@code http.max-body-bytes}
   * (larger, for an upload such as a proof photo, or smaller). It applies once the route is matched:
   * a declared {@code Content-Length} over it is {@code 413} before the handler runs, and reading
   * past it is {@code 413}. Global middleware, which runs before routing, reads under the global
   * limit. Works for typed operations too ({@code Routes.bind(op, handler, options)}).
   *
   * @param bytes the limit, not negative
   * @return a copy with the limit
   * @throws IllegalArgumentException when negative
   */
  public RouteOptions maxBodyBytes(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("a body limit must not be negative");
    }
    return new RouteOptions(requiresContext, acceptsForms, bytes, middleware);
  }

  /**
   * The route's own body limit.
   *
   * @return the limit, or empty when the route uses {@code http.max-body-bytes}
   */
  public OptionalLong maxBodyBytes() {
    return maxBodyBytes < 0 ? OptionalLong.empty() : OptionalLong.of(maxBodyBytes);
  }

  /**
   * Whether the route needs a context.
   *
   * @return true when a {@link ContextResolver} must populate {@link Request#context()}
   */
  public boolean needsContext() {
    return requiresContext;
  }

  /**
   * The route middleware.
   *
   * @return outermost first
   */
  public List<Middleware<Request, Response>> middleware() {
    return middleware;
  }
}
