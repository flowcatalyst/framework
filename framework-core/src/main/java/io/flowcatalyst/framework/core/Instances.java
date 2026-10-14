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
 * {@code database} with one {@link InstanceFactory} (core contract section 2, amended by the
 * config-and-code contract section 2.1: configuration names connections, never a driver).
 *
 * <p>Capability registries ({@code Databases}, {@code RedisConnections}) wrap one of these.
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
   * Builds every instance configured under {@code <section>.instances} with one factory (no driver
   * names: config-and-code contract, section 2.1). Reads {@code <section>.default}; reports as
   * start-up problems a factory failure, a key the factory did not read, a stray key in the
   * section, a missing or unknown default. Instances already built are closed when any problem is
   * found.
   *
   * @param <T> the capability type
   * @param capability the capability
   * @param section the configuration section, for example {@code database}
   * @param lookup the lookup of the provider calling this
   * @param factory builds one instance from its section
   * @return the instances; empty when nothing is configured
   * @throws StartupException listing every problem found
   */
  public static <T> Instances<T> build(
      Class<T> capability, String section, Lookup lookup, InstanceFactory<T> factory) {
    Config config = lookup.get(Config.class);
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
      T instance;
      try {
        instance = factory.create(name, ic, lookup, Collections.unmodifiableMap(new LinkedHashMap<>(built)));
      } catch (ConfigException e) {
        problems.add(Problem.fromConfig(e.problem(), module));
        continue;
      } catch (StartupException e) {
        problems.addAll(e.problems());
        continue;
      } catch (Exception e) {
        problems.add(new Problem(module, path, null, "failed: " + chain(e), e));
        continue;
      }
      built.put(name, instance);
      for (String unknown : ic.unreadKeys()) {
        problems.add(
            new Problem(
                module,
                path + "." + unknown,
                ic.sourceOf(unknown),
                "unknown key in " + capability.getSimpleName() + " instance '" + name + "'",
                null));
      }
    }

    String resolvedDefault = resolveDefault(section, module, names, defaultName, problems);
    if (!problems.isEmpty()) {
      closeAll(built.values());
      throw new StartupException(problems);
    }
    return new Instances<>(capability.getSimpleName(), built, resolvedDefault);
  }

  private static @Nullable String resolveDefault(
      String section,
      String module,
      List<String> names,
      @Nullable String defaultName,
      List<Problem> problems) {
    if (defaultName != null) {
      if (names.contains(defaultName)) {
        return defaultName;
      }
      problems.add(
          Problem.of(
              module,
              section + ".default",
              "names instance '"
                  + defaultName
                  + "', which is not configured; known: "
                  + (names.isEmpty() ? "(none)" : String.join(", ", names))));
      return null;
    }
    if (names.size() == 1) {
      return names.get(0);
    }
    if (names.size() > 1) {
      problems.add(
          Problem.of(
              module,
              section + ".default",
              "missing; several instances are configured ("
                  + String.join(", ", names)
                  + ") so one must be named as the default"));
    }
    return null;
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

  /** The exception and its causes, each as "Type: first line of message", joined by " <- ". */
  private static String chain(Throwable t) {
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
