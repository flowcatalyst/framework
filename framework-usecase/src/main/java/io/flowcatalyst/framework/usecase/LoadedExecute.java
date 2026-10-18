package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;

/**
 * The execute phase of a use case with a load phase: the record is already loaded and authorized.
 *
 * @param <C> the command type
 * @param <L> what was loaded
 * @param <E> the result event type
 */
@FunctionalInterface
public interface LoadedExecute<C, L, E extends DomainEvent> {

  /**
   * Decides what to write.
   *
   * @param context who is acting
   * @param command the command
   * @param loaded the record, as loaded in this attempt
   * @return a {@link Plan} or a {@link Rejected}
   * @throws Exception any failure; the run reports it as an internal error and logs the cause
   */
  Decision<E> execute(Context context, C command, L loaded) throws Exception;
}
