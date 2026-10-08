package io.flowcatalyst.framework.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request failed validation: 400, {@code VALIDATION}, message {@code request is invalid},
 * {@code details} mapping each failed field path to {@code "<code>: <message>"} (the first error
 * for the field).
 */
public final class ValidationException extends HttpException {

  private static final long serialVersionUID = 1L;

  private final transient List<FieldError> errors;

  /**
   * Creates the exception.
   *
   * @param errors every error found, not empty
   */
  public ValidationException(List<FieldError> errors) {
    super(400, "VALIDATION", "request is invalid", details(errors));
    this.errors = List.copyOf(errors);
  }

  /**
   * An exception with one error.
   *
   * @param field the field or parameter name
   * @param code the code
   * @param message the message; must not contain the offending value
   * @return the exception
   */
  public static ValidationException of(String field, String code, String message) {
    return new ValidationException(List.of(new FieldError(field, code, message)));
  }

  /**
   * Every error found.
   *
   * @return the errors in the order found
   */
  public List<FieldError> errors() {
    return errors;
  }

  private static Map<String, String> details(List<FieldError> errors) {
    Map<String, String> details = new LinkedHashMap<>();
    for (FieldError e : errors) {
      details.putIfAbsent(e.field(), e.code() + ": " + e.message());
    }
    return details;
  }
}
