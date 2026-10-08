package io.flowcatalyst.framework.context;

import java.util.UUID;

/**
 * Follows a request into logs, jobs and events.
 *
 * @param value the identifier text, never blank
 */
public record CorrelationId(String value) {
  /** Rejects blank values. */
  public CorrelationId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("CorrelationId must not be blank");
    }
  }

  /**
   * Generates a new random identifier.
   *
   * @return a fresh correlation id
   */
  public static CorrelationId generate() {
    return new CorrelationId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
