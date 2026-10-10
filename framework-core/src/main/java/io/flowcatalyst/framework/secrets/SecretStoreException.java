package io.flowcatalyst.framework.secrets;

/**
 * A secret store could not be read: an I/O error, a permission problem, a file that is too large
 * or not UTF-8. The message names the store, client and secret name, never a value or content.
 */
public final class SecretStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed, without any value
   * @param cause the underlying failure, or null
   */
  public SecretStoreException(String message, @org.jspecify.annotations.Nullable Throwable cause) {
    super(message, cause);
  }
}
