package io.flowcatalyst.framework.usecase.jdbc;

import java.sql.SQLException;

/**
 * Maps the current row of a result to a value, usually a record.
 *
 * @param <T> the value type
 */
@FunctionalInterface
public interface RowMapper<T> {

  /**
   * Maps one row.
   *
   * @param row the current row
   * @return the value
   * @throws SQLException on a database error
   */
  T map(Row row) throws SQLException;
}
