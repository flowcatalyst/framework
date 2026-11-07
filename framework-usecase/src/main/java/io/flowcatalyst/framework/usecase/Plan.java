package io.flowcatalyst.framework.usecase;

import java.util.ArrayList;
import java.util.List;

/**
 * What to write: aggregates to save or delete and the events that say what happened, append-only
 * rows, and optionally dispatch jobs for the platform. Applied by the runner in one transaction, in
 * this order. The first event is the use case's result.
 *
 * @param <E> the type of the first event
 * @param result the first event, returned to the caller on commit
 * @param steps the writes in order; the first one carries {@code result}
 * @param dispatchJobs dispatch jobs written to the outbox after the events (write-path contract,
 *     section 7a), in order
 * @param appends append-only rows ({@link #andAppend}), each written right after the step it
 *     follows, in the order they were added
 */
public record Plan<E extends DomainEvent>(
    E result, List<Step> steps, List<DispatchJob> dispatchJobs, List<Appended<?>> appends)
    implements Decision<E> {

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

  /**
   * An append-only row of the plan ({@link #andAppend}).
   *
   * @param <R> the row type
   * @param after how many steps are written before it: the row is written right after step number
   *     {@code after} (1 for the first), so a row added after a {@code save} can refer to it
   * @param row the row
   * @param appender writes it
   */
  public record Appended<R>(int after, R row, Appender<? super R> appender) {

    /**
     * Writes the row with its appender.
     *
     * @param tx the attempt's transaction
     * @throws java.sql.SQLException on a database error
     */
    void write(Tx tx) throws java.sql.SQLException {
      appender.append(tx, row);
    }
  }

  /**
   * Copies the lists, and requires at least one step and appends that each follow one.
   *
   * @throws IllegalArgumentException when there is no step (a plan of appends only is refused: the
   *     audit row needs a subject and the use case a result event), or an append's position is not
   *     after a step, or the positions go backwards
   */
  public Plan {
    steps = List.copyOf(steps);
    dispatchJobs = List.copyOf(dispatchJobs);
    appends = List.copyOf(appends);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException(
          appends.isEmpty()
              ? "a plan carries at least one event"
              : "a plan carries at least one event; appends alone are not a plan (an append is never the audit row's subject)");
    }
    int last = 1;
    for (Appended<?> a : appends) {
      if (a.after() < last || a.after() > steps.size()) {
        throw new IllegalArgumentException(
            "an append follows a step: position " + a.after() + " of " + steps.size() + " steps");
      }
      last = a.after();
    }
  }

  /**
   * A plan without appends.
   *
   * @param result the first event
   * @param steps the writes in order
   * @param dispatchJobs the dispatch jobs
   */
  public Plan(E result, List<Step> steps, List<DispatchJob> dispatchJobs) {
    this(result, steps, dispatchJobs, List.of());
  }

  /**
   * A plan without dispatch jobs or appends.
   *
   * @param result the first event
   * @param steps the writes in order
   */
  public Plan(E result, List<Step> steps) {
    this(result, steps, List.of(), List.of());
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

  /**
   * Adds a dispatch job, written to the outbox in the same transaction (write-path contract,
   * section 7a). The platform's outbox processor delivers it to {@code /api/dispatch-jobs/batch}.
   *
   * @param job the job
   * @return the extended plan
   */
  public Plan<E> andDispatch(DispatchJob job) {
    List<DispatchJob> more = new ArrayList<>(dispatchJobs);
    more.add(job);
    return new Plan<>(result, steps, more, appends);
  }

  /**
   * Adds an append-only row, written in the same transaction right after the steps added so far
   * (so it may refer to an aggregate saved before it) and before any step added later. An append
   * is not an aggregate: it is never loaded, never version-checked, never the audit row's subject,
   * and produces no event. Use it for rows that are only ever inserted (an activity log, a
   * projection, a live-update record); a record the application later changes is an aggregate.
   *
   * @param <R> the row type
   * @param row the row
   * @param appender writes it
   * @return the extended plan
   */
  public <R> Plan<E> andAppend(R row, Appender<? super R> appender) {
    List<Appended<?>> more = new ArrayList<>(appends);
    more.add(new Appended<>(steps.size(), row, appender));
    return new Plan<>(result, steps, dispatchJobs, more);
  }

  private Plan<E> with(Step step) {
    List<Step> more = new ArrayList<>(steps);
    more.add(step);
    return new Plan<>(result, more, dispatchJobs, appends);
  }
}
