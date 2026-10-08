package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.InstanceConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** The registry of drivers: factories by capability type and driver name. */
public final class Drivers {

  private final Map<Class<?>, Map<String, Entry<?>>> factories = new LinkedHashMap<>();

  Drivers() {}

  /**
   * Registers drivers for one module during {@code register}.
   *
   * <p>Obtained from {@link AppBuilder#drivers()}.
   */
  public static final class Registrar {
    private final Drivers drivers;
    private final String module;
    private final List<Problem> problems;

    Registrar(Drivers drivers, String module, List<Problem> problems) {
      this.drivers = drivers;
      this.module = module;
      this.problems = problems;
    }

    /**
     * Registers a driver. A second registration of one name for one capability fails start-up.
     *
     * @param <T> the capability type
     * @param capability the capability, for example {@code LockStore.class}
     * @param name the driver name used in configuration
     * @param factory builds an instance from its configuration section
     */
    public <T> void register(Class<T> capability, String name, DriverFactory<T> factory) {
      Map<String, Entry<?>> byName =
          drivers.factories.computeIfAbsent(capability, c -> new LinkedHashMap<>());
      if (byName.putIfAbsent(name, new Entry<>(capability, factory)) != null) {
        problems.add(
            Problem.of(
                module,
                capability.getSimpleName() + " driver '" + name + "'",
                "registered twice"));
      }
    }
  }

  /**
   * The driver names registered for a capability.
   *
   * @param capability the capability
   * @return sorted names, possibly empty
   */
  public List<String> names(Class<?> capability) {
    Map<String, Entry<?>> byName = factories.get(capability);
    return byName == null ? List.of() : new ArrayList<>(new TreeSet<>(byName.keySet()));
  }

  /**
   * Builds an instance with the named driver.
   *
   * @param <T> the capability type
   * @param capability the capability
   * @param driver the driver name
   * @param name the instance name
   * @param config the instance's section
   * @param lookup typed access to services
   * @param built instances already built for this capability
   * @return the instance, or null when no such driver is registered
   * @throws Exception whatever the factory throws
   */
  @Nullable <T> T create(
      Class<T> capability,
      String driver,
      String name,
      InstanceConfig config,
      Lookup lookup,
      Map<String, ?> built)
      throws Exception {
    Map<String, Entry<?>> byName = factories.get(capability);
    Entry<?> entry = byName == null ? null : byName.get(driver);
    if (entry == null) {
      return null;
    }
    Object made = entry.build(name, config, lookup, built);
    if (made == null) {
      throw new IllegalStateException("driver '" + driver + "' returned null");
    }
    return capability.cast(made);
  }

  /** A factory with its capability type, so the built-instance map can be re-typed safely. */
  private record Entry<T>(Class<T> capability, DriverFactory<T> factory) {
    @Nullable Object build(
        String name, InstanceConfig config, Lookup lookup, Map<String, ?> built) throws Exception {
      Map<String, T> typed = new LinkedHashMap<>();
      for (Map.Entry<String, ?> e : built.entrySet()) {
        typed.put(e.getKey(), capability.cast(e.getValue()));
      }
      return factory.create(name, config, lookup, Collections.unmodifiableMap(typed));
    }
  }
}
