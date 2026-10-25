package io.flowcatalyst.framework.secrets;

import java.time.Duration;

/**
 * How long found secrets are kept in memory (secrets contract section 3; config-and-code contract
 * section 3.7). Not-found answers are never cached.
 *
 * @param ttl how long a found value is kept, 0 (no cache) to 24 hours
 * @param maxEntries how many values are kept, 1 to 1 000 000; the least recently used goes first
 * @param staleIfError how long past its time to live an expired value may still be served when the
 *     store throws {@link SecretStoreException} (platform down, 5xx, network); 0 turns it off, at most
 *     24 hours. Never applies to "not found" or a refusal
 */
public record CachePolicy(Duration ttl, int maxEntries, Duration staleIfError) {

  /** The default time an expired value is served while the store is failing: five minutes. */
  public static final Duration DEFAULT_STALE_IF_ERROR = Duration.ofMinutes(5);

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
    if (staleIfError.isNegative() || staleIfError.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("staleIfError must be 0 to 24h, was " + staleIfError);
    }
  }

  /**
   * A policy with the default {@link #DEFAULT_STALE_IF_ERROR}.
   *
   * @param ttl 0 to 24 hours
   * @param maxEntries 1 to 1 000 000
   */
  public CachePolicy(Duration ttl, int maxEntries) {
    this(ttl, maxEntries, DEFAULT_STALE_IF_ERROR);
  }

  /**
   * A copy with another stale-if-error window.
   *
   * @param value 0 (serve nothing stale) to 24 hours
   * @return the copy
   */
  public CachePolicy withStaleIfError(Duration value) {
    return new CachePolicy(ttl, maxEntries, value);
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
    return new CachePolicy(Duration.ZERO, 1, Duration.ZERO);
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
