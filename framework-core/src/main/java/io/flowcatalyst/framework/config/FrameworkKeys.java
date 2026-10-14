package io.flowcatalyst.framework.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The framework's removed keys (config-and-code contract, section 8). Kept in one place, in core,
 * so a leftover key fails start-up even when the module it belonged to is no longer listed. Most
 * specific patterns first: a key is reported with the first pattern it matches.
 */
final class FrameworkKeys {

  private FrameworkKeys() {}

  static final List<RemovedKey> REMOVED = build();

  private static List<RemovedKey> build() {
    List<RemovedKey> l = new ArrayList<>();

    // core
    l.add(k("app.shutdown.drain", "core", "AppBuilder.shutdownDrain(Duration)"));
    for (String s : List.of("timeout", "critical", "informational")) {
      l.add(k("app.health." + s, "core", "AppBuilder.health(HealthPolicy)"));
    }
    for (String s : List.of("driver", "max-series")) {
      l.add(k("app.metrics." + s, "core", "AppBuilder.metrics(MetricsOptions)"));
    }

    // database and redis
    String pool = "DatabaseModule.builder().pool(DatabasePool)";
    for (String s : List.of("max", "min-idle", "connect-timeout")) {
      l.add(k("database.instances.*.pool." + s, "database", pool));
    }
    l.add(k("database.instances.*.pool", "database", pool));
    l.add(k("database.instances.*.migrate", "database", "DatabaseModule.builder().migrate(boolean)"));
    l.add(k("database.instances.*.driver", "database", "nothing: PostgreSQL is the only database driver"));
    String redisPool = "RedisModule.builder().pool(RedisPool)";
    l.add(k("redis.instances.*.timeout", "redis", redisPool));
    l.add(k("redis.instances.*.pool.max", "redis", redisPool));
    l.add(k("redis.instances.*.pool", "redis", redisPool));
    l.add(k("redis.instances.*.driver", "redis", "nothing: one Redis driver"));

    // locks
    String stores =
        "LocksModule.builder().store(name, LockStores.memory() | DatabaseLockStores.database(..) |"
            + " RedisLockStores.redis(..))";
    l.add(k("locks.default", "locks", "LocksModule.builder().defaultStore(name)"));
    for (String s : List.of("driver", "database", "connection", "prefix")) {
      l.add(k("locks.instances.*." + s, "locks", stores));
    }
    l.add(k("locks", "locks", stores));

    // http
    String jetty = ", given to new JettyHttpModule(options)";
    l.add(k("http.max-body-bytes", "http", "HttpOptions.withMaxBodyBytes(long)" + jetty));
    l.add(k("http.request-timeout", "http", "HttpOptions.withRequestTimeout(Duration)" + jetty));
    l.add(k("http.shutdown-grace", "http", "HttpOptions.withShutdownGrace(Duration)" + jetty));
    l.add(k("http.metrics.enabled", "http", "HttpOptions.withMetricsEndpoint(path)" + jetty));
    l.add(k("http.metrics.path", "http", "HttpOptions.withMetricsEndpoint(path)" + jetty));

    // http-client
    l.add(k("http-client.event-loops", "http-client", "HttpClientModule.builder().eventLoops(int)"));
    l.add(
        k(
            "http-client.max-connections-per-host",
            "http-client",
            "HttpClientModule.builder().maxConnectionsPerHost(int)"));
    String named = "nothing: clients are named in code, HttpClientModule.builder().client(name, options)";
    l.add(k("http-clients.default", "http-client", named));
    l.add(k("http-clients.instances.*.driver", "http-client", named));
    l.add(k("http-clients.instances.*.connect-timeout", "http-client", "HttpClientOptions.withConnectTimeout(Duration)"));
    l.add(k("http-clients.instances.*.request-timeout", "http-client", "HttpClientOptions.withRequestTimeout(Duration)"));
    l.add(k("http-clients.instances.*.max-response-bytes", "http-client", "HttpClientOptions.withMaxResponseBytes(long)"));
    for (String s : List.of("max-attempts", "initial-backoff", "max-backoff", "max-retry-after")) {
      l.add(k("http-clients.instances.*.retry." + s, "http-client", "HttpClientOptions.withRetry(RetryPolicy)"));
    }
    l.add(k("http-clients.instances.*.retry", "http-client", "HttpClientOptions.withRetry(RetryPolicy)"));
    for (String s : List.of("type", "method", "scope", "audience", "name")) {
      l.add(k("http-clients.instances.*.auth." + s, "http-client", "HttpClientOptions.withAuth(ClientAuth)"));
    }

    // rate limits (MA item 10)
    String limiters = "MemoryRateLimiter.create or RedisRateLimiter.create, built in code";
    l.add(k("rate-limits.default", "rate-limit", limiters));
    for (String s : List.of("driver", "limit", "period", "burst", "max-keys", "connection", "prefix")) {
      l.add(k("rate-limits.instances.*." + s, "rate-limit", limiters));
    }
    l.add(k("rate-limits", "rate-limit", limiters));

    return List.copyOf(l);
  }

  private static RemovedKey k(String pattern, String module, String movedTo) {
    return new RemovedKey(pattern, module, movedTo);
  }
}
