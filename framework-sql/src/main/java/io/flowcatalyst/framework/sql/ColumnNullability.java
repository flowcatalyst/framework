package io.flowcatalyst.framework.sql;

/** The nullness the generated code gives a result column. */
public enum ColumnNullability {
  /** Read straight from a {@code NOT NULL} table column: non-null, not checked. */
  NOT_NULL,
  /** Declared non-null with {@code !}: non-null, a null read throws. */
  CHECKED,
  /** Nullable: boxed and {@code @Nullable}. */
  NULLABLE
}
