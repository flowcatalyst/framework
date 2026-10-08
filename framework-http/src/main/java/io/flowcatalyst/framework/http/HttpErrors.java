package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.usecase.UseCaseError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the platform's error envelope: {@code {"error": CODE, "message": "...", "details": {}}},
 * with {@code details} omitted when empty.
 */
public final class HttpErrors {

  private HttpErrors() {}

  /**
   * The response for a use case failure: status from the error's kind, {@code error} its code.
   *
   * @param error the failure
   * @return the envelope response
   */
  public static Response of(UseCaseError error) {
    return error(error.httpStatus(), error.code(), error.message(), error.details());
  }

  /**
   * An envelope response.
   *
   * @param status the HTTP status
   * @param code the error code
   * @param message the message, safe to show a caller
   * @param details extra string values; omitted from the body when empty
   * @return the response
   */
  public static Response error(int status, String code, String message, Map<String, String> details) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    if (!details.isEmpty()) {
      body.put("details", details);
    }
    return Response.json(status, body);
  }

  /**
   * An envelope response without details.
   *
   * @param status the HTTP status
   * @param code the error code
   * @param message the message
   * @return the response
   */
  public static Response error(int status, String code, String message) {
    return error(status, code, message, Map.of());
  }

  /**
   * A 400 {@code VALIDATION} response.
   *
   * @param message the message
   * @return the response
   */
  public static Response validation(String message) {
    return error(400, "VALIDATION", message);
  }

  /**
   * A 401 {@code UNAUTHENTICATED} response.
   *
   * @param message the message
   * @return the response
   */
  public static Response unauthenticated(String message) {
    return error(401, "UNAUTHENTICATED", message);
  }

  /**
   * A 403 response.
   *
   * @param code the error code
   * @param message the message
   * @return the response
   */
  public static Response forbidden(String code, String message) {
    return error(403, code, message);
  }

  /**
   * A 404 {@code NOT_FOUND} response.
   *
   * @param message the message
   * @return the response
   */
  public static Response notFound(String message) {
    return error(404, "NOT_FOUND", message);
  }
}
