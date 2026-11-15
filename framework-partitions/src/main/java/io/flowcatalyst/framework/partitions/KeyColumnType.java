package io.flowcatalyst.framework.partitions;

/** The type of the column of a {@link KeyKind#COLUMN} key. */
public enum KeyColumnType {
  /** The column of {@link Key#byColumn}. */
  TIMESTAMPTZ,
  /** The column of {@link Key#byDateColumn}. */
  DATE
}
