package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.ConfigException;
import io.flowcatalyst.framework.config.InstanceConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The named instances of one capability, built from a configuration section such as
 * {@code locks} (core contract, section 2).
 *
 * <p>Capability registries ({@code Locks}, {@code Databases}, {@code RedisConnections}) wrap one
 * of these.
 *
 * @param <T> the capability type
 */
public final class Instances<T> implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Instances.class);

  private final String capabilityName;
  private final Map<String, T> byName;
  private final @Nullable String defaultName;

  private Instances(String capabilityName, Map<String, T> byName, @Nullable String defaultName) {
    this.capabilityName = capabilityName;
    this.byName = Collections.unmodifiableMap(byName);
    this.defaultName = defaultName;
  }

  /**
   * Builds every instance configured under {@code section}.
   *
   * <p>Reads {@code <section>.default} and {@code <section>.instances.<name>.driver}, builds each
   * instance with the registered driver, and reports as start-up problems: an unknown driver
   * name (listing the registered ones), a factory failure, a key the factory did not read, a
   * missing or unknown default. Instances already built are closed when any problem is found.
   *
   * @param <T> the capability type
   * @param capability the capability, the key drivers are registered under
   * @param section the configuration section, for example {@code locks}
   * @param lookup the lookup of the provider calling this
   * @return the instances; empty when nothing is configured
   * @throws StartupException listing every problem found
   */
  public static <T> Instances<T> build(Class<T> capability, String section, Lookup lookup) {
    Config config = lookup.get(Config.class);
    Drivers drivers = lookup.get(Drivers.class);
    String module = lookup.moduleId();
    List<Problem> problems = new ArrayList<>();

    InstanceConfig top = config.section(section);
    String defaultName = null;
    try {
      defaultName = top.findString("default").orElse(null);
    } catch (ConfigException e) {
      problems.add(Problem.fromConfig(e.problem(), module));
    }
    InstanceConfig instances = top.section("instances");
    List<String> names = new ArrayList<>(instances.keys());
    // Mark the instances subtree as handled so only stray top-level keys are reported.
    for (String stray : top.unreadKeys()) {
      if (!stray.equals("instances") && !stray.startsWith("instances.")) {
        problems.add(
            new Problem(
                module,
                section + "." + stray,
                top.sourceOf(stray),
                "unknown key in section '" + section + "'",
                null));
      }
    }

    Map<String, T> built = new LinkedHashMap<>();
    for (String name : names) {
      String path = section + ".instances." + name;
      InstanceConfig ic = instances.section(name);
      String driver;
      try {
        driver = ic.string("driver");
      } catch (ConfigException e) {
        problems.add(Problem.fromConfig(e.problem(), module));
        continue;
      }
      List<String> known = drivers.names(capability);
      if (!known.contains(driver)) {
        problems.add(
            Problem.of(
                module,
                path + ".driver",
                "unknown driver '"
                    + driver
                    + "' for "
                    + capability.getSimpleName()
                    + "; registered: "
                    + (known.isEmpty() ? "(none)" : String.join(", ", known))));
        continue;
      }
      T instance;
      try {
        instance = drivers.create(capability, driver, name, ic, lookup, built);
      } catch (ConfigException e) {
        problems.add(Problem.fromConfig(e.problem(), module));
        continue;
      } catch (StartupException e) {
        problems.addAll(e.problems());
        continue;
      } catch (Exception e) {
        problems.add(
            new Problem(
                module,
                path,
                null,
                "driver '" + driver + "' failed: " + rootMessage(e),
                e));
        continue;
      }
      if (instance == null) {
        problems.add(Problem.of(module, path, "driver '" + driver + "' is not registered"));
        continue;
      }
      built.put(name, instance);
      for (String unknown : ic.unreadKeys()) {
        problems.add(
            new Problem(
                module,
                path + "." + unknown,
                ic.sourceOf(unknown),
                "unknown key for driver '" + driver + "'",
                null));
      }
    }

    String resolvedDefault = null;
    if (defaultName != null) {
      if (names.contains(defaultName)) {
        resolvedDefault = defaultName;
      } else {
        problems.add(
            Problem.of(
                module,
                section + ".default",
                "names instance '"
                    + defaultName
                    + "', which is not configured; known: "
                    + (names.isEmpty() ? "(none)" : String.join(", ", names))));
      }
    } else if (names.size() == 1) {
      resolvedDefault = names.get(0);
    } else if (names.size() > 1) {
      problems.add(
          Problem.of(
              module,
              section + ".default",
              "missing; several instances are configured ("
                  + String.join(", ", names)
                  + ") so one must be named as the default"));
    }

    if (!problems.isEmpty()) {
      closeAll(built.values());
      throw new StartupException(problems);
    }
    return new Instances<>(capability.getSimpleName(), built, resolvedDefault);
  }

  /**
   * Returns the named instance.
   *
   * @param name the instance name
   * @return the instance
   * @throws IllegalArgumentException naming the known instances when there is no such instance
   */
  public T get(String name) {
    T instance = byName.get(name);
    if (instance == null) {
      throw new IllegalArgumentException(
          "no "
              + capabilityName
              + " instance named '"
              + name
              + "'; known instances: "
              + (byName.isEmpty() ? "(none)" : String.join(", ", byName.keySet())));
    }
    return instance;
  }

  /**
   * The default instance.
   *
   * @return the instance named by {@code default}, or the only instance
   * @throws IllegalStateException when no instance is configured
   */
  public T defaultInstance() {
    if (defaultName == null) {
      throw new IllegalStateException("no " + capabilityName + " instance is configured");
    }
    return get(defaultName);
  }

  /**
   * The default instance's name.
   *
   * @return the name, or empty when no instance is configured
   */
  public Optional<String> defaultName() {
    return Optional.ofNullable(defaultName);
  }

  /**
   * The configured instance names.
   *
   * @return names in configuration order
   */
  public Set<String> names() {
    return byName.keySet();
  }

  /**
   * All instances.
   *
   * @return a read-only map in configuration order
   */
  public Map<String, T> all() {
    return byName;
  }

  /** Closes every instance that is {@link AutoCloseable}, in reverse order; failures are logged. */
  @Override
  public void close() {
    closeAll(byName.values());
  }

  private static void closeAll(Iterable<?> instances) {
    List<Object> list = new ArrayList<>();
    for (Object o : instances) {
      list.add(o);
    }
    Collections.reverse(list);
    for (Object o : list) {
      if (o instanceof AutoCloseable c) {
        try {
          c.close();
        } catch (Exception e) {
          LOG.warn("failed to close {}", o.getClass().getSimpleName(), e);
        }
      }
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String m = root.getMessage();
    String first = m == null ? "" : m.lines().findFirst().orElse("");
    return root.getClass().getSimpleName() + (first.isEmpty() ? "" : ": " + first);
  }
}
