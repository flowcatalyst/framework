package io.flowcatalyst.framework.auth.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.MutableClock;
import io.flowcatalyst.framework.auth.SessionHandles;
import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.auth.SessionStoreContract;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;
import io.flowcatalyst.framework.redis.RedisFixture;
import io.flowcatalyst.framework.redis.RedisModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The store contract on a real Redis, and what only this driver does: hashed keys and a TTL. */
class RedisSessionStoreTest extends SessionStoreContract {

  static RedisFixture redis;
  static App app;
  static RedisConnection connection;

  @BeforeAll
  static void startRedis() throws Exception {
    redis = RedisFixture.start();
    Path dir = Files.createTempDirectory("auth-redis");
    Files.writeString(dir.resolve("application.yaml"), "redis:\n  instances:\n    main: { url: \"" + redis.url() + "\" }\n");
    app = App.builder().configDirectory(dir).environment(Map.of()).module(new RedisModule()).start();
    connection = app.get(RedisConnections.class).defaultInstance();
  }

  @AfterAll
  static void stop() {
    app.close();
    redis.close();
  }

  @Override
  protected SessionStore newStore(MutableClock clock) {
    connection.client().flushAll();
    return RedisSessionStore.create(connection, clock);
  }

  @Test
  void keysHoldTheHashOfTheHandleAndExpireOnTheServerToo() {
    String handle = store.create(SessionStore.SESSION, "{}", clock.instant().plus(Duration.ofMinutes(5)));
    assertThat(connection.client().exists("fw:sess:session:" + handle)).isFalse();
    String key = "fw:sess:session:" + SessionHandles.hash(handle);
    assertThat(connection.client().exists(key)).isTrue();
    assertThat(connection.client().pttl(key)).isBetween(1L, Duration.ofMinutes(5).toMillis());
  }
}
