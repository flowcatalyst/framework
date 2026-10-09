package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link DispatchDelivery#once}.
 *
 * @param <T> what the work returns
 */
public sealed interface OnceResult<T extends @Nullable Object> {

  /**
   * The work ran and committed, with the job's record.
   *
   * @param <T> what the work returns
   * @param value what it returned
   */
  record Ran<T extends @Nullable Object>(T value) implements OnceResult<T> {}

  /**
   * The work had already been done for this job and handler; it did not run again.
   *
   * @param <T> what the work returns
   */
  record AlreadyDone<T extends @Nullable Object>() implements OnceResult<T> {}
}
