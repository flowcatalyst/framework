package io.flowcatalyst.framework.usecase;

/** What kind of failure a {@link UseCaseError} is, and the HTTP status it maps to. */
public enum ErrorKind {
  /** The command is malformed. */
  VALIDATION(400),
  /** The caller may not do this. */
  AUTHORIZATION(403),
  /** Something the command refers to does not exist. */
  NOT_FOUND(404),
  /** The state changed or a unique value is taken. */
  CONFLICT(409),
  /** A business rule forbids this in the current state. */
  BUSINESS_RULE(409),
  /** A bug or an infrastructure failure; the cause is logged, not returned. */
  INTERNAL(500);

  private final int httpStatus;

  ErrorKind(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  /**
   * The HTTP status for this kind.
   *
   * @return the status code
   */
  public int httpStatus() {
    return httpStatus;
  }
}
