package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/**
 * A {@code -- param:} header line.
 *
 * @param name the parameter name as written in the query, without the colon
 * @param nullable whether the Java parameter may be null
 * @param type the application type, or null to use the database's type
 * @param enumType whether {@code type} is an enum stored as its name
 * @param line the 1-based line of the declaration
 */
public record ParamDecl(
    String name, boolean nullable, @Nullable String type, boolean enumType, int line) {}
