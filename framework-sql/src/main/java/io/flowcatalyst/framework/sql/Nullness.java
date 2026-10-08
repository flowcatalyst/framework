package io.flowcatalyst.framework.sql;

/** What a result column promises. */
public enum Nullness {
  /** No override: decided from the database's description. */
  DEFAULT,
  /** {@code !}: declared non-null by the author, checked at run time. */
  NON_NULL,
  /** {@code ?}: declared nullable by the author. */
  NULLABLE
}
