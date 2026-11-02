package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.pipeline.Middleware;
import java.util.ArrayList;
import java.util.List;

/** Per-route settings: whether a context is required, whether form bodies are accepted, and route middleware. Immutable. */
public final class RouteOptions {

  private static final RouteOptions NONE = new RouteOptions(false, false, List.of());

  private final boolean requiresContext;
  private final boolean acceptsForms;
  private final List<Middleware<Request, Response>> middleware;

  private RouteOptions(
      boolean requiresContext, boolean acceptsForms, List<Middleware<Request, Response>> middleware) {
    this.requiresContext = requiresContext;
    this.acceptsForms = acceptsForms;
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
    return new RouteOptions(true, false, List.of());
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
    return new RouteOptions(requiresContext, true, middleware);
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
    return new RouteOptions(requiresContext, acceptsForms, List.copyOf(list));
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
