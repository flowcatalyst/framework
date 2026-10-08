package io.flowcatalyst.framework.usecase.jdbc;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.usecase.AggregateId;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A small typed JDBC helper (write-path contract, section 9): prepare, bind typed values, map
 * rows to records. Used for reads outside use cases and inside repositories' explicit SQL.
 *
 * <p>Parameters are bound by position from the values given. Supported: {@code String},
 * {@code Integer}, {@code Long}, {@code Short}, {@code Boolean}, {@code Double},
 * {@code BigDecimal}, {@code Instant} (as timestamptz), {@code UUID}, {@code byte[]}, enums (by
 * name), {@link AggregateId}, {@link ClientId}, {@link PrincipalId}, {@link CorrelationId}, and
 * {@link SqlNull} for a typed NULL. Anything else is an {@link IllegalArgumentException}.
 *
 * <p>The overloads taking a {@link DataSource} borrow a connection for the call and return it;
 * the ones taking a {@link Connection} (for example a repository's {@code tx.connection()}) use
 * it as given.
 */
public final class Jdbc {

  private Jdbc() {}

  /**
   * A typed SQL NULL parameter.
   *
   * @param sqlType a {@link java.sql.Types} constant
   * @return the parameter
   */
  public static SqlNull nullOf(int sqlType) {
    return new SqlNull(sqlType);
  }

  /**
   * Runs a query and maps every row.
   *
   * @param <T> the row type
   * @param dataSource where to run it
   * @param sql the SQL, with {@code ?} parameters
   * @param mapper maps one row
   * @param params the parameter values
   * @return the rows, in order
   * @throws SQLException on a database error
   */
  public static <T> List<T> query(
      DataSource dataSource, String sql, RowMapper<T> mapper, Object... params)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return query(connection, sql, mapper, params);
    }
  }

  /**
   * Runs a query on a connection and maps every row.
   *
   * @param <T> the row type
   * @param connection the connection
   * @param sql the SQL, with {@code ?} parameters
   * @param mapper maps one row
   * @param params the parameter values
   * @return the rows, in order
   * @throws SQLException on a database error
   */
  public static <T> List<T> query(
      Connection connection, String sql, RowMapper<T> mapper, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, params);
      try (ResultSet resultSet = statement.executeQuery()) {
        Row row = new Row(resultSet);
        List<T> out = new ArrayList<>();
        while (resultSet.next()) {
          out.add(mapper.map(row));
        }
        return out;
      }
    }
  }

  /**
   * Runs a query expected to return at most one row.
   *
   * @param <T> the row type
   * @param dataSource where to run it
   * @param sql the SQL, with {@code ?} parameters
   * @param mapper maps the row
   * @param params the parameter values
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   * @throws IllegalStateException when the query returns more than one row
   */
  public static <T> Optional<T> queryOne(
      DataSource dataSource, String sql, RowMapper<T> mapper, Object... params)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return queryOne(connection, sql, mapper, params);
    }
  }

  /**
   * Runs a query on a connection, expected to return at most one row.
   *
   * @param <T> the row type
   * @param connection the connection
   * @param sql the SQL, with {@code ?} parameters
   * @param mapper maps the row
   * @param params the parameter values
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   * @throws IllegalStateException when the query returns more than one row
   */
  public static <T> Optional<T> queryOne(
      Connection connection, String sql, RowMapper<T> mapper, Object... params)
      throws SQLException {
    List<T> rows = query(connection, sql, mapper, params);
    if (rows.size() > 1) {
      throw new IllegalStateException("expected at most one row but the query returned " + rows.size());
    }
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /**
   * Runs an INSERT, UPDATE or DELETE.
   *
   * @param dataSource where to run it (auto-commit)
   * @param sql the SQL, with {@code ?} parameters
   * @param params the parameter values
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int update(DataSource dataSource, String sql, Object... params)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return update(connection, sql, params);
    }
  }

  /**
   * Runs an INSERT, UPDATE or DELETE on a connection, for example a repository's transaction.
   *
   * @param connection the connection
   * @param sql the SQL, with {@code ?} parameters
   * @param params the parameter values
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int update(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, params);
      return statement.executeUpdate();
    }
  }

  /**
   * Binds parameter values by position, starting at 1.
   *
   * @param statement the statement
   * @param params the values
   * @throws SQLException on a database error
   * @throws IllegalArgumentException for an unsupported type
   */
  public static void bind(PreparedStatement statement, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      int index = i + 1;
      switch (params[i]) {
        case String v -> statement.setString(index, v);
        case Integer v -> statement.setInt(index, v);
        case Long v -> statement.setLong(index, v);
        case Short v -> statement.setShort(index, v);
        case Boolean v -> statement.setBoolean(index, v);
        case Double v -> statement.setDouble(index, v);
        case BigDecimal v -> statement.setBigDecimal(index, v);
        case Instant v -> statement.setObject(index, OffsetDateTime.ofInstant(v, ZoneOffset.UTC));
        case UUID v -> statement.setObject(index, v);
        case byte[] v -> statement.setBytes(index, v);
        case Enum<?> v -> statement.setString(index, v.name());
        case AggregateId v -> statement.setString(index, v.value());
        case ClientId v -> statement.setString(index, v.value());
        case PrincipalId v -> statement.setString(index, v.value());
        case CorrelationId v -> statement.setString(index, v.value());
        case SqlNull v -> statement.setNull(index, v.sqlType());
        default ->
            throw new IllegalArgumentException(
                "parameter " + index + " has unsupported type " + params[i].getClass().getName());
      }
    }
  }
}
