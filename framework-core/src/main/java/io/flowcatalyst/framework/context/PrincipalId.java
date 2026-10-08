package io.flowcatalyst.framework.context;

/**
 * Identifies the user or service account acting.
 *
 * @param value the identifier text, never blank
 */
public record PrincipalId(String value) {
  /** Rejects blank values. */
  public PrincipalId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("PrincipalId must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
