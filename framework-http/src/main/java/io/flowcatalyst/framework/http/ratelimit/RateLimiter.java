package io.flowcatalyst.framework.http.ratelimit;

/**
 * One named limiter: a default policy and a store (rate-limiting contract, sections 1 and 2). Built
 * in code: {@link MemoryRateLimiter#create(String, RateLimitPolicy)}, or the {@code redis} driver's
 * {@code RedisRateLimiter.create}.
 */
public interface RateLimiter {

  /**
   * The name; keys are namespaced by it and metrics are labelled with it.
   *
   * @return the name
   */
  String name();

  /**
   * The default policy, used when a request brings none of its own.
   *
   * @return the policy
   */
  RateLimitPolicy policy();

  /**
   * Takes {@code cost} from the key's budget under the default policy when there is enough.
   *
   * @param key the caller's key, at most 200 characters (longer ones are hashed by the middleware)
   * @param cost 1 to the burst
   * @return the decision
   * @throws RateLimitStoreException when the store cannot answer
   */
  Decision acquire(String key, int cost);

  /**
   * Takes {@code cost} from the key's budget under {@code policy} (contract section 3a). The key's
   * stored state does not depend on the policy, so a key's policy may change from one request to
   * the next.
   *
   * <p>The default accepts only the limiter's own policy, for limiters written before per-request
   * policies; the shipped drivers override it.
   *
   * @param key the caller's key, at most 200 characters
   * @param cost 1 to that policy's burst
   * @param policy the policy to decide with
   * @return the decision
   * @throws RateLimitStoreException when the store cannot answer
   * @throws UnsupportedOperationException when the limiter does not support per-request policies
   *     and {@code policy} is not its own
   */
  default Decision acquire(String key, int cost, RateLimitPolicy policy) {
    if (policy.equals(policy())) {
      return acquire(key, cost);
    }
    throw new UnsupportedOperationException("rate limiter " + name() + " does not take per-request policies");
  }

  /**
   * Whether {@link #acquire(String, int, RateLimitPolicy)} accepts any policy.
   *
   * @return false unless the limiter overrides it
   */
  default boolean supportsPerRequestPolicy() {
    return false;
  }
}
