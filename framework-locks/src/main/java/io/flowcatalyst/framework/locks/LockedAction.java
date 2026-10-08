package io.flowcatalyst.framework.locks;

import org.jspecify.annotations.Nullable;

/**
 * Work done while holding a lock.
 *
 * @param <T> the type returned
 */
@FunctionalInterface
public interface LockedAction<T extends @Nullable Object> {

  /**
   * Runs the work.
   *
   * @param lease the held lease; pass {@link Lease#fencingToken()} on to what is written
   * @return the result
   * @throws Exception any failure; checked ones reach the caller as {@link LockedActionException}
   */
  T run(Lease lease) throws Exception;
}
