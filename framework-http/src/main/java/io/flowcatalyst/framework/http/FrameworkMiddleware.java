package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.Timer;
import io.flowcatalyst.framework.pipeline.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The three middleware the framework installs, outermost first, not removable. */
final class FrameworkMiddleware {

  private static final Logger ACCESS = LoggerFactory.getLogger("io.flowcatalyst.framework.http.access");
  private static final Logger LOG = LoggerFactory.getLogger(FrameworkMiddleware.class);

  static final String CORRELATION_HEADER = "X-Correlation-Id";
  static final String NOSNIFF_HEADER = "X-Content-Type-Options";

  private FrameworkMiddleware() {}

  /**
   * Echoes the request's correlation id on every response, and marks every response
   * {@code X-Content-Type-Options: nosniff} (static-assets contract section 6.1).
   */
  static Middleware<Request, Response> correlationId() {
    return (request, next) ->
        next.handle(request)
            .header(CORRELATION_HEADER, request.correlationId().value())
            .header(NOSNIFF_HEADER, "nosniff");
  }

  /** Turns exceptions into the error envelope. */
  static Middleware<Request, Response> errorMapping() {
    return (request, next) -> {
      try {
        return next.handle(request);
      } catch (HttpException e) {
        return e.toResponse();
      } catch (ClientDisconnectedException e) {
        LOG.debug("client disconnected (correlation id {})", request.correlationId());
        return Response.noContent();
      } catch (Exception e) {
        LOG.error("unhandled exception (correlation id {})", request.correlationId(), e);
        return HttpErrors.error(500, "INTERNAL", "internal error");
      }
    };
  }

  /**
   * Logs method, path template, status, duration and correlation id, and records the request
   * metrics with the same labels. Never query or body; the route label is the matched pattern or
   * {@code <unmatched>}, so request paths never become label values.
   */
  static Middleware<Request, Response> accessLog(MonotonicClock clock, Metrics metrics) {
    Counter requests =
        metrics.counter(
            "fw_http_server_requests_total",
            "HTTP requests by method, route pattern and status.",
            "method",
            "route",
            "status");
    Timer durations =
        metrics.timer(
            "fw_http_server_request_duration_seconds",
            "HTTP request duration by method and route pattern.",
            "method",
            "route");
    return (request, next) -> {
      long start = clock.nanos();
      int status = 500;
      try {
        Response response = next.handle(request);
        status = response.status();
        return response;
      } catch (HttpException e) {
        status = e.status();
        throw e;
      } finally {
        String template = request.routePattern();
        String route = template == null ? "<unmatched>" : template;
        long took = clock.nanos() - start;
        String method = request.method().name();
        requests.labels(method, route, Integer.toString(status)).inc();
        durations.labels(method, route).recordNanos(took);
        ACCESS.info(
            "{} {} {} {}ms cid={}",
            request.method(),
            route,
            status,
            took / 1_000_000,
            request.correlationId());
      }
    };
  }
}
