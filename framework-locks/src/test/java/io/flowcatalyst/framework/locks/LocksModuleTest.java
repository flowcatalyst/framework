package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocksModuleTest {

  @TempDir Path dir;

  private void yaml(String text) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), text);
  }

  private App start() {
    return App.builder().configDirectory(dir).environment(Map.of()).module(new LocksModule()).start();
  }

  private StartupException failure() {
    Throwable t = catchThrowable(() -> start().close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void buildsMemoryInstancesAndTheDefault() throws IOException {
    yaml(
        """
        locks:
          default: local
          instances:
            local: { driver: memory }
            other: { driver: memory }
        """);
    try (App app = start()) {
      Locks locks = app.get(Locks.class);
      assertThat(locks.names()).containsExactly("local", "other");
      LockKey key = LockKey.service("x");
      assertThat(locks.defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      // separate instances do not share state
      assertThat(locks.get("other").tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      assertThat(locks.defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Busy.class);
    }
  }

  @Test
  void aSingleInstanceNeedsNoDefault() throws IOException {
    yaml("locks:\n  instances:\n    only: { driver: memory }\n");
    try (App app = start()) {
      assertThat(app.get(Locks.class).defaultInstance()).isNotNull();
    }
  }

  @Test
  void noInstancesIsValidAndGetThrowsNamingNothing() {
    try (App app = start()) {
      Locks locks = app.get(Locks.class);
      assertThat(locks.names()).isEmpty();
      assertThatIllegalArgumentException().isThrownBy(() -> locks.get("x")).withMessageContaining("(none)");
      assertThatIllegalStateException().isThrownBy(locks::defaultInstance);
    }
  }

  @Test
  void anUnknownDriverNameFailsStartUpListingTheRegisteredOnes() throws IOException {
    yaml("locks:\n  instances:\n    a: { driver: redis }\n");
    assertThat(failure().getMessage()).contains("unknown driver 'redis'").contains("registered: memory");
  }

  @Test
  void aKeyTheMemoryDriverDoesNotReadIsUnknown() throws IOException {
    yaml("locks:\n  instances:\n    a: { driver: memory, prefix: \"x:\" }\n");
    assertThat(failure().getMessage()).contains("locks.instances.a.prefix").contains("unknown key");
  }

  @Test
  void severalInstancesWithoutADefaultFail() throws IOException {
    yaml("locks:\n  instances:\n    a: { driver: memory }\n    b: { driver: memory }\n");
    assertThat(failure().getMessage()).contains("locks.default");
  }
}
