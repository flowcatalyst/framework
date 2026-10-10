package io.flowcatalyst.framework.httpclient;

import org.jspecify.annotations.Nullable;

/**
 * A convenience call ({@code sendJson}, {@code getJson}, {@code postJson}) got a response that is
 * not 2xx. Carries the status and, when the body is the platform's error envelope, its {@code
 * error} code; never the body itself.
 */
public final class UnexpectedStatusException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final @Nullable String errorCode;

  /**
   * Creates the exception.
   *
   * @param status the HTTP status
   * @param errorCode the envelope's {@code error} code, or null
   * @param description service, method and path template
   */
  public UnexpectedStatusException(int status, @Nullable String errorCode, String description) {
    super(description + " answered " + status + (errorCode == null ? "" : " " + errorCode));
    this.status = status;
    this.errorCode = errorCode;
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
   * The {@code error} code of the platform's error envelope, when the body was one.
   *
   * @return the code, or null
   */
  public @Nullable String errorCode() {
    return errorCode;
  }
}
