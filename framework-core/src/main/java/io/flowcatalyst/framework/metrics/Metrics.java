package io.flowcatalyst.framework.metrics;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Creates instruments and reads them back. Provided by the core module; the driver is chosen by
 * {@code app.metrics.driver} ({@code memory} or {@code noop}).
 *
 * <p>Names, help texts and label names are checked when an instrument is created and a breach is an
 * {@link IllegalArgumentException}: names {@code [a-z][a-z0-9_]*}, counters ending {@code _total},
 * timers ending {@code _seconds}. Creating the same instrument twice returns the same family.
 * Label <em>values</em> never make a recording throw; the number of series per family is bounded
 * (background-and-metrics contract, section 2).
 */
public interface Metrics {

  /** The drivers {@code app.metrics.driver} chooses from. */
  enum Driver {
    /** In-process, readable with {@link #snapshot()} and the Prometheus endpoint. */
    MEMORY,
    /** Records nothing. */
    NOOP
  }

  /** The default upper bounds of a timer's buckets, in seconds. */
  List<Double> DEFAULT_BUCKETS =
      List.of(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0);

  /**
   * A counter family.
   *
   * @param name ends in {@code _total}
   * @param help what it counts
   * @param labelNames the label names, at most 10
   * @return the family
   */
  Counter counter(String name, String help, String... labelNames);

  /**
   * A gauge family.
   *
   * @param name neither {@code _total} nor {@code _seconds} at the end
   * @param help what it measures
   * @param labelNames the label names, at most 10
   * @return the family
   */
  Gauge gauge(String name, String help, String... labelNames);

  /**
   * A gauge without labels whose value is read when metrics are exported.
   *
   * @param name neither {@code _total} nor {@code _seconds} at the end
   * @param help what it measures
   * @param value read on export; must be quick and must not throw
   */
  void gauge(String name, String help, DoubleSupplier value);

  /**
   * A timer family with the {@link #DEFAULT_BUCKETS}.
   *
   * @param name ends in {@code _seconds}
   * @param help what it times
   * @param labelNames the label names, at most 10
   * @return the family
   */
  Timer timer(String name, String help, String... labelNames);

  /**
   * A timer family with explicit bucket bounds.
   *
   * @param name ends in {@code _seconds}
   * @param help what it times
   * @param buckets upper bounds in seconds, positive, finite, strictly increasing, at most 50
   * @param labelNames the label names, at most 10
   * @return the family
   */
  Timer timer(String name, String help, List<Double> buckets, String... labelNames);

  /**
   * Whether {@link #snapshot()} returns what was recorded (false for the no-op driver).
   *
   * @return true when recordings can be read back
   */
  boolean readable();

  /**
   * Everything recorded so far.
   *
   * @return the families and their series; empty for the no-op driver
   */
  MetricsSnapshot snapshot();

  /**
   * The in-memory driver.
   *
   * @param maxSeries the most series one family may hold, 1 to 100 000
   * @param clock the clock {@link Timer.Series#time} measures with
   * @return a new registry
   */
  static Metrics memory(int maxSeries, MonotonicClock clock) {
    return new InMemoryMetrics(true, maxSeries, clock);
  }

  /**
   * The no-op driver: names are still checked, nothing is recorded.
   *
   * @return a new registry
   */
  static Metrics noop() {
    return new InMemoryMetrics(false, 1, MonotonicClock.system());
  }
}
