package io.flowcatalyst.framework.flowcatalyst;

/** A call to the FlowCatalyst platform failed, or the platform client is not available. */
public final class PlatformException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the underlying failure
   */
  public PlatformException(String message, Throwable cause) {
    super(message, cause);
  }
}
