package io.flowcatalyst.framework.redis;

import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

/**
 * Provides {@link RedisConnections}: the instances configured under {@code redis}.
 *
 * <pre>{@code
 * redis:
 *   default: default
 *   instances:
 *     default: { url: "redis://localhost:6379/0", timeout: 2s }
 * }</pre>
 *
 * <p>An instance is a connection, so all of it is configuration (config-and-code contract, section
 * 3.3). Keys per instance: {@code url} (required; {@code redis://} or {@code rediss://}, may carry
 * user and password), {@code password} (a secret; wins over the URL's), {@code timeout} (connect
 * and read, default 2s, more than 0 and at most 5 minutes) and {@code pool.max} (default 16, 1 to
 * 1000), checked at start-up. Instances name no {@code driver}: there is one. Each instance's
 * health check is {@code redis:<name>}.
 */
public final class RedisModule implements Module {

  /** The module id. */
  public static final String ID = "redis";

  /** The longest {@code timeout} accepted. */
  static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  /** The largest {@code pool.max} accepted. */
  static final int MAX_POOL = 1000;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.provide(
        RedisConnections.class,
        lookup ->
            new RedisConnections(
                Instances.build(
                    RedisConnection.class,
                    "redis",
                    lookup,
                    (name, section, l, built) -> createInstance(name, section))));
  }

  @Override
  public void boot(App app) {
    RedisConnections connections = app.get(RedisConnections.class);
    for (String name : connections.names()) {
      RedisConnection connection = connections.get(name);
      app.health().add("redis:" + name, connection::ping);
    }
  }

  private static RedisConnection createInstance(String name, InstanceConfig config) {
    String url = config.string("url");
    Duration timeout = config.duration("timeout", Duration.ofSeconds(2));
    InstanceConfig poolSection = config.section("pool");
    int poolMax = poolSection.integer("max", 16);
    Secret passwordKey = config.findSecret("password").orElse(null);

    // Checked before any connection is opened.
    List<Problem> problems = new ArrayList<>();
    if (!timeout.isPositive() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      problems.add(outOfRange(config, "timeout", "must be more than 0 and at most 5m, was " + timeout));
    }
    if (poolMax < 1 || poolMax > MAX_POOL) {
      problems.add(outOfRange(poolSection, "max", "must be 1 to 1000, was " + poolMax));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      // The URL may carry a password, so the cause is not attached or echoed.
      throw new IllegalArgumentException("redis instance '" + name + "': url is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equals("redis") || scheme.equals("rediss"))) {
      throw new IllegalArgumentException(
          "redis instance '" + name + "': url must start with redis:// or rediss://");
    }
    String host = uri.getHost();
    if (host == null) {
      throw new IllegalArgumentException("redis instance '" + name + "': url has no host");
    }
    int port = uri.getPort() < 0 ? 6379 : uri.getPort();

    String user = null;
    String password = null;
    String userInfo = uri.getUserInfo();
    if (userInfo != null) {
      int colon = userInfo.indexOf(':');
      if (colon < 0) {
        password = userInfo;
      } else {
        String u = userInfo.substring(0, colon);
        user = u.isEmpty() ? null : u;
        password = userInfo.substring(colon + 1);
      }
    }
    if (passwordKey != null) {
      password = passwordKey.reveal();
    }
    int database = 0;
    String path = uri.getPath();
    if (path != null && path.length() > 1) {
      try {
        database = Integer.parseInt(path.substring(1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "redis instance '" + name + "': the url path must be a database number");
      }
    }

    int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    DefaultJedisClientConfig.Builder clientConfig =
        DefaultJedisClientConfig.builder()
            .timeoutMillis(millis)
            .database(database)
            .ssl(scheme.equals("rediss"));
    if (user != null) {
      clientConfig.user(user);
    }
    if (password != null) {
      clientConfig.password(password);
    }
    GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
    pool.setMaxTotal(poolMax);
    pool.setMaxIdle(poolMax);
    pool.setMaxWait(timeout);

    RedisClient client =
        RedisClient.builder()
            .hostAndPort(new HostAndPort(host, port))
            .clientConfig(clientConfig.build())
            .poolConfig(pool)
            .build();
    RedisConnection connection = new RedisConnection(name, client);
    try {
      connection.ping();
    } catch (RuntimeException e) {
      client.close();
      throw new IllegalStateException(
          "cannot reach redis instance '" + name + "' at " + host + ":" + port, e);
    }
    return connection;
  }

  private static Problem outOfRange(InstanceConfig section, String key, String message) {
    return new Problem(ID, section.path() + "." + key, section.sourceOf(key), message, null);
  }
}
