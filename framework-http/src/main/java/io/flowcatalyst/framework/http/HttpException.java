package io.flowcatalyst.framework.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown by handlers (or a {@link ContextResolver}) to answer with the error envelope. The
 * error-mapping middleware turns it into a response.
 */
public class HttpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final transient Map<String, String> details;

  /**
   * Creates the exception.
   *
   * @param status the HTTP status
   * @param code the {@code error} value of the envelope
   * @param message the {@code message} value; safe to show a caller
   * @param details the {@code details} value; keeps its order; omitted from the envelope if empty
   */
  public HttpException(int status, String code, String message, Map<String, String> details) {
    super(message, null, false, false);
    this.status = status;
    this.code = code;
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  /**
   * Creates the exception without details.
   *
   * @param status the HTTP status
   * @param code the {@code error} value of the envelope
   * @param message the {@code message} value; safe to show a caller
   */
  public HttpException(int status, String code, String message) {
    this(status, code, message, Map.of());
  }

  /**
   * The HTTP status.
   *
   * @return the status
   */
  public int status() {
    return status;
  }

  /**
   * The envelope's error code.
   *
   * @return the code
   */
  public String code() {
    return code;
  }

  /**
   * The envelope's details.
   *
   * @return the details, possibly empty
   */
  public Map<String, String> details() {
    return details;
  }

  /**
   * The envelope response for this exception.
   *
   * @return the response
   */
  public Response toResponse() {
    return HttpErrors.error(status, code, getMessage() == null ? "" : getMessage(), details);
  }
}
