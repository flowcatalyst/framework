package io.flowcatalyst.framework.http.ratelimit;

/** A limiter's store could not answer (for example Redis is down). */
public final class RateLimitStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the store's failure
   */
  public RateLimitStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
