package io.flowcatalyst.framework.core;

/**
 * Builds one service. When it needs another service it asks the lookup, and that service is
 * built first.
 *
 * @param <T> the service type
 */
@FunctionalInterface
public interface Provider<T> {

  /**
   * Builds the service.
   *
   * @param lookup typed access to other services and to contributions
   * @return the service, never null
   * @throws Exception any failure; it is reported as a start-up problem naming the module
   */
  T create(Lookup lookup) throws Exception;
}
