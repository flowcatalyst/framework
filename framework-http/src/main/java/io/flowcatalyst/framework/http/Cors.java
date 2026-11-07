package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.pipeline.Middleware;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The CORS middleware of a {@link CorsPolicy}. The dispatcher runs it just inside the correlation
 * id, outside the error mapping, so error answers and {@code 404}s carry the headers too and a page
 * can read them.
 */
final class Cors {

  private Cors() {}

  static Middleware<Request, Response> middleware(CorsPolicy policy) {
    String methods = policy.allowedMethods().stream().map(Method::name).collect(Collectors.joining(", "));
    String headers = String.join(", ", policy.allowedHeaders());
    String exposed = String.join(", ", policy.exposedHeaders());
    String maxAge = Long.toString(policy.maxAge().toSeconds());
    return (request, next) -> {
      String origin = request.header("Origin");
      if (origin == null) {
        return next.handle(request);
      }
      boolean allowed = policy.allowsOrigin(origin);
      String requestedMethod = request.header("Access-Control-Request-Method");
      if (request.method() == Method.OPTIONS && requestedMethod != null) {
        Response vary =
            Response.empty(204)
                .addHeader("Vary", "Origin")
                .addHeader("Vary", "Access-Control-Request-Method")
                .addHeader("Vary", "Access-Control-Request-Headers");
        if (!allowed || !preflightAllowed(policy, requestedMethod, request.header("Access-Control-Request-Headers"))) {
          return HttpErrors.error(403, "CORS_REFUSED", "this cross-origin request is not allowed")
              .addHeader("Vary", "Origin");
        }
        Response answer =
            allowOrigin(vary, policy, origin)
                .header("Access-Control-Allow-Methods", methods)
                .header("Access-Control-Allow-Headers", headers)
                .header("Access-Control-Max-Age", maxAge);
        return answer;
      }
      Response response = next.handle(request).addHeader("Vary", "Origin");
      if (!allowed) {
        return response;
      }
      response = allowOrigin(response, policy, origin);
      return exposed.isEmpty() ? response : response.header("Access-Control-Expose-Headers", exposed);
    };
  }

  private static Response allowOrigin(Response response, CorsPolicy policy, String origin) {
    boolean any = policy.allowedOrigins().contains("*");
    // never Access-Control-Allow-Credentials: the session cookie is never usable across origins
    return response.header("Access-Control-Allow-Origin", any ? "*" : origin);
  }

  private static boolean preflightAllowed(
      CorsPolicy policy, String method, @Nullable String requestedHeaders) {
    Method m = Method.parse(method.trim());
    if (m == null || !policy.allowedMethods().contains(m)) {
      return false;
    }
    if (requestedHeaders == null || requestedHeaders.isBlank()) {
      return true;
    }
    for (String h : requestedHeaders.split(",")) {
      if (!h.isBlank() && !policy.allowsHeader(h)) {
        return false;
      }
    }
    return true;
  }
}
