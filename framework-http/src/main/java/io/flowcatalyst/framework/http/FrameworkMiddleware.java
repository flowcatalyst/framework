package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.pipeline.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The three middleware the framework installs, outermost first, not removable. */
final class FrameworkMiddleware {

  private static final Logger ACCESS = LoggerFactory.getLogger("io.flowcatalyst.framework.http.access");
  private static final Logger LOG = LoggerFactory.getLogger(FrameworkMiddleware.class);

  static final String CORRELATION_HEADER = "X-Correlation-Id";

  private FrameworkMiddleware() {}

  /** Echoes the request's correlation id on every response. */
  static Middleware<Request, Response> correlationId() {
    return (request, next) ->
        next.handle(request).header(CORRELATION_HEADER, request.correlationId().value());
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

  /** Logs method, path template, status, duration and correlation id. Never query or body. */
  static Middleware<Request, Response> accessLog(MonotonicClock clock) {
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
        ACCESS.info(
            "{} {} {} {}ms cid={}",
            request.method(),
            template == null ? "<unmatched>" : template,
            status,
            (clock.nanos() - start) / 1_000_000,
            request.correlationId());
      }
    };
  }
}
