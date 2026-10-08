package io.flowcatalyst.framework.usecase;

/**
 * The outcome of running a use case. Expected outcomes never throw.
 *
 * @param <E> the result event type
 */
public sealed interface UseCaseResult<E extends DomainEvent> {

  /**
   * The plan was applied and committed.
   *
   * @param <E> the result event type
   * @param event the use case's result event
   */
  record Committed<E extends DomainEvent>(E event) implements UseCaseResult<E> {}

  /**
   * Nothing was written.
   *
   * @param <E> the result event type
   * @param error why
   */
  record Failed<E extends DomainEvent>(UseCaseError error) implements UseCaseResult<E> {}
}
