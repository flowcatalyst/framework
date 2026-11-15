package io.flowcatalyst.framework.partitions;

import org.jspecify.annotations.Nullable;

/**
 * A partition operation failed: the database could not be read or written, or a pass had failed steps
 * ({@link Partitions#pass}). {@link PartitionRefusedException} is the typed refusal of an operation that
 * did not run.
 */
public class PartitionsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the cause, or null
   */
  public PartitionsException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
