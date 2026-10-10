package io.flowcatalyst.framework.metrics;

/** A counter family: one monotonically growing value per combination of label values. */
public interface Counter {

  /**
   * The series for these label values, created on first use.
   *
   * @param labelValues one per label name, in order; longer than 200 characters is cut
   * @return the series; a no-op series when the family is full
   * @throws IllegalArgumentException when the number of values differs from the label names
   */
  Series labels(String... labelValues);

  /** Adds one; only for a family without labels. */
  default void inc() {
    labels().inc();
  }

  /**
   * Adds an amount; only for a family without labels.
   *
   * @param amount zero or more
   */
  default void inc(double amount) {
    labels().inc(amount);
  }

  /** One counter series. */
  interface Series {
    /** Adds one. */
    void inc();

    /**
     * Adds an amount.
     *
     * @param amount zero or more; a negative or NaN amount is ignored
     */
    void inc(double amount);
  }
}
