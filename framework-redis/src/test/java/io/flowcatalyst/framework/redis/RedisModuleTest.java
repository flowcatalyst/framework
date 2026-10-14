package io.flowcatalyst.framework.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisModuleTest {

  static RedisFixture plain;
  static RedisFixture secured;

  @TempDir Path dir;

  @BeforeAll
  static void start() {
    plain = RedisFixture.start();
    secured = RedisFixture.start("redis-server", "--requirepass", "s3cret-pw");
  }

  @AfterAll
  static void stop() {
    plain.close();
    secured.close();
  }

  private void yaml(String text) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), text);
  }

  private App start(Map<String, String> env) {
    return start(env, new RedisModule());
  }

  private App start(Map<String, String> env, RedisModule module) {
    return App.builder().configDirectory(dir).environment(env).module(module).start();
  }

  private StartupException failure(Map<String, String> env) {
    return failure(env, new RedisModule());
  }

  private StartupException failure(Map<String, String> env, RedisModule module) {
    Throwable t = catchThrowable(() -> start(env, module).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void startsPingsAndExposesAWorkingClient() throws IOException {
    yaml("redis:\n  default: default\n  instances:\n    default: { url: \"" + plain.url() + "\" }\n");
    try (App app = start(Map.of())) {
      RedisConnection c = app.get(RedisConnections.class).defaultInstance();
      assertThat(c.name()).isEqualTo("default");
      c.client().set("k", "v");
      assertThat(c.client().get("k")).isEqualTo("v");
      assertThat(app.health().check().results()).containsEntry("redis:default", new HealthResult.Up());
    }
  }

  @Test
  void aSingleInstanceIsTheDefault() throws IOException {
    yaml("redis:\n  instances:\n    only: { url: \"" + plain.url() + "\" }\n");
    try (App app = start(Map.of())) {
      assertThat(app.get(RedisConnections.class).defaultInstance().name()).isEqualTo("only");
    }
  }

  @Test
  void databaseNumberComesFromTheUrlPath() throws IOException {
    yaml(
        "redis:\n  default: a\n  instances:\n    a: { url: \"redis://%s:%d/1\" }\n    b: { url: \"redis://%s:%d/2\" }\n"
            .formatted(plain.host(), plain.port(), plain.host(), plain.port()));
    try (App app = start(Map.of())) {
      RedisConnections r = app.get(RedisConnections.class);
      r.get("a").client().set("only-in-1", "x");
      assertThat(r.get("b").client().get("only-in-1")).isNull();
      assertThat(r.get("a").client().get("only-in-1")).isEqualTo("x");
    }
  }

  @Test
  void passwordInTheUrlWorks() throws IOException {
    yaml(
        "redis:\n  instances:\n    s: { url: \"redis://:s3cret-pw@%s:%d/0\" }\n"
            .formatted(secured.host(), secured.port()));
    try (App app = start(Map.of())) {
      assertThat(app.get(RedisConnections.class).defaultInstance().client().ping()).isEqualTo("PONG");
    }
  }

  @Test
  void passwordKeyIsASecretAndWinsOverTheUrl() throws IOException {
    yaml(
        "redis:\n  instances:\n    s: { url: \"redis://:wrong@%s:%d/0\", password: s3cret-pw }\n"
            .formatted(secured.host(), secured.port()));
    try (App app = start(Map.of())) {
      assertThat(app.get(RedisConnections.class).defaultInstance().client().ping()).isEqualTo("PONG");
    }
  }

  @Test
  void passwordCanComeFromTheEnvironment() throws IOException {
    yaml("redis:\n  instances:\n    s: { url: \"%s\" }\n".formatted(secured.url()));
    try (App app = start(Map.of("REDIS_INSTANCES_S_PASSWORD", "s3cret-pw"))) {
      assertThat(app.get(RedisConnections.class).defaultInstance().client().ping()).isEqualTo("PONG");
    }
  }

  @Test
  void aWrongPasswordFailsStartUpNamingTheInstanceWithoutRepeatingIt() throws IOException {
    yaml("redis:\n  instances:\n    s: { url: \"%s\", password: not-the-password-123 }\n".formatted(secured.url()));
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("module 'redis'").contains("redis.instances.s").contains("'s'");
    assertThat(e.getMessage()).doesNotContain("not-the-password-123");
  }

  @Test
  void anUnreachableServerFailsStartUpNamingTheInstance() throws IOException {
    yaml("redis:\n  instances:\n    gone: { url: \"redis://127.0.0.1:1/0\" }\n");
    assertThat(failure(Map.of()).getMessage()).contains("cannot reach redis instance 'gone'");
  }

  @Test
  void badUrlsAreReported() throws IOException {
    yaml(
        """
        redis:
          default: a
          instances:
            a: { url: "http://localhost:6379" }
            b: { url: "redis://localhost:6379/abc" }
            c: { url: "redis:///0" }
            d: { url: "redis://:pw@exa mple" }
        """);
    StartupException e = failure(Map.of());
    assertThat(e.problems()).hasSize(4);
    assertThat(e.getMessage())
        .contains("must start with redis://")
        .contains("database number")
        .contains("no host")
        .contains("not a valid URI")
        .doesNotContain("pw");
  }

  @Test
  void unknownKeysAndMissingUrlAreReported() throws IOException {
    yaml("redis:\n  instances:\n    a: { usr: x }\n    b: { url: \"" + plain.url() + "\", tmeout: 1s }\n  default: b\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("redis.instances.a.url").contains("redis.instances.b.tmeout");
  }

  @Test
  void noInstancesIsValid() {
    try (App app = start(Map.of())) {
      RedisConnections r = app.get(RedisConnections.class);
      assertThat(r.names()).isEmpty();
      assertThatIllegalStateException().isThrownBy(r::defaultInstance);
      assertThatIllegalArgumentException().isThrownBy(() -> r.get("x"));
    }
  }

  @Test
  void closingTheApplicationClosesThePool() throws IOException {
    yaml("redis:\n  instances:\n    a: { url: \"" + plain.url() + "\" }\n");
    App app = start(Map.of());
    RedisConnection c = app.get(RedisConnections.class).defaultInstance();
    app.close();
    assertThat(catchThrowable(c::ping)).isNotNull();
  }

  // ---- behaviour in code (config-and-code contract, section 3.3) ----------------------------

  @Test
  void redisPoolDefaultsAndRanges() {
    RedisPool d = RedisPool.defaults();
    assertThat(d).isEqualTo(new RedisPool(Duration.ofSeconds(2), 16));
    assertThat(d.withTimeout(Duration.ofMinutes(5)).timeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(d.withMaxSize(1).maxSize()).isEqualTo(1);
    assertThat(d.withMaxSize(1000).maxSize()).isEqualTo(1000);
    assertThatIllegalArgumentException().isThrownBy(() -> d.withTimeout(Duration.ZERO)).withMessageContaining("timeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> d.withTimeout(Duration.ofMinutes(5).plusNanos(1)))
        .withMessageContaining("timeout");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMaxSize(0)).withMessageContaining("maxSize");
    assertThatIllegalArgumentException().isThrownBy(() -> d.withMaxSize(1001)).withMessageContaining("maxSize");
  }

  @Test
  void theTimeoutFromCodeBoundsAServerThatNeverAnswers() throws IOException {
    // A socket that accepts connections (in the backlog) and never answers: PING waits the timeout.
    try (ServerSocket silent = new ServerSocket(0)) {
      yaml("redis:\n  instances:\n    mute: { url: \"redis://127.0.0.1:" + silent.getLocalPort() + "/0\" }\n");
      long t0 = System.nanoTime();
      StartupException e =
          failure(Map.of(), RedisModule.builder().pool(RedisPool.defaults().withTimeout(Duration.ofMillis(300))).build());
      assertThat(e.getMessage()).contains("cannot reach redis instance 'mute'");
      // the default would wait 2s
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
    }
  }

  @Test
  void thePoolSizeFromCodeBoundsConcurrentConnections() throws Exception {
    yaml("redis:\n  instances:\n    a: { url: \"" + plain.url() + "\" }\n    b: { url: \"" + plain.url() + "\" }\n  default: a\n");
    RedisModule module =
        RedisModule.builder()
            .pool(RedisPool.defaults().withMaxSize(1).withTimeout(Duration.ofMillis(300)))
            .pool("b", RedisPool.defaults().withMaxSize(2))
            .build();
    try (App app = start(Map.of(), module)) {
      for (String name : new String[] {"a", "b"}) {
        RedisConnection c = app.get(RedisConnections.class).get(name);
        CompletableFuture<Object> holder =
            CompletableFuture.supplyAsync(() -> c.client().blpop(2, "nothing-" + name));
        Thread.sleep(300); // the holder has the connection
        Throwable second = catchThrowable(() -> c.client().ping());
        if (name.equals("a")) {
          assertThat(second).as("a pool of one is exhausted").isNotNull();
        } else {
          assertThat(second).as("a pool of two has room").isNull();
        }
        holder.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void aPoolForAnInstanceThatIsNotConfiguredFailsStartUp() throws IOException {
    yaml("redis:\n  instances:\n    a: { url: \"" + plain.url() + "\" }\n");
    StartupException e = failure(Map.of(), RedisModule.builder().pool("cache", RedisPool.defaults()).build());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("redis.instances.cache");
      assertThat(p.message()).contains("not configured");
    });
  }

  @Test
  void theRemovedInstanceKeysFailStartUpNamingTheCode() throws IOException {
    yaml("redis:\n  instances:\n    a: { url: \"" + plain.url() + "\", timeout: 2s, driver: redis, pool: { max: 4 } }\n");
    StartupException e = failure(Map.of());
    assertThat(e.problems())
        .extracting(p -> p.key())
        .containsExactlyInAnyOrder("redis.instances.a.timeout", "redis.instances.a.driver", "redis.instances.a.pool.max");
    assertThat(e.getMessage()).contains("RedisModule.builder().pool(RedisPool)");
    yaml("redis:\n  instances:\n    a: { url: \"" + plain.url() + "\" }\n");
    assertThat(failure(Map.of("REDIS_INSTANCES_A_TIMEOUT", "1s")).problems())
        .singleElement()
        .satisfies(p -> assertThat(p.source()).isEqualTo("environment variable REDIS_INSTANCES_A_TIMEOUT"));
  }
}
