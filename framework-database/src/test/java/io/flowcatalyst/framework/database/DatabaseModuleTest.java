package io.flowcatalyst.framework.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.StartupException;
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
    return start(env, new DatabaseModule());
  }

  private App start(Map<String, String> env, DatabaseModule module) {
    return App.builder().configDirectory(dir).environment(env).module(module).start();
  }

  private StartupException failure(Map<String, String> env) {
    return failure(env, new DatabaseModule());
  }

  private StartupException failure(Map<String, String> env, DatabaseModule module) {
    Throwable t = catchThrowable(() -> start(env, module).close());
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
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    DatabaseModule module =
        DatabaseModule.builder()
            .pool(DatabasePool.defaults().withMaxSize(3).withMinIdle(2).withConnectTimeout(Duration.ofSeconds(2)))
            .build();
    try (App app = start(Map.of(), module)) {
      Database db = app.get(Databases.class).defaultInstance();
      assertThat(db.name()).isEqualTo("main");
      HikariDataSource pool = (HikariDataSource) db.dataSource();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
      assertThat(pool.getMinimumIdle()).isEqualTo(2);
      assertThat(pool.getConnectionTimeout()).isEqualTo(2000);
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
            main: { url: "jdbc:postgresql://127.0.0.1:1/none", username: u, password: hunter2 }
        """);
    StartupException e =
        failure(Map.of(), DatabaseModule.builder().pool(DatabasePool.defaults().withConnectTimeout(Duration.ofSeconds(1))).build());
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
            b: { url: %s, username: %s, password: %s, typo: 1 }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("database.instances.a.url")
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
            bad: { url: "jdbc:postgresql://127.0.0.1:1/none", username: u }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    failure(Map.of(), DatabaseModule.builder().pool("bad", DatabasePool.defaults().withConnectTimeout(Duration.ofSeconds(1))).build());
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

  // ---- behaviour in code (config-and-code contract, section 3.2) ----------------------------

  @Test
  void theDefaultPoolIsTheOldConfigurationDefault() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    try (App app = start(Map.of())) {
      HikariDataSource pool = (HikariDataSource) app.get(Databases.class).defaultInstance().dataSource();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
      assertThat(pool.getMinimumIdle()).isEqualTo(1);
      assertThat(pool.getConnectionTimeout()).isEqualTo(5000);
      assertThat(app.get(Databases.class).defaultInstance().migrates()).isTrue();
    }
  }

  @Test
  void aPoolForOneInstanceWinsOverTheCommonOne() throws IOException {
    pg.execute("DROP DATABASE IF EXISTS other");
    pg.createDatabase("other");
    yaml(
        """
        database:
          default: first
          instances:
            first: { url: %s, username: %s, password: %s }
            second: { url: %s, username: %s, password: %s }
        """
            .formatted(
                pg.jdbcUrl(), pg.username(), pg.password(),
                pg.jdbcUrl("other"), pg.username(), pg.password()));
    DatabaseModule module =
        DatabaseModule.builder()
            .pool(DatabasePool.defaults().withMaxSize(7))
            .pool("second", DatabasePool.defaults().withMaxSize(2).withMinIdle(0))
            .build();
    try (App app = start(Map.of(), module)) {
      Databases dbs = app.get(Databases.class);
      assertThat(((HikariDataSource) dbs.get("first").dataSource()).getMaximumPoolSize()).isEqualTo(7);
      assertThat(((HikariDataSource) dbs.get("second").dataSource()).getMaximumPoolSize()).isEqualTo(2);
    }
  }

  @Test
  void aPoolForAnInstanceThatIsNotConfiguredFailsStartUp() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    StartupException e =
        failure(Map.of(), DatabaseModule.builder().pool("mian", DatabasePool.defaults()).build());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("database.instances.mian");
      assertThat(p.message()).contains("not configured").contains("main");
    });
  }

  @Test
  void poolNamesAreCheckedAndGivenOnce() {
    DatabaseModule.Builder b = DatabaseModule.builder().pool("main", DatabasePool.defaults());
    assertThatIllegalArgumentException().isThrownBy(() -> b.pool("main", DatabasePool.defaults()));
    assertThatIllegalArgumentException().isThrownBy(() -> b.pool("Main", DatabasePool.defaults()));
  }

  @Test
  void databasePoolRanges() {
    DatabasePool d = DatabasePool.defaults();
    assertThat(d).isEqualTo(new DatabasePool(10, 1, Duration.ofSeconds(5)));
    assertThat(d.withMaxSize(1000).maxSize()).isEqualTo(1000);
    assertThat(d.withMaxSize(1).withMinIdle(0).maxSize()).isEqualTo(1);
    assertThat(d.withConnectTimeout(Duration.ofMillis(250)).connectTimeout()).isEqualTo(Duration.ofMillis(250));
    assertThat(d.withConnectTimeout(Duration.ofMinutes(5)).connectTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMaxSize(0)).withMessageContaining("maxSize");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMaxSize(1001)).withMessageContaining("maxSize");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMinIdle(-1)).withMessageContaining("minIdle");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMinIdle(11)).withMessageContaining("minIdle");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> d.withConnectTimeout(Duration.ofMillis(249)))
        .withMessageContaining("connectTimeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> d.withConnectTimeout(Duration.ofMinutes(5).plusNanos(1)))
        .withMessageContaining("connectTimeout");
  }

  static final Setting<Integer> POOL = Setting.integer("orders.db-pool").withDefault(10);

  @Test
  void thePoolMayComeFromAnApplicationSetting() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    Module orders =
        new Module() {
          @Override
          public String id() {
            return "orders";
          }

          @Override
          public void register(AppBuilder app) {
            app.declare(POOL);
          }
        };
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("ORDERS_DB_POOL", "4"))
            .module(DatabaseModule.builder().pool(config -> DatabasePool.defaults().withMaxSize(config.get(POOL))).build())
            .module(orders)
            .start()) {
      HikariDataSource pool = (HikariDataSource) app.get(Databases.class).defaultInstance().dataSource();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
    }
  }

  @Test
  void theRemovedInstanceKeysFailStartUpNamingTheCode() throws IOException {
    yaml(
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s, driver: postgresql, migrate: false, pool: { max: 3, min-idle: 1, connect-timeout: 2s } }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    StartupException e = failure(Map.of("DATABASE_INSTANCES_MAIN_POOL_MAX", "4"));
    assertThat(e.problems())
        .extracting(p -> p.key() + " <- " + p.source())
        .containsExactlyInAnyOrder(
            "database.instances.main.driver <- " + dir.resolve("application.yaml"),
            "database.instances.main.migrate <- " + dir.resolve("application.yaml"),
            "database.instances.main.pool.max <- " + dir.resolve("application.yaml"),
            "database.instances.main.pool.min-idle <- " + dir.resolve("application.yaml"),
            "database.instances.main.pool.connect-timeout <- " + dir.resolve("application.yaml"));
    assertThat(e.getMessage())
        .contains("DatabaseModule.builder().pool(DatabasePool)")
        .contains("DatabaseModule.builder().migrate(boolean)");
  }

  @Test
  void aRemovedPoolKeyInTheEnvironmentAloneFailsStartUp() throws IOException {
    yaml("database:\n  instances:\n    " + instance("main") + "\n");
    StartupException e = failure(Map.of("DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "1s"));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("database.instances.main.pool.connect-timeout");
      assertThat(p.source()).isEqualTo("environment variable DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT");
    });
  }
}
