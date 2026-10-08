package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/**
 * A {@code -- column:} header line: the override of a result column, for when the override would
 * not fit in a column alias (PostgreSQL cuts identifiers at 63 bytes).
 *
 * @param name the column's name (its alias, without overrides)
 * @param nullness the nullness override
 * @param type the application type, or null to use the database's type
 * @param enumType whether {@code type} is an enum stored as its name
 * @param line the 1-based line of the declaration
 */
public record ColumnDecl(
    String name, Nullness nullness, @Nullable String type, boolean enumType, int line) {}
