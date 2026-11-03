package io.flowcatalyst.framework.auth.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.CookieKeys;
import io.flowcatalyst.framework.auth.FieldOptions;
import io.flowcatalyst.framework.auth.FieldReplayCache;
import io.flowcatalyst.framework.auth.MemorySessionStore;
import io.flowcatalyst.framework.auth.MutableClock;
import io.flowcatalyst.framework.auth.TestFieldDevice;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.redis.RedisConnection;
import io.flowcatalyst.framework.redis.RedisConnections;
import io.flowcatalyst.framework.redis.RedisFixture;
import io.flowcatalyst.framework.redis.RedisModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The proof replay cache on a real Redis, alone and behind two replicas of an application. */
class RedisFieldReplayCacheTest {

  static final String ORIGIN = "https://app.example";
  static final String KEYS = CookieKeys.generate("k1");

  static RedisFixture redis;
  static App redisApp;
  static RedisConnection connection;

  @BeforeAll
  static void startRedis() throws Exception {
    redis = RedisFixture.start();
    Path dir = Files.createTempDirectory("auth-redis-field");
    Files.writeString(dir.resolve("application.yaml"), "redis:\n  instances:\n    main: { url: \"" + redis.url() + "\" }\n");
    redisApp = App.builder().configDirectory(dir).environment(Map.of()).module(new RedisModule()).start();
    connection = redisApp.get(RedisConnections.class).defaultInstance();
  }

  @AfterAll
  static void stop() {
    redisApp.close();
    redis.close();
  }

  @Test
  void aProofIdIsAcceptedOnceAndRemembersForTheTtl() {
    connection.client().flushAll();
    RedisFieldReplayCache cache = RedisFieldReplayCache.create(connection);
    assertThat(cache.firstUse("jkt1", "a", FieldReplayCache.WINDOW)).isTrue();
    assertThat(cache.firstUse("jkt1", "a", FieldReplayCache.WINDOW)).isFalse();
    assertThat(cache.firstUse("jkt2", "a", FieldReplayCache.WINDOW)).isTrue();
    assertThat(cache.firstUse("jkt1", "b", FieldReplayCache.WINDOW)).isTrue();
    assertThat(connection.client().exists("fw:dpop:jkt1:a")).isTrue();
    assertThat(connection.client().pttl("fw:dpop:jkt1:a")).isBetween(1L, Duration.ofMinutes(11).toMillis());
  }

  @Test
  void aShortTtlLetsTheIdGoAgain() throws Exception {
    connection.client().flushAll();
    RedisFieldReplayCache cache = RedisFieldReplayCache.create(connection);
    assertThat(cache.firstUse("jkt", "x", Duration.ofMillis(50))).isTrue();
    Thread.sleep(120);
    assertThat(cache.firstUse("jkt", "x", Duration.ofMillis(50))).isTrue();
  }

  /** One replica: an application with field workers whose replay cache is the Redis one. */
  private record Replica(App app, TestClient client) {}

  private Replica replica(Path dir, TestOidcProvider provider, MutableClock clock) {
    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", "app-client");
    env.put("AUTH_PUBLIC_BASE_URL", ORIGIN);
    env.put("AUTH_COOKIE_KEYS", KEYS);
    Module routes =
        new Module() {
          @Override
          public String id() {
            return "http";
          }

          @Override
          public void register(AppBuilder app) {
            app.provide(Routes.class, lookup -> new Routes());
          }
        };
    Module test =
        new Module() {
          @Override
          public String id() {
            return "test-routes";
          }

          @Override
          public void boot(App app) {
            app.get(Routes.class).forModule(id()).get("/api/me", request -> Response.ok(Map.of("principal", request.context().principal().toString())), RouteOptions.requiresContext());
          }
        };
    App app =
        App.builder()
            .configDirectory(dir)
            .environment(env)
            .instantSource(clock)
            .module(routes)
            .module(
                AuthModule.builder()
                    .sessions(lookup -> MemorySessionStore.create(clock))
                    .application(new ApplicationCode("orders"))
                    .fieldWorkers(FieldOptions.builder().replayCache(lookup -> RedisFieldReplayCache.create(connection)).build())
                    .build())
            .module(test)
            .start();
    return new Replica(app, TestClient.of(app.get(Routes.class), app.get(ContextResolver.class)));
  }

  @Test
  void aProofAcceptedByOneReplicaIsRefusedByTheOther() throws Exception {
    connection.client().flushAll();
    MutableClock clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
    Path dir = Files.createTempDirectory("auth-redis-field-app");
    try (TestOidcProvider provider = TestOidcProvider.start(clock)) {
      Replica a = replica(dir, provider, clock);
      Replica b = replica(dir, provider, clock);
      try {
        TestFieldDevice device = TestFieldDevice.create(clock);
        String token = provider.fieldToken("orders", device.jkt(), Map.of());
        // both replicas derive the same nonces from the shared cookie keys
        String nonce = nonceOf(a);
        String proof = device.proof("GET", ORIGIN + "/api/me", nonce, token);
        TestResponse first = send(a, token, proof);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.tree().path("principal").asString()).isEqualTo("fwu_0TESTWORKER");
        TestResponse replay = send(b, token, proof);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.header("WWW-Authenticate")).isEqualTo("DPoP error=\"invalid_token\"");
        // a fresh proof for the same token works on either
        assertThat(send(b, token, device.proof("GET", ORIGIN + "/api/me", nonceOf(b), token)).status()).isEqualTo(200);
      } finally {
        a.app().close();
        b.app().close();
      }
    }
  }

  /** Gets a nonce the way a device does: send without one, read {@code DPoP-Nonce}. */
  private static String nonceOf(Replica replica) {
    TestResponse r = replica.client().get("/api/me").header("Authorization", "DPoP x").header("DPoP", "y").send();
    return TestFieldDevice.nonceFrom(r.header("DPoP-Nonce"));
  }

  private static TestResponse send(Replica replica, String token, String proof) {
    return replica.client().get("/api/me").header("Authorization", "DPoP " + token).header("DPoP", proof).send();
  }
}
