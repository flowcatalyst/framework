package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.InstanceConfig;
import java.util.Map;

/**
 * Builds one named instance of a capability whose instances come from configuration (database,
 * Redis), from its section (core contract section 2, amended by the config-and-code contract
 * section 2.1). A section holds connection and secret keys only.
 *
 * @param <T> the capability type
 */
@FunctionalInterface
public interface InstanceFactory<T> {

  /**
   * Builds the instance. Read configuration through {@code config}; any key left unread is
   * reported as unknown after this returns.
   *
   * @param name the instance name
   * @param config the instance's section
   * @param lookup typed access to services
   * @param built instances of this capability already built, by name, in configuration order
   * @return the instance
   * @throws Exception any failure; it is reported as a start-up problem
   */
  T create(String name, InstanceConfig config, Lookup lookup, Map<String, T> built) throws Exception;
}
