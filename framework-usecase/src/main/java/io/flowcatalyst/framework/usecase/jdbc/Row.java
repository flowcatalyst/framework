package io.flowcatalyst.framework.usecase.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Typed access to the current row of a result, by column label. The plain getters fail with
 * {@link IllegalStateException} naming the column when it is NULL; the {@code optional...}
 * getters return empty instead.
 */
public final class Row {

  private final ResultSet resultSet;

  Row(ResultSet resultSet) {
    this.resultSet = resultSet;
  }

  /**
   * A text column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public String string(String column) throws SQLException {
    return optionalString(column).orElseThrow(() -> isNull(column));
  }

  /**
   * A nullable text column.
   *
   * @param column the column label
   * @return the value, or empty for NULL
   * @throws SQLException on a database error
   */
  public Optional<String> optionalString(String column) throws SQLException {
    return Optional.ofNullable(resultSet.getString(column));
  }

  /**
   * An integer column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public int integer(String column) throws SQLException {
    return optionalInteger(column).orElseThrow(() -> isNull(column));
  }

  /**
   * A nullable integer column.
   *
   * @param column the column label
   * @return the value, or empty for NULL
   * @throws SQLException on a database error
   */
  public OptionalInt optionalInteger(String column) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? OptionalInt.empty() : OptionalInt.of(value);
  }

  /**
   * A bigint column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public long longValue(String column) throws SQLException {
    return optionalLong(column).orElseThrow(() -> isNull(column));
  }

  /**
   * A nullable bigint column.
   *
   * @param column the column label
   * @return the value, or empty for NULL
   * @throws SQLException on a database error
   */
  public OptionalLong optionalLong(String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /**
   * A boolean column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public boolean bool(String column) throws SQLException {
    boolean value = resultSet.getBoolean(column);
    if (resultSet.wasNull()) {
      throw isNull(column);
    }
    return value;
  }

  /**
   * A numeric column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public BigDecimal decimal(String column) throws SQLException {
    BigDecimal value = resultSet.getBigDecimal(column);
    if (value == null) {
      throw isNull(column);
    }
    return value;
  }

  /**
   * A timestamptz column.
   *
   * @param column the column label
   * @return the instant
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public Instant instant(String column) throws SQLException {
    return optionalInstant(column).orElseThrow(() -> isNull(column));
  }

  /**
   * A nullable timestamptz column.
   *
   * @param column the column label
   * @return the instant, or empty for NULL
   * @throws SQLException on a database error
   */
  public Optional<Instant> optionalInstant(String column) throws SQLException {
    return Optional.ofNullable(resultSet.getObject(column, OffsetDateTime.class))
        .map(OffsetDateTime::toInstant);
  }

  /**
   * A uuid column.
   *
   * @param column the column label
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public UUID uuid(String column) throws SQLException {
    UUID value = resultSet.getObject(column, UUID.class);
    if (value == null) {
      throw isNull(column);
    }
    return value;
  }

  /**
   * A bytea column.
   *
   * @param column the column label
   * @return the bytes
   * @throws SQLException on a database error
   * @throws IllegalStateException when the value is NULL
   */
  public byte[] bytes(String column) throws SQLException {
    byte[] value = resultSet.getBytes(column);
    if (value == null) {
      throw isNull(column);
    }
    return value;
  }

  private static IllegalStateException isNull(String column) {
    return new IllegalStateException("column '" + column + "' is NULL");
  }
}
