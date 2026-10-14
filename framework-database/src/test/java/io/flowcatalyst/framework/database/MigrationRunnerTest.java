package io.flowcatalyst.framework.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {

  static PostgresFixture pg;

  @TempDir Path dir;
  private Path configDir;
  private Path modA;
  private Path modB;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  /** The module each start uses; a test may replace it before starting. */
  private DatabaseModule.Builder database = DatabaseModule.builder();

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    configDir = Files.createDirectories(dir.resolve("config"));
    modA = Files.createDirectories(dir.resolve("a"));
    modB = Files.createDirectories(dir.resolve("b"));
    yaml("main", "");
  }

  private void yaml(String defaultName, String extra) throws IOException {
    Files.writeString(
        configDir.resolve("application.yaml"),
        """
        database:
          default: %s
          instances:
            main:
              url: %s
              username: %s
              password: %s
              %s
        """
            .formatted(defaultName, pg.jdbcUrl(), pg.username(), pg.password(), extra));
  }

  private AppBuilder builder(Module... modules) {
    AppBuilder b =
        App.builder()
            .configDirectory(configDir)
            .environment(Map.of())
            .module(database.pool(DatabasePool.defaults().withMaxSize(4)).build());
    for (Module m : modules) {
      b.module(m);
    }
    return b;
  }

  private StartupException failure(Module... modules) {
    Throwable t = catchThrowable(() -> builder(modules).start().close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  private void startAndClose(Module... modules) {
    builder(modules).start().close();
  }

  private List<String> history() {
    List<String> rows = new ArrayList<>();
    String all =
        pg.query(
            "SELECT string_agg(module || ':' || version || ':' || description, ',' ORDER BY module, version) "
                + "FROM fw_schema_history");
    if (all != null) {
      rows.addAll(List.of(all.split(",")));
    }
    return rows;
  }

  private boolean tableExists(String name) {
    return "t".equals(pg.query("SELECT to_regclass('" + name + "') IS NOT NULL"));
  }

  // ---- applying ---------------------------------------------------------------------------

  @Test
  void appliesSetsInModuleOrderAndByVersionWithinASet() throws IOException {
    TestMigrations.write(modA, "V0002__second.sql", "INSERT INTO log(msg) VALUES ('a2');");
    TestMigrations.write(modA, "V0001__first.sql", "CREATE TABLE log(id serial, msg text); INSERT INTO log(msg) VALUES ('a1');");
    TestMigrations.write(modB, "V0001__b_first.sql", "INSERT INTO log(msg) VALUES ('b1');");
    startAndClose(TestMigrations.in("alpha", modA), TestMigrations.in("beta", modB));
    assertThat(pg.query("SELECT string_agg(msg, ',' ORDER BY id) FROM log")).isEqualTo("a1,a2,b1");
    assertThat(history()).containsExactly("alpha:1:first", "alpha:2:second", "beta:1:b first");
    assertThat(pg.query("SELECT length(checksum) FROM fw_schema_history WHERE module = 'alpha' AND version = 1"))
        .isEqualTo("64");
    assertThat(pg.query("SELECT count(*) FROM fw_schema_history WHERE applied_at IS NOT NULL"))
        .isEqualTo("3");
  }

  @Test
  void checksumIsTheSha256OfTheFileBytes() throws Exception {
    String sql = "CREATE TABLE t(id int);";
    TestMigrations.write(modA, "V0001__t.sql", sql);
    startAndClose(TestMigrations.in("alpha", modA));
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(pg.query("SELECT checksum FROM fw_schema_history")).isEqualTo(expected);
  }

  @Test
  void aSecondStartAppliesNothingAgain() throws IOException {
    TestMigrations.write(modA, "V0001__t.sql", "CREATE TABLE t(id int); INSERT INTO t VALUES (1);");
    startAndClose(TestMigrations.in("alpha", modA));
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(pg.query("SELECT count(*) FROM t")).isEqualTo("1");
    assertThat(history()).hasSize(1);
  }

  @Test
  void aNewVersionIsAppliedAlone() throws IOException {
    TestMigrations.write(modA, "V0001__t.sql", "CREATE TABLE t(id int); INSERT INTO t VALUES (1);");
    startAndClose(TestMigrations.in("alpha", modA));
    TestMigrations.write(modA, "V0002__more.sql", "INSERT INTO t VALUES (2);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(pg.query("SELECT count(*) FROM t")).isEqualTo("2");
    assertThat(history()).containsExactly("alpha:1:t", "alpha:2:more");
  }

  @Test
  void versionsOfDifferentModulesAreIndependent() throws IOException {
    TestMigrations.write(modA, "V0001__a.sql", "CREATE TABLE a(id int);");
    TestMigrations.write(modB, "V0001__b.sql", "CREATE TABLE b(id int);");
    startAndClose(TestMigrations.in("alpha", modA), TestMigrations.in("beta", modB));
    assertThat(tableExists("a") && tableExists("b")).isTrue();
  }

  @Test
  void theAdvisoryLockIsReleasedAfterTheRun() throws IOException {
    TestMigrations.write(modA, "V0001__a.sql", "CREATE TABLE a(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(pg.query("SELECT pg_try_advisory_lock(" + MigrationRunner.ADVISORY_LOCK_KEY + ")"))
        .isEqualTo("t");
  }

  @Test
  void theAdvisoryLockIsReleasedAfterAFailedRun() throws IOException {
    TestMigrations.write(modA, "V0001__bad.sql", "SELECT nope FROM nowhere;");
    failure(TestMigrations.in("alpha", modA));
    assertThat(pg.query("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")).isEqualTo("0");
  }

  @Test
  void instancesStartingTogetherApplyEachFileOnce() throws Exception {
    TestMigrations.write(
        modA,
        "V0001__slow.sql",
        "CREATE TABLE counter(n int); SELECT pg_sleep(1); INSERT INTO counter VALUES (1);");
    TestMigrations.write(modA, "V0002__more.sql", "INSERT INTO counter VALUES (2);");
    int starters = 4;
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<Void>> runs = new ArrayList<>();
    for (int i = 0; i < starters; i++) {
      TestMigrations mod = TestMigrations.in("alpha", modA);
      runs.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  throw new IllegalStateException(e);
                }
                startAndClose(mod);
              }));
    }
    go.countDown();
    CompletableFuture.allOf(runs.toArray(new CompletableFuture<?>[0])).get();
    assertThat(pg.query("SELECT string_agg(n::text, ',' ORDER BY n) FROM counter")).isEqualTo("1,2");
    assertThat(history()).containsExactly("alpha:1:slow", "alpha:2:more");
  }

  @Test
  void migrationsDoNotRunWhenMigrateIsFalse() throws IOException {
    database = DatabaseModule.builder().migrate(false);
    TestMigrations.write(modA, "V0001__a.sql", "CREATE TABLE a(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(tableExists("a")).isFalse();
    assertThat(tableExists("fw_schema_history")).isFalse();
  }

  @Test
  void migrationsRunOnTheDefaultInstanceOnly() throws IOException {
    pg.execute("DROP DATABASE IF EXISTS second");
    pg.createDatabase("second");
    Files.writeString(
        configDir.resolve("application.yaml"),
        """
        database:
          default: main
          instances:
            second: { url: %s, username: %s, password: %s }
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(
                pg.jdbcUrl("second"), pg.username(), pg.password(),
                pg.jdbcUrl(), pg.username(), pg.password()));
    TestMigrations.write(modA, "V0001__a.sql", "CREATE TABLE a(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(tableExists("a")).isTrue();
    assertThat(queryOn(pg.jdbcUrl("second"), "SELECT to_regclass('a') IS NOT NULL")).isEqualTo("f");
  }

  private String queryOn(String url, String sql) {
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, pg.username(), pg.password());
        java.sql.Statement s = c.createStatement();
        java.sql.ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void noMigrationSetsIsFine() {
    startAndClose();
    assertThat(tableExists("fw_schema_history")).isFalse();
  }

  // ---- failures ---------------------------------------------------------------------------

  @Test
  void aFailingFileRollsBackAndNamesModuleFileAndDatabaseError() throws IOException {
    TestMigrations.write(modA, "V0001__ok.sql", "CREATE TABLE ok(id int);");
    TestMigrations.write(
        modA, "V0002__bad.sql", "CREATE TABLE half(id int); INSERT INTO missing_table VALUES (1);");
    TestMigrations.write(modA, "V0003__never.sql", "CREATE TABLE never(id int);");
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.getMessage())
        .contains("module 'alpha'")
        .contains("V0002__bad.sql")
        .contains("missing_table")
        .contains("rolled back");
    assertThat(tableExists("ok")).isTrue();
    assertThat(tableExists("half")).as("the failed file's first statement is rolled back").isFalse();
    assertThat(tableExists("never")).isFalse();
    assertThat(history()).containsExactly("alpha:1:ok");
  }

  @Test
  void aFailedFileIsRetriedOnTheNextStart() throws IOException {
    TestMigrations.write(modA, "V0001__bad.sql", "SELECT 1/0;");
    failure(TestMigrations.in("alpha", modA));
    TestMigrations.write(modA, "V0001__bad.sql", "CREATE TABLE fixed(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(tableExists("fixed")).isTrue();
  }

  @Test
  void aChangedChecksumFailsStartUpAndAppliesNothing() throws IOException {
    TestMigrations.write(modA, "V0001__t.sql", "CREATE TABLE t(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    TestMigrations.write(modA, "V0001__t.sql", "CREATE TABLE t(id int, extra int);");
    TestMigrations.write(modA, "V0002__more.sql", "CREATE TABLE more(id int);");
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.getMessage()).contains("module 'alpha'").contains("V0001__t.sql").contains("changed");
    assertThat(tableExists("more")).as("nothing is applied after a validation failure").isFalse();
  }

  @Test
  void anAppliedVersionWhoseFileIsGoneFails() throws IOException {
    TestMigrations.write(modA, "V0001__t.sql", "CREATE TABLE t(id int);");
    TestMigrations.write(modA, "V0002__u.sql", "CREATE TABLE u(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    Files.delete(modA.resolve("migrations/V0002__u.sql"));
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.getMessage()).contains("module 'alpha'").contains("version 2").contains("file is gone");
  }

  @Test
  void aNeverAppliedFileBelowTheHighestAppliedVersionFails() throws IOException {
    TestMigrations.write(modA, "V0002__two.sql", "CREATE TABLE two(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    TestMigrations.write(modA, "V0001__one.sql", "CREATE TABLE one(id int);");
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.getMessage()).contains("V0001__one.sql").contains("never applied").contains("(2)");
    assertThat(tableExists("one")).isFalse();
  }

  @Test
  void aLowerVersionIsFineWhileNothingHigherWasApplied() throws IOException {
    TestMigrations.write(modA, "V0001__one.sql", "CREATE TABLE one(id int);");
    TestMigrations.write(modA, "V0003__three.sql", "CREATE TABLE three(id int);");
    startAndClose(TestMigrations.in("alpha", modA));
    assertThat(history()).containsExactly("alpha:1:one", "alpha:3:three");
  }

  @Test
  void twoFilesWithOneVersionFail() throws IOException {
    TestMigrations.write(modA, "V0001__a.sql", "CREATE TABLE a(id int);");
    TestMigrations.write(modA, "V1__b.sql", "CREATE TABLE b(id int);");
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.getMessage()).contains("module 'alpha'").contains("same version (1)");
    assertThat(tableExists("a")).isFalse();
  }

  @Test
  void badFileNamesAndVersionZeroFail() throws IOException {
    TestMigrations.write(modA, "V0000__zero.sql", "SELECT 1;");
    TestMigrations.write(modA, "create_table.sql", "SELECT 1;");
    TestMigrations.write(modA, "notes.txt", "ignored");
    StartupException e = failure(TestMigrations.in("alpha", modA));
    assertThat(e.problems()).hasSize(2);
    assertThat(e.getMessage()).contains("V0000__zero.sql").contains("create_table.sql");
  }

  @Test
  void aMissingLocationFails() throws IOException {
    Module broken =
        new Module() {
          @Override
          public String id() {
            return "broken";
          }

          @Override
          public void register(AppBuilder app) {
            app.contribute(
                MigrationSet.class,
                new MigrationSet("broken", getClass().getClassLoader(), "does/not/exist"));
          }
        };
    assertThat(failure(broken).getMessage()).contains("module 'broken'").contains("does/not/exist");
  }

  @Test
  void migrationsReadFromAJar() throws Exception {
    Path jar = dir.resolve("mig.jar");
    try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
      z.putNextEntry(new java.util.zip.ZipEntry("db/migration/"));
      z.closeEntry();
      z.putNextEntry(new java.util.zip.ZipEntry("db/migration/V0001__from_jar.sql"));
      z.write("CREATE TABLE from_jar(id int);".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      z.closeEntry();
    }
    Module m =
        new Module() {
          @Override
          public String id() {
            return "jarmod";
          }

          @Override
          public void register(AppBuilder app) throws Exception {
            app.contribute(
                MigrationSet.class,
                new MigrationSet(
                    "jarmod",
                    new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null),
                    "db/migration"));
          }
        };
    startAndClose(m);
    assertThat(tableExists("from_jar")).isTrue();
  }
}
