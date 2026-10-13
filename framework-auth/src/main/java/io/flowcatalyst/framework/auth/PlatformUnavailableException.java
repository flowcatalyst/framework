package io.flowcatalyst.framework.auth;

/** The platform's discovery document, keys or token endpoint could not be read. */
final class PlatformUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  PlatformUnavailableException(String message) {
    super(message);
  }

  PlatformUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
