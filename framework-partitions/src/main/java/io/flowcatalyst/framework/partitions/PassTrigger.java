package io.flowcatalyst.framework.partitions;

/** What ran a pass. */
public enum PassTrigger {
  /** The background loop's pass. */
  LOOP,
  /** A pass an operator ran ({@link Partitions#runPass}). */
  MANUAL
}
