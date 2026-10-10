package io.flowcatalyst.framework.metrics;

import java.time.Duration;
import java.util.concurrent.Callable;

/** A timer family: a histogram of durations in seconds per combination of label values. */
public interface Timer {

  /**
   * The series for these label values, created on first use.
   *
   * @param labelValues one per label name, in order; longer than 200 characters is cut
   * @return the series; a no-op series when the family is full
   * @throws IllegalArgumentException when the number of values differs from the label names
   */
  Series labels(String... labelValues);

  /**
   * Records a duration; only for a family without labels.
   *
   * @param duration the duration; negative records zero
   */
  default void record(Duration duration) {
    labels().record(duration);
  }

  /** One timer series. */
  interface Series {
    /**
     * Records a duration.
     *
     * @param duration the duration; negative records zero
     */
    default void record(Duration duration) {
      recordNanos(duration.toNanos());
    }

    /**
     * Records a duration in nanoseconds.
     *
     * @param nanos the duration; negative records zero
     */
    void recordNanos(long nanos);

    /**
     * Runs the work and records how long it took, also when it throws.
     *
     * @param <T> the result type
     * @param work the work
     * @return what the work returned
     * @throws Exception what the work threw
     */
    <T> T time(Callable<T> work) throws Exception;
  }
}
