package io.flowcatalyst.framework.http.ratelimit;

import java.time.Duration;

/** What a limiter decided for one request. */
public sealed interface Decision {

  /**
   * The request may go ahead.
   *
   * @param remaining further requests allowed right now
   * @param resetAfter time until the bucket is full again
   */
  record Allowed(long remaining, Duration resetAfter) implements Decision {}

  /**
   * The request is over the limit; nothing was used up.
   *
   * @param retryAfter time until a request of this cost would be allowed
   * @param resetAfter time until the bucket is full again
   */
  record Limited(Duration retryAfter, Duration resetAfter) implements Decision {}
}
