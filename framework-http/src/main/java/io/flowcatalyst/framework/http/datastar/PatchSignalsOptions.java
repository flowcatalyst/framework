package io.flowcatalyst.framework.http.datastar;

import org.jspecify.annotations.Nullable;

/**
 * Options of {@link ServerSentEventGenerator#patchSignals(String, PatchSignalsOptions)}.
 *
 * @param onlyIfMissing only set signals the client does not have yet
 * @param eventId the SSE event id, or null
 * @param retryMillis the client's reconnect delay; the default is {@value ServerSentEventGenerator#DEFAULT_RETRY_MILLIS}
 */
public record PatchSignalsOptions(boolean onlyIfMissing, @Nullable String eventId, long retryMillis) {

  /**
   * The defaults: overwrite, no id, default retry.
   *
   * @return the options
   */
  public static PatchSignalsOptions defaults() {
    return new PatchSignalsOptions(false, null, ServerSentEventGenerator.DEFAULT_RETRY_MILLIS);
  }

  /**
   * Sets only-if-missing.
   *
   * @param value true to keep existing client values
   * @return a copy
   */
  public PatchSignalsOptions onlyIfMissing(boolean value) {
    return new PatchSignalsOptions(value, eventId, retryMillis);
  }

  /**
   * Sets the event id.
   *
   * @param value the id
   * @return a copy
   */
  public PatchSignalsOptions eventId(String value) {
    return new PatchSignalsOptions(onlyIfMissing, value, retryMillis);
  }

  /**
   * Sets the retry delay.
   *
   * @param millis the delay in milliseconds
   * @return a copy
   */
  public PatchSignalsOptions retry(long millis) {
    return new PatchSignalsOptions(onlyIfMissing, eventId, millis);
  }
}
