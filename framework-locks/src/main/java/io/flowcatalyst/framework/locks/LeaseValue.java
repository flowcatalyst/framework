package io.flowcatalyst.framework.locks;

import java.time.Duration;

/** The only {@link Lease}. Package-private, so callers cannot construct one. */
record LeaseValue(
    LockKey key, String ownerToken, long fencingToken, long deadlineNanos, LockStore store)
    implements Lease {

  @Override
  public Duration remaining() {
    return Duration.ofNanos(deadlineNanos - store.nanos());
  }

  @Override
  public String toString() {
    // The owner token is a credential for refresh and release, so it is not printed.
    return "Lease[key=" + key + ", fencingToken=" + fencingToken + "]";
  }
}
