package io.flowcatalyst.framework.core;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How a {@link Background} loop runs (background-and-metrics contract, section 1).
 *
 * @param interval the wait after a run ends before the next starts, 10 ms to 24 h
 * @param initialDelay the wait before the first run, zero to 24 h
 * @param gate decides before each run whether this instance runs it, or null for always
 * @param downAfterFailures how many failed runs in a row make the health check down, 1 to 1000
 * @param stuckAfter how long one run may last before the health check is down, or null for no
 *     limit
 * @param criticality the health check's criticality
 */
public record LoopOptions(
    Duration interval,
    Duration initialDelay,
    @Nullable LoopGate gate,
    int downAfterFailures,
    @Nullable Duration stuckAfter,
    Criticality criticality) {

  private static final Duration MIN_INTERVAL = Duration.ofMillis(10);
  private static final Duration MAX = Duration.ofHours(24);

  /** Checks the ranges. */
  public LoopOptions {
    if (interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX) > 0) {
      throw new IllegalArgumentException("loop interval must be 10ms to 24h");
    }
    if (initialDelay.isNegative() || initialDelay.compareTo(MAX) > 0) {
      throw new IllegalArgumentException("loop initial delay must be zero to 24h");
    }
    if (downAfterFailures < 1 || downAfterFailures > 1000) {
      throw new IllegalArgumentException("downAfterFailures must be 1 to 1000");
    }
    if (stuckAfter != null && (!stuckAfter.isPositive() || stuckAfter.compareTo(MAX) > 0)) {
      throw new IllegalArgumentException("stuckAfter must be positive and at most 24h");
    }
  }

  /**
   * Runs every {@code interval} (fixed delay), starting at once, ungated, down after 3 failed runs
   * in a row, informational.
   *
   * @param interval the wait between runs
   * @return the options
   */
  public static LoopOptions every(Duration interval) {
    return new LoopOptions(interval, Duration.ZERO, null, 3, null, Criticality.INFORMATIONAL);
  }

  /**
   * Waits before the first run.
   *
   * @param delay zero to 24 h
   * @return a copy
   */
  public LoopOptions initialDelay(Duration delay) {
    return new LoopOptions(interval, delay, gate, downAfterFailures, stuckAfter, criticality);
  }

  /**
   * Runs only when the gate admits it.
   *
   * @param value the gate, for example a lock gate
   * @return a copy
   */
  public LoopOptions gatedBy(LoopGate value) {
    return new LoopOptions(interval, initialDelay, value, downAfterFailures, stuckAfter, criticality);
  }

  /**
   * Sets how many failed runs in a row make the health check down.
   *
   * @param runs 1 to 1000
   * @return a copy
   */
  public LoopOptions downAfterFailures(int runs) {
    return new LoopOptions(interval, initialDelay, gate, runs, stuckAfter, criticality);
  }

  /**
   * Makes the health check down while one run lasts longer than this.
   *
   * @param limit positive, at most 24 h
   * @return a copy
   */
  public LoopOptions stuckAfter(Duration limit) {
    return new LoopOptions(interval, initialDelay, gate, downAfterFailures, limit, criticality);
  }

  /**
   * Makes the health check critical: a failing loop makes the application not ready.
   *
   * @return a copy
   */
  public LoopOptions critical() {
    return new LoopOptions(
        interval, initialDelay, gate, downAfterFailures, stuckAfter, Criticality.CRITICAL);
  }
}
