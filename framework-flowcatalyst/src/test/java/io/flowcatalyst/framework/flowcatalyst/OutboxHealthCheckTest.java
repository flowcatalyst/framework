package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.OutboxModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code outbox} health check against the real {@code outbox_messages} migration. */
class OutboxHealthCheckTest {

  static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

  static PostgresFixture pg;

  @TempDir Path dir;

  FakePlatform platform;

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
    platform.close();
  }

  private Map<String, String> env(String... extra) {
    Map<String, String> env = new HashMap<>();
    env.put("FLOWCATALYST_BASE_URL", platform.baseUrl());
    env.put("FLOWCATALYST_APPLICATION", "orders");
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    for (int i = 0; i < extra.length; i += 2) {
      env.put(extra[i], extra[i + 1]);
    }
    return env;
  }

  private App start(boolean withDatabase, boolean withOutbox, Map<String, String> env) {
    AppBuilder b = App.builder().configDirectory(dir).environment(env).instantSource(() -> NOW);
    if (withDatabase) {
      b.module(new DatabaseModule());
    }
    if (withOutbox) {
      b.module(new OutboxModule());
    }
    b.module(new FlowCatalystModule());
    return b.start();
  }

  private App start() {
    return start(true, true, env());
  }

  private static void insert(String id, int status, Instant createdAt) {
    pg.execute(
        "INSERT INTO outbox_messages (id, type, payload, status, created_at, updated_at)"
            + " VALUES ('%s', 'EVENT', '{}', %d, '%s', '%s')"
                .formatted(id, status, createdAt, createdAt));
  }

  private static HealthResult outbox(App app) {
    return app.health().check().results().get("outbox");
  }

  @Test
  void anEmptyOutboxIsUp() {
    try (App app = start()) {
      assertThat(outbox(app)).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void aPendingRowYoungerThanTheLimitIsUp() {
    try (App app = start()) {
      insert("r1", 0, NOW.minus(Duration.ofMinutes(4)));
      assertThat(outbox(app)).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void aPendingRowExactlyAtTheLimitIsUp() {
    try (App app = start()) {
      insert("r1", 0, NOW.minus(Duration.ofMinutes(5)));
      assertThat(outbox(app)).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void aPendingRowOlderThanTheLimitIsDownAndSaysWhy() {
    try (App app = start()) {
      insert("young", 0, NOW.minusSeconds(10));
      insert("old", 0, NOW.minus(Duration.ofMinutes(5)).minusSeconds(1));
      HealthResult result = outbox(app);
      assertThat(result).isInstanceOf(HealthResult.Down.class);
      assertThat(((HealthResult.Down) result).reason())
          .contains("301s")
          .contains("stale after 300s")
          .contains("outbox processor");
    }
  }

  @Test
  void anInProgressRowCountsAsUndelivered() {
    try (App app = start()) {
      insert("claimed", 9, NOW.minus(Duration.ofMinutes(30)));
      assertThat(outbox(app)).isInstanceOf(HealthResult.Down.class);
    }
  }

  @Test
  void terminallyFailedRowsAreNotABacklog() {
    try (App app = start()) {
      for (int status : new int[] {1, 2, 3, 4, 5, 6}) {
        insert("failed-" + status, status, NOW.minus(Duration.ofDays(2)));
      }
      assertThat(outbox(app)).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void theLimitIsASetting() {
    try (App app = start(true, true, env("FLOWCATALYST_OUTBOX_STALE_AFTER", "30s"))) {
      insert("r1", 0, NOW.minusSeconds(31));
      assertThat(outbox(app)).isInstanceOf(HealthResult.Down.class);
    }
  }

  @Test
  void theCheckOnlyReads() {
    try (App app = start()) {
      insert("old", 0, NOW.minus(Duration.ofHours(1)));
      insert("claimed", 9, NOW.minus(Duration.ofHours(1)));
      String before = pg.query("SELECT string_agg(id || ':' || status || ':' || updated_at, ',' ORDER BY id) FROM outbox_messages");
      outbox(app);
      outbox(app);
      String after = pg.query("SELECT string_agg(id || ':' || status || ':' || updated_at, ',' ORDER BY id) FROM outbox_messages");
      assertThat(after).isEqualTo(before);
    }
  }

  @Test
  void withoutTheTableThereIsNoCheck() {
    try (App app = start(true, false, env())) {
      assertThat(app.health().check().results())
          .containsKey("database:main")
          .doesNotContainKey("outbox");
    }
  }

  @Test
  void withoutADatabaseThereIsNoCheck() {
    try (App app = start(false, false, env())) {
      assertThat(app.health().check().results())
          .containsKey("flowcatalyst")
          .doesNotContainKey("outbox");
    }
  }

  @Test
  void aDisabledModuleAddsNoCheck() {
    try (App app = start(true, true, Map.of("FLOWCATALYST_ENABLED", "false"))) {
      assertThat(app.health().check().results()).doesNotContainKey("outbox");
    }
  }

  @Test
  void theDatabaseGoingAwayIsDownNotUp() {
    try (App app = start()) {
      pg.execute("ALTER TABLE outbox_messages RENAME TO outbox_messages_gone");
      HealthResult result = outbox(app);
      assertThat(result).isInstanceOf(HealthResult.Down.class);
      assertThat(((HealthResult.Down) result).reason()).contains("outbox_messages");
    }
  }

  @Test
  void aNonPositiveLimitFailsStartUp() {
    Throwable t = catchThrowable(() -> start(true, true, env("FLOWCATALYST_OUTBOX_STALE_AFTER", "0s")).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems())
        .extracting(Problem::key)
        .containsExactly("flowcatalyst.outbox.stale-after");
  }
}
