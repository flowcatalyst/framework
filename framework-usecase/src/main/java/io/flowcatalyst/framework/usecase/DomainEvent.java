package io.flowcatalyst.framework.usecase;

/**
 * A domain event: a record that carries its {@link EventMetadata} and its own data fields. The
 * data written to the outbox is the record's fields as JSON, without the metadata.
 *
 * <pre>{@code
 * record OrderCreated(EventMetadata metadata, String orderId, int lineCount)
 *     implements DomainEvent {}
 * }</pre>
 */
public interface DomainEvent {

  /**
   * The event's metadata.
   *
   * @return the metadata
   */
  EventMetadata metadata();
}
