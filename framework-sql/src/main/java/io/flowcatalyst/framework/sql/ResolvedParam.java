package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/**
 * A parameter after the database has described it.
 *
 * @param name the name in the query
 * @param javaName the Java parameter name
 * @param type the Java type it maps to
 * @param array whether it is a one-dimensional array ({@code List<T>})
 * @param pgType the database's type name (the element type for arrays), used to build arrays
 * @param app the application type, or null
 * @param nullable whether the Java parameter may be null
 */
public record ResolvedParam(
    String name,
    String javaName,
    ValueType type,
    boolean array,
    String pgType,
    @Nullable AppType app,
    boolean nullable) {}
