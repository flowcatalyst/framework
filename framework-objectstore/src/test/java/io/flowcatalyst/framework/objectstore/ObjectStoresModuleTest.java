package io.flowcatalyst.framework.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStoresModuleTest {

  @TempDir Path dir;

  private App start(ObjectStoresModule module, Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env).module(module).start();
  }

  @Test
  void storesAreNamedInCodeAndKeepSeparateValues() {
    ObjectStoresModule m =
        ObjectStoresModule.builder()
            .store("one", ObjectStoreFactories.memory())
            .store("two", ObjectStoreFactories.memory())
            .defaultStore("two")
            .build();
    try (App app = start(m, Map.of())) {
      ObjectStores stores = app.get(ObjectStores.class);
      assertThat(stores.names()).containsExactly("one", "two");
      stores.get("one").put("k", new byte[] {1});
      assertThat(stores.get("two").get("k")).isEmpty();
      assertThat(stores.defaultInstance()).isSameAs(stores.get("two"));
      assertThat(stores.get("one").name()).isEqualTo("one");
    }
  }

  @Test
  void aSingleStoreIsTheDefaultAndSeveralNeedOne() {
    try (App app = start(ObjectStoresModule.builder().store("only", ObjectStoreFactories.memory()).build(), Map.of())) {
      assertThat(app.get(ObjectStores.class).defaultInstance().name()).isEqualTo("only");
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ObjectStoresModule.builder().store("a", ObjectStoreFactories.memory()).store("b", ObjectStoreFactories.memory()).build())
        .withMessageContaining("defaultStore");
    assertThatIllegalArgumentException().isThrownBy(() -> ObjectStoresModule.builder().store("Bad Name", ObjectStoreFactories.memory()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ObjectStoresModule.builder().store("a", ObjectStoreFactories.memory()).store("a", ObjectStoreFactories.memory()));
    try (App app = start(ObjectStoresModule.builder().build(), Map.of())) {
      assertThatIllegalStateException().isThrownBy(() -> app.get(ObjectStores.class).defaultInstance());
      assertThatIllegalArgumentException().isThrownBy(() -> app.get(ObjectStores.class).get("x")).withMessageContaining("(none)");
    }
  }

  @Test
  void theFolderDriverStartsWhenLocalOrTestAndRefusesElsewhere() throws IOException {
    Path folder = dir.resolve("objects");
    ObjectStoresModule m = ObjectStoresModule.builder().store("f", ObjectStoreFactories.folder(folder)).build();
    for (Map<String, String> ok : java.util.List.of(Map.<String, String>of(), Map.of("APP_ENV", "local"), Map.of("APP_ENV", "test"))) {
      try (App app = start(m, ok)) {
        app.get(ObjectStores.class).get("f").put("a/b", new byte[] {7});
        assertThat(Files.readAllBytes(folder.resolve("a/b"))).containsExactly(7);
      }
    }
    for (String env : new String[] {"prod", "staging", "np", "production"}) {
      Throwable t = catchThrowable(() -> start(m, Map.of("APP_ENV", env)).close());
      assertThat(t).isInstanceOf(StartupException.class);
      assertThat(t.getMessage()).contains("object store 'f'").contains("local or test").contains(env);
    }
  }

  @Test
  void aStoreThatIsAutoCloseableIsClosedWhenTheApplicationStops() {
    AtomicBoolean closed = new AtomicBoolean();
    class Closing implements ObjectStore, AutoCloseable {
      @Override
      public String name() { return "c"; }
      @Override
      public void put(String key, byte[] content) {}
      @Override
      public java.util.Optional<byte[]> get(String key) { return java.util.Optional.empty(); }
      @Override
      public void delete(String key) {}
      @Override
      public void close() { closed.set(true); }
    }
    try (App app = start(ObjectStoresModule.builder().store("c", (n, l) -> new Closing()).build(), Map.of())) {
      assertThat(closed).isFalse();
    }
    assertThat(closed).isTrue();
  }

  @Test
  void aFactoryFailureNamesTheStoreAndEarlierStoresAreClosed() {
    AtomicBoolean closed = new AtomicBoolean();
    class Closing implements ObjectStore, AutoCloseable {
      @Override
      public String name() { return "good"; }
      @Override
      public void put(String key, byte[] content) {}
      @Override
      public java.util.Optional<byte[]> get(String key) { return java.util.Optional.empty(); }
      @Override
      public void delete(String key) {}
      @Override
      public void close() { closed.set(true); }
    }
    ObjectStoresModule m =
        ObjectStoresModule.builder()
            .store("good", (n, l) -> new Closing())
            .store("bad", (n, l) -> { throw new IllegalStateException("no bucket"); })
            .defaultStore("good")
            .build();
    Throwable t = catchThrowable(() -> start(m, Map.of()).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("object store 'bad'").contains("no bucket");
    assertThat(closed).isTrue();
  }

  @Test
  void aLeftoverConfigKeyFailsStartup() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "object-stores:\n  x: 1\n");
    Throwable t = catchThrowable(() -> start(ObjectStoresModule.builder().store("m", ObjectStoreFactories.memory()).build(), Map.of()).close());
    assertThat(t).isInstanceOf(StartupException.class);
  }

  @Test
  void relativeFolderAndBadLimitsAreRefusedWhenBuilt() {
    assertThatIllegalArgumentException().isThrownBy(() -> ObjectStoreFactories.folder(Path.of("relative/dir")));
    assertThatIllegalArgumentException().isThrownBy(() -> ObjectStoreFactories.memory().withMaxBytes(0));
  }
}
