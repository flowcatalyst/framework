package io.flowcatalyst.framework.usecase;

/**
 * A refused push (push contract, section 1): a topic, type or data that breaks the rules. It is an
 * {@link IllegalArgumentException}; {@link #reason()} is one of the codes of
 * {@code conformance/push/topics.json}. The runner turns one thrown while a plan is decided into
 * {@code INTERNAL} / {@code INVALID_PUSH} (a bug in the use case).
 */
public final class InvalidPushException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** A topic that is not {@code {application}:{segment}(:{segment})*}. */
  public static final String TOPIC_INVALID = "TOPIC_INVALID";

  /** A type that is not {@code [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)*}. */
  public static final String TYPE_INVALID = "TYPE_INVALID";

  /** A type that is reserved ({@code reset}, {@code auth-expired}, {@code subscribed}, {@code fw.*}). */
  public static final String TYPE_RESERVED = "TYPE_RESERVED";

  /** Data that is not JSON. */
  public static final String DATA_INVALID = "DATA_INVALID";

  /** Data that is JSON but not an object. */
  public static final String DATA_NOT_OBJECT = "DATA_NOT_OBJECT";

  /** Data over the size limit. */
  public static final String DATA_TOO_LARGE = "DATA_TOO_LARGE";

  private final String reason;

  /**
   * Creates the exception.
   *
   * @param reason one of the codes of this class
   * @param message what is wrong, for the log
   */
  public InvalidPushException(String reason, String message) {
    super(reason + ": " + message);
    this.reason = reason;
  }

  /**
   * The refusal code.
   *
   * @return one of the codes of this class
   */
  public String reason() {
    return reason;
  }
}
