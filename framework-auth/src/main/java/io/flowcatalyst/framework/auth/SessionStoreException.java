package io.flowcatalyst.framework.auth;

/** A session store could not do what was asked; answered as {@code 500}, never as "no session". */
public final class SessionStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed; never contains a handle or a record
   * @param cause the cause
   */
  public SessionStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed; never contains a handle or a record
   */
  public SessionStoreException(String message) {
    super(message);
  }
}
