package io.flowcatalyst.framework.locks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The registry of named lock stores. Provided by {@link LocksModule}. */
public final class Locks {

  private final Map<String, LockStore> stores;
  private final @Nullable String defaultName;

  Locks(Map<String, LockStore> stores, @Nullable String defaultName) {
    this.stores = Collections.unmodifiableMap(new LinkedHashMap<>(stores));
    this.defaultName = defaultName;
  }

  /**
   * Returns the named store.
   *
   * @param name the store name
   * @return the store
   * @throws IllegalArgumentException naming the known stores when there is no such store
   */
  public LockStore get(String name) {
    LockStore store = stores.get(name);
    if (store == null) {
      throw new IllegalArgumentException(
          "no LockStore instance named '"
              + name
              + "'; known instances: "
              + (stores.isEmpty() ? "(none)" : String.join(", ", stores.keySet())));
    }
    return store;
  }

  /**
   * The default store.
   *
   * @return the store named by {@code LocksModule.Builder.defaultStore}, or the only one
   * @throws IllegalStateException when no store is given
   */
  public LockStore defaultInstance() {
    String name = defaultName;
    if (name == null) {
      throw new IllegalStateException("no LockStore instance is configured");
    }
    return get(name);
  }

  /**
   * The store names.
   *
   * @return names in the order they were given
   */
  public Set<String> names() {
    return stores.keySet();
  }
}
