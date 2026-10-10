package io.flowcatalyst.framework.core;

import java.util.Map;

/**
 * An expected failure that is safe to show a caller: an HTTP-style status, an {@code UPPER_SNAKE}
 * code, a message and string details.
 *
 * <p>This is the shape the HTTP layer turns into the platform's error envelope
 * ({@code HttpErrors.of(ServiceError)}). It lives in core so that the HTTP modules need not depend
 * on the write path: a use case's {@code UseCaseError} implements it, and so can any other module's
 * error value. Implementations never put the text of an internal cause in {@link #message()}.
 */
public interface ServiceError {

  /**
   * The HTTP status this failure maps to.
   *
   * @return a status code, 400 to 599
   */
  int httpStatus();

  /**
   * The machine-readable code.
   *
   * @return an {@code UPPER_SNAKE} code
   */
  String code();

  /**
   * The message for the caller.
   *
   * @return text safe to show a caller
   */
  String message();

  /**
   * Extra string values, for example the violated constraint.
   *
   * @return the details, possibly empty
   */
  Map<String, String> details();
}
