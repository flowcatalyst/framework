package io.flowcatalyst.framework.core;

/** A monotonic source of nanoseconds, for measuring elapsed time. Injected so tests control it. */
@FunctionalInterface
public interface MonotonicClock {

  /**
   * The current reading.
   *
   * @return nanoseconds from an arbitrary origin; only differences are meaningful
   */
  long nanos();

  /**
   * The real clock.
   *
   * @return a clock reading {@link System#nanoTime()}
   */
  static MonotonicClock system() {
    return System::nanoTime;
  }
}
