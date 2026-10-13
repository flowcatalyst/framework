package io.flowcatalyst.framework.http.ratelimit;

import java.time.Duration;

/**
 * {@code limit} requests per {@code period}, with up to {@code burst} at once (rate-limiting
 * contract, section 1).
 *
 * @param limit requests per period, 1 to 1 000 000
 * @param period the period, 1 ms to 1 day
 * @param burst requests allowed at once, 1 to 1 000 000; {@code burst * period / limit} at most
 *     about 73 years
 */
public record RateLimitPolicy(int limit, Duration period, int burst) {

  static final int MAX = 1_000_000;

  /** The largest window {@code burst * period / limit}, in nanoseconds (about 73 years). */
  static final long MAX_WINDOW_NANOS = Long.MAX_VALUE / 4;

  /** Checks the ranges. */
  public RateLimitPolicy {
    if (limit < 1 || limit > MAX) {
      throw new IllegalArgumentException("limit must be 1 to " + MAX);
    }
    if (burst < 1 || burst > MAX) {
      throw new IllegalArgumentException("burst must be 1 to " + MAX);
    }
    if (period.compareTo(Duration.ofMillis(1)) < 0 || period.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException("period must be 1ms to 1 day");
    }
    if (period.toNanos() / limit < 1_000) {
      throw new IllegalArgumentException("period / limit must be at least 1 microsecond");
    }
    if (burst > MAX_WINDOW_NANOS / (period.toNanos() / limit)) {
      throw new IllegalArgumentException("burst * period / limit must be at most 73 years");
    }
  }

  /**
   * A policy whose burst equals its limit.
   *
   * @param limit requests per period
   * @param period the period
   * @return the policy
   */
  public static RateLimitPolicy of(int limit, Duration period) {
    return new RateLimitPolicy(limit, period, limit);
  }

  /**
   * The time one request's worth of budget takes to come back.
   *
   * @return {@code period / limit} in nanoseconds
   */
  public long intervalNanos() {
    return period.toNanos() / limit;
  }
}
