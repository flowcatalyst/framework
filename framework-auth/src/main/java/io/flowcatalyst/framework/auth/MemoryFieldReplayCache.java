package io.flowcatalyst.framework.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;

/** The in-process {@link FieldReplayCache}: a map with expiry, swept as it grows. */
final class MemoryFieldReplayCache implements FieldReplayCache {

  private static final int SWEEP_AT = 4096;

  private final InstantSource clock;
  private final Map<String, Instant> seen = new HashMap<>();
  private int nextSweep = SWEEP_AT;

  MemoryFieldReplayCache(InstantSource clock) {
    this.clock = clock;
  }

  @Override
  public synchronized boolean firstUse(String jkt, String jti, Duration ttl) {
    Instant now = clock.instant();
    if (seen.size() >= nextSweep) {
      seen.values().removeIf(expiry -> !expiry.isAfter(now));
      nextSweep = Math.max(SWEEP_AT, seen.size() * 2);
    }
    String key = jkt + ":" + jti;
    Instant until = seen.get(key);
    if (until != null && until.isAfter(now)) {
      return false;
    }
    seen.put(key, now.plus(ttl));
    return true;
  }
}
