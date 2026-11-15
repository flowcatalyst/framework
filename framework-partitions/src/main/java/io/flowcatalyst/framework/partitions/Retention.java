package io.flowcatalyst.framework.partitions;

import java.time.Duration;

/**
 * What becomes of a partition once its whole range is older than a duration. A retention is required:
 * a {@link Table} without one does not start, because nothing is deleted that nobody wrote down.
 */
public sealed interface Retention {

  /**
   * Drops a partition when its whole range ended at least {@code after} ago. {@code after} must be at
   * least one interval ({@link Interval#span()}).
   *
   * @param after how long after its range ended a partition is dropped
   * @return the retention
   */
  static Retention dropAfter(Duration after) {
    return new Drop(after);
  }

  /**
   * Writes a partition to the named object store as gzip'd NDJSON, verifies it and drops it, once its
   * whole range ended at least {@code after} ago. Nothing is dropped unless the archive was written
   * and verified.
   *
   * @param after how long after its range ended a partition is archived and dropped
   * @param store the name of an object store of {@code ObjectStoresModule}
   * @return the retention
   */
  static Retention archiveThenDropAfter(Duration after, String store) {
    return new ArchiveThenDrop(after, store);
  }

  /**
   * Removes nothing: the decision to keep everything is written down.
   *
   * @return the retention
   */
  static Retention keepForever() {
    return new Keep();
  }

  /**
   * The retention kind the status reports.
   *
   * @return the kind
   */
  RetentionKind kind();

  /**
   * {@link #dropAfter}.
   *
   * @param after the duration
   */
  record Drop(Duration after) implements Retention {
    @Override
    public RetentionKind kind() {
      return RetentionKind.DROP;
    }

    @Override
    public String toString() {
      return "dropAfter(" + after + ")";
    }
  }

  /**
   * {@link #archiveThenDropAfter}.
   *
   * @param after the duration
   * @param store the object store's name
   */
  record ArchiveThenDrop(Duration after, String store) implements Retention {
    @Override
    public RetentionKind kind() {
      return RetentionKind.ARCHIVE_THEN_DROP;
    }

    @Override
    public String toString() {
      return "archiveThenDropAfter(" + after + ", \"" + store + "\")";
    }
  }

  /** {@link #keepForever}. */
  record Keep() implements Retention {
    @Override
    public RetentionKind kind() {
      return RetentionKind.KEEP;
    }

    @Override
    public String toString() {
      return "keepForever()";
    }
  }
}
