package io.flowcatalyst.framework.usecase;

import java.util.ArrayList;
import java.util.List;

/**
 * What to write: aggregates to save or delete and the events that say what happened. Applied by
 * the runner in one transaction, in this order. The first event is the use case's result.
 *
 * @param <E> the type of the first event
 * @param result the first event, returned to the caller on commit
 * @param steps the writes in order; the first one carries {@code result}
 */
public record Plan<E extends DomainEvent>(E result, List<Step> steps) implements Decision<E> {

  /** One write of a plan, with the event it produces. */
  public sealed interface Step permits Save, Delete, Emit {

    /**
     * The event this step produces.
     *
     * @return the event
     */
    DomainEvent event();
  }

  /**
   * Insert (version {@code 0}) or update (otherwise) an aggregate.
   *
   * @param <A> the aggregate type
   * @param aggregate the aggregate
   * @param repository its repository
   * @param event the event
   */
  public record Save<A extends Aggregate>(
      A aggregate, Repository<?, A> repository, DomainEvent event) implements Step {}

  /**
   * Delete an aggregate.
   *
   * @param <A> the aggregate type
   * @param aggregate the aggregate, at the version it was loaded with
   * @param repository its repository
   * @param event the event
   */
  public record Delete<A extends Aggregate>(
      A aggregate, Repository<?, A> repository, DomainEvent event) implements Step {}

  /**
   * An event without an aggregate change.
   *
   * @param event the event
   */
  public record Emit(DomainEvent event) implements Step {}

  /** Copies the steps and requires at least one. */
  public Plan {
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("a plan carries at least one event");
    }
  }

  /**
   * A plan that saves an aggregate.
   *
   * @param <A> the aggregate type
   * @param <E> the event type
   * @param aggregate the aggregate
   * @param repository its repository
   * @param event the result event
   * @return the plan
   */
  public static <A extends Aggregate, E extends DomainEvent> Plan<E> save(
      A aggregate, Repository<?, A> repository, E event) {
    return new Plan<>(event, List.of(new Save<>(aggregate, repository, event)));
  }

  /**
   * A plan that deletes an aggregate.
   *
   * @param <A> the aggregate type
   * @param <E> the event type
   * @param aggregate the aggregate
   * @param repository its repository
   * @param event the result event
   * @return the plan
   */
  public static <A extends Aggregate, E extends DomainEvent> Plan<E> delete(
      A aggregate, Repository<?, A> repository, E event) {
    return new Plan<>(event, List.of(new Delete<>(aggregate, repository, event)));
  }

  /**
   * A plan that only emits an event.
   *
   * @param <E> the event type
   * @param event the result event
   * @return the plan
   */
  public static <E extends DomainEvent> Plan<E> emit(E event) {
    return new Plan<>(event, List.of(new Emit(event)));
  }

  /**
   * Adds saving another aggregate.
   *
   * @param <A> the aggregate type
   * @param aggregate the aggregate
   * @param repository its repository
   * @param event its event
   * @return the extended plan
   */
  public <A extends Aggregate> Plan<E> andSave(
      A aggregate, Repository<?, A> repository, DomainEvent event) {
    return with(new Save<>(aggregate, repository, event));
  }

  /**
   * Adds deleting another aggregate.
   *
   * @param <A> the aggregate type
   * @param aggregate the aggregate
   * @param repository its repository
   * @param event its event
   * @return the extended plan
   */
  public <A extends Aggregate> Plan<E> andDelete(
      A aggregate, Repository<?, A> repository, DomainEvent event) {
    return with(new Delete<>(aggregate, repository, event));
  }

  /**
   * Adds another event with no aggregate change.
   *
   * @param event the event
   * @return the extended plan
   */
  public Plan<E> andEmit(DomainEvent event) {
    return with(new Emit(event));
  }

  private Plan<E> with(Step step) {
    List<Step> more = new ArrayList<>(steps);
    more.add(step);
    return new Plan<>(result, more);
  }
}
