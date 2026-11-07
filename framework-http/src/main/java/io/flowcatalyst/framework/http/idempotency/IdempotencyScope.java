package io.flowcatalyst.framework.http.idempotency;

/**
 * Whose key it is: a key is unique per client and caller, so two callers (or two clients) may use
 * the same key without meeting.
 *
 * @param clientId the context's client
 * @param principalId the context's principal
 * @param key the {@code Idempotency-Key} header's value, 1 to {@link Idempotency#MAX_KEY_LENGTH}
 *     printable ASCII characters
 */
public record IdempotencyScope(String clientId, String principalId, String key) {

  /** Checks the key. */
  public IdempotencyScope {
    if (!Idempotency.validKey(key)) {
      throw new IllegalArgumentException(
          "an idempotency key is 1 to " + Idempotency.MAX_KEY_LENGTH + " printable ASCII characters");
    }
  }
}
