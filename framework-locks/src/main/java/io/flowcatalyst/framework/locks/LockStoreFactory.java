package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.Lookup;

/**
 * Builds one named lock store at start-up, with the driver it names (config-and-code contract,
 * section 3.4). {@link LockStores#memory()}, {@code DatabaseLockStores.database()} and {@code
 * RedisLockStores.redis()} are the framework's; an application may write its own.
 */
@FunctionalInterface
public interface LockStoreFactory {

  /**
   * Builds the store.
   *
   * @param name the store's name in {@link Locks}
   * @param lookup typed access to services (connections, the clock, {@code Config})
   * @return the store
   * @throws Exception any failure; it fails start-up naming the store
   */
  LockStore create(String name, Lookup lookup) throws Exception;
}
