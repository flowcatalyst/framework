package io.flowcatalyst.framework.http.ratelimit;

import java.time.Duration;

/**
 * The GCRA decision on one stored theoretical arrival time, shared by the memory driver and
 * checked against the Redis script's arithmetic in tests. All values in one time unit.
 */
final class Gcra {

  /**
   * The decision and the TAT to store.
   *
   * @param store whether to store {@code newTat}: always when allowed; when limited, only when the
   *     stored TAT was beyond the policy's window and was capped (contract section 3a)
   */
  record Step(boolean allowed, boolean store, long newTat, long remaining, long retryAfter, long resetAfter) {}

  private Gcra() {}

  /**
   * One decision. The TAT is capped at {@code now + burst * interval} (an empty bucket under this
   * policy); with one fixed policy the cap never applies.
   */
  static Step step(long storedTat, long now, long interval, long burst, long cost) {
    long window = burst * interval; // bounded by RateLimitPolicy, no overflow
    long tat = Math.min(Math.max(storedTat, now), now + window);
    long next = tat + interval * cost;
    long allowAt = next - window;
    if (now < allowAt) {
      return new Step(false, tat < storedTat, tat, 0, allowAt - now, tat - now);
    }
    return new Step(true, true, next, (now - allowAt) / interval, 0, next - now);
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
