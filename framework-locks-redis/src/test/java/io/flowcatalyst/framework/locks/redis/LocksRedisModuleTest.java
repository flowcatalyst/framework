package io.flowcatalyst.framework.locks.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LocksModule;
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

class LocksRedisModuleTest {

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

  private void yaml(String locks) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        redis:
          default: one
          instances:
            one: { url: "%s" }
            two: { url: "redis://%s:%d/3" }
        locks:
        %s
        """
            .formatted(redis.url(), redis.host(), redis.port(), locks.indent(2)));
  }

  private App start() {
    return App.builder()
        .configDirectory(dir)
        .environment(Map.of())
        .module(new RedisModule())
        .module(new LocksModule())
        .module(new LocksRedisModule())
        .start();
  }

  @Test
  void buildsARedisLockInstanceOnTheDefaultConnection() throws IOException {
    yaml("instances:\n  shared: { driver: redis }");
    try (App app = start()) {
      LockKey key = LockKey.service("m-" + System.nanoTime());
      assertThat(app.get(Locks.class).defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
    }
  }

  @Test
  void connectionAndPrefixAreConfigurable() throws IOException {
    yaml("default: a\ninstances:\n  a: { driver: redis, connection: one, prefix: \"pa:\" }\n  b: { driver: redis, connection: two, prefix: \"pb:\" }");
    try (App app = start()) {
      Locks locks = app.get(Locks.class);
      LockKey key = LockKey.service("m-" + System.nanoTime());
      assertThat(locks.get("a").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
      assertThat(locks.get("b").tryAcquire(key, Duration.ofSeconds(5))).isInstanceOf(AcquireResult.Acquired.class);
      assertThat(app.get(io.flowcatalyst.framework.redis.RedisConnections.class).get("one").client().get("pa:svc:" + key.name()))
          .isNotNull();
    }
  }

  @Test
  void anUnknownConnectionAndAnUnknownKeyAreReported() throws IOException {
    yaml("instances:\n  a: { driver: redis, connection: nope }\n  b: { driver: redis, database: x }\ndefault: a");
    Throwable t = catchThrowable(() -> start().close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("nope").contains("locks.instances.a");
  }

  @Test
  void theOtherDriversStayAvailableWhenRedisIsListedToo() throws IOException {
    yaml("default: m\ninstances:\n  m: { driver: memory }\n  r: { driver: redis }");
    try (App app = start()) {
      assertThat(app.get(Locks.class).names()).containsExactly("m", "r");
    }
  }
}
