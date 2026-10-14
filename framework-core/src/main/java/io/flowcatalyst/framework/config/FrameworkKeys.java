package io.flowcatalyst.framework.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The framework's removed keys (config-and-code contract, section 8): keys of things an application
 * can have many of, which are now defined in code, and driver keys. Kept in one place, in core,
 * so a leftover key fails start-up even when the module it belonged to is no longer listed. Most
 * specific patterns first: a key is reported with the first pattern it matches.
 */
final class FrameworkKeys {

  private FrameworkKeys() {}

  static final List<RemovedKey> REMOVED = build();

  private static List<RemovedKey> build() {
    List<RemovedKey> l = new ArrayList<>();

    // database and redis: instances are connections and stay configuration; only the driver went
    l.add(k("database.instances.*.driver", "database", "nothing: PostgreSQL is the only database driver"));
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

    // secrets
    String secretStores =
        "SecretsModule.builder().store(name, SecretStores.env(prefix) | SecretStores.file(directory)),"
            + " with a CachePolicy";
    l.add(k("secrets.default", "secrets", "SecretsModule.builder().defaultStore(name)"));
    for (String s : List.of("driver", "directory", "prefix", "max-bytes", "cache-ttl", "cache-max")) {
      l.add(k("secrets.instances.*." + s, "secrets", secretStores));
    }
    l.add(k("secrets", "secrets", secretStores));

    // flowcatalyst
    String fc = ", given to new FlowCatalystModule(options)";
    l.add(k("flowcatalyst.enabled", "flowcatalyst", "FlowCatalystOptions.disabled() or withEnabled(boolean)" + fc));
    l.add(k("flowcatalyst.timeout", "flowcatalyst", "FlowCatalystOptions.withTimeout(Duration)" + fc));
    l.add(k("flowcatalyst.sync.on-boot", "flowcatalyst", "FlowCatalystOptions.withSyncOnBoot(boolean)" + fc));
    l.add(k("flowcatalyst.outbox.stale-after", "flowcatalyst", "FlowCatalystOptions.withOutboxStaleAfter(Duration)" + fc));
    String rc = ", given with FlowCatalystOptions.withReceivers(options)";
    l.add(k("flowcatalyst.receivers.enabled", "flowcatalyst", "ReceiverOptions.disabled()" + rc));
    l.add(k("flowcatalyst.receivers.path", "flowcatalyst", "ReceiverOptions.withPath(String)" + rc));
    l.add(k("flowcatalyst.receivers.require-signature", "flowcatalyst", "ReceiverOptions.withRequireSignature(boolean)" + rc));
    l.add(k("flowcatalyst.receivers.dispatch-timeout", "flowcatalyst", "ReceiverOptions.withDispatchTimeout(Duration)" + rc));
    l.add(k("flowcatalyst.receivers.job-timeout", "flowcatalyst", "ReceiverOptions.withJobTimeout(Duration)" + rc));
    l.add(k("flowcatalyst.receivers.job-lock-lease", "flowcatalyst", "ReceiverOptions.withJobLockLease(Duration)" + rc));
    l.add(
        k(
            "flowcatalyst.receivers.completion-retry-delay",
            "flowcatalyst",
            "ReceiverOptions.withCompletionRetryDelay(Duration)" + rc));
    l.add(k("flowcatalyst.receivers.once-retention", "flowcatalyst", "ReceiverOptions.withOnceRetention(Duration)" + rc));
    l.add(
        k(
            "flowcatalyst.receivers.once-purge-interval",
            "flowcatalyst",
            "ReceiverOptions.withOncePurgeInterval(Duration)" + rc));
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
