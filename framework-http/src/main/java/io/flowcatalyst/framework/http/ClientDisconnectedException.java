package io.flowcatalyst.framework.http;

/**
 * The client closed the connection while a response was being written. Handlers may let it
 * propagate: it is logged at DEBUG, not as an error, and produces no response body.
 */
public final class ClientDisconnectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param cause the write failure
   */
  public ClientDisconnectedException(Throwable cause) {
    super("client disconnected", cause);
  }
}
