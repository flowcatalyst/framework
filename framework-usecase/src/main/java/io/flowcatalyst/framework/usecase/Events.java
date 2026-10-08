package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.Ids;
import java.time.InstantSource;

/**
 * Makes event metadata: the generated id, the time from the injected clock, and the context's
 * correlation, principal, causation and execution ids. Provided by {@link UseCaseModule}.
 */
public final class Events {

  private static final String SPEC_VERSION = "1.0";

  private final InstantSource clock;
  private final Ids ids;

  /**
   * Creates the factory.
   *
   * @param clock the wall-clock source
   * @param ids the id generator
   */
  public Events(InstantSource clock, Ids ids) {
    this.clock = clock;
    this.ids = ids;
  }

  /**
   * Metadata for an event about one aggregate.
   *
   * @param context who is acting
   * @param type the event type
   * @param subject the aggregate the event is about
   * @return the metadata
   */
  public EventMetadata metadata(Context context, EventType type, AggregateId subject) {
    return new EventMetadata(
        ids.generate(),
        SPEC_VERSION,
        type.type(),
        type.source(),
        type.subject(subject),
        clock.instant(),
        context.correlationId().value(),
        context.principal() == null ? null : context.principal().value(),
        context.causationId(),
        context.executionId(),
        type.messageGroup(subject));
  }
}
