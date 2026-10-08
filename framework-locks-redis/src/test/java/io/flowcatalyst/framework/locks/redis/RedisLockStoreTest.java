package io.flowcatalyst.framework.locks.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.Lease;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreContract;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import io.flowcatalyst.framework.locks.RefreshResult;
import io.flowcatalyst.framework.locks.ReleaseResult;
import io.flowcatalyst.framework.redis.RedisFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/** The Redis driver against the shared contract suite, plus what only Redis can show. */
class RedisLockStoreTest extends LockStoreContract {

  static RedisFixture redis;
  static RedisClient client;

  @BeforeAll
  static void start() {
    redis = RedisFixture.start();
    client = RedisClient.create(redis.host(), redis.port());
  }

  @AfterAll
  static void stop() {
    client.close();
    redis.close();
  }

  private RedisLockDriver driver;

  @Override
  protected LockStore newStore() {
    RedisLockDriver d = new RedisLockDriver(client, "fw:lock:");
    if (store == null) {
      driver = d;
    }
    return new LockStore(d, MonotonicClock.system());
  }

  @Override
  protected void letTimePass(Duration d) throws InterruptedException {
    Thread.sleep(d.toMillis());
  }

  @Override
  protected void expireNow(LockKey key) {
    driver.expireNow(key.stored());
  }

  private Lease lease(LockStore s, String name, Duration d) {
    return ((AcquireResult.Acquired) s.tryAcquire(LockKey.service(name), d)).lease();
  }

  @Test
  void scriptsAreReloadedAfterTheServerForgetsThem() {
    String name = "noscript-" + System.nanoTime();
    Lease held = lease(store, name, Duration.ofSeconds(30));
    client.scriptFlush(); // the server forgets every script, as after a restart
    assertThat(store.refresh(held, Duration.ofSeconds(30))).isInstanceOf(RefreshResult.Refreshed.class);
    client.scriptFlush();
    assertThat(store.release(held)).isInstanceOf(ReleaseResult.Released.class);
    client.scriptFlush();
    assertThat(store.tryAcquire(LockKey.service(name), Duration.ofSeconds(30)))
        .isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void theLockIsStoredAsOwnerColonFenceWithAMillisecondTtlUnderThePrefix() {
    String name = "layout-" + System.nanoTime();
    Lease held = lease(store, name, Duration.ofSeconds(30));
    String stored = client.get("fw:lock:svc:" + name);
    assertThat(stored).isEqualTo(held.ownerToken() + ":" + held.fencingToken());
    assertThat(client.pttl("fw:lock:svc:" + name)).isBetween(20_000L, 30_000L);
    assertThat(client.ttl("fw:lock:__fence")).as("the counter never expires").isEqualTo(-1L);
    store.release(held);
    assertThat(client.exists("fw:lock:svc:" + name)).isFalse();
  }

  @Test
  void differentPrefixesAreIndependentLockSpaces() {
    LockStore a = new LockStore(new RedisLockDriver(client, "pa:"), MonotonicClock.system());
    LockStore b = new LockStore(new RedisLockDriver(client, "pb:"), MonotonicClock.system());
    LockKey key = LockKey.service("same-" + System.nanoTime());
    assertThat(a.tryAcquire(key, Duration.ofSeconds(30))).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(b.tryAcquire(key, Duration.ofSeconds(30))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void twoStoresOnTheSameServerExcludeEachOther() throws Exception {
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
  void fencingTokensComeFromOneCounterAcrossStoresAndKeys() {
    long t1 = lease(newStore(), "c1-" + System.nanoTime(), Duration.ofSeconds(5)).fencingToken();
    long t2 = lease(newStore(), "c2-" + System.nanoTime(), Duration.ofSeconds(5)).fencingToken();
    assertThat(t2).isGreaterThan(t1);
  }

  @Test
  void anUnreachableServerIsAnExceptionNotABusy() {
    try (RedisClient dead = RedisClient.create("127.0.0.1", 1)) {
      assertThatThrownBy(() -> new RedisLockDriver(dead, "x:"))
          .isInstanceOf(LockStoreUnavailableException.class)
          .hasMessageContaining("scripts");
    }
  }
}
