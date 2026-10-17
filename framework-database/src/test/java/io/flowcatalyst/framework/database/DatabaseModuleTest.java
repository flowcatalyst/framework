package io.flowcatalyst.framework.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseModuleTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void reset() {
    pg.reset();
  }

  private void yaml(String text) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), text);
  }

  private App start(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env).module(new DatabaseModule()).start();
  }

  private StartupException failure(Map<String, String> env) {
    Throwable t = catchThrowable(() -> start(env).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  private String instance(String name) {
    return "%s: { url: %s, username: %s, password: %s }"
        .formatted(name, pg.jdbcUrl(), pg.username(), pg.password());
  }

  @Test
  void startsWithAPooledInstanceAndAHealthCheck() throws Exception {
    yaml(
        """
        database:
          instances:
            main:
              url: %s
              username: %s
              password: %s
              pool: { max: 3, min-idle: 1, connect-timeout: 2s }
              migrate: true
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    try (App app = start(Map.of())) {
      Database db = app.get(Databases.class).defaultInstance();
      assertThat(db.name()).isEqualTo("main");
      HikariDataSource pool = (HikariDataSource) db.dataSource();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
      assertThat(pool.getPoolName()).isEqualTo("fw-db-main");
      db.ping();
      assertThat(app.health().check().results()).containsEntry("database:main", new HealthResult.Up());
    }
  }

  @Test
  void passwordCanComeFromTheEnvironmentAsASecret() throws IOException {
    yaml(
        """
        database:
          instances:
            main: { url: %s, username: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username()));
    try (App app = start(Map.of("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password()))) {
      assertThat(app.get(Databases.class).defaultInstance()).isNotNull();
    }
  }

  @Test
  void severalInstancesAndTheDefault() throws IOException {
    pg.execute("DROP DATABASE IF EXISTS other");
    pg.createDatabase("other");
    yaml(
        """
        database:
          default: second
          instances:
            first: { url: %s, username: %s, password: %s }
            second: { url: %s, username: %s, password: %s }
        """
            .formatted(
                pg.jdbcUrl(), pg.username(), pg.password(),
                pg.jdbcUrl("other"), pg.username(), pg.password()));
    try (App app = start(Map.of())) {
      Databases dbs = app.get(Databases.class);
      assertThat(dbs.names()).containsExactly("first", "second");
      assertThat(dbs.defaultInstance().name()).isEqualTo("second");
      assertThat(dbs.get("first").name()).isEqualTo("first");
      assertThatIllegalArgumentException()
          .isThrownBy(() -> dbs.get("third"))
          .withMessageContaining("first, second");
      assertThat(app.health().check().results().keySet())
          .containsExactly("database:first", "database:second");
    }
  }

  @Test
  void noInstancesIsValid() {
    try (App app = start(Map.of())) {
      Databases dbs = app.get(Databases.class);
      assertThat(dbs.names()).isEmpty();
      assertThatIllegalStateException().isThrownBy(dbs::defaultInstance);
    }
  }

  @Test
  void anUnreachableDatabaseFailsStartUpNamingTheInstance() throws IOException {
    yaml(
        """
        database:
          instances:
            main: { url: "jdbc:postgresql://127.0.0.1:1/none", username: u, password: hunter2, pool: { connect-timeout: 1s } }
        """);
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("module 'database'")
        .contains("database.instances.main")
        .contains("cannot connect to database instance 'main'");
    assertThat(e.getMessage()).doesNotContain("hunter2");
  }

  @Test
  void aWrongPasswordFailsAndTheMessageDoesNotRepeatIt() throws IOException {
    yaml(
        """
        database:
          instances:
            main: { url: %s, username: %s, password: "wrong-password-xyz" }
        """
            .formatted(pg.jdbcUrl(), pg.username()));
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("main").doesNotContain("wrong-password-xyz");
  }

  @Test
  void aMissingUrlAndAnUnknownKeyAreReported() throws IOException {
    yaml(
        """
        database:
          instances:
            a: { username: u }
            b: { url: %s, username: %s, password: %s, pool: { maxx: 3 }, typo: 1 }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("database.instances.a.url")
        .contains("database.instances.b.pool.maxx")
        .contains("database.instances.b.typo");
  }

  @Test
  void aFailureClosesTheInstancesAlreadyBuilt() throws IOException {
    yaml(
        """
        database:
          default: good
          instances:
            good: { url: %s, username: %s, password: %s }
            bad: { url: "jdbc:postgresql://127.0.0.1:1/none", username: u, pool: { connect-timeout: 1s } }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    failure(Map.of());
    // The good pool is closed with its Hikari housekeeping threads; nothing should remain.
    assertThat(
            Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("fw-db-good")))
        .allSatisfy(t -> assertThat(t.isAlive()).isFalse());
  }

  @Test
  void closingTheApplicationClosesThePools() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    App app = start(Map.of());
    HikariDataSource pool = (HikariDataSource) app.get(Databases.class).defaultInstance().dataSource();
    app.close();
    assertThat(pool.isClosed()).isTrue();
  }

  @Test
  void requireTableChecksExistenceAndExplainsThatMigrationsDoNotRunThere() throws Exception {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    pg.execute("CREATE TABLE present(id int)");
    try (App app = start(Map.of())) {
      Database db = app.get(Databases.class).defaultInstance();
      db.requireTable("present", "the thing");
      assertThat(db.tableExists("absent")).isFalse();
      assertThatIllegalStateException()
          .isThrownBy(() -> db.requireTable("absent", "the thing"))
          .withMessageContaining("the thing")
          .withMessageContaining("'absent'")
          .withMessageContaining("'main'")
          .withMessageContaining("migrations do not run on this instance");
    }
  }

  @Test
  void healthIsDownWhenThePoolIsClosed() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    try (App app = start(Map.of())) {
      ((HikariDataSource) app.get(Databases.class).defaultInstance().dataSource()).close();
      assertThat(app.health().check().results().get("database:main"))
          .isInstanceOf(HealthResult.Down.class);
    }
  }

  // ---- an instance is a connection: configuration (config-and-code contract, section 3.2) ------

  @Test
  void theDefaults() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    try (App app = start(Map.of())) {
      Database db = app.get(Databases.class).defaultInstance();
      HikariDataSource pool = (HikariDataSource) db.dataSource();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
      assertThat(pool.getMinimumIdle()).isEqualTo(1);
      assertThat(pool.getConnectionTimeout()).isEqualTo(5000);
      assertThat(pool.getDataSourceProperties()).containsEntry("loginTimeout", "10");
      assertThat(db.migrates()).isTrue();
    }
  }

  @Test
  void eachInstanceHasItsOwnPoolAndTheEnvironmentOverridesOneValue() throws IOException {
    pg.execute("DROP DATABASE IF EXISTS other");
    pg.createDatabase("other");
    yaml(
        """
        database:
          default: first
          instances:
            first: { url: %s, username: %s, password: %s, pool: { max: 7 } }
            second: { url: %s, username: %s, password: %s, pool: { max: 5, min-idle: 0 }, migrate: false }
        """
            .formatted(
                pg.jdbcUrl(), pg.username(), pg.password(),
                pg.jdbcUrl("other"), pg.username(), pg.password()));
    try (App app = start(Map.of("DATABASE_INSTANCES_SECOND_POOL_MAX", "2"))) {
      Databases dbs = app.get(Databases.class);
      assertThat(((HikariDataSource) dbs.get("first").dataSource()).getMaximumPoolSize()).isEqualTo(7);
      assertThat(((HikariDataSource) dbs.get("second").dataSource()).getMaximumPoolSize()).isEqualTo(2);
      assertThat(dbs.get("second").migrates()).isFalse();
    }
  }

  @Test
  void thePoolRangesAreCheckedAtStartUp() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    // just inside every bound starts
    try (App app =
        start(
            Map.of(
                "DATABASE_INSTANCES_MAIN_POOL_MAX", "1",
                "DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE", "0",
                "DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "250ms"))) {
      assertThat(((HikariDataSource) app.get(Databases.class).defaultInstance().dataSource()).getConnectionTimeout())
          .isEqualTo(250);
    }
    try (App app =
        start(
            Map.of(
                "DATABASE_INSTANCES_MAIN_POOL_MAX", "1000",
                "DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE", "0",
                "DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "5m"))) {
      assertThat(((HikariDataSource) app.get(Databases.class).defaultInstance().dataSource()).getMaximumPoolSize())
          .isEqualTo(1000);
    }
    // min-idle may equal max
    try (App app = start(Map.of("DATABASE_INSTANCES_MAIN_POOL_MAX", "3", "DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE", "3"))) {
      assertThat(((HikariDataSource) app.get(Databases.class).defaultInstance().dataSource()).getMinimumIdle())
          .isEqualTo(3);
    }
    // just outside every bound fails, naming the key and where it came from
    assertOutOfRange("DATABASE_INSTANCES_MAIN_POOL_MAX", "0", "database.instances.main.pool.max", "1 to 1000");
    assertOutOfRange("DATABASE_INSTANCES_MAIN_POOL_MAX", "1001", "database.instances.main.pool.max", "1 to 1000");
    assertOutOfRange("DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE", "-1", "database.instances.main.pool.min-idle", "0 to pool.max");
    assertOutOfRange("DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE", "11", "database.instances.main.pool.min-idle", "0 to pool.max (10)");
    assertOutOfRange(
        "DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "249ms", "database.instances.main.pool.connect-timeout", "250ms to 5m");
    assertOutOfRange(
        "DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "300001ms", "database.instances.main.pool.connect-timeout", "250ms to 5m");
  }

  private void assertOutOfRange(String variable, String value, String key, String message) {
    StartupException e = failure(Map.of(variable, value));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.module()).isEqualTo("database");
      assertThat(p.key()).isEqualTo(key);
      assertThat(p.source()).isEqualTo("environment variable " + variable);
      assertThat(p.message()).contains(message);
    });
  }

  @Test
  void aDriverKeyFailsStartUpInAFileAndInTheEnvironment() throws IOException {
    yaml(
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s, driver: postgresql }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    StartupException e = failure(Map.of());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("database.instances.main.driver");
      assertThat(p.message()).contains("PostgreSQL is the only database driver");
    });
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    assertThat(failure(Map.of("DATABASE_INSTANCES_MAIN_DRIVER", "postgresql")).problems())
        .singleElement()
        .satisfies(p -> assertThat(p.source()).isEqualTo("environment variable DATABASE_INSTANCES_MAIN_DRIVER"));
  }
}
