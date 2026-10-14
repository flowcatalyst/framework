package io.flowcatalyst.framework.redis;

import io.flowcatalyst.framework.config.Config;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 *     default: { url: "redis://localhost:6379/0" }
 * }</pre>
 *
 * <p>Keys per instance, connection only: {@code url} (required; {@code redis://} or {@code
 * rediss://}, may carry user and password) and {@code password} (a secret; wins over the URL's).
 * The timeout and pool size are code (config-and-code contract, section 3.3):
 *
 * <pre>{@code
 * RedisModule.builder().pool(RedisPool.defaults().withMaxSize(32)).pool("cache", RedisPool.defaults().withTimeout(Duration.ofMillis(500))).build()
 * }</pre>
 *
 * <p>Each instance's health check is {@code redis:<name>}.
 */
public final class RedisModule implements Module {

  /** The module id. */
  public static final String ID = "redis";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");

  private final Function<Config, RedisPool> pool;
  private final Map<String, Function<Config, RedisPool>> pools;

  /** The defaults: {@link RedisPool#defaults()} for every instance. */
  public RedisModule() {
    this(builder());
  }

  private RedisModule(Builder b) {
    this.pool = b.pool;
    this.pools = new LinkedHashMap<>(b.pools);
  }

  /**
   * Starts a module definition with the defaults.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link RedisModule}. */
  public static final class Builder {
    private Function<Config, RedisPool> pool = c -> RedisPool.defaults();
    private final Map<String, Function<Config, RedisPool>> pools = new LinkedHashMap<>();

    private Builder() {}

    /**
     * The pool of every instance without one of its own (default {@link RedisPool#defaults()}).
     *
     * @param value the pool
     * @return this builder
     */
    public Builder pool(RedisPool value) {
      this.pool = c -> value;
      return this;
    }

    /**
     * As {@link #pool(RedisPool)}, computed from configuration once it is loaded.
     *
     * @param value computes the pool
     * @return this builder
     */
    public Builder pool(Function<Config, RedisPool> value) {
      this.pool = value;
      return this;
    }

    /**
     * The pool of one instance. Naming an instance that is not configured fails start-up.
     *
     * @param instance the instance name under {@code redis.instances}
     * @param value the pool
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder pool(String instance, RedisPool value) {
      return pool(instance, c -> value);
    }

    /**
     * As {@link #pool(String, RedisPool)}, computed from configuration once it is loaded.
     *
     * @param instance the instance name
     * @param value computes the pool
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder pool(String instance, Function<Config, RedisPool> value) {
      if (!NAME.matcher(instance).matches()) {
        throw new IllegalArgumentException("invalid redis instance name '" + instance + "'");
      }
      if (pools.putIfAbsent(instance, value) != null) {
        throw new IllegalArgumentException("a pool for redis instance '" + instance + "' is already given");
      }
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public RedisModule build() {
      return new RedisModule(this);
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.provide(
        RedisConnections.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          RedisPool common = pool.apply(config);
          Map<String, RedisPool> own = new LinkedHashMap<>();
          for (Map.Entry<String, Function<Config, RedisPool>> e : pools.entrySet()) {
            own.put(e.getKey(), e.getValue().apply(config));
          }
          Set<String> configured = config.section("redis").section("instances").keys();
          List<Problem> problems = new ArrayList<>();
          for (String name : own.keySet()) {
            if (!configured.contains(name)) {
              problems.add(
                  Problem.of(
                      ID,
                      "redis.instances." + name,
                      "RedisModule.builder().pool(\""
                          + name
                          + "\", ...) names an instance that is not configured; configured: "
                          + (configured.isEmpty() ? "(none)" : String.join(", ", configured))));
            }
          }
          if (!problems.isEmpty()) {
            throw new StartupException(problems);
          }
          return new RedisConnections(
              Instances.build(
                  RedisConnection.class,
                  "redis",
                  lookup,
                  (name, section, l, built) -> createInstance(name, section, own.getOrDefault(name, common))));
        });
  }

  @Override
  public void boot(App app) {
    RedisConnections connections = app.get(RedisConnections.class);
    for (String name : connections.names()) {
      RedisConnection connection = connections.get(name);
      app.health().add("redis:" + name, connection::ping);
    }
  }

  private static RedisConnection createInstance(String name, InstanceConfig config, RedisPool settings) {
    String url = config.string("url");
    Duration timeout = settings.timeout();
    int poolMax = settings.maxSize();
    Secret passwordKey = config.findSecret("password").orElse(null);

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
}
