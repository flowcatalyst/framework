package io.flowcatalyst.framework.http.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.ratelimit.Decision;
import io.flowcatalyst.framework.http.ratelimit.RateLimitModule;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.http.ratelimit.RateLimitStoreException;
import io.flowcatalyst.framework.http.ratelimit.RateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimiters;
import io.flowcatalyst.framework.redis.RedisConnections;
import io.flowcatalyst.framework.redis.RedisFixture;
import io.flowcatalyst.framework.redis.RedisModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.RedisClient;

/** The redis driver against a real Redis (rate-limiting contract, section 2). */
class RedisRateLimiterTest {

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

  App start(String limiter, RedisFixture fixture) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        redis:
          instances:
            default: { url: "%s" }
        rate-limits:
          instances:
            login: %s
        """
            .formatted(fixture.url(), limiter));
    return App.builder()
        .configDirectory(dir)
        .environment(Map.of())
        .module(new RedisModule())
        .module(new RateLimitModule())
        .module(new RateLimitRedisModule())
        .start();
  }

  RedisClient client(App app) {
    return app.get(RedisConnections.class).defaultInstance().client();
  }

  @Test
  void aBurstIsAllowedThenLimitedWithTheServersArithmetic() throws Exception {
    try (App app = start("{ driver: redis, limit: 3, period: 1m, prefix: \"t1:\" }", redis)) {
      RateLimiter login = app.get(RateLimiters.class).get("login");
      assertThat(login.policy()).isEqualTo(RateLimitPolicy.of(3, Duration.ofMinutes(1)));
      Decision first = login.acquire("ip:1.2.3.4", 1);
      assertThat(first).isInstanceOf(Decision.Allowed.class);
      assertThat(((Decision.Allowed) first).remaining()).isEqualTo(2);
      assertThat(((Decision.Allowed) login.acquire("ip:1.2.3.4", 1)).remaining()).isEqualTo(1);
      assertThat(((Decision.Allowed) login.acquire("ip:1.2.3.4", 1)).remaining()).isZero();
      Decision limited = login.acquire("ip:1.2.3.4", 1);
      assertThat(limited).isInstanceOf(Decision.Limited.class);
      Duration retry = ((Decision.Limited) limited).retryAfter();
      assertThat(retry).isGreaterThan(Duration.ofSeconds(19)).isLessThanOrEqualTo(Duration.ofSeconds(20));
      // the key lives under the prefix and the instance name, and expires when the bucket is full
      long ttl = client(app).pttl("t1:login:ip:1.2.3.4");
      assertThat(ttl).isBetween(59_000L, 60_002L);
      assertThat(login.acquire("ip:5.6.7.8", 1)).isInstanceOf(Decision.Allowed.class);
    }
  }

  @Test
  void replicasShareOneBudget() throws Exception {
    try (App one = start("{ driver: redis, limit: 10, period: 1h, burst: 10, prefix: \"t2:\" }", redis);
        App two = start("{ driver: redis, limit: 10, period: 1h, burst: 10, prefix: \"t2:\" }", redis)) {
      RateLimiter a = one.get(RateLimiters.class).get("login");
      RateLimiter b = two.get(RateLimiters.class).get("login");
      AtomicInteger allowed = new AtomicInteger();
      CountDownLatch go = new CountDownLatch(1);
      List<Thread> threads = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        RateLimiter replica = i % 2 == 0 ? a : b;
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        go.await();
                      } catch (InterruptedException e) {
                        return;
                      }
                      if (replica.acquire("shared", 1) instanceof Decision.Allowed) {
                        allowed.incrementAndGet();
                      }
                    }));
      }
      go.countDown();
      for (Thread t : threads) {
        t.join();
      }
      assertThat(allowed).hasValue(10);
    }
  }

  @Test
  void aForgottenScriptIsLoadedAgain() throws Exception {
    try (App app = start("{ driver: redis, limit: 5, period: 1m, prefix: \"t3:\" }", redis)) {
      RateLimiter login = app.get(RateLimiters.class).get("login");
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
      client(app).scriptFlush();
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
    }
  }

  @Test
  void aStoreThatGoesAwayIsAStoreException() throws Exception {
    RedisFixture own = RedisFixture.start();
    try (App app = start("{ driver: redis, limit: 5, period: 1m }", own)) {
      RateLimiter login = app.get(RateLimiters.class).get("login");
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
      own.close();
      assertThatThrownBy(() -> login.acquire("k", 1)).isInstanceOf(RateLimitStoreException.class);
    }
  }
}
