package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code memory} driver: one process, bounded (rate-limiting contract, section 2). When the map
 * is full, keys whose bucket is full again are purged (at most once a second); if it is still full,
 * new keys share one overflow bucket, so a flood of keys neither grows memory nor resets existing
 * keys.
 */
public final class MemoryRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryRateLimiter.class);

  /** The key new callers share when the map is full. */
  static final String OVERFLOW = "\u0000overflow";

  private static final long PURGE_EVERY = 1_000_000_000L;
  private static final long WARN_EVERY = 60_000_000_000L;

  private final String name;
  private final RateLimitPolicy policy;
  private final int maxKeys;
  private final MonotonicClock clock;
  private final Map<String, Long> tats = new ConcurrentHashMap<>();
  private final AtomicLong lastPurge = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

  /**
   * Creates the limiter.
   *
   * @param name the instance name
   * @param policy the policy
   * @param maxKeys the most keys held, 1 to 10 000 000
   * @param clock the monotonic clock
   */
  public MemoryRateLimiter(String name, RateLimitPolicy policy, int maxKeys, MonotonicClock clock) {
    if (maxKeys < 1 || maxKeys > 10_000_000) {
      throw new IllegalArgumentException("max-keys must be 1 to 10000000");
    }
    this.name = name;
    this.policy = policy;
    this.maxKeys = maxKeys;
    this.clock = clock;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public RateLimitPolicy policy() {
    return policy;
  }

  @Override
  public Decision acquire(String key, int cost) {
    Gcra.checkCost(cost, policy);
    long now = clock.nanos();
    String k = key;
    if (!tats.containsKey(k) && tats.size() >= maxKeys) {
      purge(now);
      if (tats.size() >= maxKeys) {
        k = OVERFLOW;
        warnFull(now);
      }
    }
    Gcra.Step[] out = new Gcra.Step[1];
    tats.compute(
        k,
        (key2, stored) -> {
          Gcra.Step step =
              Gcra.step(stored == null ? now : stored, now, policy.intervalNanos(), policy.burst(), cost);
          out[0] = step;
          if (step.allowed()) {
            return step.newTat();
          }
          return stored;
        });
    return Gcra.decision(out[0], 1);
  }

  /** Removes keys whose bucket is full again; they would start full anyway. */
  private void purge(long now) {
    long last = lastPurge.get();
    if (last != Long.MIN_VALUE && now - last < PURGE_EVERY) {
      return;
    }
    if (!lastPurge.compareAndSet(last, now)) {
      return;
    }
    tats.entrySet().removeIf(e -> !e.getKey().equals(OVERFLOW) && e.getValue() - now <= 0);
  }

  private void warnFull(long now) {
    long last = lastWarn.get();
    if ((last == Long.MIN_VALUE || now - last >= WARN_EVERY) && lastWarn.compareAndSet(last, now)) {
      LOG.warn("rate limiter {} holds {} keys (max-keys); new callers share one overflow bucket", name, maxKeys);
    }
  }

  /**
   * The number of keys held, for tests and diagnostics.
   *
   * @return the count
   */
  public int size() {
    return tats.size();
  }
}
