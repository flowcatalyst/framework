package io.flowcatalyst.framework.flowcatalyst;

import java.time.Duration;

/**
 * What a dispatch-job handler tells the platform (flowcatalyst contract, section 4).
 *
 * <ul>
 *   <li>{@link Ack}: done; the platform completes the job ({@code 200 {"ack":true}}).
 *   <li>{@link Defer}: not now; the platform delivers again after the delay without spending a
 *       retry ({@code 200 {"ack":false,"delaySeconds":n}}).
 *   <li>{@link Reject}: this job can never succeed; the platform fails it at once without retrying
 *       ({@code 422} with {@code FlowCatalyst-Outcome: reject}).
 * </ul>
 *
 * <p>A handler that throws, or runs past {@code flowcatalyst.receivers.dispatch-timeout}, is a
 * transient error ({@code 503}): the platform retries it with its own backoff. A handler that
 * wants a terminal failure returns {@link #reject(String)}.
 */
public sealed interface DispatchResult {

  /** The default delay of {@link #defer()}: the platform's own default. */
  Duration DEFAULT_DEFER = Duration.ofSeconds(30);

  /** The largest delay the platform accepts (an unsigned 32-bit number of seconds). */
  long MAX_DEFER_SECONDS = 4_294_967_295L;

  /** The longest reject reason sent; longer reasons are cut. */
  int MAX_REASON_LENGTH = 500;

  /** The job is done. */
  record Ack() implements DispatchResult {}

  /**
   * Deliver again later, without spending a retry.
   *
   * @param delay how long to wait; sent as whole seconds, rounded up
   */
  record Defer(Duration delay) implements DispatchResult {

    /** Rejects a negative delay, or one longer than {@link #MAX_DEFER_SECONDS}. */
    public Defer {
      if (delay.isNegative()) {
        throw new IllegalArgumentException("a defer delay cannot be negative");
      }
      if (delay.compareTo(Duration.ofSeconds(MAX_DEFER_SECONDS)) > 0) {
        throw new IllegalArgumentException("a defer delay is at most " + MAX_DEFER_SECONDS + " seconds");
      }
    }

    /**
     * The delay in whole seconds, rounded up.
     *
     * @return the {@code delaySeconds} value sent to the platform
     */
    public long delaySeconds() {
      long seconds = delay.getSeconds();
      return delay.getNano() > 0 ? seconds + 1 : seconds;
    }
  }

  /**
   * The job can never succeed: fail it now, do not retry.
   *
   * @param reason why, returned to the platform in the error message; control characters are
   *     replaced by spaces and it is cut to {@link #MAX_REASON_LENGTH} characters. Write it for an
   *     operator: never put exception text, stack traces or secrets in it.
   */
  record Reject(String reason) implements DispatchResult {

    /** Rejects a blank reason and cleans the text. */
    public Reject {
      if (reason.isBlank()) {
        throw new IllegalArgumentException("a reject reason is required");
      }
      reason = LogSafe.clean(reason, MAX_REASON_LENGTH);
    }
  }

  /**
   * The job is done.
   *
   * @return {@link Ack}
   */
  static DispatchResult ack() {
    return new Ack();
  }

  /**
   * Deliver again after {@link #DEFAULT_DEFER}.
   *
   * @return {@link Defer}
   */
  static DispatchResult defer() {
    return new Defer(DEFAULT_DEFER);
  }

  /**
   * Deliver again after a delay.
   *
   * @param delay how long to wait, zero to {@link #MAX_DEFER_SECONDS} seconds
   * @return {@link Defer}
   * @throws IllegalArgumentException when the delay is out of range
   */
  static DispatchResult defer(Duration delay) {
    return new Defer(delay);
  }

  /**
   * Fail the job now, without retrying.
   *
   * @param reason why, for an operator
   * @return {@link Reject}
   * @throws IllegalArgumentException when the reason is blank
   */
  static DispatchResult reject(String reason) {
    return new Reject(reason);
  }
}
