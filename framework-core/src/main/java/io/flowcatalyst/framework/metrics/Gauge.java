package io.flowcatalyst.framework.metrics;

/** A gauge family: one value per combination of label values that can go up and down. */
public interface Gauge {

  /**
   * The series for these label values, created on first use.
   *
   * @param labelValues one per label name, in order; longer than 200 characters is cut
   * @return the series; a no-op series when the family is full
   * @throws IllegalArgumentException when the number of values differs from the label names
   */
  Series labels(String... labelValues);

  /**
   * Sets the value; only for a family without labels.
   *
   * @param value the value
   */
  default void set(double value) {
    labels().set(value);
  }

  /** One gauge series. */
  interface Series {
    /**
     * Sets the value.
     *
     * @param value the value
     */
    void set(double value);

    /**
     * Adds to the value.
     *
     * @param delta the change
     */
    void inc(double delta);

    /**
     * Subtracts from the value.
     *
     * @param delta the change
     */
    default void dec(double delta) {
      inc(-delta);
    }
  }
}
