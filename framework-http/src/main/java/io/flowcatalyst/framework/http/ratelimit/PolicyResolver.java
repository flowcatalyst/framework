package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.http.Request;
import org.jspecify.annotations.Nullable;

/**
 * Says which policy a request is decided with (rate-limiting contract, section 3a). It runs on
 * every request, so it must be an in-memory lookup, never a network call.
 */
@FunctionalInterface
public interface PolicyResolver {

  /**
   * The policy.
   *
   * @param request the request
   * @return the policy, {@link RateLimitPolicy#UNLIMITED} for a caller with no limit, or null for the
   *     limiter's default policy (never unlimited); a thrown exception is a policy
   *     failure, which follows {@link RateLimit.OnStoreFailure}
   */
  @Nullable RateLimitPolicy policy(Request request);
}
