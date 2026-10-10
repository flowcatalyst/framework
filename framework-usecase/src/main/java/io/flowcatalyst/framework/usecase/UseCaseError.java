package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.core.ServiceError;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Why a use case did not commit.
 *
 * @param kind the kind, which fixes the HTTP status
 * @param code an {@code UPPER_SNAKE} code
 * @param message safe to show a caller; never carries the text of an internal cause
 * @param details extra string values, for example the violated constraint
 */
public record UseCaseError(ErrorKind kind, String code, String message, Map<String, String> details)
    implements ServiceError {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

  /** Validates the code and copies the details. */
  public UseCaseError {
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("error code must be UPPER_SNAKE: " + code);
    }
    details = Map.copyOf(details);
  }

  /**
   * The HTTP status of this error's kind.
   *
   * @return the status code
   */
  @Override
  public int httpStatus() {
    return kind.httpStatus();
  }

  /**
   * A copy with details.
   *
   * @param extra the details
   * @return the new error
   */
  public UseCaseError withDetails(Map<String, String> extra) {
    return new UseCaseError(kind, code, message, extra);
  }

  /**
   * A {@link ErrorKind#VALIDATION} error.
   *
   * @param code the code
   * @param message the message for the caller
   * @return the error
   */
  public static UseCaseError validation(String code, String message) {
    return new UseCaseError(ErrorKind.VALIDATION, code, message, Map.of());
  }

  /**
   * An {@link ErrorKind#AUTHORIZATION} error.
   *
   * @param code the code
   * @param message the message for the caller
   * @return the error
   */
  public static UseCaseError authorization(String code, String message) {
    return new UseCaseError(ErrorKind.AUTHORIZATION, code, message, Map.of());
  }

  /**
   * A {@link ErrorKind#NOT_FOUND} error.
   *
   * @param code the code
   * @param message the message for the caller
   * @return the error
   */
  public static UseCaseError notFound(String code, String message) {
    return new UseCaseError(ErrorKind.NOT_FOUND, code, message, Map.of());
  }

  /**
   * A {@link ErrorKind#CONFLICT} error.
   *
   * @param code the code
   * @param message the message for the caller
   * @return the error
   */
  public static UseCaseError conflict(String code, String message) {
    return new UseCaseError(ErrorKind.CONFLICT, code, message, Map.of());
  }

  /**
   * A {@link ErrorKind#BUSINESS_RULE} error.
   *
   * @param code the code
   * @param message the message for the caller
   * @return the error
   */
  public static UseCaseError businessRule(String code, String message) {
    return new UseCaseError(ErrorKind.BUSINESS_RULE, code, message, Map.of());
  }

  /**
   * An {@link ErrorKind#INTERNAL} error. The message is fixed text naming the step; pass the
   * cause to the logger, never into this error.
   *
   * @param code the code, usually the name of the step that failed
   * @return the error
   */
  public static UseCaseError internal(String code) {
    return new UseCaseError(
        ErrorKind.INTERNAL, code, "The operation could not be completed (" + code + ").", Map.of());
  }
}
