package io.flowcatalyst.framework.secrets;

import java.time.Duration;

/**
 * How long found secrets are kept in memory (secrets contract section 3; config-and-code contract
 * section 3.7). Not-found answers are never cached.
 *
 * @param ttl how long a found value is kept, 0 (no cache) to 24 hours
 * @param maxEntries how many values are kept, 1 to 1 000 000; the least recently used goes first
 */
public record CachePolicy(Duration ttl, int maxEntries) {

  /** The longest time to live accepted. */
  public static final Duration MAX_TTL = Duration.ofHours(24);

  /** The most entries accepted. */
  public static final int MAX_ENTRIES_LIMIT = 1_000_000;

  /** Checks the ranges. */
  public CachePolicy {
    if (ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("ttl must be 0 to 24h, was " + ttl);
    }
    if (maxEntries < 1 || maxEntries > MAX_ENTRIES_LIMIT) {
      throw new IllegalArgumentException("maxEntries must be 1 to 1000000, was " + maxEntries);
    }
  }

  /**
   * Five minutes, 10 000 entries.
   *
   * @return the defaults
   */
  public static CachePolicy defaults() {
    return new CachePolicy(Duration.ofMinutes(5), 10_000);
  }

  /**
   * No cache: every lookup reads the store.
   *
   * @return the policy
   */
  public static CachePolicy none() {
    return new CachePolicy(Duration.ZERO, 1);
  }

  /**
   * A policy.
   *
   * @param ttl 0 to 24 hours
   * @param maxEntries 1 to 1 000 000
   * @return the policy
   */
  public static CachePolicy of(Duration ttl, int maxEntries) {
    return new CachePolicy(ttl, maxEntries);
  }
}
