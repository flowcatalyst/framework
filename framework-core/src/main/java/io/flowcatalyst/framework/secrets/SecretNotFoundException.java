package io.flowcatalyst.framework.secrets;

/** {@link SecretResolver#require} found no secret, or the store refused the lookup. */
public final class SecretNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the store, client and secret name
   */
  public SecretNotFoundException(String message) {
    super(message);
  }
}
