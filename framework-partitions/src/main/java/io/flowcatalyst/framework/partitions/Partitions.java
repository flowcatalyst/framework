package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.HealthCheck;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.WithLockResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * The declared partitioned tables: created ahead, removed by retention, read and acted on. The service
 * {@link PartitionsModule} provides; tests and tools may run a pass or ensure a range by hand.
 *
 * <p>An admin section is built on three things: {@link #status()} and {@link #tableStatus} and {@link
 * #archives} to read, and {@link #runPass} and {@link #ensureAs} to act, each taking the acting principal's
 * {@link Context} for the record. The typed refusals are {@link PartitionRefusedException}s.
 */
public final class Partitions {

  /** The name of the background loop (health check {@code background:partitions}). */
  public static final String LOOP_NAME = "partitions";

  /** The service lock that admits one replica to a pass, when {@link PartitionsModule.Builder#locks()} is given. */
  public static final String LOCK_NAME = "fw-partitions";

  /** The most partitions one {@link #ensure} call creates. */
  public static final int MAX_ENSURE_RANGE = 400;

  /** The most archive records {@link #archives} returns in a page. */
  public static final int MAX_ARCHIVE_PAGE = 200;

  /** The lease of the pass lock, refreshed while a pass runs. */
  static final Duration LOCK_LEASE = Duration.ofMinutes(5);

  private final Core core;
  private final List<Target> targets;
  private final Map<String, Target> byName;
  private final @Nullable LockStore lockStore;
  private final LockKey lockKey = LockKey.service(LOCK_NAME);
  private final ReentrantLock passLock = new ReentrantLock();

  Partitions(Core core, List<Target> targets, @Nullable LockStore lockStore) {
    this.core = core;
    this.targets = List.copyOf(targets);
    Map<String, Target> names = new java.util.LinkedHashMap<>();
    for (Target t : targets) {
      names.put(t.name(), t);
    }
    this.byName = names;
    this.lockStore = lockStore;
  }

  /**
   * The object key of the first (and, for a partition that fits, only) object of an archived partition:
   * {@code partitions/<schema>.<table>/<partition name>.ndjson.gz}; the others sit beside it as
   * {@code .part2.ndjson.gz} and so on.
   *
   * @param schema the table's schema
   * @param table the unqualified table
   * @param partition the partition's name
   * @return the key
   */
  public static String archiveKey(String schema, String table, String partition) {
    return Archiver.archiveKey(schema, table, partition);
  }

  /**
   * The declared table names, in declaration order.
   *
   * @return the names as declared
   */
  public List<String> tables() {
    return targets.stream().map(Target::name).toList();
  }

  List<Target> targets() {
    return targets;
  }

  @Nullable LockStore lockStore() {
    return lockStore;
  }

  private @Nullable Target lookup(String table) {
    Target t = byName.get(table);
    if (t != null) {
      return t;
    }
    for (Target candidate : targets) {
      if (candidate.qualified.equals(table)) {
        return candidate;
      }
    }
    return null;
  }

  private Target require(String table) {
    Target t = lookup(table);
    if (t == null) {
      throw notDeclared(table);
    }
    return t;
  }

  private PartitionRefusedException notDeclared(String table) {
    return new PartitionRefusedException(
        PartitionRefusedException.Reason.TABLE_NOT_DECLARED,
        "table is not declared: \"" + table + "\"; declared: " + String.join(", ", tables()));
  }

  // ---- ensure ------------------------------------------------------------------------------

  /**
   * Creates the partitions that cover {@code [from, to]} (both inclusive instants) for the declared table
   * (named as declared or schema-qualified), for imports and backfills that write outside the window the loop
   * keeps. Refuses more than {@link #MAX_ENSURE_RANGE} partitions in one call, a window before 1970 or ending
   * before it starts, a table that is not declared and a detached table of a partition's name, each as a
   * {@link PartitionRefusedException}. The result says how many partitions of the range retention removes at
   * the next pass.
   *
   * @param table the table, as declared or schema-qualified
   * @param from the start of the window
   * @param to the end of the window
   * @return what it did
   * @throws PartitionRefusedException when the call is refused
   * @throws PartitionsException when the database fails
   */
  public EnsureResult ensure(String table, Instant from, Instant to) {
    Target tg = require(table);
    if (to.isBefore(from)) {
      throw new PartitionRefusedException(
          PartitionRefusedException.Reason.RANGE_INVALID, "range is invalid: to is before from");
    }
    if (from.isBefore(Instant.EPOCH)) {
      throw new PartitionRefusedException(
          PartitionRefusedException.Reason.RANGE_INVALID, "range is invalid: from is before 1970-01-01");
    }
    Instant now = core.clock.instant();
    List<Instant> starts = new ArrayList<>();
    int due = 0;
    for (Instant s = tg.interval().start(from); !s.isAfter(to); s = tg.interval().next(s)) {
      if (starts.size() >= MAX_ENSURE_RANGE) {
        throw new PartitionRefusedException(
            PartitionRefusedException.Reason.RANGE_TOO_LARGE,
            "range needs too many partitions: that range needs more than "
                + MAX_ENSURE_RANGE
                + " partitions; ensure it in several calls");
      }
      starts.add(s);
      if (tg.due(s, now)) {
        due++;
      }
    }
    try {
      int created = Ensure.starts(tg, starts);
      Ensure.setAhead(tg, now);
      return new EnsureResult(created, due);
    } catch (SQLException e) {
      throw new PartitionsException("ensure " + tg.qualified + ": " + Log.describe(e), e);
    }
  }

  /**
   * {@link #ensure} for an operator: the same refusals and result, and an INFO line {@code partitions: manual
   * ensure} naming the principal of {@code by}, the table, the range and the outcome (the framework has no
   * audit store for an operator's action, so the log line is the record).
   *
   * @param by the acting principal's context
   * @param table the table, as declared or schema-qualified
   * @param from the start of the window
   * @param to the end of the window
   * @return what it did
   * @throws PartitionRefusedException when the call is refused
   * @throws PartitionsException when the database fails
   */
  public EnsureResult ensureAs(Context by, String table, Instant from, Instant to) {
    Object[] range = {"from", from, "to", to};
    try {
      EnsureResult res = ensure(table, from, to);
      recordManual(
          "partitions: manual ensure",
          by,
          table,
          "ok",
          range,
          new Object[] {"created", res.created(), "already_due", res.alreadyDue()});
      return res;
    } catch (PartitionRefusedException e) {
      recordManual("partitions: manual ensure", by, table, "refused: " + Log.describe(e), range);
      throw e;
    } catch (RuntimeException e) {
      recordManual("partitions: manual ensure", by, table, "failed: " + Log.describe(e), range);
      throw e;
    }
  }

  // ---- passes ------------------------------------------------------------------------------

  /**
   * Runs one ensure-then-retention pass over every table now: the loop's work. A table's failure does not stop
   * the others. It waits for a manual pass ({@link #runPass}) of this process to finish; replicas are told
   * apart by the service lock, which the loop's gate takes.
   *
   * @throws PartitionsException when a step of any table failed (its message lists the tables; the causes are
   *     suppressed), so the loop counts a failed run
   */
  public void pass() {
    passLock.lock();
    try {
      Passed passed = passAll(targets, Passes.Origin.LOOP);
      if (!passed.errors.isEmpty()) {
        PartitionsException e =
            new PartitionsException("partition pass failed: " + String.join("; ", passed.messages()), null);
        passed.errors.forEach(e::addSuppressed);
        throw e;
      }
    } finally {
      passLock.unlock();
    }
  }

  private static final class Passed {
    final List<PassResult> results = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();

    List<String> messages() {
      return messages;
    }
  }

  private Passed passAll(List<Target> which, Passes.Origin origin) {
    Passed out = new Passed();
    for (Target tg : which) {
      List<Throwable> errs = new ArrayList<>();
      out.results.add(passTable(tg, core.clock.instant(), origin, errs));
      for (Throwable err : errs) {
        out.errors.add(err);
        out.messages.add("table " + tg.name() + ": " + err.getMessage());
      }
    }
    return out;
  }

  private PassResult passTable(Target tg, Instant now, Passes.Origin origin, List<Throwable> errs) {
    List<Step> failed = new ArrayList<>();
    int created = 0;
    try {
      created = Ensure.current(core, tg, now);
    } catch (SQLException | RuntimeException e) {
      core.failed(tg, Step.ENSURE.label());
      Log.error("partition ensure failed", "table", tg.qualified, "error", Log.describe(e));
      failed.add(Step.ENSURE);
      errs.add(new PartitionsException("ensure: " + Log.describe(e), e));
    }
    Retainer.Outcome ret = Retainer.retain(core, tg, now);
    failed.addAll(ret.failed);
    errs.addAll(ret.errors);
    PassResult res = new PassResult(tg.qualified, created, ret.dropped, ret.archived, failed);
    Passes.record(core, tg, res, origin);
    return res;
  }

  /**
   * Runs a pass by hand, for an operator: the ensure-then-retention pass of the declared table (named as
   * declared or schema-qualified), or of every table when {@code table} is empty. {@code by} is the acting
   * principal's context, which the record names: an INFO line {@code partitions: manual pass} with the
   * principal, the table and the outcome. The pass is recorded as {@link PassTrigger#MANUAL} with the
   * principal's id as the actor.
   *
   * <p>A manual pass never overlaps the loop's, here or on another replica: with a lock store it tries the
   * loop's service lock once without waiting, and in this process it tries the pass mutex; when either is
   * held it is refused with {@link PartitionRefusedException.Reason#PASS_RUNNING}, never queued. When the
   * pass ran, no exception is thrown and a step that failed is in the result's {@link PassResult#failed()}
   * (the log has the cause).
   *
   * @param by the acting principal's context
   * @param table the table, as declared or schema-qualified; empty for every table
   * @return one result per table, in declaration order
   * @throws PartitionRefusedException for an unknown table or a running pass
   * @throws PartitionsException when the lock store fails
   */
  public List<PassResult> runPass(Context by, String table) {
    List<Target> which = targets;
    String scope = table.isEmpty() ? "all" : table;
    if (!table.isEmpty()) {
      Target tg = lookup(table);
      if (tg == null) {
        PartitionRefusedException err = notDeclared(table);
        recordManual("partitions: manual pass", by, table, "refused: " + err.getMessage());
        throw err;
      }
      which = List.of(tg);
    }
    if (!passLock.tryLock()) {
      recordManual("partitions: manual pass", by, scope, "refused: a pass is already running in this process");
      throw new PartitionRefusedException(
          PartitionRefusedException.Reason.PASS_RUNNING, "a pass is already running");
    }
    try {
      Passes.Origin origin = manualOrigin(by);
      List<PassResult> results = new ArrayList<>();
      boolean lost = false;
      LockStore store = lockStore;
      if (store != null) {
        List<Target> run = which;
        WithLockResult<Boolean> res;
        try {
          res =
              store.withLock(
                  lockKey,
                  LOCK_LEASE,
                  Duration.ZERO,
                  lease -> {
                    results.addAll(passAll(run, origin).results);
                    return Boolean.TRUE;
                  });
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          recordManual("partitions: manual pass", by, scope, "failed: interrupted");
          throw new PartitionsException("the pass lock could not be taken: interrupted", e);
        } catch (RuntimeException e) {
          recordManual("partitions: manual pass", by, scope, "failed: " + Log.describe(e));
          throw new PartitionsException("the pass lock could not be taken: " + Log.describe(e), e);
        }
        switch (res) {
          case WithLockResult.NotAcquired<Boolean> n -> {
            recordManual(
                "partitions: manual pass",
                by,
                scope,
                "refused: the pass lock is held (another replica's pass is running)");
            throw new PartitionRefusedException(
                PartitionRefusedException.Reason.PASS_RUNNING, "a pass is already running");
          }
          case WithLockResult.Lost<Boolean> l -> lost = true;
          case WithLockResult.Completed<Boolean> c -> {}
        }
      } else {
        results.addAll(passAll(which, origin).results);
      }
      String outcome = "ok";
      List<String> failedTables = results.stream().filter(r -> !r.passed()).map(PassResult::table).toList();
      if (!failedTables.isEmpty()) {
        outcome = "steps failed: " + String.join(", ", failedTables);
      }
      if (lost) {
        outcome = "the pass lock was lost while the pass ran; " + outcome;
      }
      recordManual(
          "partitions: manual pass",
          by,
          scope,
          outcome,
          new Object[] {
            "created", results.stream().mapToInt(PassResult::created).sum(),
            "dropped", results.stream().mapToInt(PassResult::dropped).sum(),
            "archived", results.stream().mapToInt(PassResult::archived).sum()
          });
      return List.copyOf(results);
    } finally {
      passLock.unlock();
    }
  }

  private static Passes.Origin manualOrigin(Context by) {
    return new Passes.Origin(
        PassTrigger.MANUAL, by.findPrincipal().map(p -> p.value()).orElse(""));
  }

  /**
   * The record of an operator's action: who (the context's principal, its kind and the correlation id), what
   * and the outcome.
   */
  private void recordManual(String message, Context by, String table, String outcome, Object[]... extra) {
    List<Object> pairs = new ArrayList<>(List.of("table", table, "outcome", outcome));
    pairs.add("principal");
    pairs.add(by.findPrincipal().map(p -> p.value()).orElse(""));
    by.findPrincipalKind()
        .ifPresent(
            kind -> {
              pairs.add("principal_kind");
              pairs.add(kind.name());
            });
    pairs.add("correlation_id");
    pairs.add(by.correlationId().value());
    for (Object[] more : extra) {
      for (Object o : more) {
        pairs.add(o);
      }
    }
    Log.info(message, pairs.toArray());
  }

  // ---- health --------------------------------------------------------------------------------

  /**
   * The health check {@code partitions:<table>}: down when the partition for the next interval does not exist,
   * read from the catalog on every check, because inserts fail from the boundary on.
   */
  HealthCheck healthCheck(Target tg) {
    return () -> {
      List<Catalog.Child> kids;
      try (Connection c = tg.db().dataSource().getConnection()) {
        kids = Catalog.children(c, tg);
      } catch (SQLException e) {
        throw new IllegalStateException("cannot read the catalog");
      }
      Optional<String> problem = StatusReader.nextPartitionProblem(tg, kids, core.clock.instant());
      if (problem.isPresent()) {
        throw new IllegalStateException(problem.get());
      }
    };
  }

  // ---- status ----------------------------------------------------------------------------------

  /**
   * The status of every declared table, in declaration order, read from the catalog and the module's own
   * tables with a fixed number of queries per table (at most five), whatever the number of partitions. It
   * never counts rows.
   *
   * @return one status per table
   * @throws PartitionsException when the database cannot be read
   */
  public List<TableStatus> status() {
    List<TableStatus> out = new ArrayList<>();
    for (Target tg : targets) {
      out.add(statusOf(tg));
    }
    return out;
  }

  /**
   * The status of one table, named as declared or schema-qualified.
   *
   * @param table the table
   * @return the status, empty when no declared table has that name
   * @throws PartitionsException when the database cannot be read
   */
  public Optional<TableStatus> tableStatus(String table) {
    Target tg = lookup(table);
    return tg == null ? Optional.empty() : Optional.of(statusOf(tg));
  }

  private TableStatus statusOf(Target tg) {
    try {
      return StatusReader.read(core, tg);
    } catch (SQLException e) {
      throw new PartitionsException("table " + tg.name() + ": " + Log.describe(e), e);
    }
  }

  /**
   * The archived partitions of a table from the manifest, newest first, in pages: {@code after} is the
   * partition name of the last record of the previous page (empty for the first page), {@code limit} is 1 to
   * {@link #MAX_ARCHIVE_PAGE} (a smaller or larger value is clamped). An instance without the manifest has
   * none. One query per call.
   *
   * @param table the table, as declared or schema-qualified
   * @param after the partition of the last record of the previous page, or empty
   * @param limit the page size
   * @return the records
   * @throws PartitionRefusedException when the table is not declared
   * @throws PartitionsException when the database cannot be read
   */
  public List<ArchiveRecord> archives(String table, String after, int limit) {
    Target tg = require(table);
    try {
      return StatusReader.archives(tg, after, Math.max(1, Math.min(MAX_ARCHIVE_PAGE, limit)));
    } catch (SQLException e) {
      throw new PartitionsException("table " + tg.name() + ": " + Log.describe(e), e);
    }
  }
}
