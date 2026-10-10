package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.LoopGate;
import java.time.Duration;

/**
 * A {@link LoopGate} that runs a background loop's run only while holding a lock, so of several
 * replicas one does each run (background-and-metrics contract, section 1). Each run takes the lock
 * with {@link LockStore#withLock} and a wait of zero: when another holder has it the run is
 * skipped, not delayed; while the run lasts the lease is refreshed; the run receives the fencing
 * token.
 *
 * <pre>{@code
 * background.loop(
 *     "invoice-sweeper",
 *     LoopOptions.every(Duration.ofMinutes(1))
 *         .gatedBy(LockGate.service(app.get(Locks.class), "invoice-sweeper", Duration.ofMinutes(2))),
 *     tick -> sweep(tick.fencingToken().orElseThrow()));
 * }</pre>
 */
public final class LockGate implements LoopGate {

  private final LockStore store;
  private final LockKey key;
  private final Duration lease;

  private LockGate(LockStore store, LockKey key, Duration lease) {
    if (lease.compareTo(Duration.ofMillis(100)) < 0 || lease.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("lease must be 100ms to 24h");
    }
    this.store = store;
    this.key = key;
    this.lease = lease;
  }

  /**
   * A gate on a key in a store.
   *
   * @param store the lock store
   * @param key the key
   * @param lease the lease, 100 ms to 24 h; refreshed every third of it while a run lasts
   * @return the gate
   */
  public static LockGate of(LockStore store, LockKey key, Duration lease) {
    return new LockGate(store, key, lease);
  }

  /**
   * A gate on a service-scoped key in the default lock instance.
   *
   * @param locks the lock registry
   * @param name the key's name, {@code [A-Za-z0-9._:-]{1,200}}
   * @param lease the lease, 100 ms to 24 h
   * @return the gate
   * @throws IllegalStateException when no lock instance is configured
   * @throws IllegalArgumentException when the name is bad
   */
  public static LockGate service(Locks locks, String name, Duration lease) {
    return new LockGate(locks.defaultInstance(), LockKey.service(name), lease);
  }

  @Override
  public Result enter(String loop, GatedRun work) throws Exception {
    WithLockResult<Boolean> result;
    try {
      result =
          store.withLock(
              key,
              lease,
              Duration.ZERO,
              held -> {
                work.run(held.fencingToken());
                return Boolean.TRUE;
              });
    } catch (LockedActionException e) {
      // The task threw a checked exception; withLock wrapped it. Hand the task's own exception on.
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
    return switch (result) {
      case WithLockResult.Completed<Boolean> done -> new Ran();
      case WithLockResult.NotAcquired<Boolean> busy -> new Skipped("lock " + key + " is held");
      case WithLockResult.Lost<Boolean> gone -> new Lost();
    };
  }
}
