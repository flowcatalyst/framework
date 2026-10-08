package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import java.util.Optional;

/**
 * A validate or authorize phase: returns the error, or empty when the command passes.
 *
 * @param <C> the command type
 */
@FunctionalInterface
public interface Check<C> {

  /**
   * Checks the command.
   *
   * @param context who is acting
   * @param command the command
   * @return an error, or empty
   * @throws Exception any failure; the run reports it as an internal error and logs the cause
   */
  Optional<UseCaseError> check(Context context, C command) throws Exception;
}
