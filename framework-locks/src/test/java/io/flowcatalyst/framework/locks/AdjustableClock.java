package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A monotonic clock that follows real time and can be moved forward at once, so contract tests
 * can let the memory driver's leases expire without sleeping while threads that really sleep
 * (the {@code withLock} refresher) still see time pass.
 */
public final class AdjustableClock implements MonotonicClock {

  private final AtomicLong offsetNanos = new AtomicLong();

  @Override
  public long nanos() {
    return System.nanoTime() + offsetNanos.get();
  }

  /** Moves the clock forward. */
  public void advance(Duration d) {
    offsetNanos.addAndGet(d.toNanos());
  }
}
