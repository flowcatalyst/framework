package io.flowcatalyst.framework.flowcatalyst;

/** A call to the FlowCatalyst platform failed, or the platform client is not available. */
public final class PlatformException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of failure it was, for callers that answer differently (the admin pages). */
  public enum Kind {
    /** The platform refused the application's service account (HTTP 403). */
    FORBIDDEN,
    /** The platform has no such record (HTTP 404). */
    NOT_FOUND,
    /** Anything else: unreachable, a timeout, a server error, a refused credential. */
    OTHER
  }

  private final Kind kind;

  /**
   * Creates the exception with its kind.
   *
   * @param kind what kind of failure
   * @param message what failed
   * @param cause the underlying failure
   */
  public PlatformException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  /**
   * What kind of failure it was.
   *
   * @return the kind; {@code OTHER} when not known
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the underlying failure
   */
  public PlatformException(String message, Throwable cause) {
    super(message, cause);
    this.kind = Kind.OTHER;
  }

  /**
   * Creates the exception without an underlying failure.
   *
   * @param message what failed
   */
  public PlatformException(String message) {
    super(message);
    this.kind = Kind.OTHER;
  }
}
