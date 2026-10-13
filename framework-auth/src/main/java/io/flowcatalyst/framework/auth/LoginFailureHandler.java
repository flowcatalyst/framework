package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Answers a failed sign-in. The default is the JSON error envelope; the framework renders no pages.
 */
@FunctionalInterface
public interface LoginFailureHandler {

  /**
   * The answer to send.
   *
   * @param failure what went wrong
   * @param request the callback request
   * @return the response; the login binding cookie is cleared on it by the framework
   */
  Response handle(LoginFailure failure, Request request);

  /**
   * The default: the error envelope with the failure's status, code and details.
   *
   * @return the handler
   */
  static LoginFailureHandler json() {
    return (failure, request) -> HttpErrors.error(failure.status(), failure.code(), failure.message(), failure.details());
  }

  /**
   * A {@code 302} to an application page with {@code ?error=<CODE>} (or {@code &error=} when the
   * path has a query).
   *
   * @param path a same-origin path, for example {@code /signed-out}
   * @return the handler
   * @throws IllegalArgumentException when the path is not a same-origin path
   */
  static LoginFailureHandler redirectTo(String path) {
    if (!ReturnToPolicy.isSameOriginPath(path)) {
      throw new IllegalArgumentException("a login failure page must be a same-origin path: " + path);
    }
    String separator = path.contains("?") ? "&" : "?";
    return (failure, request) ->
        Response.redirect(path + separator + "error=" + URLEncoder.encode(failure.code(), StandardCharsets.UTF_8));
  }
}
