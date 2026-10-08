package io.flowcatalyst.framework.locks.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreContract;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The PostgreSQL driver against the shared contract suite, plus what only a database can show. */
class DatabaseLockStoreTest extends LockStoreContract {

  static PostgresFixture pg;
  static HikariDataSource pool;

  @BeforeAll
  static void start() throws IOException {
    pg = PostgresFixture.start();
    // The migration file the module ships, applied by hand: the module path has its own test.
    try (var in =
        DatabaseLockStoreTest.class.getClassLoader().getResourceAsStream("db/migration/locks/V0001__create_lock_table.sql")) {
      assertThat(in).isNotNull();
      pg.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(pg.jdbcUrl());
    config.setUsername(pg.username());
    config.setPassword(pg.password());
    config.setMaximumPoolSize(16);
    pool = new HikariDataSource(config);
  }

  @AfterAll
  static void stop() {
    pool.close();
    pg.close();
  }

  @Override
  protected LockStore newStore() {
    return new LockStore(new DatabaseLockDriver(pool), MonotonicClock.system());
  }

  @Override
  protected void letTimePass(Duration d) throws InterruptedException {
    Thread.sleep(d.toMillis());
  }

  @Override
  protected void expireNow(LockKey key) {
    try (Connection c = pool.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE fw_lock SET expires_at = clock_timestamp() - interval '1 second' WHERE lock_key = ?")) {
      ps.setString(1, key.stored());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void twoStoresOnTheSameDatabaseExcludeEachOtherLikeTwoProcesses() throws Exception {
    LockStore a = newStore();
    LockStore b = newStore();
    LockKey key = LockKey.service("shared-" + System.nanoTime());
    int n = 40;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger won = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      LockStore s = i % 2 == 0 ? a : b;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      return;
                    }
                    if (s.tryAcquire(key, Duration.ofSeconds(60)) instanceof AcquireResult.Acquired) {
                      won.incrementAndGet();
                    }
                  }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }
    assertThat(won).hasValue(1);
  }

  @Test
  void fencingTokensComeFromOneSequenceAcrossStoresAndKeys() {
    LockStore a = newStore();
    LockStore b = newStore();
    long t1 = ((AcquireResult.Acquired) a.tryAcquire(LockKey.service("seq-1-" + System.nanoTime()), Duration.ofSeconds(5))).lease().fencingToken();
    long t2 = ((AcquireResult.Acquired) b.tryAcquire(LockKey.service("seq-2-" + System.nanoTime()), Duration.ofSeconds(5))).lease().fencingToken();
    assertThat(t2).isGreaterThan(t1);
  }

  @Test
  void aReleasedRowKeepsItsLastTokenSoTheNextTokenIsHigher() {
    LockKey key = LockKey.service("reuse-" + System.nanoTime());
    var first = ((AcquireResult.Acquired) store.tryAcquire(key, Duration.ofSeconds(5))).lease();
    store.release(first);
    var second = ((AcquireResult.Acquired) store.tryAcquire(key, Duration.ofSeconds(5))).lease();
    assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
  }

  @Test
  void anUnreachableDatabaseIsAnExceptionNotABusy() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
    config.setUsername("u");
    config.setPassword("p");
    config.setConnectionTimeout(500);
    config.setInitializationFailTimeout(-1);
    try (HikariDataSource dead = new HikariDataSource(config)) {
      LockStore broken = new LockStore(new DatabaseLockDriver(dead), MonotonicClock.system());
      LockKey key = LockKey.service("dead");
      assertThatThrownBy(() -> broken.tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(LockStoreUnavailableException.class)
          .hasCauseInstanceOf(SQLException.class);
    }
  }
}
