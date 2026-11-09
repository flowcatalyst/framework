package io.flowcatalyst.framework.push;

/** The log could not be read or written (the database or Redis failed). */
public final class LogException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause why
   */
  public LogException(String message, Throwable cause) {
    super(message, cause);
  }
}
