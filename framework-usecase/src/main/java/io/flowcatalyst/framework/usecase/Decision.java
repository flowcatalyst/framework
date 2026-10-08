package io.flowcatalyst.framework.usecase;

/**
 * What execute decided: a {@link Plan} of writes, or a {@link Rejected} error.
 *
 * @param <E> the use case's result event
 */
public sealed interface Decision<E extends DomainEvent> permits Plan, Rejected {

  /**
   * Rejects the command.
   *
   * @param <E> the use case's result event
   * @param error why
   * @return the rejection
   */
  static <E extends DomainEvent> Decision<E> reject(UseCaseError error) {
    return new Rejected<>(error);
  }
}
