package io.flowcatalyst.framework.objectstore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The object stores of an application, by name. Provided by {@link ObjectStoresModule}.
 *
 * <pre>{@code
 * ObjectStore archive = app.get(ObjectStores.class).get("archive");
 * archive.put("sets/2026-10-21/apply-1.json", bytes);
 * }</pre>
 */
public final class ObjectStores implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ObjectStores.class);

  private final Map<String, ObjectStore> stores;
  private final @Nullable String defaultName;

  ObjectStores(Map<String, ObjectStore> stores, @Nullable String defaultName) {
    this.stores = Collections.unmodifiableMap(new LinkedHashMap<>(stores));
    this.defaultName = defaultName;
  }

  /**
   * A store by name.
   *
   * @param name the name given to {@link ObjectStoresModule.Builder#store}
   * @return the store
   * @throws IllegalArgumentException when there is no such store
   */
  public ObjectStore get(String name) {
    ObjectStore store = stores.get(name);
    if (store == null) {
      throw new IllegalArgumentException(
          "no ObjectStore named '"
              + name
              + "'; known stores: "
              + (stores.isEmpty() ? "(none)" : String.join(", ", stores.keySet())));
    }
    return store;
  }

  /**
   * The default store.
   *
   * @return the store
   * @throws IllegalStateException when none is configured
   */
  public ObjectStore defaultInstance() {
    String name = defaultName;
    if (name == null) {
      throw new IllegalStateException("no ObjectStore is configured");
    }
    return get(name);
  }

  /**
   * The names of every store.
   *
   * @return names, in the order they were added
   */
  public Set<String> names() {
    return stores.keySet();
  }

  /** Closes every store that is {@link AutoCloseable}; a failure is logged and does not stop the rest. */
  @Override
  public void close() {
    for (Map.Entry<String, ObjectStore> e : stores.entrySet()) {
      if (e.getValue() instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception ex) {
          LOG.warn("closing object store '{}' failed", e.getKey(), ex);
        }
      }
    }
  }
}
