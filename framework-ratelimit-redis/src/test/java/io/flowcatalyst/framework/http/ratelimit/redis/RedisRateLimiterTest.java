package io.flowcatalyst.framework.http.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.ratelimit.Decision;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.http.ratelimit.RateLimitStoreException;
import io.flowcatalyst.framework.http.ratelimit.RateLimiter;
import io.flowcatalyst.framework.redis.RedisConnection;
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

  /** Only the connection is configuration; the limiters are built in code. */
  App start(RedisFixture fixture) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        redis:
          instances:
            default: { url: "%s" }
        """
            .formatted(fixture.url()));
    return App.builder().configDirectory(dir).environment(Map.of()).module(new RedisModule()).start();
  }

  RedisConnection connection(App app) {
    return app.get(RedisConnections.class).defaultInstance();
  }

  RedisClient client(App app) {
    return connection(app).client();
  }

  @Test
  void aBurstIsAllowedThenLimitedWithTheServersArithmetic() throws Exception {
    try (App app = start(redis)) {
      RateLimiter login = RedisRateLimiter.create("login", connection(app), RateLimitPolicy.of(3, Duration.ofMinutes(1)), "t1:");
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
    RateLimitPolicy policy = new RateLimitPolicy(10, Duration.ofHours(1), 10);
    try (App one = start(redis);
        App two = start(redis)) {
      RateLimiter a = RedisRateLimiter.create("login", connection(one), policy, "t2:");
      RateLimiter b = RedisRateLimiter.create("login", connection(two), policy, "t2:");
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
    try (App app = start(redis)) {
      RateLimiter login = RedisRateLimiter.create("login", connection(app), RateLimitPolicy.of(5, Duration.ofMinutes(1)), "t3:");
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
      client(app).scriptFlush();
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
    }
  }

  @Test
  void aStoreThatGoesAwayIsAStoreException() throws Exception {
    RedisFixture own = RedisFixture.start();
    try (App app = start(own)) {
      RateLimiter login = RedisRateLimiter.create("login", connection(app), RateLimitPolicy.of(5, Duration.ofMinutes(1)));
      assertThat(login.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
      own.close();
      assertThatThrownBy(() -> login.acquire("k", 1)).isInstanceOf(RateLimitStoreException.class);
    }
  }

  @Test
  void aKeysPolicyChangesFromItsNextRequestOnTheServersClock() throws Exception {
    try (App app = start(redis)) {
      // 3 per minute: T 20 s, window 60 s
      RateLimiter ingest = RedisRateLimiter.create("ingest", connection(app), RateLimitPolicy.of(3, Duration.ofMinutes(1)), "t5:");
      assertThat(ingest.supportsPerRequestPolicy()).isTrue();
      for (String key : new String[] {"raised", "lowered"}) {
        for (int i = 0; i < 3; i++) {
          assertThat(ingest.acquire(key, 1)).isInstanceOf(Decision.Allowed.class);
        }
        assertThat(ingest.acquire(key, 1)).isInstanceOf(Decision.Limited.class);
      }
      // raised to 60 per minute, burst 120 (T 1 s, window 120 s): the 60 s debt is kept, about 59 left
      Decision raised = ingest.acquire("raised", 1, new RateLimitPolicy(60, Duration.ofMinutes(1), 120));
      assertThat(raised).isInstanceOf(Decision.Allowed.class);
      assertThat(((Decision.Allowed) raised).remaining()).isBetween(58L, 59L);
      // lowered window, 1 per 10 s, burst 1 (window 10 s): the debt is capped at an empty bucket, so
      // the retry is one new interval (without the cap, about 60 s)
      RateLimitPolicy lowered = new RateLimitPolicy(1, Duration.ofSeconds(10), 1);
      Decision limited = ingest.acquire("lowered", 1, lowered);
      assertThat(limited).isInstanceOf(Decision.Limited.class);
      assertThat(((Decision.Limited) limited).retryAfter())
          .isGreaterThan(Duration.ofSeconds(9))
          .isLessThanOrEqualTo(Duration.ofSeconds(10));
      // the capped value was written back, with a TTL to match
      assertThat(client(app).pttl("t5:ingest:lowered")).isBetween(9_000L, 10_002L);
      // a policy whose window is under a second: retry well under a second, from the same state
      Decision fast = ingest.acquire("lowered", 1, new RateLimitPolicy(3, Duration.ofSeconds(1), 3));
      assertThat(fast).isInstanceOf(Decision.Limited.class);
      assertThat(((Decision.Limited) fast).retryAfter()).isLessThanOrEqualTo(Duration.ofMillis(334));
    }
  }

  @Test
  void namesAreChecked() throws Exception {
    try (App app = start(redis)) {
      RateLimitPolicy p = RateLimitPolicy.of(1, Duration.ofSeconds(1));
      assertThat(RedisRateLimiter.create("login", connection(app), p).name()).isEqualTo("login");
      for (String bad : new String[] {"", "Login", "a:b"}) {
        assertThatIllegalArgumentException().as(bad).isThrownBy(() -> RedisRateLimiter.create(bad, connection(app), p));
      }
    }
  }
}
