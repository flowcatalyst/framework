package io.flowcatalyst.framework.sql;

/**
 * An application type a column or parameter maps to.
 *
 * @param className the fully qualified class name, as written in the query file
 * @param enumType true for an enum stored as its {@code name()}; false for a one-component record
 *     with a component named {@code value}
 */
public record AppType(String className, boolean enumType) {}
