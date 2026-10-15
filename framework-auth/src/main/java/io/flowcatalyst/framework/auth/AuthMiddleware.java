package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Next;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Global middleware installed by {@link AuthModule}: gives each request its {@link RequestState},
 * writes the cookies the session code asked for (also on error answers), and turns a {@code 401}
 * into a {@code 302} to {@code /auth/login} for an HTML navigation (contract section 7.4).
 */
final class AuthMiddleware implements Middleware<Request, Response> {

  private final LoginFailureHandler failures;

  AuthMiddleware(LoginFailureHandler failures) {
    this.failures = failures;
  }

  @Override
  public Response handle(Request request, Next<Request, Response> next) throws Exception {
    RequestState state = RequestState.of(request);
    Response response;
    try {
      response = next.handle(request);
    } catch (HttpException e) {
      response = e.toResponse();
    }
    if (response.status() == 401 && isNavigation(request)) {
      response = Response.redirect(loginUrl(request)).header("Cache-Control", "no-store");
    }
    if (response.status() == 429 && isLoginRoute(request) && isNavigation(request)) {
      // the rate limiter answered a browser navigation to a sign-in route (contract 4.1 and section 8)
      LoginFailure failure =
          new LoginFailure(429, "RATE_LIMITED", "too many sign-in attempts; wait a minute and try again", Map.of());
      response = failures.handle(failure, request).header("Cache-Control", "no-store");
    }
    return state.apply(response);
  }

  /**
   * An HTML navigation: {@code GET} or {@code HEAD}, no {@code Authorization}, and {@code
   * Sec-Fetch-Mode: navigate} or, without that header, an {@code Accept} naming {@code text/html}.
   */
  static boolean isNavigation(Request request) {
    if (request.method() != Method.GET && request.method() != Method.HEAD) {
      return false;
    }
    if (request.header("Authorization") != null) {
      return false;
    }
    String mode = request.header("Sec-Fetch-Mode");
    if (mode != null) {
      return mode.trim().equalsIgnoreCase("navigate");
    }
    String accept = request.header("Accept");
    return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
  }

  private static boolean isLoginRoute(Request request) {
    String path = request.path();
    return path.equals("/auth/login") || path.equals("/auth/callback");
  }

  static String loginUrl(Request request) {
    String query = request.rawQuery();
    String target = request.path() + (query == null || query.isEmpty() ? "" : "?" + query);
    return "/auth/login?returnTo=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
  }
}
