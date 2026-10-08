package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.pipeline.Middleware;
import java.util.ArrayList;
import java.util.List;

/** Per-route settings: whether a context is required, and route middleware. Immutable. */
public final class RouteOptions {

  private static final RouteOptions NONE = new RouteOptions(false, List.of());

  private final boolean requiresContext;
  private final List<Middleware<Request, Response>> middleware;

  private RouteOptions(boolean requiresContext, List<Middleware<Request, Response>> middleware) {
    this.requiresContext = requiresContext;
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
    return new RouteOptions(true, List.of());
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
    return new RouteOptions(requiresContext, List.copyOf(list));
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
