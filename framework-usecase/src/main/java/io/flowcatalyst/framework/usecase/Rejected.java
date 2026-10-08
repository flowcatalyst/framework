package io.flowcatalyst.framework.usecase;

/**
 * The decision not to write anything.
 *
 * @param <E> the use case's result event
 * @param error why
 */
public record Rejected<E extends DomainEvent>(UseCaseError error) implements Decision<E> {}
