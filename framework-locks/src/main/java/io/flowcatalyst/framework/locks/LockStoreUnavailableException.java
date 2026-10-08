package io.flowcatalyst.framework.locks;

/**
 * The store could not be reached or answered with an error. Not being able to get the lock is
 * never this exception; that is {@link AcquireResult.Busy}.
 */
public final class LockStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the underlying failure
   */
  public LockStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
