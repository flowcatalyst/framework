/**
 * Rate limiting for HTTP routes (rate-limiting contract): named {@link
 * io.flowcatalyst.framework.http.ratelimit.RateLimiter}s built in code with a default GCRA policy,
 * the {@code memory} driver, key and policy resolvers, and the {@link
 * io.flowcatalyst.framework.http.ratelimit.RateLimit} middleware answering 429 with {@code
 * Retry-After}.
 */
@NullMarked
package io.flowcatalyst.framework.http.ratelimit;

import org.jspecify.annotations.NullMarked;
