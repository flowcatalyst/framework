package io.flowcatalyst.framework.locks;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link LockStore#withLock}.
 *
 * @param <T> the type the action returns
 */
public sealed interface WithLockResult<T extends @Nullable Object> {

  /**
   * The action returned and the lock was held throughout.
   *
   * @param <T> the type the action returns
   * @param value what the action returned
   */
  record Completed<T extends @Nullable Object>(T value) implements WithLockResult<T> {}

  /**
   * The lock could not be acquired within the wait; the action did not run.
   *
   * @param <T> the type the action returns
   */
  record NotAcquired<T extends @Nullable Object>() implements WithLockResult<T> {}

  /**
   * The lock was lost while the action ran. The caller must treat the work as having run without
   * exclusion.
   *
   * @param <T> the type the action returns
   * @param result what the action returned, if it still returned normally with a non-null value
   */
  record Lost<T extends @Nullable Object>(@Nullable T result) implements WithLockResult<T> {
    /**
     * What the action returned.
     *
     * @return the value, present if the action still returned normally (and not null)
     */
    public Optional<T> value() {
      return Optional.ofNullable(result);
    }
  }
}
