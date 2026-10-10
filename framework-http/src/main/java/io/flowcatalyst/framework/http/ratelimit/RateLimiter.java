package io.flowcatalyst.framework.http.ratelimit;

/** One named limiter: a policy and a store (rate-limiting contract, sections 1 and 2). */
public interface RateLimiter {

  /**
   * The instance name; keys are namespaced by it.
   *
   * @return the name
   */
  String name();

  /**
   * The policy.
   *
   * @return the policy
   */
  RateLimitPolicy policy();

  /**
   * Takes {@code cost} from the key's budget when there is enough.
   *
   * @param key the caller's key, at most 200 characters (longer ones are hashed by the middleware)
   * @param cost 1 to the burst
   * @return the decision
   * @throws RateLimitStoreException when the store cannot answer
   */
  Decision acquire(String key, int cost);
}
