package io.flowcatalyst.framework.partitions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Partition names and the advisory lock key (contract section 2): pure functions. */
public final class PartitionNames {

  /** PostgreSQL's identifier limit in bytes. */
  static final int MAX_IDENTIFIER = 63;

  /** The length of {@code _p20261101}. */
  static final int SUFFIX_LENGTH = 10;

  private static final Pattern SUFFIX = Pattern.compile("^_p(\\d{8})$");
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
  private static final String LOCK_SALT = "fw-partitions:";

  private PartitionNames() {}

  /**
   * The name of the partition of a table whose interval starts at {@code start}:
   * {@code <table>_p<YYYYMMDD>}, the same shape for every interval so names sort in time order. The
   * partition is created in the schema of the parent.
   *
   * @param table the unqualified table name
   * @param start the interval start
   * @return the name
   */
  public static String name(String table, Instant start) {
    return table + "_p" + DAY.format(start.atOffset(ZoneOffset.UTC));
  }

  /**
   * The interval start a name carries for the table, when it is one of this framework's names for
   * it: another shape, a date that is not a start of the interval, or another table's name that
   * merely begins the same way is not.
   *
   * @param table the unqualified table name
   * @param interval the declared interval
   * @param name a relation name
   * @return the start, or empty
   */
  public static Optional<Instant> parse(String table, Interval interval, String name) {
    if (name.length() != table.length() + SUFFIX_LENGTH || !name.startsWith(table)) {
      return Optional.empty();
    }
    Matcher m = SUFFIX.matcher(name.substring(table.length()));
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      Instant start = LocalDate.parse(m.group(1), DAY).atStartOfDay().toInstant(ZoneOffset.UTC);
      return interval.start(start).equals(start) ? Optional.of(start) : Optional.empty();
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * The advisory lock key that serialises the creation of partitions of one table: the FNV-1a 64-bit
   * hash (offset 14695981039346656037, prime 1099511628211) of the UTF-8 bytes of
   * {@code fw-partitions:<schema>.<table>}, as a signed 64-bit integer, passed to
   * {@code pg_advisory_xact_lock(bigint)}.
   *
   * @param schema the resolved schema
   * @param table the resolved table
   * @return the key
   */
  public static long lockKey(String schema, String table) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : (LOCK_SALT + schema + "." + table).getBytes(StandardCharsets.UTF_8)) {
      hash ^= b & 0xFFL;
      hash *= 0x100000001b3L;
    }
    return hash;
  }
}
