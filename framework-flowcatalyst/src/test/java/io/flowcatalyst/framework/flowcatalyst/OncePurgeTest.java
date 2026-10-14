package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LockStores;
import io.flowcatalyst.framework.locks.LocksModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The purge of old {@code fw_dispatch_once} rows, with the application's injected clock. */
class OncePurgeTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  FakePlatform platform;
  App app;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    platform = FakePlatform.start();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    platform.close();
  }

  /** Starts the app with a purge interval so long that only the first run happens. */
  private void start(String... env) {
    String[] all = new String[env.length + 2];
    all[0] = "FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL";
    all[1] = "1h";
    System.arraycopy(env, 0, all, 2, env.length);
    app =
        Receiving.start(
            dir, Receiving.env(platform, all), () -> NOW, new DatabaseModule(), LocksModule.builder().store("local", LockStores.memory()).build());
  }

  private static void row(String job, Instant processedAt) {
    pg.execute(
        "INSERT INTO fw_dispatch_once (handler, job_id, processed_at) VALUES ('h', '%s', '%s')"
            .formatted(job, processedAt));
  }

  private static String jobs() {
    return pg.query("SELECT coalesce(string_agg(job_id, ',' ORDER BY job_id), '') FROM fw_dispatch_once");
  }

  private OncePurge purge(Duration retention, boolean withLocks) {
    Database db = app.get(Databases.class).defaultInstance();
    return new OncePurge(
        db,
        withLocks ? app.get(Locks.class) : null,
        retention,
        Duration.ofHours(1),
        () -> NOW);
  }

  private void awaitJobs(String expected) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!jobs().equals(expected) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(jobs()).isEqualTo(expected);
  }

  @Test
  void theDefaultRetentionIs30DaysAndTheStartUpRunDeletesOlderRowsOnly() throws Exception {
    // The migration has run only once the app is up, so create the rows through a first start.
    start();
    app.close();
    row("old", NOW.minus(Duration.ofDays(31)));
    row("justOver", NOW.minus(Duration.ofDays(30)).minusSeconds(1));
    row("exactly", NOW.minus(Duration.ofDays(30)));
    row("recent", NOW.minus(Duration.ofDays(29)));
    start();
    awaitJobs("exactly,recent");
  }

  @Test
  void theRetentionSettingDecidesTheCutoff() throws Exception {
    start();
    app.close();
    row("a", NOW.minus(Duration.ofHours(3)));
    row("b", NOW.minus(Duration.ofMinutes(30)));
    start("FLOWCATALYST_RECEIVERS_ONCE_RETENTION", "1h");
    awaitJobs("b");
  }

  @Test
  void aRunDeletesInBatchesUntilNothingOldIsLeft() throws Exception {
    start();
    pg.execute(
        "INSERT INTO fw_dispatch_once (handler, job_id, processed_at)"
            + " SELECT 'h', 'old' || g, '%s'::timestamptz FROM generate_series(1, %d) g"
                .formatted(NOW.minus(Duration.ofDays(60)), OncePurge.BATCH * 2 + 7));
    row("recent", NOW);
    assertThat(purge(Duration.ofDays(30), false).runOnce()).isEqualTo(OncePurge.BATCH * 2L + 7);
    assertThat(jobs()).isEqualTo("recent");
    assertThat(purge(Duration.ofDays(30), false).runOnce()).isZero();
  }

  @Test
  void theRunUsesTheInjectedClockNotTheDatabaseClock() throws Exception {
    start();
    // Written "now" by the database's own clock: far newer than NOW's 2026-10-09 minus 30 days.
    pg.execute("INSERT INTO fw_dispatch_once (handler, job_id) VALUES ('h', 'dbNow')");
    row("old", NOW.minus(Duration.ofDays(40)));
    purge(Duration.ofDays(30), false).runOnce();
    assertThat(jobs()).isEqualTo("dbNow");
  }

  @Test
  void aRunWhileAnotherReplicaHoldsTheLockDeletesNothing() throws Exception {
    start();
    row("old", NOW.minus(Duration.ofDays(60)));
    LockStore store = app.get(Locks.class).defaultInstance();
    AcquireResult held =
        store.tryAcquire(LockKey.service(OncePurge.LOCK_NAME), Duration.ofMinutes(1));
    assertThat(held).isInstanceOf(AcquireResult.Acquired.class);
    OncePurge purge = purge(Duration.ofDays(30), true);
    assertThat(purge.runOnce()).isZero();
    assertThat(jobs()).isEqualTo("old");
    store.release(((AcquireResult.Acquired) held).lease());
    assertThat(purge.runOnce()).isEqualTo(1);
    assertThat(jobs()).isEmpty();
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withoutALockInstanceTheRunStillDeletes() throws Exception {
    start();
    row("old", NOW.minus(Duration.ofDays(60)));
    assertThat(purge(Duration.ofDays(30), false).runOnce()).isEqualTo(1);
  }

  @Test
  void theBackgroundTaskRunsRepeatedlyAndStopsAtShutdown() throws Exception {
    start("FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", "50ms");
    // Rows added while the app runs are purged by a later run.
    row("late", NOW.minus(Duration.ofDays(90)));
    awaitJobs("");
    app.close();
    app = null;
    row("afterShutdown", NOW.minus(Duration.ofDays(90)));
    Thread.sleep(300);
    assertThat(jobs()).isEqualTo("afterShutdown");
  }

  @Test
  void nonPositiveRetentionOrIntervalFailsStartUp() {
    for (String[] bad :
        new String[][] {
          {"FLOWCATALYST_RECEIVERS_ONCE_RETENTION", "0s"},
          {"FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", "0s"}
        }) {
      Throwable t = catchThrowable(() -> start(bad));
      assertThat(t).isInstanceOf(StartupException.class);
      assertThat(((StartupException) t).problems())
          .extracting(Problem::key)
          .containsExactly(
              bad[0].equals("FLOWCATALYST_RECEIVERS_ONCE_RETENTION")
                  ? "flowcatalyst.receivers.once-retention"
                  : "flowcatalyst.receivers.once-purge-interval");
      app = null;
    }
  }
}
