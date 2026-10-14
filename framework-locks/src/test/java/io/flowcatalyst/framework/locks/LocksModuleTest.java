package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LocksModule}: stores named in code, the driver chosen by the factory (config-and-code 3.4). */
class LocksModuleTest {

  @TempDir Path dir;

  private void yaml(String text) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), text);
  }

  private App start(LocksModule module, Module... more) {
    AppBuilder b = App.builder().configDirectory(dir).environment(Map.of()).module(module);
    for (Module m : more) {
      b.module(m);
    }
    return b.start();
  }

  private StartupException failure(LocksModule module) {
    Throwable t = catchThrowable(() -> start(module).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void buildsMemoryStoresAndTheDefault() {
    LocksModule module =
        LocksModule.builder()
            .store("local", LockStores.memory())
            .store("other", LockStores.memory())
            .defaultStore("local")
            .build();
    try (App app = start(module)) {
      Locks locks = app.get(Locks.class);
      assertThat(locks.names()).containsExactly("local", "other");
      LockKey key = LockKey.service("x");
      assertThat(locks.defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      // separate stores do not share state
      assertThat(locks.get("other").tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Acquired.class);
      assertThat(locks.defaultInstance().tryAcquire(key, Duration.ofSeconds(5)))
          .isInstanceOf(AcquireResult.Busy.class);
    }
  }

  @Test
  void aSingleStoreIsTheDefault() {
    try (App app = start(LocksModule.builder().store("only", LockStores.memory()).build())) {
      assertThat(app.get(Locks.class).defaultInstance()).isSameAs(app.get(Locks.class).get("only"));
    }
  }

  @Test
  void noStoresIsValidAndGetThrowsNamingNothing() {
    try (App app = start(LocksModule.builder().build())) {
      Locks locks = app.get(Locks.class);
      assertThat(locks.names()).isEmpty();
      assertThatIllegalArgumentException().isThrownBy(() -> locks.get("x")).withMessageContaining("(none)");
      assertThatIllegalStateException().isThrownBy(locks::defaultInstance);
    }
  }

  @Test
  void severalStoresWithoutADefaultAreRefusedWhenBuilt() {
    LocksModule.Builder b = LocksModule.builder().store("a", LockStores.memory()).store("b", LockStores.memory());
    assertThatIllegalArgumentException().isThrownBy(b::build).withMessageContaining("defaultStore").withMessageContaining("a, b");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> LocksModule.builder().store("a", LockStores.memory()).defaultStore("z").build())
        .withMessageContaining("'z'");
  }

  @Test
  void storeNamesAreCheckedAndGivenOnce() {
    LocksModule.Builder b = LocksModule.builder().store("a", LockStores.memory());
    assertThatIllegalArgumentException().isThrownBy(() -> b.store("a", LockStores.memory())).withMessageContaining("already");
    assertThatIllegalArgumentException().isThrownBy(() -> b.store("A", LockStores.memory()));
    assertThatIllegalArgumentException().isThrownBy(() -> b.store("a.b", LockStores.memory()));
  }

  @Test
  void aFactoryThatFailsFailsStartUpNamingTheStore() {
    StartupException e =
        failure(
            LocksModule.builder()
                .store("broken", (name, lookup) -> { throw new IllegalStateException("no server"); })
                .store("fine", LockStores.memory())
                .defaultStore("fine")
                .build());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.module()).isEqualTo("locks");
      assertThat(p.key()).isEqualTo("lock store 'broken'");
      assertThat(p.message()).contains("no server");
    });
  }

  static final Setting<Boolean> SHARED = Setting.bool("demo.shared-locks").withDefault(false);

  @Test
  void anApplicationFactoryCanReadItsOwnSettings() {
    Module demo =
        new Module() {
          @Override
          public String id() {
            return "demo";
          }

          @Override
          public void register(AppBuilder app) {
            app.declare(SHARED);
          }
        };
    LockStoreFactory chosen =
        (name, lookup) -> {
          boolean shared = lookup.get(Config.class).get(SHARED);
          return LockStores.memory().create(shared ? name + "-shared" : name, lookup);
        };
    try (App app = start(LocksModule.builder().store("main", chosen).build(), demo)) {
      assertThat(app.get(Locks.class).defaultInstance()).isNotNull();
    }
  }

  @Test
  void aLeftoverLocksSectionFailsStartUpNamingTheCode() throws IOException {
    yaml("locks:\n  default: main\n  instances:\n    main: { driver: memory }\n    shared: { driver: redis, connection: default, prefix: \"p:\" }\n");
    StartupException e = failure(LocksModule.builder().build());
    assertThat(e.problems())
        .extracting(p -> p.key())
        .containsExactlyInAnyOrder(
            "locks.default",
            "locks.instances.main.driver",
            "locks.instances.shared.driver",
            "locks.instances.shared.connection",
            "locks.instances.shared.prefix");
    assertThat(e.getMessage()).contains("LocksModule.builder().store(name, LockStores.memory()");
  }

  @Test
  void aLeftoverDefaultInTheEnvironmentFailsStartUp() {
    Throwable t =
        catchThrowable(
            () ->
                App.builder()
                    .configDirectory(dir)
                    .environment(Map.of("LOCKS_DEFAULT", "main"))
                    .module(LocksModule.builder().build())
                    .start()
                    .close());
    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("environment variable LOCKS_DEFAULT");
  }
}
