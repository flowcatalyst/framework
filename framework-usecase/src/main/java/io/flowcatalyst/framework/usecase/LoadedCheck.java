package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import java.util.Optional;

/**
 * The record-level access check: runs after the record was loaded and before execute, so a rule
 * that depends on the record ("a requester may comment only on a ticket they opened") sits with
 * the other access checks.
 *
 * @param <C> the command type
 * @param <L> what was loaded
 */
@FunctionalInterface
public interface LoadedCheck<C, L> {

  /**
   * Checks the command against the loaded record.
   *
   * @param context who is acting
   * @param command the command
   * @param loaded the record
   * @return an error, or empty when the caller may proceed
   * @throws Exception any failure; the run reports it as an internal error ({@code AUTHORIZE}) and
   *     logs the cause
   */
  Optional<UseCaseError> check(Context context, C command, L loaded) throws Exception;
}
