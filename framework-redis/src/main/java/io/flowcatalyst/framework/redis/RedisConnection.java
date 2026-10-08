package io.flowcatalyst.framework.redis;

import redis.clients.jedis.RedisClient;

/** One named connection to a standalone Redis or Valkey server, backed by a Jedis pool. */
public final class RedisConnection implements AutoCloseable {

  private final String name;
  private final RedisClient client;

  RedisConnection(String name, RedisClient client) {
    this.name = name;
    this.client = client;
  }

  /**
   * The instance name.
   *
   * @return the name used in configuration
   */
  public String name() {
    return name;
  }

  /**
   * The pooled client. Blocking; use it from virtual threads. Each command borrows a connection
   * from the pool and returns it.
   *
   * @return the client
   */
  public RedisClient client() {
    return client;
  }

  /**
   * Checks that the server answers.
   *
   * @throws IllegalStateException when it does not answer {@code PONG}
   */
  public void ping() {
    String reply = client.ping();
    if (!"PONG".equals(reply)) {
      throw new IllegalStateException("redis instance '" + name + "' answered " + reply);
    }
  }

  /** Closes the pool. */
  @Override
  public void close() {
    client.close();
  }
}
