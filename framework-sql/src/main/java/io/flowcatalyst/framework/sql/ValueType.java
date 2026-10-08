package io.flowcatalyst.framework.sql;

/** The Java type a PostgreSQL type maps to (checked-SQL contract, section 2). */
public enum ValueType {
  /** {@code text}, {@code varchar}, {@code char}: {@code String}. */
  STRING,
  /** {@code int2}, {@code int4}: {@code int}. */
  INT,
  /** {@code int8}: {@code long}. */
  LONG,
  /** {@code bool}: {@code boolean}. */
  BOOLEAN,
  /** {@code numeric}: {@code BigDecimal}. */
  BIG_DECIMAL,
  /** {@code float4}: {@code float}. */
  FLOAT,
  /** {@code float8}: {@code double}. */
  DOUBLE,
  /** {@code timestamptz}: {@code Instant}. */
  INSTANT,
  /** {@code timestamp}: {@code LocalDateTime}. */
  LOCAL_DATE_TIME,
  /** {@code date}: {@code LocalDate}. */
  LOCAL_DATE,
  /** {@code uuid}: {@code UUID}. */
  UUID,
  /** {@code bytea}: {@code byte[]}. */
  BYTES,
  /** {@code json}, {@code jsonb}: {@code String} holding the JSON text. */
  JSON
}
