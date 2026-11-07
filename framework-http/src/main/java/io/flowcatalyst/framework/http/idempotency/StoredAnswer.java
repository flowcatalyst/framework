package io.flowcatalyst.framework.http.idempotency;

import org.jspecify.annotations.Nullable;

/**
 * The answer kept for a key: what a repeat of the request gets again.
 *
 * <p>The array is compared by identity, as Java arrays are.
 *
 * @param status the status, 200 to 299
 * @param contentType the {@code Content-Type}, or null when the answer had none
 * @param body the body bytes, possibly empty
 */
public record StoredAnswer(int status, @Nullable String contentType, byte[] body) {

  /** Checks the status. */
  public StoredAnswer {
    if (status < 200 || status > 299) {
      throw new IllegalArgumentException("only a 2xx answer is kept, not " + status);
    }
  }
}
