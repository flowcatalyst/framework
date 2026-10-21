package io.flowcatalyst.framework.objectstore;

import io.flowcatalyst.framework.core.Lookup;

/**
 * Builds one named object store at start-up, with the driver it names (config-and-code contract,
 * section 3.4). {@link ObjectStoreFactories#memory()} and {@link ObjectStoreFactories#folder} are the
 * framework's, {@code S3ObjectStores.s3} is in {@code framework-objectstore-s3}; an application may
 * write its own.
 */
@FunctionalInterface
public interface ObjectStoreFactory {

  /**
   * Builds the store.
   *
   * @param name the store's name in {@link ObjectStores}
   * @param lookup typed access to services ({@code Config}, the clock, the environment)
   * @return the store; if it is {@link AutoCloseable} it is closed when the application stops
   * @throws Exception any failure; it fails start-up naming the store
   */
  ObjectStore create(String name, Lookup lookup) throws Exception;
}
