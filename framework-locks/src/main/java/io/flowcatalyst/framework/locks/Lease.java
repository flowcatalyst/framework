package io.flowcatalyst.framework.locks;

/**
 * The right to hold a lock until a deadline, with the fencing token of that acquisition.
 *
 * <p>Only a {@link LockStore} creates leases, and a store accepts only leases it created: a lease
 * from one store passed to another is an {@link IllegalArgumentException}. Whatever the protected
 * work writes to should reject a write carrying a lower {@link #fencingToken()} than one it has
 * already seen.
 */
public sealed interface Lease permits LeaseValue {

  /**
   * The key held.
   *
   * @return the key
   */
  LockKey key();

  /**
   * The opaque owner token: at least 128 random bits, unique per acquisition.
   *
   * @return the token
   */
  String ownerToken();

  /**
   * The fencing token: grows with each acquisition of this key from this store.
   *
   * @return the token
   */
  long fencingToken();

  /**
   * The local deadline: the holder's monotonic clock reading taken before the request was sent,
   * plus the lease length, so it is never later than the store's own expiry.
   *
   * @return nanoseconds on the store's monotonic clock
   */
  long deadlineNanos();

  /**
   * Time left before the local deadline.
   *
   * @return the remaining time, zero or negative once the deadline has passed
   */
  java.time.Duration remaining();

  /**
   * Whether the local deadline has passed. Once true, the lock must be treated as lost.
   *
   * @return true when {@link #remaining()} is zero or less
   */
  default boolean expiredLocally() {
    return !remaining().isPositive();
  }
}
