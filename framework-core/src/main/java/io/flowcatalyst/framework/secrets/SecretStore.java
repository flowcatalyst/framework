package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.context.ClientId;

/**
 * One configured secret store: the driver interface (secrets contract, sections 3 and 4). Every
 * lookup is keyed by the client the caller acts for and a validated name.
 */
public interface SecretStore {

  /**
   * Looks a secret up.
   *
   * @param client the client the caller acts for
   * @param name a name matching {@code [a-z0-9]+(-[a-z0-9]+)*}
   * @return what was found
   * @throws SecretStoreException when the store cannot be read; the message carries no value
   */
  SecretResult find(ClientId client, String name);

  /**
   * Drops a cached value, if this store caches.
   *
   * @param client the client
   * @param name the name
   */
  default void evict(ClientId client, String name) {}

  /** Drops every cached value, if this store caches. */
  default void evictAll() {}
}
