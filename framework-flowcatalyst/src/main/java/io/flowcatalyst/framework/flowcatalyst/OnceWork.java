package io.flowcatalyst.framework.flowcatalyst;

import java.sql.Connection;
import org.jspecify.annotations.Nullable;

/**
 * Work done at most once per dispatch job, inside the transaction {@link DispatchDelivery#once}
 * opens.
 *
 * @param <T> what the work returns
 */
@FunctionalInterface
public interface OnceWork<T extends @Nullable Object> {

  /**
   * Does the work.
   *
   * @param tx the open transaction (READ COMMITTED, not auto-commit); do not commit, roll back or
   *     close it
   * @return a value for the caller
   * @throws Exception any failure; the transaction, including the job's record, is rolled back
   */
  T run(Connection tx) throws Exception;
}
