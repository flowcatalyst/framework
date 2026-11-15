package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.locks.LockGate;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStores;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Manages range-partitioned tables (partitions contract). Which tables are partitioned and how long they are
 * kept are facts of the application, so they are code, given to the builder: there is no configuration. The
 * module contributes the migration set of {@code fw_partition_archives} and {@code fw_partition_passes} (to the
 * default database instance, with {@code DatabaseModule}), validates every table at start-up, runs one ensure
 * pass before the application's {@code started} hooks, adds a critical health check {@code partitions:<table>}
 * per table and the background loop {@code partitions}.
 *
 * <pre>{@code
 * PartitionsModule.builder()
 *     .table(
 *         Table.named("readings")
 *             .by(Key.byId("rdg"))
 *             .interval(Interval.MONTH)
 *             .ahead(3)
 *             .retention(Retention.dropAfter(Duration.ofDays(90))))
 *     .locks() // with LocksModule: one replica runs the loop
 *     .build()
 * }</pre>
 *
 * <p>The module needs {@code DatabaseModule}; a table that archives needs {@code ObjectStoresModule}, and
 * {@link Builder#locks()} needs {@code LocksModule}.
 */
public final class PartitionsModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "partitions";

  /** The default wait between passes of the loop. */
  public static final Duration DEFAULT_EVERY = Duration.ofHours(1);

  /** The default compressed size at which an archive is cut into another object: 16 MiB. */
  public static final int DEFAULT_SEGMENT_BYTES = 16 << 20;

  /** The wait before the loop's first pass: start-up already ran an ensure pass, so the first is for retention. */
  static final Duration FIRST_PASS_DELAY = Duration.ofMinutes(1);

  private final List<Table> tables;
  private final Duration every;
  private final int segmentBytes;
  private final boolean useLocks;

  private PartitionsModule(List<Table> tables, Duration every, int segmentBytes, boolean useLocks) {
    this.tables = tables;
    this.every = every;
    this.segmentBytes = segmentBytes;
    this.useLocks = useLocks;
  }

  /**
   * Starts a module definition with no table.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link PartitionsModule}. */
  public static final class Builder {
    private final List<Table> tables = new ArrayList<>();
    private Duration every = DEFAULT_EVERY;
    private int segmentBytes = DEFAULT_SEGMENT_BYTES;
    private boolean useLocks;

    private Builder() {}

    /**
     * Declares a partitioned table. A declaration is checked at start-up, all problems together; a table given
     * twice (also under two spellings, {@code readings} and {@code public.readings}) is a start-up error.
     *
     * @param table the declaration
     * @return this builder
     */
    public Builder table(Table table) {
      tables.add(table);
      return this;
    }

    /**
     * Sets the wait between passes of the loop (default {@link #DEFAULT_EVERY}); the first pass is one minute
     * after start, or {@code every} when that is shorter.
     *
     * @param wait 10 ms to 24 h, as for any loop
     * @return this builder
     * @throws IllegalArgumentException when out of range
     */
    public Builder every(Duration wait) {
      if (wait.compareTo(Duration.ofMillis(10)) < 0 || wait.compareTo(Duration.ofHours(24)) > 0) {
        throw new IllegalArgumentException("partitions loop interval must be 10ms to 24h, was " + wait);
      }
      this.every = wait;
      return this;
    }

    /**
     * Sets the compressed size at which an archive is cut into another object (default 16 MiB). An object store
     * takes whole values only, so a large partition is written as several objects; keep this below the stores'
     * size limit.
     *
     * @param bytes at least 1
     * @return this builder
     * @throws IllegalArgumentException when below 1
     */
    public Builder archiveSegmentBytes(int bytes) {
      if (bytes < 1) {
        throw new IllegalArgumentException("archiveSegmentBytes must be at least 1, was " + bytes);
      }
      this.segmentBytes = bytes;
      return this;
    }

    /**
     * Gates the loop with the service lock {@code fw-partitions} (lease 5 minutes) on the default lock store of
     * {@code LocksModule}, so one replica runs each pass. Without it every replica runs the passes; they are
     * safe to run concurrently (creation is serialised by an advisory lock, a drop is one transaction) but waste
     * work, and two replicas may both archive the same partition (rewritten under the same keys). A manual pass
     * ({@link Partitions#runPass}) takes the same lock.
     *
     * @return this builder
     */
    public Builder locks() {
      this.useLocks = true;
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public PartitionsModule build() {
      return new PartitionsModule(List.copyOf(tables), every, segmentBytes, useLocks);
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, PartitionsModule.class, "db/migration/partitions");
    app.provide(Partitions.class, this::create);
  }

  @Override
  public void boot(App app) {
    Partitions partitions = app.get(Partitions.class);
    for (Target tg : partitions.targets()) {
      app.health().add("partitions:" + tg.name(), partitions.healthCheck(tg));
    }
    if (partitions.targets().isEmpty()) {
      return;
    }
    LoopOptions options =
        LoopOptions.every(every).initialDelay(every.compareTo(FIRST_PASS_DELAY) < 0 ? every : FIRST_PASS_DELAY);
    LockStore store = partitions.lockStore();
    if (store != null) {
      options = options.gatedBy(LockGate.of(store, io.flowcatalyst.framework.locks.LockKey.service(Partitions.LOCK_NAME), Partitions.LOCK_LEASE));
    }
    app.get(Background.class).loop(Partitions.LOOP_NAME, options, tick -> partitions.pass());
  }

  private static Problem problem(String table, String message) {
    return Problem.of(ID, "table " + table, message);
  }

  /**
   * Checks the declarations, validates every table against its database (it exists, is {@code PARTITION BY
   * RANGE} on exactly the declared key, and for an id table the key column is {@code COLLATE "C"}), and runs one
   * ensure pass so the first insert finds its partition. Every problem is returned at once.
   */
  private Partitions create(Lookup lookup) {
    Databases dbs = lookup.get(Databases.class);
    InstantSource clock = lookup.get(InstantSource.class);
    Metrics metrics = lookup.get(Metrics.class);
    List<Problem> problems = new ArrayList<>();

    Target.Metrics families =
        new Target.Metrics(
            metrics.gauge("fw_partitions_ahead", "Partitions that exist after the current interval, per table.", "table"),
            metrics.counter(
                "fw_partition_failures_total",
                "Failed steps of partition passes: step is ensure, retention (drop or list), archive, or record (the last-pass record).",
                "table",
                "step"),
            metrics.counter("fw_partitions_dropped_total", "Partitions dropped by retention.", "table"),
            metrics.counter("fw_partitions_archived_total", "Partitions written to an object store and verified.", "table"),
            metrics.counter("fw_partition_rows_archived_total", "Rows written to archives.", "table"));
    Core core = new Core(clock, families.failures(), segmentBytes);

    @Nullable ObjectStores stores = null;
    boolean storesMissing = false;
    if (tables.stream().anyMatch(t -> t.retention() instanceof Retention.ArchiveThenDrop)) {
      try {
        stores = lookup.get(ObjectStores.class);
      } catch (RuntimeException e) {
        // the graph recorded that no module provides them; say which tables need them
        storesMissing = true;
      }
    }
    @Nullable LockStore lockStore = null;
    if (useLocks) {
      try {
        lockStore = lookup.get(Locks.class).defaultInstance();
      } catch (RuntimeException e) {
        problems.add(Problem.of(ID, "locks", "the loop is gated by a lock but no lock store is available: " + Log.describe(e)));
      }
    }

    Set<String> seenDecl = new HashSet<>();
    List<Spec> specs = new ArrayList<>();
    for (Table t : tables) {
      if (!seenDecl.add(t.name())) {
        problems.add(problem(t.name(), "declared twice"));
        continue;
      }
      List<String> msgs = t.check();
      if (!msgs.isEmpty()) {
        msgs.forEach(m -> problems.add(problem(t.name(), m)));
        continue;
      }
      Key key = t.by();
      Interval interval = t.interval();
      Retention retention = t.retention();
      if (key == null || interval == null || retention == null) {
        throw new IllegalStateException("a checked table has its key, interval and retention");
      }
      @Nullable ObjectStore store = null;
      if (retention instanceof Retention.ArchiveThenDrop a) {
        if (stores == null) {
          if (storesMissing) {
            problems.add(
                problem(
                    t.name(),
                    "retention names object store \"" + a.store() + "\", but no ObjectStoresModule provides object stores"));
          }
          continue;
        }
        try {
          store = stores.get(a.store());
        } catch (IllegalArgumentException e) {
          problems.add(problem(t.name(), "retention: " + Log.describe(e)));
          continue;
        }
      }
      Database db;
      try {
        db = t.database().isEmpty() ? dbs.defaultInstance() : dbs.get(t.database());
      } catch (RuntimeException e) {
        problems.add(problem(t.name(), Log.describe(e)));
        continue;
      }
      specs.add(new Spec(t, key, interval, t.ahead(), retention, db, store));
    }

    // Against the database: resolve names, find tables declared twice under different spellings, check the shape.
    Map<String, String> seenRel = new LinkedHashMap<>();
    List<Target> targets = new ArrayList<>();
    for (Spec spec : specs) {
      Catalog.Validation v;
      try (Connection c = spec.db().dataSource().getConnection()) {
        v = Catalog.validate(c, spec);
      } catch (SQLException e) {
        problems.add(problem(spec.declaredName(), "cannot read the catalog: " + Log.describe(e)));
        continue;
      }
      v.problems().forEach(m -> problems.add(problem(spec.declaredName(), m)));
      if (!v.problems().isEmpty() || v.names().isEmpty()) {
        continue;
      }
      String schema = v.names().get()[0];
      String rel = v.names().get()[1];
      String id = spec.db().name() + "/" + schema + "." + rel;
      String first = seenRel.putIfAbsent(id, spec.declaredName());
      if (first != null) {
        problems.add(problem(spec.declaredName(), "is the same table as " + first + ": declared twice"));
        continue;
      }
      targets.add(new Target(spec, schema, rel, families));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }

    for (Target tg : targets) {
      try {
        Ensure.current(core, tg, clock.instant());
      } catch (SQLException | RuntimeException e) {
        problems.add(problem(tg.name(), "cannot create its partitions: " + Log.describe(e)));
      }
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    Passes.warnMissing(targets);
    return new Partitions(core, targets, lockStore);
  }
}
