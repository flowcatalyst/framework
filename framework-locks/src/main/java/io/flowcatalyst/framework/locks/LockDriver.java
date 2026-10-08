package io.flowcatalyst.framework.locks;

import java.util.OptionalLong;

/**
 * What a driver implements. Small on purpose: {@link LockStore} builds the user-facing API
 * (validation, tokens, local deadlines, waiting, {@code withLock}) once on top of it.
 *
 * <p>Implementations guarantee the points of section 3 of the locks contract: mutual exclusion,
 * expiry on the store's own clock, strictly increasing fencing tokens per key, owner-checked
 * refresh and release that fail after expiry, and no reentrancy. Infrastructure failures throw
 * {@link LockStoreUnavailableException} with the cause attached.
 */
public interface LockDriver {

  /**
   * Takes the lock if it is free or expired.
   *
   * @param key the stored key
   * @param ownerToken the acquirer's owner token
   * @param leaseMillis the lease length in milliseconds
   * @return the new fencing token, or empty when the lock is held and unexpired
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis);

  /**
   * Extends the lease if the owner and fencing token match and it has not expired.
   *
   * @param key the stored key
   * @param ownerToken the owner token
   * @param fencingToken the fencing token
   * @param leaseMillis the new lease length in milliseconds, counted from now
   * @return true if extended
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis);

  /**
   * Releases the lock if the owner and fencing token match and it has not expired.
   *
   * @param key the stored key
   * @param ownerToken the owner token
   * @param fencingToken the fencing token
   * @return true if released
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  boolean release(String key, String ownerToken, long fencingToken);
}
