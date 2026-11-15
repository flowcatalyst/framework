package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The last-pass record shared across replicas (contract section 11). */
final class Passes {

  /** Who started a pass: the trigger, and for a manual pass the acting principal's id (empty when none). */
  record Origin(PassTrigger trigger, String actor) {
    static final Origin LOOP = new Origin(PassTrigger.LOOP, "");
  }

  /**
   * What exists on a table's database instance of the module's own tables.
   *
   * @param manifest {@code fw_partition_archives} exists
   * @param passes {@code fw_partition_passes} exists
   * @param trigger the passes table has {@code pass_trigger} and {@code actor} (V0003)
   */
  record Aux(boolean manifest, boolean passes, boolean trigger) {}

  private Passes() {}

  /**
   * Whether the module's two tables exist on the instance, and whether the passes table has the two origin
   * columns, in one query. The migrations create them on the default instance only.
   */
  static Aux aux(Connection c) throws SQLException {
    return Sql.queryOne(
        c,
        """
        SELECT to_regclass('fw_partition_archives') IS NOT NULL, to_regclass('fw_partition_passes') IS NOT NULL,
        EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('fw_partition_passes') AND attname = 'pass_trigger' AND NOT attisdropped)""",
        rs -> new Aux(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)));
  }

  /** The three statuses of a pass for the record: SKIPPED when a step does not apply to the table. */
  static StepStatus[] stepStatuses(Target tg, PassResult res) {
    RetentionKind kind = tg.spec.retention().kind();
    return new StepStatus[] {
      pick(res, Step.ENSURE, true),
      pick(res, Step.RETENTION, kind != RetentionKind.KEEP),
      pick(res, Step.ARCHIVE, kind == RetentionKind.ARCHIVE_THEN_DROP)
    };
  }

  private static StepStatus pick(PassResult res, Step step, boolean applies) {
    if (!applies) {
      return StepStatus.SKIPPED;
    }
    return res.failed().contains(step) ? StepStatus.FAILED : StepStatus.OK;
  }

  /**
   * Upserts the table's last pass into its own database, so every replica (and an admin section) sees the pass
   * whichever replica ran it. A failure is logged and counted, never a failure of the pass; an instance
   * without {@code fw_partition_passes} records nothing (start-up said so).
   */
  static void record(Core core, Target tg, PassResult res, Origin origin) {
    // the pass's own thread may be interrupted (shutdown): the record is still worth a try
    boolean interrupted = Thread.interrupted();
    try (Connection c = tg.db().dataSource().getConnection()) {
      Aux aux = aux(c);
      if (!aux.passes()) {
        return;
      }
      StepStatus[] st = stepStatuses(tg, res);
      OffsetDateTime at = OffsetDateTime.ofInstant(core.clock.instant(), ZoneOffset.UTC);
      @Nullable String actor = origin.actor().isEmpty() ? null : origin.actor();
      if (aux.trigger()) {
        Sql.update(
            c,
            """
            INSERT INTO fw_partition_passes
            (table_name, ended_at, ensure_status, retention_status, archive_status, created, dropped, archived, pass_trigger, actor)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (table_name) DO UPDATE SET ended_at = EXCLUDED.ended_at, ensure_status = EXCLUDED.ensure_status,
              retention_status = EXCLUDED.retention_status, archive_status = EXCLUDED.archive_status,
              created = EXCLUDED.created, dropped = EXCLUDED.dropped, archived = EXCLUDED.archived,
              pass_trigger = EXCLUDED.pass_trigger, actor = EXCLUDED.actor""",
            tg.qualified,
            at,
            st[0].name(),
            st[1].name(),
            st[2].name(),
            res.created(),
            res.dropped(),
            res.archived(),
            origin.trigger().name(),
            actor);
      } else {
        Sql.update(
            c,
            """
            INSERT INTO fw_partition_passes
            (table_name, ended_at, ensure_status, retention_status, archive_status, created, dropped, archived)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (table_name) DO UPDATE SET ended_at = EXCLUDED.ended_at, ensure_status = EXCLUDED.ensure_status,
              retention_status = EXCLUDED.retention_status, archive_status = EXCLUDED.archive_status,
              created = EXCLUDED.created, dropped = EXCLUDED.dropped, archived = EXCLUDED.archived""",
            tg.qualified,
            at,
            st[0].name(),
            st[1].name(),
            st[2].name(),
            res.created(),
            res.dropped(),
            res.archived());
      }
    } catch (SQLException | RuntimeException e) {
      core.failed(tg, "record");
      Log.error("could not record the last partition pass", "table", tg.qualified, "error", Log.describe(e));
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Reads the table's record; {@code withOrigin} says the table has the origin columns. */
  static Optional<LastPass> read(Connection c, Target tg, boolean withOrigin) throws SQLException {
    String cols = withOrigin ? "pass_trigger, actor" : "NULL::text, NULL::text";
    return Sql.query(
            c,
            "SELECT ended_at, ensure_status, retention_status, archive_status, created, dropped, archived, "
                + cols
                + " FROM fw_partition_passes WHERE table_name = ?",
            rs -> {
              String trigger = rs.getString(8);
              String actor = rs.getString(9);
              return new LastPass(
                  rs.getObject(1, OffsetDateTime.class).toInstant(),
                  StepStatus.valueOf(rs.getString(2)),
                  StepStatus.valueOf(rs.getString(3)),
                  StepStatus.valueOf(rs.getString(4)),
                  rs.getInt(5),
                  rs.getInt(6),
                  rs.getInt(7),
                  trigger == null ? Optional.empty() : Optional.of(PassTrigger.valueOf(trigger)),
                  actor == null ? Optional.empty() : Optional.of(actor));
            },
            tg.qualified)
        .stream()
        .findFirst();
  }

  /**
   * Says, once at start-up and only for an instance that has a declared table, that {@code fw_partition_passes}
   * is missing there (the migration runs on the default instance only), or lacks the two origin columns.
   */
  static void warnMissing(java.util.Collection<Target> targets) {
    java.util.Set<String> said = new java.util.HashSet<>();
    for (Target tg : targets) {
      String name = tg.db().name();
      if (said.contains(name)) {
        continue;
      }
      try (Connection c = tg.db().dataSource().getConnection()) {
        Aux aux = aux(c);
        if (!aux.passes()) {
          said.add(name);
          Log.warn(
              "table fw_partition_passes does not exist in database instance '"
                  + name
                  + "': the last pass of its partitioned tables is not recorded (migrations run on the default "
                  + "instance only; create the table by hand, as in db/migration/partitions/V0002__create_partition_passes_table.sql)");
        } else if (!aux.trigger()) {
          said.add(name);
          Log.warn(
              "table fw_partition_passes in database instance '"
                  + name
                  + "' has no pass_trigger and actor columns: the last pass of its partitioned tables is "
                  + "recorded without what triggered it and who (migrations run on the default instance only; "
                  + "add the columns by hand, as in db/migration/partitions/V0003__add_partition_pass_trigger.sql)");
        }
      } catch (SQLException e) {
        // the table is then read at the first pass, which says what it finds
      }
    }
  }
}
