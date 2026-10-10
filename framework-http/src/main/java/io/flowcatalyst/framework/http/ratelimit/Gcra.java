package io.flowcatalyst.framework.http.ratelimit;

import java.time.Duration;

/**
 * The GCRA decision on one stored theoretical arrival time, shared by the memory driver and
 * checked against the Redis script's arithmetic in tests. All values in one time unit.
 */
final class Gcra {

  /** The decision and the TAT to store (only when allowed). */
  record Step(boolean allowed, long newTat, long remaining, long retryAfter, long resetAfter) {}

  private Gcra() {}

  static Step step(long storedTat, long now, long interval, long burst, long cost) {
    long tat = Math.max(storedTat, now);
    long next = tat + interval * cost;
    long allowAt = next - burst * interval;
    if (now < allowAt) {
      return new Step(false, storedTat, 0, allowAt - now, tat - now);
    }
    return new Step(true, next, (now - allowAt) / interval, 0, next - now);
  }

  static void checkCost(int cost, RateLimitPolicy policy) {
    if (cost < 1 || cost > policy.burst()) {
      throw new IllegalArgumentException("cost must be 1 to the burst (" + policy.burst() + ")");
    }
  }

  static Decision decision(Step step, long nanosPerUnit) {
    return step.allowed()
        ? new Decision.Allowed(step.remaining(), Duration.ofNanos(step.resetAfter() * nanosPerUnit))
        : new Decision.Limited(
            Duration.ofNanos(step.retryAfter() * nanosPerUnit),
            Duration.ofNanos(step.resetAfter() * nanosPerUnit));
  }
}
