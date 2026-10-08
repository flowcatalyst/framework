package io.flowcatalyst.framework.examples.locksdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.redis.RedisFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Starts the example against real PostgreSQL and Redis, using the config the example ships. */
class LocksDemoTest {

  static PostgresFixture pg;
  static RedisFixture redis;

  @BeforeAll
  static void start() {
    pg = PostgresFixture.start();
    redis = RedisFixture.start();
  }

  @AfterAll
  static void stop() {
    redis.close();
    pg.close();
  }

  private Map<String, String> env() {
    return Map.of(
        "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
        "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
        "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password(),
        "REDIS_INSTANCES_DEFAULT_URL", redis.url());
  }

  @Test
  void startsWithAllThreeLockInstancesAndTakesALockOnEach() throws Exception {
    try (App app = LocksDemo.builder().configDirectory(Path.of("config")).environment(env()).start()) {
      List<String> report = LocksDemo.run(app);
      assertThat(report.get(0)).startsWith("main: completed with token ");
      assertThat(report.get(1)).startsWith("shared: completed with token ");
      assertThat(report.get(2)).startsWith("local: completed with token ");
      assertThat(report.get(3)).isEqualTo("health: up [database:main, redis:default]");
      assertThat(app.moduleIds())
          .containsExactly("core", "database", "redis", "locks", "locks-database", "locks-redis");
    }
    assertThat(pg.query("SELECT module || ':' || version FROM fw_schema_history"))
        .isEqualTo("locks-database:1");
  }

  @Test
  void aMissingDatabaseFailsStartUpNamingTheInstance() {
    Map<String, String> bad = Map.of(
        "DATABASE_INSTANCES_MAIN_URL", "jdbc:postgresql://127.0.0.1:1/none",
        "DATABASE_INSTANCES_MAIN_PASSWORD", "x",
        "DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT", "1s",
        "REDIS_INSTANCES_DEFAULT_URL", redis.url());
    Throwable t = catchThrowable(() -> LocksDemo.builder().configDirectory(Path.of("config")).environment(bad).start());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("database.instances.main").contains("cannot connect");
  }
}
