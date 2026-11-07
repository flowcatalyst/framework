package io.flowcatalyst.framework.http.idempotency.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.idempotency.Idempotency;
import io.flowcatalyst.framework.http.idempotency.IdempotencyScope;
import io.flowcatalyst.framework.http.idempotency.IdempotencyStore;
import io.flowcatalyst.framework.http.idempotency.IdempotencyStoreContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PostgreSQL store against the shared contract, its pruning, and the module's wiring. */
class DatabaseIdempotencyStoreTest extends IdempotencyStoreContract {

  static PostgresFixture pg;
  static HikariDataSource pool;
  static DatabaseIdempotencyStore store;

  @TempDir Path dir;

  @BeforeAll
  static void start() throws IOException {
    pg = PostgresFixture.start();
    // the migration the module ships, applied by hand; the module path has its own test below
    try (var in =
        DatabaseIdempotencyStoreTest.class
            .getClassLoader()
            .getResourceAsStream("db/migration/http-idempotency/V0001__create_idempotency_keys.sql")) {
      assertThat(in).isNotNull();
      pg.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(pg.jdbcUrl());
    config.setUsername(pg.username());
    config.setPassword(pg.password());
    config.setMaximumPoolSize(16);
    pool = new HikariDataSource(config);
    store = new DatabaseIdempotencyStore(pool);
  }

  @AfterAll
  static void stop() {
    pool.close();
    pg.close();
  }

  @Override
  protected IdempotencyStore store() {
    return store;
  }

  @Test
  void pruneForgetsKeysTakenBeforeTheCutOffInBatches() {
    String run = Long.toHexString(System.nanoTime());
    for (int i = 0; i < 3; i++) {
      reserve(store, new IdempotencyScope("prune-" + run, "u", "old-" + i), "h", "t" + i, NOW.minus(Duration.ofDays(400)));
    }
    IdempotencyScope fresh = new IdempotencyScope("prune-" + run, "u", "fresh");
    reserve(store, fresh, "h", "t", NOW.minus(Duration.ofDays(399)));
    assertThat(store.prune(NOW.minus(Duration.ofDays(399)).minusSeconds(1), 2)).isEqualTo(2);
    assertThat(store.prune(NOW.minus(Duration.ofDays(399)).minusSeconds(1), 2)).isEqualTo(1);
    assertThat(store.prune(NOW.minus(Duration.ofDays(399)).minusSeconds(1), 2)).isZero();
    assertThat(store.find(fresh)).isPresent();
  }

  @Test
  void theModuleMigratesTheTableAndProvidesTheStore() throws Exception {
    String database = "idem_module_" + Long.toHexString(System.nanoTime());
    pg.execute("CREATE DATABASE " + database);
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        idempotency:
          retention: 2h
          wait: 3s
          in-flight-timeout: 1m
        """.formatted(pg.jdbcUrl(database), pg.username(), pg.password()));
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("APP_ENV", "test"))
            .module(new DatabaseModule())
            .module(new IdempotencyDatabaseModule())
            .start()) {
      IdempotencyStore provided = app.get(IdempotencyStore.class);
      assertThat(provided).isInstanceOf(DatabaseIdempotencyStore.class);
      IdempotencyScope k = new IdempotencyScope("acme", "u", "module");
      assertThat(reserve(provided, k, "h", "t1")).isTrue();
      assertThat(reserve(provided, k, "h", "t2")).isFalse();
      assertThat(app.get(Idempotency.Options.class))
          .isEqualTo(new Idempotency.Options(Duration.ofHours(2), Duration.ofSeconds(3), Duration.ofMinutes(1)));
      // the Go framework's table and migration set: the history names the module http-idempotency
      try (var c = DriverManager.getConnection(pg.jdbcUrl(database), pg.username(), pg.password());
          var columns =
              c.createStatement()
                  .executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = 'fw_idempotency_keys'"
                      + " AND column_name IN ('idempotency_key', 'token')")) {
        assertThat(columns.next()).isTrue();
        assertThat(columns.getInt(1)).isEqualTo(2);
        try (var history =
            c.createStatement().executeQuery("SELECT module, version FROM fw_schema_history WHERE module = 'http-idempotency'")) {
          assertThat(history.next()).isTrue();
          assertThat(history.getInt(2)).isEqualTo(1);
        }
      }
    }
  }
}
