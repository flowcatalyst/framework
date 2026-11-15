package io.flowcatalyst.framework.partitions;

/** Where a partition is against the module's clock. */
public enum Position {
  /** The partition's range ended. */
  PAST,
  /** The partition contains now. */
  CURRENT,
  /** The partition starts after now. */
  FUTURE
}
