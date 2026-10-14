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
