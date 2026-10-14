package io.flowcatalyst.framework.http;

import org.jspecify.annotations.Nullable;

/**
 * One check of a field value. A rule other than {@link Rules#notNull()} and
 * {@link Rules#notBlank()} passes on null: absence is {@code notNull}'s job.
 *
 * @param <T> the value type
 */
@FunctionalInterface
public interface Rule<T> {

  /**
   * Checks the value.
   *
   * @param value the value, possibly null
   * @param violations where to record what is wrong
   */
  void check(@Nullable T value, Violations violations);

  /**
   * What this rule checks, as data, for the OpenAPI document (OpenAPI contract section 5.4). The
   * standard {@link Rules} describe themselves; a lambda and {@link Rules#custom} document nothing.
   *
   * @return the constraint; {@link Constraint.None} by default
   */
  default Constraint describe() {
    return Constraint.none();
  }
}
