/**
 * Rate limiting for HTTP routes (rate-limiting contract): named {@link
 * io.flowcatalyst.framework.http.ratelimit.RateLimiter} instances with a GCRA policy, the {@code
 * memory} driver, key resolvers, and the {@link io.flowcatalyst.framework.http.ratelimit.RateLimit}
 * middleware answering 429 with {@code Retry-After}.
 */
@NullMarked
package io.flowcatalyst.framework.http.ratelimit;

import org.jspecify.annotations.NullMarked;
