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
import java.util.Map;
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
    return App.builder().configDirectory(dir).environment(env).module(new RedisModule()).start();
  }

  private StartupException failure(Map<String, String> env) {
    Throwable t = catchThrowable(() -> start(env).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void startsPingsAndExposesAWorkingClient() throws IOException {
    yaml("redis:\n  default: default\n  instances:\n    default: { url: \"" + plain.url() + "\", timeout: 2s }\n");
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
    yaml("redis:\n  instances:\n    gone: { url: \"redis://127.0.0.1:1/0\", timeout: 500ms }\n");
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
    yaml("redis:\n  instances:\n    a: { timeout: 1s }\n    b: { url: \"" + plain.url() + "\", tmeout: 1s }\n  default: b\n");
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
}
