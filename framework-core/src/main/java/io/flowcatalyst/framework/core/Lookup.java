package io.flowcatalyst.framework.core;

import java.util.List;

/** Typed access to services. A map built at start-up; no reflection. */
public interface Lookup {

  /**
   * The module on whose behalf this lookup is made, used to name the asker in start-up errors.
   *
   * @return a module id
   */
  String moduleId();

  /**
   * Returns the service of that type, building it first if needed.
   *
   * @param <T> the service type
   * @param type the service type; exactly one module provides each type
   * @return the service
   * @throws IllegalArgumentException when no module provides it
   */
  <T> T get(Class<T> type);

  /**
   * Everything modules contributed under a type with {@link AppBuilder#contribute}.
   *
   * @param <T> the contribution type
   * @param type the contribution type
   * @return the contributions in module registration order
   */
  <T> List<T> contributions(Class<T> type);
}
