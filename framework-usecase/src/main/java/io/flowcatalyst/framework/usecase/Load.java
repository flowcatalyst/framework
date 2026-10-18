package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import java.util.Optional;

/**
 * The load phase of a use case that works on one stored record: finds it, once per attempt. The
 * result goes to {@link LoadedCheck} and then to {@link LoadedExecute}.
 *
 * @param <C> the command type
 * @param <L> what is loaded (usually an aggregate)
 */
@FunctionalInterface
public interface Load<C, L> {

  /**
   * Finds the record, scoped to the context's client (the repository's {@code find} does).
   *
   * @param context who is acting
   * @param command the command
   * @return the record, or empty when there is none
   * @throws Exception any failure; the run reports it as an internal error ({@code LOAD}) and logs
   *     the cause
   */
  Optional<L> load(Context context, C command) throws Exception;
}
