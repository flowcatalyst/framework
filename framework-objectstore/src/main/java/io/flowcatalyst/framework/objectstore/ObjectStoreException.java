package io.flowcatalyst.framework.objectstore;

/** The store could not be read or written (disk, network, permissions), or a value is too large to read. */
public class ObjectStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   */
  public ObjectStoreException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the underlying failure
   */
  public ObjectStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
