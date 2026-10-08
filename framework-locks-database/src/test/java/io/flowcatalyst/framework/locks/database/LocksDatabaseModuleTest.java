package io.flowcatalyst.framework.locks.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LocksModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocksDatabaseModuleTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @BeforeEach
  void reset() {
    pg.reset();
    pg.execute("DROP DATABASE IF EXISTS second");
    pg.createDatabase("second");
  }

  private void yaml(String locks) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          default: main
          instances:
            main: { url: %s, username: %s, password: %s }
            second: { url: %s, username: %s, password: %s }
        locks:
        %s
        """
            .formatted(
                pg.jdbcUrl(), pg.username(), pg.password(),
                pg.jdbcUrl("second"), pg.username(), pg.password(),
                locks.indent(2)));
  }

  private App start() {
    return App.builder()
        .configDirectory(dir)
        .environment(Map.of())
        .module(new DatabaseModule())
        .module(new LocksModule())
        .module(new LocksDatabaseModule())
        .start();
  }

  private StartupException failure() {
    Throwable t = catchThrowable(() -> start().close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void theDriverWorksOnTheDefaultDatabaseAfterTheModuleMigration() throws IOException {
    yaml("instances:\n  main: { driver: database }");
    try (App app = start()) {
      LockStore store = app.get(Locks.class).defaultInstance();
      assertThat(store.tryAcquire(LockKey.service("k"), Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      assertThat(pg.query("SELECT module || ':' || version FROM fw_schema_history"))
          .isEqualTo("locks-database:1");
      assertThat(pg.query("SELECT count(*) FROM fw_lock")).isEqualTo("1");
    }
  }

  @Test
  void theModuleListOrderDoesNotMatter() throws IOException {
    yaml("instances:\n  main: { driver: database }");
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(new LocksDatabaseModule())
            .module(new LocksModule())
            .module(new DatabaseModule())
            .start()) {
      assertThat(app.get(Locks.class).defaultInstance()).isNotNull();
    }
  }

  @Test
  void aNonDefaultDatabaseWithoutTheTableFailsStartUp() throws IOException {
    yaml("instances:\n  odd: { driver: database, database: second }");
    StartupException e = failure();
    assertThat(e.getMessage())
        .contains("fw_lock")
        .contains("'second'")
        .contains("migrations do not run on this instance");
  }

  @Test
  void aNonDefaultDatabaseWithTheTableWorks() throws IOException {
    pg.execute(
        pg.jdbcUrl("second"),
        "CREATE SEQUENCE fw_lock_fence; CREATE TABLE fw_lock (lock_key text PRIMARY KEY, owner_token text, "
            + "fencing_token bigint NOT NULL, expires_at timestamptz NOT NULL)");
    yaml("instances:\n  odd: { driver: database, database: second }");
    try (App app = start()) {
      assertThat(app.get(Locks.class).get("odd").tryAcquire(LockKey.service("k"), Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
    }
  }

  @Test
  void anUnknownDatabaseNameAndAnUnknownKeyAreReported() throws IOException {
    yaml("instances:\n  a: { driver: database, database: nope }\n  b: { driver: database, connection: x }\ndefault: b");
    StartupException e = failure();
    assertThat(e.getMessage()).contains("nope").contains("locks.instances.a");
  }

  @Test
  void theDatabaseAndMemoryDriversCanRunSideBySide() throws IOException {
    yaml("default: db\ninstances:\n  db: { driver: database }\n  mem: { driver: memory }");
    try (App app = start()) {
      Locks locks = app.get(Locks.class);
      LockKey key = LockKey.service("both");
      assertThat(locks.get("db").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
      assertThat(locks.get("mem").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
    }
  }
}
