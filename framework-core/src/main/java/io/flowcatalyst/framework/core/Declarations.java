package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Declaration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the modules of an application declared in {@code register}, read-only: their contributions
 * and their settings. Given to a {@link DeclarationCommand}, and returned by {@link
 * AppBuilder#declarations()} for tools and tests. Nothing in it was built from configuration.
 */
public final class Declarations {

  private final List<String> moduleIds;
  private final List<Declaration> settings;
  private final Map<Class<?>, List<Object>> contributions;

  Declarations(List<String> moduleIds, List<Declaration> settings, Map<Class<?>, List<Object>> contributions) {
    this.moduleIds = List.copyOf(moduleIds);
    this.settings = List.copyOf(settings);
    Map<Class<?>, List<Object>> copy = new LinkedHashMap<>();
    contributions.forEach((type, items) -> copy.put(type, List.copyOf(items)));
    this.contributions = copy;
  }

  /**
   * The ids of the registered modules, in registration order, the framework's own {@code core}
   * first.
   *
   * @return the module ids
   */
  public List<String> moduleIds() {
    return moduleIds;
  }

  /**
   * Every declared setting with the module that declared it.
   *
   * @return the declarations, in declaration order
   */
  public List<Declaration> settings() {
    return settings;
  }

  /**
   * Everything modules contributed under a type with {@link AppBuilder#contribute}.
   *
   * @param <T> the contribution type
   * @param type the contribution type
   * @return the contributions in module registration order
   */
  public <T> List<T> contributions(Class<T> type) {
    List<T> out = new ArrayList<>();
    for (Object item : contributions.getOrDefault(type, List.of())) {
      out.add(type.cast(item));
    }
    return List.copyOf(out);
  }
}
