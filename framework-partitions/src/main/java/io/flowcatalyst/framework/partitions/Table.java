package io.flowcatalyst.framework.partitions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Declares one partitioned table. Which tables are partitioned and how long they are kept are facts of
 * the application, the same in every deployment, so they are code (config-and-code contract): there is
 * no configuration for them. A declaration is checked when the application starts, all problems
 * together, so a missing part is a start-up error rather than a constructor failure.
 *
 * <pre>{@code
 * Table.named("readings")
 *     .by(Key.byId("rdg"))
 *     .interval(Interval.MONTH)
 *     .ahead(3)
 *     .retention(Retention.dropAfter(Duration.ofDays(90)))
 * }</pre>
 *
 * @param name the table, optionally schema-qualified ({@code readings}, {@code telemetry.readings}); each
 *     part {@code [a-z_][a-z0-9_]*}
 * @param database a database instance name; empty is the default instance
 * @param by the partition key, or null when not given
 * @param interval the width of a partition, or null when not given
 * @param ahead how many partitions beyond the current one are kept created, 1 to 24
 * @param retention what becomes of old partitions, or null when not given (a start-up error)
 */
public record Table(
    String name,
    String database,
    @Nullable Key by,
    @Nullable Interval interval,
    int ahead,
    @Nullable Retention retention) {

  /** The fewest partitions ahead. */
  public static final int AHEAD_MIN = 1;

  /** The most partitions ahead. */
  public static final int AHEAD_MAX = 24;

  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");
  private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9]*");

  /**
   * Starts a declaration of a table on the default database instance.
   *
   * @param name the table, optionally schema-qualified
   * @return a declaration with nothing else set
   */
  public static Table named(String name) {
    return new Table(name, "", null, null, 0, null);
  }

  /**
   * Names the database instance of the table.
   *
   * @param instance an instance under {@code database.instances}; empty is the default
   * @return a copy
   */
  public Table database(String instance) {
    return new Table(name, instance, by, interval, ahead, retention);
  }

  /**
   * Sets the partition key.
   *
   * @param key {@link Key#byId}, {@link Key#byColumn} or {@link Key#byDateColumn}
   * @return a copy
   */
  public Table by(Key key) {
    return new Table(name, database, key, interval, ahead, retention);
  }

  /**
   * Sets the interval.
   *
   * @param value the width of a partition
   * @return a copy
   */
  public Table interval(Interval value) {
    return new Table(name, database, by, value, ahead, retention);
  }

  /**
   * Sets how many partitions beyond the current one are kept created.
   *
   * @param value 1 to 24
   * @return a copy
   */
  public Table ahead(int value) {
    return new Table(name, database, by, interval, value, retention);
  }

  /**
   * Sets the retention.
   *
   * @param value what becomes of old partitions
   * @return a copy
   */
  public Table retention(Retention value) {
    return new Table(name, database, by, interval, ahead, value);
  }

  /** The schema part of {@link #name()}, empty when not qualified; empty also when the name is invalid. */
  Optional<String> schemaPart() {
    return split(name).map(p -> p[0]);
  }

  /** The unqualified table of {@link #name()}. */
  Optional<String> tablePart() {
    return split(name).map(p -> p[1]);
  }

  /** {@code [schema, table]} (schema empty when not given), empty for an invalid name. */
  private static Optional<String[]> split(String name) {
    String[] parts = name.split("\\.", -1);
    if (parts.length == 1 && IDENTIFIER.matcher(parts[0]).matches()) {
      return Optional.of(new String[] {"", parts[0]});
    }
    if (parts.length == 2
        && IDENTIFIER.matcher(parts[0]).matches()
        && IDENTIFIER.matcher(parts[1]).matches()) {
      return Optional.of(new String[] {parts[0], parts[1]});
    }
    return Optional.empty();
  }

  /**
   * Every problem of the declaration that can be found without the database.
   *
   * @return the problems, empty when the declaration is sound
   */
  List<String> check() {
    List<String> out = new ArrayList<>();
    Optional<String> table = tablePart();
    if (table.isEmpty()) {
      out.add("name \"" + name + "\" must be [schema.]table, each part matching [a-z_][a-z0-9_]*");
    } else {
      int length = table.get().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + PartitionNames.SUFFIX_LENGTH;
      if (length > PartitionNames.MAX_IDENTIFIER) {
        out.add(
            "table name \""
                + table.get()
                + "\" is too long: its partition names ("
                + table.get()
                + "_pYYYYMMDD) would be "
                + length
                + " bytes, over PostgreSQL's "
                + PartitionNames.MAX_IDENTIFIER);
      }
    }
    Key key = by;
    if (key == null) {
      out.add("no partition key: use Key.byId, Key.byColumn or Key.byDateColumn");
    } else {
      if (key.isId() && !PREFIX.matcher(key.prefix()).matches()) {
        out.add("byId prefix \"" + key.prefix() + "\" must match [a-z][a-z0-9]*");
      }
      if (!IDENTIFIER.matcher(key.column()).matches()) {
        out.add("key column \"" + key.column() + "\" must match [a-z_][a-z0-9_]*");
      }
    }
    if (interval == null) {
      out.add("no interval: use Interval.DAY, WEEK, MONTH or YEAR");
    }
    if (ahead < AHEAD_MIN || ahead > AHEAD_MAX) {
      out.add("ahead must be " + AHEAD_MIN + " to " + AHEAD_MAX + ", was " + ahead);
    }
    Retention r = retention;
    switch (r) {
      case null ->
          out.add(
              "no retention: say what becomes of old partitions with Retention.dropAfter, archiveThenDropAfter or keepForever");
      case Retention.Drop d -> checkAfter(out, d.after());
      case Retention.ArchiveThenDrop a -> {
        checkAfter(out, a.after());
        if (a.store().isEmpty()) {
          out.add("archiveThenDropAfter needs the name of an object store");
        }
      }
      case Retention.Keep k -> {}
    }
    return out;
  }

  private void checkAfter(List<String> out, Duration after) {
    Interval iv = interval;
    if (iv != null && after.compareTo(iv.span()) < 0) {
      out.add("retention " + after + " is shorter than one " + iv.word() + " interval (" + iv.span() + ")");
    }
  }

  /**
   * The first instant at which {@link #due} is true for the partition that starts at {@code start}: its
   * upper bound plus the longer of the retention and one interval (the skew guard).
   *
   * @param start the partition's interval start
   * @return the instant, empty for {@link Retention#keepForever}, which is never due
   */
  Optional<Instant> removalDue(Instant start) {
    Duration after = retentionAfter();
    Interval iv = interval;
    if (after == null || iv == null) {
      return Optional.empty();
    }
    Duration longer = after.compareTo(iv.span()) >= 0 ? after : iv.span();
    return Optional.of(iv.next(start).plus(longer));
  }

  /**
   * Whether the partition that starts at {@code start} may be removed at {@code now}: its whole range
   * ended at least the retention ago and, whatever the retention says, at least one interval ago. The
   * second condition guards against a skewed clock or a mis-set retention: the current and future
   * partitions are never due (contract section 5).
   *
   * @param start the partition's interval start
   * @param now the instant
   * @return true when due
   */
  boolean due(Instant start, Instant now) {
    Duration after = retentionAfter();
    Interval iv = interval;
    if (after == null || iv == null) {
      return false;
    }
    Instant upper = iv.next(start);
    if (upper.isAfter(now.minus(after))) {
      return false;
    }
    return !upper.isAfter(now.minus(iv.span()));
  }

  private @Nullable Duration retentionAfter() {
    return switch (retention) {
      case Retention.Drop d -> d.after();
      case Retention.ArchiveThenDrop a -> a.after();
      case Retention.Keep k -> null;
      case null -> null;
    };
  }

  boolean archives() {
    return retention instanceof Retention.ArchiveThenDrop;
  }
}
