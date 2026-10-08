package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;

/**
 * The execute phase: loads what it needs (once), applies the rules and returns a decision.
 *
 * @param <C> the command type
 * @param <E> the result event type
 */
@FunctionalInterface
public interface Execute<C, E extends DomainEvent> {

  /**
   * Decides what to write.
   *
   * @param context who is acting
   * @param command the command
   * @return a {@link Plan} or a {@link Rejected}
   * @throws Exception any failure; the run reports it as an internal error and logs the cause
   */
  Decision<E> execute(Context context, C command) throws Exception;
}
