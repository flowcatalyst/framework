package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.MemoryLockDriver;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactory;
import io.flowcatalyst.framework.objectstore.ObjectStoresModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** A real PostgreSQL, an application with the partitions module, a moving clock, and the log. */
abstract class PartitionsTestBase {

  static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

  static final String ID_TABLE =
      "CREATE TABLE readings (id text COLLATE \"C\" PRIMARY KEY, recorded_at timestamptz NOT NULL DEFAULT now(), payload text) PARTITION BY RANGE (id)";
  static final String COL_TABLE =
      "CREATE TABLE samples (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)";
  static final String TRAIL_TABLE =
      "CREATE TABLE trail (id text COLLATE \"C\" PRIMARY KEY, note text) PARTITION BY RANGE (id)";

  static PostgresFixture pg;

  @TempDir Path dir;

  TestClock clock = new TestClock(T0);
  LogCapture log;
  private final List<App> apps = new ArrayList<>();
  /** The URL the main instance is reached on (a test may route it through a proxy). */
  @Nullable String mainUrl;
  boolean otherDatabase;
  /** Pool maximum of every instance (1 for the query-counting test). */
  int poolMax = 10;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void resetDatabase() {
    log = LogCapture.start();
    pg.reset();
    pg.execute("DROP DATABASE IF EXISTS other");
    mainUrl = null;
    otherDatabase = false;
  }

  @AfterEach
  void closeApps() {
    for (int i = apps.size() - 1; i >= 0; i--) {
      apps.get(i).close();
    }
    apps.clear();
    log.close();
  }

  // ---- the application -----------------------------------------------------------------------

  /** What to start: the tables and the options of the module. */
  static final class Cfg {
    final List<Table> tables = new ArrayList<>();
    @Nullable ObjectStoreFactory store;
    @Nullable Duration every;
    int segment;
    boolean locks;
    @Nullable LockStore lockStore;

    static Cfg of(Table... tables) {
      Cfg c = new Cfg();
      c.tables.addAll(List.of(tables));
      return c;
    }

    Cfg store(ObjectStoreFactory factory) {
      store = factory;
      return this;
    }

    Cfg every(Duration d) {
      every = d;
      return this;
    }

    Cfg segment(int bytes) {
      segment = bytes;
      return this;
    }

    Cfg locks() {
      locks = true;
      return this;
    }

    Cfg lockStore(LockStore shared) {
      lockStore = shared;
      return this;
    }
  }

  /** A started application. */
  record Started(App app, Partitions ps) {
    double metric(String name, String... labels) {
      for (MetricsSnapshot.Family f : app.get(Metrics.class).snapshot().families()) {
        if (!f.name().equals(name)) {
          continue;
        }
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(List.of(labels))) {
            return s.value();
          }
        }
      }
      return 0;
    }
  }

  Started start(Cfg c) throws IOException {
    String yaml =
        """
        database:
          default: main
          instances:
            main: { url: "%s", username: %s, password: %s, pool: { max: %d, min-idle: 1 } }
        """
            .formatted(mainUrl == null ? pg.jdbcUrl() : mainUrl, pg.username(), pg.password(), poolMax);
    if (otherDatabase) {
      yaml +=
          "    other: { url: \"%s\", username: %s, password: %s, pool: { max: %d, min-idle: 1 } }\n"
              .formatted(pg.jdbcUrl("other"), pg.username(), pg.password(), poolMax);
    }
    Files.writeString(dir.resolve("application.yaml"), yaml, StandardCharsets.UTF_8);
    PartitionsModule.Builder pm = PartitionsModule.builder();
    c.tables.forEach(pm::table);
    if (c.every != null) {
      pm.every(c.every);
    }
    if (c.segment > 0) {
      pm.archiveSegmentBytes(c.segment);
    }
    if (c.locks || c.lockStore != null) {
      pm.locks();
    }
    var builder =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("APP_ENV", "test"))
            .instantSource(clock)
            .module(new DatabaseModule());
    if (c.store != null) {
      builder.module(ObjectStoresModule.builder().store("archive", c.store).build());
    }
    if (c.locks || c.lockStore != null) {
      LockStore shared = c.lockStore;
      builder.module(
          LocksModule.builder()
              .store(
                  "main",
                  shared != null
                      ? (name, lookup) -> shared
                      : (name, lookup) -> {
                        MonotonicClock mono = lookup.get(MonotonicClock.class);
                        return new LockStore(new MemoryLockDriver(mono::nanos), mono);
                      })
              .build());
    }
    App app = builder.module(pm.build()).start();
    apps.add(app);
    return new Started(app, app.get(Partitions.class));
  }

  /** A lock store replicas can share. */
  static LockStore sharedLockStore() {
    MonotonicClock mono = MonotonicClock.system();
    return new LockStore(new MemoryLockDriver(mono::nanos), mono);
  }

  /** The platform operator's context, which the manual actions are recorded against. */
  static Context operator() {
    return Context.of(new ClientId("ops")).withPrincipal(new PrincipalId("prn_OP1"), PrincipalKind.USER);
  }

  // ---- the database --------------------------------------------------------------------------

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, pg.username(), pg.password());
  }

  void exec(String sql) {
    exec(pg.jdbcUrl(), sql);
  }

  void exec(String url, String sql) {
    pg.execute(url, sql);
  }

  List<String> strings(String sql) {
    return strings(pg.jdbcUrl(), sql);
  }

  List<String> strings(String url, String sql) {
    try (Connection c = connect(url);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        out.add(rs.getString(1));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  int count(String sql) {
    return count(pg.jdbcUrl(), sql);
  }

  int count(String url, String sql) {
    return Integer.parseInt(strings(url, sql).get(0));
  }

  /** The names of the partitions attached to a table, sorted. */
  List<String> parts(String table) {
    return strings("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = '" + table + "'::regclass ORDER BY 1");
  }

  /** The tables of a second database instance named {@code other}, which has the module's own tables by hand. */
  void createOther(String... ddl) throws IOException {
    pg.createDatabase("other");
    otherDatabase = true;
    for (String d : ddl) {
      exec(pg.jdbcUrl("other"), d);
    }
  }

  /** The module's own migrations, as one script, for a hand-made instance. */
  static String moduleMigrations(int upTo) {
    StringBuilder sb = new StringBuilder();
    for (String f :
        new String[] {
          "V0001__create_partition_archives_table.sql",
          "V0002__create_partition_passes_table.sql",
          "V0003__add_partition_pass_trigger.sql"
        }) {
      if (Integer.parseInt(f.substring(1, 5)) > upTo) {
        continue;
      }
      try (var in = PartitionsTestBase.class.getClassLoader().getResourceAsStream("db/migration/partitions/" + f)) {
        assertThat(in).isNotNull();
        sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return sb.toString();
  }

  // ---- ids and tables ------------------------------------------------------------------------

  static String idAt(Instant at) {
    return new Ids(() -> at).generate("rdg");
  }

  static Table monthly(String name, Key by, Retention retention) {
    return Table.named(name).by(by).interval(Interval.MONTH).ahead(3).retention(retention);
  }

  static Duration days(int n) {
    return Duration.ofDays(n);
  }

  static void eventually(String what, BooleanSupplier ok) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (ok.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("timed out waiting for " + what);
  }
}
