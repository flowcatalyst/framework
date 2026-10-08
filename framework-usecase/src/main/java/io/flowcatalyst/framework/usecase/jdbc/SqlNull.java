package io.flowcatalyst.framework.usecase.jdbc;

/**
 * A typed SQL NULL to bind. Plain {@code null} is not accepted as a parameter: the database
 * needs to know the type.
 *
 * @param sqlType a {@link java.sql.Types} constant
 */
public record SqlNull(int sqlType) {}
