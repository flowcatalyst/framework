package io.flowcatalyst.framework.auth;

import java.time.Duration;
import java.time.InstantSource;

/**
 * Remembers the {@code jti} of each accepted DPoP proof per device key for {@link #WINDOW}, so a
 * captured proof cannot be used twice (contract section 15.4). The default is the memory cache; with
 * more than one replica a captured proof can be replayed against another replica inside the window,
 * so such an application passes the Redis cache ({@code RedisFieldReplayCache} in {@code
 * framework-auth-redis}). Replay also needs the access token ({@code ath}) and is limited to the
 * same method and URL.
 */
public interface FieldReplayCache {

  /** How long a proof id is remembered: the nonce window (10 minutes) plus one minute. */
  Duration WINDOW = Duration.ofMinutes(11);

  /**
   * Records a proof id.
   *
   * @param jkt the thumbprint of the device key that signed the proof
   * @param jti the proof's {@code jti}
   * @param ttl how long to remember it
   * @return true when it was not seen before (and is now), false for a replay
   * @throws RuntimeException when the cache cannot be reached; the request is then answered 503
   */
  boolean firstUse(String jkt, String jti, Duration ttl);

  /**
   * A cache in this process's memory. Replicas do not share it.
   *
   * @param clock the application's clock
   * @return the cache
   */
  static FieldReplayCache memory(InstantSource clock) {
    return new MemoryFieldReplayCache(clock);
  }
}
