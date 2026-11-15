package io.flowcatalyst.framework.partitions;

/** The kind of a table's partition key, as the status reports it. */
public enum KeyKind {
  /** A key on an id column ({@link Key#byId}, {@link Key#byIdColumn}). */
  ID,
  /** A key on a time column ({@link Key#byColumn}, {@link Key#byDateColumn}). */
  COLUMN
}
