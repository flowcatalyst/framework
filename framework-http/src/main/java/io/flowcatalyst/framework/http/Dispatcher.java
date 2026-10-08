package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a request through the full pipeline: correlation id, error mapping, access log, global
 * middleware, routing, context resolution, route middleware, handler. Server adapters call
 * {@link #handle}; so does {@link TestClient}.
 */
public final class Dispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

  private final Router router;
  private final @Nullable ContextResolver resolver;
  private final List<String> routeLines;
  private final Pipeline<Request, Response> pipeline;

  Dispatcher(
      Router router,
      List<Middleware<Request, Response>> global,
      @Nullable ContextResolver resolver,
      MonotonicClock clock,
      List<String> routeLines) {
    this.router = router;
    this.resolver = resolver;
    this.routeLines = List.copyOf(routeLines);
    List<Middleware<Request, Response>> all = new ArrayList<>();
    all.add(FrameworkMiddleware.correlationId());
    all.add(FrameworkMiddleware.errorMapping());
    all.add(FrameworkMiddleware.accessLog(clock));
    all.addAll(global);
    this.pipeline = Pipeline.of(all, this::route);
  }

  /**
   * Handles a request. Never throws: every failure becomes a response.
   *
   * @param request the request
   * @return the response; for HEAD requests the body is already dropped
   */
  public Response handle(Request request) {
    Response response;
    try {
      response = pipeline.handle(request);
    } catch (Exception | Error e) {
      LOG.error("unhandled failure outside the error mapping (correlation id {})", request.correlationId(), e);
      response = HttpErrors.error(500, "INTERNAL", "internal error");
    }
    return request.method() == Method.HEAD ? response.withoutBody() : response;
  }

  /**
   * One line per route, for the start-up log.
   *
   * @return lines like {@code GET /orders/{id} (orders)}
   */
  public List<String> describeRoutes() {
    return routeLines;
  }

  private Response route(Request request) throws Exception {
    if (request.declaredBodyTooLarge()) {
      throw new HttpException(413, "PAYLOAD_TOO_LARGE", "request body is too large");
    }
    String path = request.path();
    if (!path.startsWith("/")) {
      throw new HttpException(404, "NOT_FOUND", "no route for this path");
    }
    List<String> segments = new ArrayList<>();
    for (String raw : path.substring(1).split("/", -1)) {
      segments.add(Percent.decode(raw, false));
    }
    switch (router.match(request.method(), segments)) {
      case Router.NotFound n -> throw new HttpException(404, "NOT_FOUND", "no route for this path");
      case Router.MethodNotAllowed m -> {
        return HttpErrors.error(405, "METHOD_NOT_ALLOWED", "method not allowed for this path")
            .header("Allow", allow(m.allowed()));
      }
      case Router.Options o -> {
        return Response.noContent().header("Allow", allow(o.allowed()));
      }
      case Router.Found f -> {
        request.bind(f.route().pattern().text(), f.params());
        if (f.route().requiresContext()) {
          ContextResolver r = resolver;
          if (r == null) {
            throw new IllegalStateException("route requires a context but there is no resolver");
          }
          Context ctx = r.resolve(request);
          if (ctx == null) {
            throw new IllegalStateException("the ContextResolver returned null");
          }
          request.setContext(ctx.withCorrelationId(request.correlationId()));
        }
        return f.route().pipeline().handle(request);
      }
    }
  }

  private static String allow(java.util.Set<Method> methods) {
    return methods.stream().map(Method::name).sorted().collect(Collectors.joining(", "));
  }
}
