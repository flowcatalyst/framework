package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactories;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactory;
import io.flowcatalyst.framework.objectstore.ObjectStoreException;
import io.flowcatalyst.framework.objectstore.StoredObject;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** Object stores for tests: one that fails or corrupts on demand, one whose put waits. */
final class Stores {

  private Stores() {}

  /** A memory store with failure injection. */
  static final class Flaky implements ObjectStore {
    volatile @Nullable ObjectStore inner;
    volatile @Nullable String putError;
    volatile boolean truncate;
    volatile @Nullable Runnable afterPut;

    ObjectStoreFactory factory() {
      return (name, lookup) -> {
        inner = ObjectStoreFactories.memory().create(name, lookup);
        return this;
      };
    }

    void heal() {
      putError = null;
      truncate = false;
      afterPut = null;
    }

    private ObjectStore store() {
      ObjectStore s = inner;
      if (s == null) {
        throw new IllegalStateException("not built");
      }
      return s;
    }

    @Override
    public String name() {
      return store().name();
    }

    @Override
    public void put(String key, byte[] content) {
      put(key, content, null);
    }

    @Override
    public void put(String key, byte[] content, @Nullable String contentType) {
      String err = putError;
      if (err != null) {
        throw new ObjectStoreException(err);
      }
      byte[] written = truncate ? java.util.Arrays.copyOf(content, content.length / 2) : content;
      store().put(key, written, contentType);
      Runnable hook = afterPut;
      if (hook != null) {
        hook.run();
      }
    }

    @Override
    public Optional<StoredObject> read(String key) {
      return store().read(key);
    }

    @Override
    public Optional<byte[]> get(String key) {
      return store().get(key);
    }

    @Override
    public void delete(String key) {
      store().delete(key);
    }
  }

  /** A memory store whose first put signals and waits to be released. */
  static final class Blocking implements ObjectStore {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile @Nullable ObjectStore inner;

    ObjectStoreFactory factory() {
      return (name, lookup) -> {
        inner = ObjectStoreFactories.memory().create(name, lookup);
        return this;
      };
    }

    private ObjectStore store() {
      ObjectStore s = inner;
      if (s == null) {
        throw new IllegalStateException("not built");
      }
      return s;
    }

    @Override
    public String name() {
      return store().name();
    }

    @Override
    public void put(String key, byte[] content) {
      put(key, content, null);
    }

    @Override
    public void put(String key, byte[] content, @Nullable String contentType) {
      entered.countDown();
      try {
        if (!release.await(60, TimeUnit.SECONDS)) {
          throw new ObjectStoreException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ObjectStoreException("interrupted", e);
      }
      store().put(key, content, contentType);
    }

    @Override
    public Optional<StoredObject> read(String key) {
      return store().read(key);
    }

    @Override
    public Optional<byte[]> get(String key) {
      return store().get(key);
    }

    @Override
    public void delete(String key) {
      store().delete(key);
    }
  }
}
