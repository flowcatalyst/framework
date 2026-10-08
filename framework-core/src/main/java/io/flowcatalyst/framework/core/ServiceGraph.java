package io.flowcatalyst.framework.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds every provided service eagerly, dependencies first, collecting problems. */
final class ServiceGraph {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceGraph.class);

  /** One registered provider. */
  record Entry(String module, Class<?> type, Provider<?> provider) {}

  /** Thrown inside the graph when a dependency could not be built; the cause is already recorded. */
  private static final class DependencyFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DependencyFailedException() {
      super(null, null, false, false);
    }
  }

  private final Map<Class<?>, Entry> providers;
  private final Map<Class<?>, List<Object>> contributions;
  private final Map<Class<?>, Object> services = new LinkedHashMap<>();
  private final List<Class<?>> order = new ArrayList<>();
  private final Set<Class<?>> failed = new HashSet<>();
  private final List<Class<?>> stack = new ArrayList<>();
  private final List<Problem> problems = new ArrayList<>();

  ServiceGraph(
      Map<Class<?>, Entry> providers,
      Map<Class<?>, Object> seeded,
      Map<Class<?>, List<Object>> contributions) {
    this.providers = providers;
    this.contributions = contributions;
    this.services.putAll(seeded);
  }

  /** Builds everything. Check {@link #problems()} afterwards. */
  void buildAll() {
    for (Entry entry : providers.values()) {
      if (!services.containsKey(entry.type()) && !failed.contains(entry.type())) {
        try {
          build(entry);
        } catch (DependencyFailedException e) {
          // already recorded where it happened
        }
      }
    }
  }

  List<Problem> problems() {
    return problems;
  }

  Map<Class<?>, Object> services() {
    return services;
  }

  List<Class<?>> order() {
    return order;
  }

  /** The built services in build order, for closing. */
  List<Object> builtInOrder() {
    List<Object> out = new ArrayList<>();
    for (Class<?> type : order) {
      Object s = services.get(type);
      if (s != null) {
        out.add(s);
      }
    }
    return out;
  }

  private Object get(Class<?> type, String askerModule) {
    Object built = services.get(type);
    if (built != null) {
      return built;
    }
    if (failed.contains(type)) {
      throw new DependencyFailedException();
    }
    int at = stack.indexOf(type);
    if (at >= 0) {
      StringBuilder cycle = new StringBuilder();
      for (int i = at; i < stack.size(); i++) {
        cycle.append(stack.get(i).getSimpleName()).append(" -> ");
      }
      cycle.append(type.getSimpleName());
      problems.add(
          Problem.of(askerModule, type.getName(), "cycle between providers: " + cycle));
      throw new DependencyFailedException();
    }
    Entry entry = providers.get(type);
    if (entry == null) {
      problems.add(
          Problem.of(
              askerModule,
              type.getName(),
              "asked for a service of this type, but no module provides it"));
      throw new DependencyFailedException();
    }
    return build(entry);
  }

  private Object build(Entry entry) {
    Class<?> type = entry.type();
    stack.add(type);
    try {
      Object value = entry.provider().create(new ModuleLookup(entry.module()));
      if (value == null) {
        problems.add(Problem.of(entry.module(), type.getName(), "provider returned null"));
        failed.add(type);
        throw new DependencyFailedException();
      }
      if (!type.isInstance(value)) {
        problems.add(
            Problem.of(
                entry.module(),
                type.getName(),
                "provider returned " + value.getClass().getName() + ", not the provided type"));
        failed.add(type);
        throw new DependencyFailedException();
      }
      services.put(type, value);
      order.add(type);
      return value;
    } catch (DependencyFailedException e) {
      failed.add(type);
      throw e;
    } catch (StartupException e) {
      problems.addAll(e.problems());
      failed.add(type);
      throw new DependencyFailedException();
    } catch (Exception e) {
      LOG.debug("provider for {} failed", type.getName(), e);
      problems.add(
          new Problem(
              entry.module(),
              type.getName(),
              null,
              "provider failed: " + describe(e),
              e));
      failed.add(type);
      throw new DependencyFailedException();
    } finally {
      stack.remove(stack.size() - 1);
    }
  }

  private static String describe(Throwable t) {
    String m = t.getMessage();
    String first = m == null ? "" : m.lines().findFirst().orElse("");
    return t.getClass().getSimpleName() + (first.isEmpty() ? "" : ": " + first);
  }

  private final class ModuleLookup implements Lookup {
    private final String module;

    ModuleLookup(String module) {
      this.module = module;
    }

    @Override
    public String moduleId() {
      return module;
    }

    @Override
    public <T> T get(Class<T> type) {
      return type.cast(ServiceGraph.this.get(type, module));
    }

    @Override
    public <T> List<T> contributions(Class<T> type) {
      List<Object> items = contributions.getOrDefault(type, List.of());
      List<T> out = new ArrayList<>();
      for (Object item : items) {
        out.add(type.cast(item));
      }
      return List.copyOf(out);
    }
  }
}
