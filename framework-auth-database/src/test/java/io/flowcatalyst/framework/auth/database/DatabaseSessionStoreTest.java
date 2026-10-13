package io.flowcatalyst.framework.auth.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.auth.MutableClock;
import io.flowcatalyst.framework.auth.SessionHandles;
import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.auth.SessionStoreContract;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The store contract on PostgreSQL 18, and what only this driver does: hashing and purging. */
class DatabaseSessionStoreTest extends SessionStoreContract {

  static PostgresFixture pg;
  static HikariDataSource dataSource;

  @BeforeAll
  static void startPostgres() throws Exception {
    pg = PostgresFixture.start();
    String ddl;
    try (var in = AuthDatabaseModule.class.getResourceAsStream("/db/migration/auth/V0001__create_session_table.sql")) {
      ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    pg.execute(ddl);
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(pg.jdbcUrl());
    dataSource.setUsername(pg.username());
    dataSource.setPassword(pg.password());
    dataSource.setMaximumPoolSize(20);
  }

  @AfterAll
  static void stop() {
    dataSource.close();
    pg.close();
  }

  @Override
  protected SessionStore newStore(MutableClock clock) {
    pg.execute("TRUNCATE fw_session");
    return DatabaseSessionStore.create(dataSource, clock);
  }

  @Test
  void onlyTheHashOfAHandleIsStored() {
    String handle = store.create(SessionStore.SESSION, "{}", clock.instant().plus(Duration.ofMinutes(5)));
    assertThat(pg.query("SELECT count(*) FROM fw_session WHERE id_hash = '" + handle + "'")).isEqualTo("0");
    assertThat(pg.query("SELECT count(*) FROM fw_session WHERE id_hash = '" + SessionHandles.hash(handle) + "'")).isEqualTo("1");
  }

  @Test
  void purgeDeletesOnlyExpiredRowsInBatches() {
    DatabaseSessionStore db = (DatabaseSessionStore) store;
    for (int i = 0; i < 5; i++) {
      store.create(SessionStore.SESSION, "{}", clock.instant().plus(Duration.ofMinutes(1)));
    }
    String live = store.create(SessionStore.SESSION, "{\"live\":true}", clock.instant().plus(Duration.ofHours(1)));
    clock.advance(Duration.ofMinutes(2));
    assertThat(db.purge(3)).isEqualTo(3);
    assertThat(db.purge(3)).isEqualTo(2);
    assertThat(db.purge(3)).isZero();
    assertThat(store.read(SessionStore.SESSION, live)).isPresent();
  }
}
