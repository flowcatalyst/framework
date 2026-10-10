package io.flowcatalyst.framework.httpclient;

import java.time.Duration;
import java.util.Set;

/**
 * When a request is sent again (http-client contract, section 3). Only requests that are safe to
 * repeat are retried: idempotent methods, requests with an idempotency key, and requests marked
 * retryable. POST and PATCH are never retried otherwise.
 *
 * @param maxAttempts attempts in total, 1 to 10 (1 = no retry)
 * @param initialBackoff the first wait
 * @param maxBackoff the longest wait
 * @param maxRetryAfter the longest {@code Retry-After} honoured; a longer one returns the response
 * @param retryOnStatuses statuses that are retried
 */
public record RetryPolicy(
    int maxAttempts,
    Duration initialBackoff,
    Duration maxBackoff,
    Duration maxRetryAfter,
    Set<Integer> retryOnStatuses) {

  /** Checks the ranges and copies the set. */
  public RetryPolicy {
    if (maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("maxAttempts must be 1 to 10");
    }
    if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException("backoffs must be zero or more, and max at least initial");
    }
    if (maxRetryAfter.isNegative()) {
      throw new IllegalArgumentException("maxRetryAfter must not be negative");
    }
    retryOnStatuses = Set.copyOf(retryOnStatuses);
  }

  /**
   * Three attempts, 200 ms doubling to 5 s with jitter, Retry-After up to 30 s, on 429, 502, 503
   * and 504.
   *
   * @return the policy
   */
  public static RetryPolicy defaults() {
    return new RetryPolicy(
        3, Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(30), Set.of(429, 502, 503, 504));
  }

  /**
   * One attempt only.
   *
   * @return the policy
   */
  public static RetryPolicy none() {
    return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, Duration.ZERO, Set.of());
  }

  /**
   * A copy with another number of attempts.
   *
   * @param attempts 1 to 10
   * @return the copy
   */
  public RetryPolicy withMaxAttempts(int attempts) {
    return new RetryPolicy(attempts, initialBackoff, maxBackoff, maxRetryAfter, retryOnStatuses);
  }

  /**
   * A copy with other backoffs.
   *
   * @param initial the first wait
   * @param max the longest wait
   * @return the copy
   */
  public RetryPolicy withBackoff(Duration initial, Duration max) {
    return new RetryPolicy(maxAttempts, initial, max, maxRetryAfter, retryOnStatuses);
  }

  /**
   * A copy with another Retry-After cap.
   *
   * @param max the longest Retry-After honoured
   * @return the copy
   */
  public RetryPolicy withMaxRetryAfter(Duration max) {
    return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, max, retryOnStatuses);
  }
}
