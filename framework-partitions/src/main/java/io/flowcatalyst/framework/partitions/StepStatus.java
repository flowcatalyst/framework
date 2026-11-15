package io.flowcatalyst.framework.partitions;

/** The outcome of one step of a pass, as the last-pass record keeps it. */
public enum StepStatus {
  /** The step ran and succeeded. */
  OK,
  /** The step failed (the log has the cause). */
  FAILED,
  /** The step does not apply to the table: retention for keepForever, archive unless the retention archives. */
  SKIPPED
}
