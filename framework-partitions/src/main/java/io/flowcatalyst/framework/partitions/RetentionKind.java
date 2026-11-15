package io.flowcatalyst.framework.partitions;

/** What retention does to a partition past its time. */
public enum RetentionKind {
  /** {@link Retention#dropAfter}. */
  DROP,
  /** {@link Retention#archiveThenDropAfter}. */
  ARCHIVE_THEN_DROP,
  /** {@link Retention#keepForever}. */
  KEEP
}
