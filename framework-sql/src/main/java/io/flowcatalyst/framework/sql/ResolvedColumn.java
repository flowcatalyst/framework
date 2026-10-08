package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/**
 * A result column after the database has described it and the overrides are applied.
 *
 * @param name the column's name (its alias without overrides)
 * @param javaName the record component name
 * @param type the Java type it maps to
 * @param array whether it is a one-dimensional array ({@code List<T>})
 * @param app the application type, or null
 * @param nullability whether the column is guaranteed, checked at run time, or nullable
 */
public record ResolvedColumn(
    String name,
    String javaName,
    ValueType type,
    boolean array,
    @Nullable AppType app,
    ColumnNullability nullability) {}
