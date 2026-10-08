package io.flowcatalyst.framework.sql;

/** What a query returns. */
public enum QueryKind {
  /** At most one row: {@code Optional<Row>}; more than one at run time is an error. */
  ONE,
  /** Any number of rows: {@code List<Row>}. */
  MANY,
  /** No rows: the affected row count as {@code int}. */
  EXEC
}
