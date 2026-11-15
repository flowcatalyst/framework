package io.flowcatalyst.framework.partitions;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a table is partitioned on (contract section 2): its framework id, a {@code timestamptz}
 * column or a {@code date} column. A key is a value: the bound of an instant is a pure function of
 * the key and the instant.
 */
public final class Key {

  /** The number of low TSID bits below the milliseconds: 12 of sequence and 10 random. */
  private static final int TSID_INSTANT_SHIFT = 22;

  private static final DateTimeFormatter TIMESTAMPTZ =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);

  private enum Form {
    ID,
    COLUMN,
    DATE
  }

  private final Form form;
  private final String column;
  private final String prefix;

  private Key(Form form, String column, String prefix) {
    this.form = form;
    this.column = column;
    this.prefix = prefix;
  }

  /**
   * Partitions on the id column {@code id}, whose values are {@code <prefix>_<TSID>}: the primary key
   * stays {@code id} and a lookup by id prunes to one partition. The column must be declared
   * {@code COLLATE "C"}.
   *
   * @param prefix the id prefix, {@code [a-z][a-z0-9]*}
   * @return the key
   */
  public static Key byId(String prefix) {
    return byIdColumn("id", prefix);
  }

  /**
   * {@link #byId} on a column other than {@code id}.
   *
   * @param column the column, {@code [a-z_][a-z0-9_]*}
   * @param prefix the id prefix
   * @return the key
   */
  public static Key byIdColumn(String column, String prefix) {
    return new Key(Form.ID, column, prefix);
  }

  /**
   * Partitions on a {@code timestamptz} column (every unique constraint of the table must include it,
   * which PostgreSQL enforces).
   *
   * @param column the column
   * @return the key
   */
  public static Key byColumn(String column) {
    return new Key(Form.COLUMN, column, "");
  }

  /**
   * Partitions on a {@code date} column (a service day, a business date): the bounds are date
   * literals. A {@code date} column is declared with this key and a {@code timestamptz} column with
   * {@link #byColumn}; start-up refuses the other pairing.
   *
   * @param column the column
   * @return the key
   */
  public static Key byDateColumn(String column) {
    return new Key(Form.DATE, column, "");
  }

  /**
   * The kind of the key.
   *
   * @return {@link KeyKind#ID} for {@link #byId} and {@link #byIdColumn}, {@link KeyKind#COLUMN}
   *     for {@link #byColumn} and {@link #byDateColumn}
   */
  public KeyKind kind() {
    return form == Form.ID ? KeyKind.ID : KeyKind.COLUMN;
  }

  /**
   * The type of the column of a {@link KeyKind#COLUMN} key.
   *
   * @return {@link KeyColumnType#TIMESTAMPTZ} for {@link #byColumn}, {@link KeyColumnType#DATE} for
   *     {@link #byDateColumn}, empty for an id key
   */
  public java.util.Optional<KeyColumnType> columnType() {
    return switch (form) {
      case ID -> java.util.Optional.empty();
      case COLUMN -> java.util.Optional.of(KeyColumnType.TIMESTAMPTZ);
      case DATE -> java.util.Optional.of(KeyColumnType.DATE);
    };
  }

  /**
   * The partition key column.
   *
   * @return the column
   */
  public String column() {
    return column;
  }

  /**
   * The id prefix of a {@link #byId} key.
   *
   * @return the prefix, empty for a column key
   */
  public String prefix() {
    return prefix;
  }

  /**
   * Whether this is an id key.
   *
   * @return true for {@link #byId} and {@link #byIdColumn}
   */
  public boolean isId() {
    return form == Form.ID;
  }

  boolean isDate() {
    return form == Form.DATE;
  }

  /**
   * The text of the partition bound for an instant, as the SQL literal inside {@code FOR VALUES FROM
   * ('...') TO ('...')}: for an id key {@code prefix_} plus the TSID of {@code milliseconds << 22}
   * (the smallest id that carries that millisecond; an instant before 1970 is bound as 1970-01-01);
   * for {@link #byColumn} the instant in UTC as {@code 2026-11-01 00:00:00+00}; for {@link
   * #byDateColumn} the UTC date as {@code 2026-11-01}. A bound is inclusive as a lower bound and
   * exclusive as an upper one.
   *
   * @param t the instant, an interval start
   * @return the literal
   */
  public String bound(Instant t) {
    return switch (form) {
      case ID -> idBound(prefix, t);
      case DATE -> DATE.format(t);
      case COLUMN -> TIMESTAMPTZ.format(t) + "+00";
    };
  }

  /**
   * The id bound of an instant: {@code prefix + "_" + TSID(milliseconds << 22)}.
   *
   * @param prefix the id prefix
   * @param t the instant
   * @return the bound
   */
  public static String idBound(String prefix, Instant t) {
    long ms = Math.max(t.toEpochMilli(), 0L);
    return prefix + "_" + Tsid.encode(ms << TSID_INSTANT_SHIFT);
  }

  /**
   * The id bounds of a time window for a query on an id-keyed table: {@code WHERE id >= lo AND id <
   * hi} selects the rows whose id carries a millisecond in {@code [from, to)} and prunes to the
   * partitions that overlap the window. Never filter with a function of the id: it defeats pruning.
   *
   * @param prefix the id prefix
   * @param from the start, inclusive
   * @param to the end, exclusive
   * @return the two bounds
   */
  public static IdRange idRange(String prefix, Instant from, Instant to) {
    return new IdRange(idBound(prefix, from), idBound(prefix, to));
  }

  /**
   * The two id bounds of a time window.
   *
   * @param lo the lower bound, inclusive
   * @param hi the upper bound, exclusive
   */
  public record IdRange(String lo, String hi) {}

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof Key k && form == k.form && column.equals(k.column) && prefix.equals(k.prefix);
  }

  @Override
  public int hashCode() {
    return Objects.hash(form, column, prefix);
  }

  /** The declaration as text, for problems and logs: {@code ByID("rdg") on id}. */
  @Override
  public String toString() {
    return switch (form) {
      case ID -> "ByID(\"" + prefix + "\") on " + column;
      case COLUMN -> "ByColumn(\"" + column + "\")";
      case DATE -> "ByDateColumn(\"" + column + "\")";
    };
  }
}
