package io.flowcatalyst.framework.partitions;

/**
 * An operation the module refused to run: typed, so a caller (an admin section) maps each reason to a
 * status without reading text. The refusal for {@code ErrTableNotDeclared} is {@link
 * Reason#TABLE_NOT_DECLARED}, and so on.
 */
public final class PartitionRefusedException extends PartitionsException {

  private static final long serialVersionUID = 1L;

  /** Why an operation was refused. */
  public enum Reason {
    /** No declared table has that name. */
    TABLE_NOT_DECLARED,
    /** The range needs more than {@link Partitions#MAX_ENSURE_RANGE} partitions. */
    RANGE_TOO_LARGE,
    /** The range ends before it starts, or starts before 1970. */
    RANGE_INVALID,
    /** A table of a partition's name exists but is not attached. */
    DETACHED_LEFTOVER,
    /** Another pass (the loop's, here or on another replica, or a manual one) is running. */
    PASS_RUNNING
  }

  private final Reason reason;

  /**
   * Creates the refusal.
   *
   * @param reason why
   * @param message the text, which says more than the reason
   */
  public PartitionRefusedException(Reason reason, String message) {
    super(message, null);
    this.reason = reason;
  }

  /**
   * Why the operation was refused.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
