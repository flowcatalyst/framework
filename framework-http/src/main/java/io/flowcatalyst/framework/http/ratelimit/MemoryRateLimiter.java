package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code memory} driver: one process, bounded (rate-limiting contract, section 2). When the map
 * is full, keys whose bucket is full again are purged (at most once a second); if it is still full,
 * new keys share one overflow bucket, so a flood of keys neither grows memory nor resets existing
 * keys.
 *
 * <pre>{@code
 * RateLimiter api = MemoryRateLimiter.create("api", new RateLimitPolicy(100, Duration.ofMinutes(1), 20));
 * }</pre>
 */
public final class MemoryRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryRateLimiter.class);

  /** The key new callers share when the map is full. */
  static final String OVERFLOW = "\u0000overflow";

  private static final long PURGE_EVERY = 1_000_000_000L;
  private static final long WARN_EVERY = 60_000_000_000L;
  static final int DEFAULT_MAX_KEYS = 100_000;
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,62}");

  private final String name;
  private final RateLimitPolicy policy;
  private final int maxKeys;
  private final MonotonicClock clock;
  private final Map<String, Long> tats = new ConcurrentHashMap<>();
  private final AtomicLong lastPurge = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

  private MemoryRateLimiter(String name, RateLimitPolicy policy, int maxKeys, MonotonicClock clock) {
    this.name = checkName(name);
    if (maxKeys < 1 || maxKeys > 10_000_000) {
      throw new IllegalArgumentException("maxKeys must be 1 to 10000000");
    }
    this.policy = policy;
    this.maxKeys = maxKeys;
    this.clock = clock;
  }

  /**
   * A limiter holding at most 100 000 keys, on the system's monotonic clock.
   *
   * @param name {@code [a-z][a-z0-9_-]*}, at most 63 characters
   * @param policy the default policy
   * @return the limiter
   */
  public static MemoryRateLimiter create(String name, RateLimitPolicy policy) {
    return create(name, policy, DEFAULT_MAX_KEYS);
  }

  /**
   * A limiter on the system's monotonic clock.
   *
   * @param name {@code [a-z][a-z0-9_-]*}, at most 63 characters
   * @param policy the default policy
   * @param maxKeys the most keys held, 1 to 10 000 000
   * @return the limiter
   */
  public static MemoryRateLimiter create(String name, RateLimitPolicy policy, int maxKeys) {
    return create(name, policy, maxKeys, MonotonicClock.system());
  }

  /**
   * A limiter on the given clock, for tests that move time.
   *
   * @param name {@code [a-z][a-z0-9_-]*}, at most 63 characters
   * @param policy the default policy
   * @param maxKeys the most keys held, 1 to 10 000 000
   * @param clock the monotonic clock
   * @return the limiter
   */
  public static MemoryRateLimiter create(String name, RateLimitPolicy policy, int maxKeys, MonotonicClock clock) {
    return new MemoryRateLimiter(name, policy, maxKeys, clock);
  }

  static String checkName(String name) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("a rate limiter name must match [a-z][a-z0-9_-]* (at most 63 characters): " + name);
    }
    return name;
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
    return acquire(key, cost, policy);
  }

  @Override
  public boolean supportsPerRequestPolicy() {
    return true;
  }

  @Override
  public Decision acquire(String key, int cost, RateLimitPolicy given) {
    Gcra.checkCost(cost, given);
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
              Gcra.step(stored == null ? now : stored, now, given.intervalNanos(), given.burst(), cost);
          out[0] = step;
          return step.store() ? step.newTat() : stored;
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
