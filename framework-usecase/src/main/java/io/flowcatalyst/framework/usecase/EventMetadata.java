package io.flowcatalyst.framework.usecase;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What every event carries besides its data (write-path contract, section 6). Build it with
 * {@link Events#metadata}.
 *
 * @param eventId a TSID, generated
 * @param specVersion {@code 1.0}
 * @param type {@code application:domain:aggregate:action}
 * @param source {@code application:domain}
 * @param subject {@code domain.aggregate.id}
 * @param time when the event was made, from the injected clock
 * @param correlationId from the context
 * @param principalId the acting principal, or null
 * @param causationId from the context, or null
 * @param executionId from the context, or null
 * @param messageGroup {@code domain:aggregate:id} unless changed
 */
public record EventMetadata(
    String eventId,
    String specVersion,
    String type,
    String source,
    String subject,
    Instant time,
    String correlationId,
    @Nullable String principalId,
    @Nullable String causationId,
    @Nullable String executionId,
    String messageGroup) {

  /**
   * A copy with another message group.
   *
   * @param group the group; events sharing it are delivered in order
   * @return the new metadata
   */
  public EventMetadata withMessageGroup(String group) {
    return new EventMetadata(
        eventId,
        specVersion,
        type,
        source,
        subject,
        time,
        correlationId,
        principalId,
        causationId,
        executionId,
        group);
  }
}
