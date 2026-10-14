package io.flowcatalyst.framework.secrets;

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
 * The secrets module (id {@code secrets}): provides {@link SecretResolver} over the stores named
 * in code, each with the driver its factory chooses (config-and-code contract, section 3.7). There
 * is no {@code secrets} configuration; the module claims the component.
 *
 * <pre>{@code
 * SecretsModule.builder()
 *     .store("files", SecretStores.file(Path.of("/run/secrets/clients")))
 *     .store("env", SecretStores.env("CLIENT_SECRET_").withCache(CachePolicy.none()))
 *     .defaultStore("files")
 *     .build()
 * }</pre>
 */
public final class SecretsModule implements Module {

  /** The module id. */
  public static final String ID = "secrets";

  static final String BLOCKED =
      "the platform driver is blocked on the platform: FlowCatalyst has no endpoint that resolves a"
          + " per-client secret for an application (secrets contract, section 4); use env or file";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

  private final Map<String, SecretStoreFactory> stores;
  private final @Nullable String defaultStore;

  private SecretsModule(Map<String, SecretStoreFactory> stores, @Nullable String defaultStore) {
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

  /** Builds a {@link SecretsModule}. */
  public static final class Builder {
    private final Map<String, SecretStoreFactory> stores = new LinkedHashMap<>();
    private @Nullable String defaultStore;

    private Builder() {}

    /**
     * Adds a named store. References name it as {@code name:secret}.
     *
     * @param name {@code [a-z][a-z0-9-]*}, unique
     * @param factory builds it at start-up: {@link SecretStores#env}, {@link SecretStores#file}, or
     *     your own
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder store(String name, SecretStoreFactory factory) {
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("secret store name must match [a-z][a-z0-9-]*, was '" + name + "'");
      }
      if (stores.putIfAbsent(name, factory) != null) {
        throw new IllegalArgumentException("secret store '" + name + "' is already given");
      }
      return this;
    }

    /**
     * Names the store a reference without a store name uses. Needed with several stores; with
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
    public SecretsModule build() {
      String chosen = defaultStore;
      if (chosen != null && !stores.containsKey(chosen)) {
        throw new IllegalArgumentException(
            "defaultStore '" + chosen + "' is not a store; stores: " + names());
      }
      if (chosen == null && stores.size() == 1) {
        chosen = stores.keySet().iterator().next();
      }
      if (chosen == null && stores.size() > 1) {
        throw new IllegalArgumentException(
            "several secret stores (" + names() + "): name the default with defaultStore");
      }
      return new SecretsModule(new LinkedHashMap<>(stores), chosen);
    }

    private String names() {
      return stores.isEmpty() ? "(none)" : String.join(", ", stores.keySet());
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("secrets");
    app.provide(
        SecretResolver.class,
        lookup -> {
          Map<String, SecretStore> built = new LinkedHashMap<>();
          List<Problem> problems = new ArrayList<>();
          for (Map.Entry<String, SecretStoreFactory> e : stores.entrySet()) {
            try {
              built.put(e.getKey(), e.getValue().create(e.getKey(), lookup));
            } catch (StartupException se) {
              problems.addAll(se.problems());
            } catch (Exception ex) {
              String m = ex.getMessage();
              problems.add(
                  new Problem(
                      ID,
                      "secret store '" + e.getKey() + "'",
                      null,
                      "failed: " + ex.getClass().getSimpleName() + (m == null ? "" : ": " + m),
                      ex));
            }
          }
          if (!problems.isEmpty()) {
            throw new StartupException(problems);
          }
          return SecretResolver.of(built, defaultStore);
        });
  }
}
