package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.redis.RedisFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** {@link HardeningTest} on the Redis driver (A2): the same tests, the log in a Redis stream. */
class RedisHardeningTest extends HardeningTest {

  static RedisFixture redis;

  @BeforeAll
  static void startRedis() {
    redis = RedisFixture.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.close();
  }

  @Override
  LogBackend newBackend() {
    return new RedisBackend(redis);
  }
}
