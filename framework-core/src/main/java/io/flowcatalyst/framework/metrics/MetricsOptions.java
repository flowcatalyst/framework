package io.flowcatalyst.framework.metrics;

/**
 * Which metrics driver the application uses and how many series one metric may hold
 * (background-and-metrics contract section 2; config-and-code contract section 3.1). Given to
 * {@code AppBuilder.metrics(MetricsOptions)}.
 *
 * @param driver {@code MEMORY} (readable, the Prometheus endpoint) or {@code NOOP} (records nothing)
 * @param maxSeries the most series one metric may hold, 1 to 100 000; further label combinations
 *     are dropped and counted
 */
public record MetricsOptions(Metrics.Driver driver, int maxSeries) {

  /** The largest {@code maxSeries} accepted. */
  public static final int MAX_SERIES_LIMIT = 100_000;

  /** Checks the range. */
  public MetricsOptions {
    if (maxSeries < 1 || maxSeries > MAX_SERIES_LIMIT) {
      throw new IllegalArgumentException("maxSeries must be 1 to 100000, was " + maxSeries);
    }
  }

  /**
   * The memory driver with 1000 series per metric.
   *
   * @return the defaults
   */
  public static MetricsOptions defaults() {
    return memory(1000);
  }

  /**
   * The memory driver.
   *
   * @param maxSeries the most series one metric may hold, 1 to 100 000
   * @return the options
   */
  public static MetricsOptions memory(int maxSeries) {
    return new MetricsOptions(Metrics.Driver.MEMORY, maxSeries);
  }

  /**
   * The noop driver: records nothing, and the Prometheus endpoint has nothing to show.
   *
   * @return the options
   */
  public static MetricsOptions noop() {
    return new MetricsOptions(Metrics.Driver.NOOP, 1000);
  }
}
