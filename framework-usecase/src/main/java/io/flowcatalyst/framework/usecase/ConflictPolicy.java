package io.flowcatalyst.framework.usecase;

import java.time.Duration;

/**
 * What to do when an attempt finds the data changed underneath it (a stale aggregate, a
 * serialization failure or a deadlock).
 */
public final class ConflictPolicy {

  private static final ConflictPolicy FAIL = new ConflictPolicy(1, Duration.ZERO);

  private final int maxAttempts;
  private final Duration backoff;

  private ConflictPolicy(int maxAttempts, Duration backoff) {
    this.maxAttempts = maxAttempts;
    this.backoff = backoff;
  }

  /**
   * Fail with {@code CONFLICT} / {@code STALE_AGGREGATE} at the first conflict. The default.
   *
   * @return the policy
   */
  public static ConflictPolicy fail() {
    return FAIL;
  }

  /**
   * Run the whole use case again from the start, up to {@code maxAttempts} attempts in total.
   *
   * @param maxAttempts total attempts, 2 to 10
   * @param backoff the wait between attempts; each wait is randomised between half and one and
   *     a half times this value
   * @return the policy
   * @throws IllegalArgumentException when the attempts are out of range or the backoff is negative
   */
  public static ConflictPolicy retry(int maxAttempts, Duration backoff) {
    if (maxAttempts < 2 || maxAttempts > 10) {
      throw new IllegalArgumentException("retry attempts must be 2 to 10: " + maxAttempts);
    }
    if (backoff.isNegative()) {
      throw new IllegalArgumentException("retry backoff must not be negative: " + backoff);
    }
    return new ConflictPolicy(maxAttempts, backoff);
  }

  /**
   * The total number of attempts.
   *
   * @return 1 for {@link #fail()}
   */
  public int maxAttempts() {
    return maxAttempts;
  }

  /**
   * The base wait between attempts.
   *
   * @return the backoff
   */
  public Duration backoff() {
    return backoff;
  }
}
