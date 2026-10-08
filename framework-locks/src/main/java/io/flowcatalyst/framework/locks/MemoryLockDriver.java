package io.flowcatalyst.framework.locks;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Locks within one process only: for a single instance, local development and tests. Two
 * processes using it do not exclude each other.
 *
 * <p>Expiry uses an injected monotonic clock, so tests can move time without sleeping. Fencing
 * tokens come from one counter per driver instance. Thread-safe, with one {@link ReentrantLock}.
 */
public final class MemoryLockDriver implements LockDriver {

  private record Held(String owner, long fence, long expiresAtNanos) {}

  private static final int SWEEP_EVERY = 4096;

  private final LongSupplier clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Held> held = new HashMap<>();
  private long counter;
  private int acquisitions;

  /**
   * Creates a driver.
   *
   * @param clock monotonic nanoseconds; use the same clock as the {@link LockStore}
   */
  public MemoryLockDriver(LongSupplier clock) {
    this.clock = clock;
  }

  @Override
  public OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis) {
    lock.lock();
    try {
      long now = clock.getAsLong();
      Held current = held.get(key);
      if (current != null && current.expiresAtNanos() > now) {
        return OptionalLong.empty();
      }
      if (++acquisitions % SWEEP_EVERY == 0) {
        held.values().removeIf(h -> h.expiresAtNanos() <= now);
      }
      long fence = ++counter;
      held.put(key, new Held(ownerToken, fence, now + leaseMillis * 1_000_000L));
      return OptionalLong.of(fence);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis) {
    lock.lock();
    try {
      long now = clock.getAsLong();
      Held current = held.get(key);
      if (!matches(current, ownerToken, fencingToken, now)) {
        return false;
      }
      held.put(key, new Held(ownerToken, fencingToken, now + leaseMillis * 1_000_000L));
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean release(String key, String ownerToken, long fencingToken) {
    lock.lock();
    try {
      long now = clock.getAsLong();
      Held current = held.get(key);
      if (!matches(current, ownerToken, fencingToken, now)) {
        return false;
      }
      held.put(key, new Held(ownerToken, fencingToken, now));
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Test hook: expires the key's current lease at once.
   *
   * @param key the stored key
   */
  public void expireNow(String key) {
    lock.lock();
    try {
      Held current = held.get(key);
      if (current != null) {
        held.put(key, new Held(current.owner(), current.fence(), clock.getAsLong()));
      }
    } finally {
      lock.unlock();
    }
  }

  private static boolean matches(@org.jspecify.annotations.Nullable Held current, String owner, long fence, long now) {
    return current != null
        && current.owner().equals(owner)
        && current.fence() == fence
        && current.expiresAtNanos() > now;
  }
}
