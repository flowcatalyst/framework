package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.Nullable;

/** A call to the FlowCatalyst platform failed, or the platform client is not available. */
public final class PlatformException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What kind of failure it was, for callers that answer differently (the admin pages). */
  public enum Kind {
    /** The platform refused the application's service account (HTTP 403). */
    FORBIDDEN,
    /** The platform has no such record (HTTP 404). */
    NOT_FOUND,
    /**
     * The platform understood the request and said no, with a code and a message meant for the
     * person who made it (HTTP 400, 409 or 422, or a 403 that names a reason of its own such as
     * {@code ROLE_APP_FORBIDDEN}). {@link #code()}, {@link #serverMessage()} and {@link #status()}
     * carry what it said.
     */
    REJECTED,
    /** Anything else: unreachable, a timeout, a server error, a refused credential. */
    OTHER
  }

  private final Kind kind;
  private final int status;
  private final @Nullable String code;
  private final @Nullable String serverMessage;

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
    this.status = 0;
    this.code = null;
    this.serverMessage = null;
  }

  /**
   * Creates the exception for an HTTP answer of the platform.
   *
   * @param kind what kind of failure
   * @param status the HTTP status the platform answered
   * @param code the platform's error code ({@code error} in its envelope), or null
   * @param serverMessage the platform's message for the person, cleaned and bounded, or null
   */
  PlatformException(Kind kind, int status, @Nullable String code, @Nullable String serverMessage) {
    super("the platform answered " + status + (code == null ? "" : " " + code));
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.serverMessage = serverMessage;
  }

  /**
   * The HTTP status the platform answered.
   *
   * @return the status, or 0 when there was no answer or the call went through the SDK
   */
  public int status() {
    return status;
  }

  /**
   * The platform's error code, such as {@code EMAIL_EXISTS}.
   *
   * @return the code, or null when it sent none
   */
  public @Nullable String code() {
    return code;
  }

  /**
   * The platform's message for the person who made the request (cleaned of control characters,
   * at most 300 characters). Never contains a request body or a credential.
   *
   * @return the message, or null when it sent none
   */
  public @Nullable String serverMessage() {
    return serverMessage;
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
    this.status = 0;
    this.code = null;
    this.serverMessage = null;
  }

  /**
   * Creates the exception without an underlying failure.
   *
   * @param message what failed
   */
  public PlatformException(String message) {
    super(message);
    this.kind = Kind.OTHER;
    this.status = 0;
    this.code = null;
    this.serverMessage = null;
  }
}
