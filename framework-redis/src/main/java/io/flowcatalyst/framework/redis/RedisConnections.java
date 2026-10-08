package io.flowcatalyst.framework.redis;

import io.flowcatalyst.framework.core.Instances;
import java.util.Set;

/** The registry of named Redis connections. Provided by {@link RedisModule}. */
public final class RedisConnections implements AutoCloseable {

  private final Instances<RedisConnection> instances;

  RedisConnections(Instances<RedisConnection> instances) {
    this.instances = instances;
  }

  /**
   * Returns the named connection.
   *
   * @param name the instance name
   * @return the connection
   * @throws IllegalArgumentException naming the known instances when there is no such instance
   */
  public RedisConnection get(String name) {
    return instances.get(name);
  }

  /**
   * The default connection.
   *
   * @return the instance named by {@code redis.default}, or the only instance
   * @throws IllegalStateException when no instance is configured
   */
  public RedisConnection defaultInstance() {
    return instances.defaultInstance();
  }

  /**
   * The configured instance names.
   *
   * @return names in configuration order
   */
  public Set<String> names() {
    return instances.names();
  }

  /** Closes every pool. */
  @Override
  public void close() {
    instances.close();
  }
}
