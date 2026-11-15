package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The retention pass: drop, archive then drop, or keep (contract section 5). */
final class Retainer {

  private Retainer() {}

  /** An error that says which step it belongs to. */
  static final class StepException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient Step step;

    StepException(Step step, String message, Throwable cause) {
      super(message, cause);
      this.step = step;
    }

    Step step() {
      return step;
    }
  }

  /** What a retention pass did: partitions dropped and archived, the steps that failed, and the errors. */
  static final class Outcome {
    int dropped;
    int archived;
    final List<Step> failed = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();

    void fail(Throwable err) {
      Step step = err instanceof StepException se ? se.step() : Step.RETENTION;
      if (!failed.contains(step)) {
        failed.add(step);
      }
    }
  }

  private record DueChild(String name, Instant start, boolean detached) {}

  /**
   * Removes the partitions of the table that are due at {@code now}. One partition's failure does not
   * stop the others; nothing is dropped unless every step before the drop succeeded. For an archiving
   * table it also finishes partitions an earlier pass detached and did not get to drop (recovery).
   */
  static Outcome retain(Core core, Target tg, Instant now) {
    Outcome out = new Outcome();
    if (tg.spec.retention() instanceof Retention.Keep) {
      return out;
    }
    Catalog.Ours ours;
    List<String> detachedNames = List.of();
    try (Connection c = tg.db().dataSource().getConnection()) {
      ours = Catalog.ours(tg, Catalog.children(c, tg));
    } catch (SQLException e) {
      core.failed(tg, Step.RETENTION.label());
      out.failed.add(Step.RETENTION);
      out.errors.add(new PartitionsException("retention: list partitions: " + Log.describe(e), e));
      return out;
    }
    logStrays(core, tg, ours.strays());
    List<DueChild> due = new ArrayList<>();
    for (Map.Entry<String, Instant> e : ours.starts().entrySet()) {
      if (tg.due(e.getValue(), now)) {
        due.add(new DueChild(e.getKey(), e.getValue(), false));
      }
    }
    if (tg.archives()) {
      try (Connection c = tg.db().dataSource().getConnection()) {
        detachedNames = Catalog.detachedTables(c, tg);
      } catch (SQLException e) {
        core.failed(tg, Step.RETENTION.label());
        out.failed.add(Step.RETENTION);
        out.errors.add(new PartitionsException("retention: list detached tables: " + Log.describe(e), e));
        return out;
      }
      for (String name : detachedNames) {
        tg.parse(name)
            .filter(start -> tg.due(start, now))
            .ifPresent(start -> due.add(new DueChild(name, start, true)));
      }
    }
    due.sort(Comparator.comparing(DueChild::start));

    for (DueChild d : due) {
      if (Thread.currentThread().isInterrupted()) {
        InterruptedException stop = new InterruptedException("the pass was interrupted");
        out.errors.add(stop);
        out.fail(stop);
        break;
      }
      try {
        boolean archived = remove(core, tg, d.name(), d.detached(), out);
        out.dropped++;
        if (archived) {
          out.archived++;
        }
      } catch (StepException e) {
        out.fail(e);
        out.errors.add(new PartitionsException("partition " + d.name() + ": " + Log.describe(e), e));
      }
    }
    return out;
  }

  /** Logs, once per partition and process, what is attached to the parent but not ours. */
  private static void logStrays(Core core, Target tg, List<Catalog.Child> strays) {
    for (Catalog.Child c : strays) {
      String key = tg.qualified + "/" + c.schema() + "." + c.name();
      if (core.firstSighting(key)) {
        Log.warn(
            "partition is not one of this framework's names for the table; left alone",
            "table",
            tg.qualified,
            "partition",
            c.schema() + "." + c.name());
      }
    }
  }

  /**
   * Retires one due partition. Drop: detach and drop in one transaction. Archive then drop: detach first
   * (a short transaction; from then on no write reaches the table through the parent, so the archive cannot
   * differ from what is dropped), archive the detached table, verify, drop. A failure after the detach leaves
   * the table detached, and the next pass finds it again (recovery); it is never re-attached.
   *
   * @return whether the partition was archived (and verified)
   * @throws StepException carrying the failed step; an archived partition whose drop failed is counted
   *     in {@code out} before the exception
   */
  private static boolean remove(Core core, Target tg, String name, boolean detached, Outcome out)
      throws StepException {
    if (tg.archives()) {
      if (!detached) {
        try {
          detach(tg, name, false);
        } catch (SQLException e) {
          core.failed(tg, Step.RETENTION.label());
          Log.error("partition detach failed", "table", tg.qualified, "partition", name, "error", Log.describe(e));
          throw new StepException(Step.RETENTION, "detach: " + Log.describe(e), e);
        }
        Log.info("partition detached for archiving", "table", tg.qualified, "partition", name);
      }
      try {
        Archiver.archive(core, tg, name);
      } catch (SQLException | java.io.IOException | RuntimeException e) {
        core.failed(tg, Step.ARCHIVE.label());
        Log.error(
            "partition archive failed; left detached, not dropped",
            "table",
            tg.qualified,
            "partition",
            name,
            "error",
            Log.describe(e));
        throw new StepException(Step.ARCHIVE, "archive: " + Log.describe(e), e);
      }
      try (Connection c = tg.db().dataSource().getConnection()) {
        Sql.execute(c, Sql.drop(tg.childIdent(name)));
      } catch (SQLException e) {
        core.failed(tg, Step.RETENTION.label());
        Log.error("partition drop failed", "table", tg.qualified, "partition", name, "error", Log.describe(e));
        // archived and verified, though not dropped
        out.archived++;
        throw new StepException(Step.RETENTION, "drop: " + Log.describe(e), e);
      }
    } else {
      try {
        detach(tg, name, true);
      } catch (SQLException e) {
        core.failed(tg, Step.RETENTION.label());
        Log.error("partition drop failed", "table", tg.qualified, "partition", name, "error", Log.describe(e));
        throw new StepException(Step.RETENTION, "drop: " + Log.describe(e), e);
      }
    }
    tg.dropped.inc();
    Log.info("partition dropped", "table", tg.qualified, "partition", name);
    return tg.archives();
  }

  /**
   * Detaches the partition in one transaction, and drops it in the same transaction when {@code drop} is
   * true (a failure rolls the detach back); otherwise sets the detach mark in it.
   *
   * <p>Plain DETACH, not DETACH ... CONCURRENTLY: the concurrent form cannot run in a transaction and leaves
   * a detached-pending state that a failed run must finish. The plain form takes a stronger lock on the
   * parent for an instant; {@code lock_timeout} bounds the wait, so a long query makes the pass fail and
   * retry next time instead of queueing every insert behind the lock.
   */
  static void detach(Target tg, String name, boolean drop) throws SQLException {
    try (Connection c = tg.db().dataSource().getConnection()) {
      c.setAutoCommit(false);
      try {
        Sql.execute(c, Sql.setLockTimeout());
        String ident = tg.childIdent(name);
        Sql.execute(c, Sql.detach(tg.ident(), ident));
        if (drop) {
          Sql.execute(c, Sql.drop(ident));
        } else {
          Sql.execute(c, Sql.comment(ident, tg.detachMark()));
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
