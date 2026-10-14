package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.MonotonicClock;

/**
 * The lock store factory of {@code framework-locks}: the in-process {@code memory} driver. The
 * {@code database} driver is {@code DatabaseLockStores} (framework-locks-database), the {@code
 * redis} driver {@code RedisLockStores} (framework-locks-redis).
 *
 * <pre>{@code
 * LocksModule.builder().store("local", LockStores.memory()).build()
 * }</pre>
 */
public final class LockStores {

  private LockStores() {}

  /**
   * In-process locks: exclusive within this JVM only, so for one replica or for tests.
   *
   * @return the factory
   */
  public static LockStoreFactory memory() {
    return (name, lookup) -> {
      MonotonicClock clock = lookup.get(MonotonicClock.class);
      return new LockStore(new MemoryLockDriver(clock::nanos), clock);
    };
  }
}
