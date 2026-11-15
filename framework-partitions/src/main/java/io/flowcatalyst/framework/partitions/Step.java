package io.flowcatalyst.framework.partitions;

/** A step of a pass, as the failure counter and a {@link PassResult} name it. */
public enum Step {
  /** Creating the partitions ahead. */
  ENSURE("ensure"),
  /** Listing, detaching and dropping the due partitions. */
  RETENTION("retention"),
  /** Writing a due partition to its object store ({@link Retention#archiveThenDropAfter} only). */
  ARCHIVE("archive");

  private final String label;

  Step(String label) {
    this.label = label;
  }

  /**
   * The step's name as the {@code step} label of {@code fw_partition_failures_total} spells it.
   *
   * @return {@code ensure}, {@code retention} or {@code archive}
   */
  public String label() {
    return label;
  }
}
