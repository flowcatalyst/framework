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
   * The form of a client under which a cache should keep its values, for a store that knows two
   * names for one client (the platform's {@code clt_...} id and the client's identifier). A cache
   * calls this before a lookup and again after a found one, so a store may learn the alias from its
   * own answer. The default is the client itself.
   *
   * @param client the client as the caller named it
   * @return the canonical client; equal for every alias of one client
   */
  default ClientId canonical(ClientId client) {
    return client;
  }

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
