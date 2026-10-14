package io.flowcatalyst.framework.locks.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LockStores;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.redis.RedisConnections;
import io.flowcatalyst.framework.redis.RedisFixture;
import io.flowcatalyst.framework.redis.RedisModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RedisLockStores}: the redis lock driver chosen in code (config-and-code contract 3.4). */
class RedisLockStoresTest {

  static RedisFixture redis;

  @TempDir Path dir;

  @BeforeAll
  static void startRedis() {
    redis = RedisFixture.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.close();
  }

  private App start(LocksModule locks) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        redis:
          default: one
          instances:
            one: { url: "%s" }
            two: { url: "redis://%s:%d/3" }
        """
            .formatted(redis.url(), redis.host(), redis.port()));
    return App.builder()
        .configDirectory(dir)
        .environment(Map.of())
        .module(new RedisModule())
        .module(locks)
        .start();
  }

  @Test
  void buildsARedisLockStoreOnTheDefaultConnectionWithTheDefaultPrefix() throws IOException {
    try (App app = start(LocksModule.builder().store("shared", RedisLockStores.redis()).build())) {
      LockKey key = LockKey.service("m-" + System.nanoTime());
      assertThat(app.get(Locks.class).defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      assertThat(app.get(RedisConnections.class).get("one").client().get("fw:lock:svc:" + key.name())).isNotNull();
    }
  }

  @Test
  void connectionAndPrefixAreChosenInCode() throws IOException {
    LocksModule locks =
        LocksModule.builder()
            .store("a", RedisLockStores.redis("one", "pa:"))
            .store("b", RedisLockStores.redis("two", "pb:"))
            .defaultStore("a")
            .build();
    try (App app = start(locks)) {
      Locks all = app.get(Locks.class);
      LockKey key = LockKey.service("m-" + System.nanoTime());
      assertThat(all.get("a").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
      assertThat(all.get("b").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
      assertThat(app.get(RedisConnections.class).get("one").client().get("pa:svc:" + key.name())).isNotNull();
      assertThat(app.get(RedisConnections.class).get("two").client().get("pb:svc:" + key.name())).isNotNull();
    }
  }

  @Test
  void anUnknownConnectionIsReportedWithTheStore() {
    Throwable t = catchThrowable(() -> start(LocksModule.builder().store("a", RedisLockStores.redis("nope")).build()).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("nope").contains("lock store 'a'");
  }

  @Test
  void thePrefixIsChecked() {
    assertThatIllegalArgumentException().isThrownBy(() -> RedisLockStores.redis("one", " ")).withMessageContaining("prefix");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RedisLockStores.redis("one", "p".repeat(101)))
        .withMessageContaining("prefix");
    assertThat(RedisLockStores.redis("one", "p".repeat(100))).isNotNull();
  }

  @Test
  void theOtherDriversRunBesideRedis() throws IOException {
    LocksModule locks =
        LocksModule.builder().store("m", LockStores.memory()).store("r", RedisLockStores.redis()).defaultStore("m").build();
    try (App app = start(locks)) {
      assertThat(app.get(Locks.class).names()).containsExactly("m", "r");
    }
  }
}
