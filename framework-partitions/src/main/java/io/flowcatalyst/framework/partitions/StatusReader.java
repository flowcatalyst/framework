package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** The status read (contract section 10): at most five catalog queries per table, never a row count. */
final class StatusReader {

  private StatusReader() {}

  private record ChildStat(String schema, String name, long bytes, float tuples) {}

  /**
   * One table's status: the module's two tables (existence), the attached children with sizes and
   * estimates, the manifest summary, the detached tables and the last pass.
   */
  static TableStatus read(Core core, Target tg) throws SQLException {
    Instant now = core.clock.instant();
    try (Connection c = tg.db().dataSource().getConnection()) {
      Passes.Aux aux = Passes.aux(c);
      List<ChildStat> stats = new ArrayList<>();
      try (var ps =
              c.prepareStatement(
                  """
                  SELECT n.nspname::text, c.relname::text, pg_total_relation_size(c.oid)::bigint, c.reltuples
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE i.inhparent = ?::regclass""")) {
        ps.setString(1, tg.ident());
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            // The size is NULL when the partition was dropped or detached after the catalog row was read
            // (a pass running at the same time): it is no longer a partition, so it is left out.
            long size = rs.getLong(3);
            if (rs.wasNull()) {
              continue;
            }
            stats.add(new ChildStat(rs.getString(1), rs.getString(2), size, rs.getFloat(4)));
          }
        }
      }
      List<Catalog.Child> kids = new ArrayList<>();
      for (ChildStat s : stats) {
        kids.add(new Catalog.Child(s.schema(), s.name()));
      }
      Catalog.Ours ours = Catalog.ours(tg, kids);

      Map<String, ArchivedPartition> archived = aux.manifest() ? manifestSummary(c, tg) : Map.of();
      List<String> detached = Catalog.detachedTables(c, tg);
      Optional<LastPass> last = aux.passes() ? Passes.read(c, tg, aux.trigger()) : Optional.empty();

      List<PartitionStatus> partitions = new ArrayList<>();
      long bytes = 0;
      long rowsKnown = 0;
      int withoutEstimate = 0;
      for (ChildStat s : stats) {
        Instant start = ours.starts().get(s.name());
        if (start == null || !s.schema().equals(tg.schema)) {
          continue;
        }
        Instant to = tg.interval().next(start);
        Position position;
        if (to.isAfter(now) && !start.isAfter(now)) {
          position = Position.CURRENT;
        } else if (start.isAfter(now)) {
          position = Position.FUTURE;
        } else {
          position = Position.PAST;
        }
        OptionalLong rows;
        if (s.tuples() >= 0) {
          long n = (long) s.tuples();
          rows = OptionalLong.of(n);
          rowsKnown += n;
        } else {
          rows = OptionalLong.empty();
          withoutEstimate++;
        }
        partitions.add(
            new PartitionStatus(
                s.name(),
                start,
                to,
                position,
                s.bytes(),
                rows,
                tg.table().removalDue(start),
                Optional.ofNullable(archived.get(s.name()))));
        bytes += s.bytes();
      }
      OptionalLong rows =
          partitions.isEmpty() || withoutEstimate < partitions.size()
              ? OptionalLong.of(rowsKnown)
              : OptionalLong.empty();
      partitions.sort(Comparator.comparing(PartitionStatus::from));
      List<String> detachedNames = new ArrayList<>();
      for (String name : detached) {
        detachedNames.add(tg.schema + "." + name);
      }
      detachedNames.sort(null);
      List<String> strays = new ArrayList<>();
      for (Catalog.Child s : ours.strays()) {
        strays.add(s.schema() + "." + s.name());
      }
      strays.sort(null);
      return new TableStatus(
          tg.qualified,
          tg.table().database(),
          tg.spec.key(),
          tg.interval(),
          tg.spec.ahead(),
          retention(tg),
          nextPartitionProblem(tg, kids, now).isEmpty(),
          last,
          partitions,
          detachedNames,
          strays,
          bytes,
          rows,
          withoutEstimate);
    }
  }

  static RetentionStatus retention(Target tg) {
    return switch (tg.spec.retention()) {
      case Retention.Drop d -> new RetentionStatus(RetentionKind.DROP, d.after(), "");
      case Retention.ArchiveThenDrop a ->
          new RetentionStatus(RetentionKind.ARCHIVE_THEN_DROP, a.after(), a.store());
      case Retention.Keep k -> new RetentionStatus(RetentionKind.KEEP, Duration.ZERO, "");
    };
  }

  /**
   * The decision of the health check {@code partitions:<table>}, which the status read reports as
   * {@code healthy}: empty when the partition for the next interval is attached, otherwise the reason.
   */
  static Optional<String> nextPartitionProblem(Target tg, List<Catalog.Child> kids, Instant now) {
    Instant next = tg.interval().next(tg.interval().start(now));
    String want = PartitionNames.name(tg.rel, next);
    boolean present = kids.stream().anyMatch(c -> c.name().equals(want));
    if (present) {
      return Optional.empty();
    }
    return Optional.of(
        "the partition for the next interval (from "
            + next.toString().substring(0, 10)
            + ") does not exist; inserts will fail at the boundary");
  }

  /** One query: the archived partitions of the table, with objects, rows, bytes and the latest archive time. */
  private static Map<String, ArchivedPartition> manifestSummary(Connection c, Target tg) throws SQLException {
    Map<String, ArchivedPartition> out = new HashMap<>();
    Sql.query(
        c,
        """
        SELECT partition_name, count(*)::int, sum(row_count)::bigint, sum(byte_count)::bigint, max(archived_at)
        FROM fw_partition_archives WHERE table_name = ? GROUP BY partition_name""",
        rs -> {
          out.put(
              rs.getString(1),
              new ArchivedPartition(
                  rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getObject(5, OffsetDateTime.class).toInstant()));
          return 0;
        },
        tg.qualified);
    return out;
  }

  /** The archived partitions of a table from the manifest, newest first, one query. */
  static List<ArchiveRecord> archives(Target tg, String after, int limit) throws SQLException {
    try (Connection c = tg.db().dataSource().getConnection()) {
      if (!Passes.aux(c).manifest()) {
        return List.of();
      }
      return Sql.query(
          c,
          """
          SELECT partition_name, count(*)::int, sum(row_count)::bigint, sum(byte_count)::bigint, max(archived_at),
                 coalesce(max(object_key) FILTER (WHERE part = 1), '')
          FROM fw_partition_archives WHERE table_name = ? AND (? = '' OR partition_name < ?)
          GROUP BY partition_name ORDER BY partition_name DESC LIMIT ?""",
          rs -> record(tg, rs),
          tg.qualified,
          after,
          after,
          limit);
    }
  }

  private static ArchiveRecord record(Target tg, ResultSet rs) throws SQLException {
    String partition = rs.getString(1);
    Optional<Instant> from = tg.parse(partition);
    return new ArchiveRecord(
        tg.qualified,
        partition,
        from,
        from.map(tg.interval()::next),
        rs.getInt(2),
        rs.getLong(3),
        rs.getLong(4),
        rs.getObject(5, OffsetDateTime.class).toInstant(),
        rs.getString(6));
  }
}
