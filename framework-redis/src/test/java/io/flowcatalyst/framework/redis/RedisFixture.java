package io.flowcatalyst.framework.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** A real Redis 8 for one test class. Start it in {@code @BeforeAll}, close in {@code @AfterAll}. */
public final class RedisFixture implements AutoCloseable {

  private final GenericContainer<?> container;

  private RedisFixture(GenericContainer<?> container) {
    this.container = container;
  }

  /** Starts a container, optionally requiring a password. */
  public static RedisFixture start(String... extraArgs) {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    if (extraArgs.length > 0) {
      container.withCommand(extraArgs);
    }
    container.start();
    return new RedisFixture(container);
  }

  /** The host. */
  public String host() {
    return container.getHost();
  }

  /** The mapped port. */
  public int port() {
    return container.getMappedPort(6379);
  }

  /** A plain URL for database 0. */
  public String url() {
    return "redis://" + host() + ":" + port() + "/0";
  }

  @Override
  public void close() {
    container.stop();
  }
}
