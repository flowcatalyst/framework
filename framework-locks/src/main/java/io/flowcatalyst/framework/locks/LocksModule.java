package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Provides {@link Locks}: the lock stores named in code, each with the driver its factory names
 * (config-and-code contract, section 3.4). There is no {@code locks} configuration; the module
 * claims the component, so a leftover {@code locks} key fails start-up.
 *
 * <pre>{@code
 * LocksModule.builder()
 *     .store("main", DatabaseLockStores.database())      // with DatabaseModule and LocksDatabaseModule
 *     .store("shared", RedisLockStores.redis())          // with RedisModule
 *     .store("local", LockStores.memory())
 *     .defaultStore("main")
 *     .build()
 * }</pre>
 */
public final class LocksModule implements Module {

  /** The module id. */
  public static final String ID = "locks";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

  private final Map<String, LockStoreFactory> stores;
  private final @Nullable String defaultStore;

  private LocksModule(Map<String, LockStoreFactory> stores, @Nullable String defaultStore) {
    this.stores = stores;
    this.defaultStore = defaultStore;
  }

  /**
   * Starts a module definition with no store.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link LocksModule}. */
  public static final class Builder {
    private final Map<String, LockStoreFactory> stores = new LinkedHashMap<>();
    private @Nullable String defaultStore;

    private Builder() {}

    /**
     * Adds a named store.
     *
     * @param name {@code [a-z][a-z0-9-]*}, unique
     * @param factory builds it at start-up, with its driver
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder store(String name, LockStoreFactory factory) {
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("lock store name must match [a-z][a-z0-9-]*, was '" + name + "'");
      }
      if (stores.putIfAbsent(name, factory) != null) {
        throw new IllegalArgumentException("lock store '" + name + "' is already given");
      }
      return this;
    }

    /**
     * Names the store {@link Locks#defaultInstance()} returns. Needed with several stores; with
     * one, that one is the default.
     *
     * @param name a store added with {@link #store}
     * @return this builder
     */
    public Builder defaultStore(String name) {
      this.defaultStore = name;
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     * @throws IllegalArgumentException when the default names no store, or several stores have
     *     no default
     */
    public LocksModule build() {
      String chosen = defaultStore;
      if (chosen != null && !stores.containsKey(chosen)) {
        throw new IllegalArgumentException(
            "defaultStore '" + chosen + "' is not a store; stores: " + names(stores));
      }
      if (chosen == null && stores.size() == 1) {
        chosen = stores.keySet().iterator().next();
      }
      if (chosen == null && stores.size() > 1) {
        throw new IllegalArgumentException(
            "several lock stores (" + names(stores) + "): name the default with defaultStore");
      }
      return new LocksModule(new LinkedHashMap<>(stores), chosen);
    }

    private static String names(Map<String, ?> stores) {
      return stores.isEmpty() ? "(none)" : String.join(", ", stores.keySet());
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("locks");
    app.provide(
        Locks.class,
        lookup -> {
          Map<String, LockStore> built = new LinkedHashMap<>();
          List<Problem> problems = new ArrayList<>();
          for (Map.Entry<String, LockStoreFactory> e : stores.entrySet()) {
            try {
              LockStore store = e.getValue().create(e.getKey(), lookup);
              if (store == null) {
                throw new IllegalStateException("the factory returned null");
              }
              built.put(e.getKey(), store);
            } catch (StartupException se) {
              problems.addAll(se.problems());
            } catch (Exception ex) {
              problems.add(
                  new Problem(ID, "lock store '" + e.getKey() + "'", null, "failed: " + describe(ex), ex));
            }
          }
          if (!problems.isEmpty()) {
            throw new StartupException(problems);
          }
          return new Locks(built, defaultStore);
        });
  }

  private static String describe(Throwable t) {
    StringBuilder sb = new StringBuilder();
    Throwable cur = t;
    for (int depth = 0; cur != null && depth < 4; depth++) {
      if (depth > 0) {
        sb.append(" <- ");
      }
      String m = cur.getMessage();
      String first = m == null ? "" : m.lines().findFirst().orElse("");
      sb.append(cur.getClass().getSimpleName());
      if (!first.isEmpty()) {
        sb.append(": ").append(first);
      }
      cur = cur.getCause() == cur ? null : cur.getCause();
    }
    return sb.toString();
  }
}
