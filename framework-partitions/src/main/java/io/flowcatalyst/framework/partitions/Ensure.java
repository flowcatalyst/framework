package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The ensure pass: create the missing partitions (contract section 4). */
final class Ensure {

  private Ensure() {}

  /**
   * Creates the partitions of the interval that contains {@code now} and the next {@code ahead} ones.
   *
   * @return how many it created
   */
  static int current(Core core, Target tg, Instant now) throws SQLException {
    List<Instant> starts = new ArrayList<>();
    Instant s = tg.interval().start(now);
    for (int i = 0; i <= tg.spec.ahead(); i++) {
      starts.add(s);
      s = tg.interval().next(s);
    }
    int created = starts(tg, starts);
    setAhead(tg, now);
    return created;
  }

  /**
   * Creates the missing partitions for the interval starts, in one transaction that holds the table's
   * advisory lock, so replicas starting together do not race on the catalog. Never a DEFAULT partition.
   */
  static int starts(Target tg, List<Instant> starts) throws SQLException {
    try (Connection c = tg.db().dataSource().getConnection()) {
      c.setAutoCommit(false);
      try {
        int created = createMissing(c, tg, starts);
        c.commit();
        return created;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static int createMissing(Connection c, Target tg, List<Instant> starts) throws SQLException {
    Sql.execute(c, Sql.setLockTimeout());
    Sql.query(c, Sql.advisoryLock(), rs -> 0, PartitionNames.lockKey(tg.schema, tg.rel));
    Set<String> have = new HashSet<>();
    for (Catalog.Child child : Catalog.children(c, tg)) {
      if (child.schema().equals(tg.schema)) {
        have.add(child.name());
      }
    }
    int created = 0;
    for (Instant start : starts) {
      String name = PartitionNames.name(tg.rel, start);
      if (have.contains(name)) {
        continue;
      }
      // CREATE TABLE IF NOT EXISTS ... PARTITION OF does nothing when a table of that name exists, even one
      // that is not attached: a detached leftover in a current or future range must be loud.
      boolean exists = Sql.queryOne(c, Sql.relationExists(), rs -> rs.getBoolean(1), tg.childIdent(name));
      if (exists) {
        throw new PartitionRefusedException(
            PartitionRefusedException.Reason.DETACHED_LEFTOVER,
            "a detached table has the name of a partition: table "
                + tg.childIdent(name)
                + " exists but is not attached as a partition of "
                + tg.ident()
                + " (a detached leftover?); attach it, or archive and drop it, so inserts for its range have a partition");
      }
      Key key = tg.spec.key();
      Sql.execute(
          c,
          Sql.createPartition(
              tg.childIdent(name),
              tg.ident(),
              key.bound(start),
              key.bound(tg.interval().next(start))));
      created++;
      Log.info("partition created", "table", tg.qualified, "partition", name);
    }
    return created;
  }

  /** Updates the gauge from the catalog: the partitions that start after the current interval. */
  static void setAhead(Target tg, Instant now) {
    try (Connection c = tg.db().dataSource().getConnection()) {
      Catalog.Ours ours = Catalog.ours(tg, Catalog.children(c, tg));
      Instant cur = tg.interval().start(now);
      long n = ours.starts().values().stream().filter(s -> s.isAfter(cur)).count();
      tg.ahead.set((double) n);
    } catch (SQLException e) {
      // the gauge keeps its last value; the pass itself succeeded
    }
  }
}
