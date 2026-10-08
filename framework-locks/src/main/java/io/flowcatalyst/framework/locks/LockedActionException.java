package io.flowcatalyst.framework.locks;

/** A checked exception thrown by a locked action, carried to the caller of {@code withLock}. */
public final class LockedActionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param cause the exception the action threw
   */
  public LockedActionException(Exception cause) {
    super(cause);
  }
}
