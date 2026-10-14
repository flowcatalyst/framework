package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.core.Lookup;

/**
 * Builds one named secret store at start-up, with the driver it names (config-and-code contract,
 * section 3.7). {@link SecretStores} has the framework's; an application may write its own.
 */
@FunctionalInterface
public interface SecretStoreFactory {

  /**
   * Builds the store.
   *
   * @param name the store's name, which references use ({@code name:secret})
   * @param lookup typed access to services ({@code Config}, the clock, the environment)
   * @return the store
   * @throws Exception any failure; it fails start-up naming the store
   */
  SecretStore create(String name, Lookup lookup) throws Exception;
}
