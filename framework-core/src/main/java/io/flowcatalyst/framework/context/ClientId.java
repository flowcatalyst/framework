package io.flowcatalyst.framework.context;

/**
 * Identifies a client (the tenant). Always present in a {@link Context}.
 *
 * @param value the identifier text, never blank
 */
public record ClientId(String value) {
  /** Rejects blank values. */
  public ClientId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("ClientId must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
