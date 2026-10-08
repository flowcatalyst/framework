package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One named lock instance: the user-facing API, built once on top of a {@link LockDriver}.
 *
 * <p>Handles argument limits, owner tokens, local deadlines, waiting with backoff and
 * {@link #withLock}. Thread-safe.
 */
public final class LockStore {

  private static final Logger LOG = LoggerFactory.getLogger(LockStore.class);

  static final Duration MIN_LEASE = Duration.ofMillis(100);
  static final Duration MAX_LEASE = Duration.ofHours(24);
  static final Duration MAX_WAIT = Duration.ofHours(24);

  private static final long BACKOFF_START_MS = 10;
  private static final long BACKOFF_MAX_MS = 250;
  private static final long REFRESH_RETRY_MAX_MS = 250;

  private final LockDriver driver;
  private final MonotonicClock clock;
  private final SecureRandom random = new SecureRandom();
  private final AtomicInteger activeRefreshers = new AtomicInteger();

  /**
   * Creates a store over a driver.
   *
   * @param driver the driver
   * @param clock the monotonic clock for local deadlines and waiting; should be the one the
   *     driver uses for expiry when the driver expires on a local clock (the memory driver)
   */
  public LockStore(LockDriver driver, MonotonicClock clock) {
    this.driver = driver;
    this.clock = clock;
  }

  long nanos() {
    return clock.nanos();
  }

  /**
   * The number of {@code withLock} refresher threads currently running. Zero whenever no
   * {@code withLock} call is in progress; exposed so tests can prove none leak.
   *
   * @return the count
   */
  public int activeRefreshers() {
    return activeRefreshers.get();
  }

  // ---- acquire -----------------------------------------------------------------------------

  /**
   * Tries once to take the lock.
   *
   * @param key what to lock
   * @param lease how long to hold it, 100 ms to 24 h
   * @return {@link AcquireResult.Acquired} or {@link AcquireResult.Busy}
   * @throws IllegalArgumentException when the lease length is out of range
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  public AcquireResult tryAcquire(LockKey key, Duration lease) {
    long leaseMillis = leaseMillis(lease);
    String owner = newOwnerToken();
    long sentAt = clock.nanos();
    OptionalLong fence = driver.tryAcquire(key.stored(), owner, leaseMillis);
    if (fence.isEmpty()) {
      return new AcquireResult.Busy();
    }
    return new AcquireResult.Acquired(
        new LeaseValue(key, owner, fence.getAsLong(), sentAt + millisToNanos(leaseMillis), this));
  }

  /**
   * Takes the lock, retrying with backoff (10 ms doubling to 250 ms, with jitter) until it is
   * acquired or the wait is used up.
   *
   * @param key what to lock
   * @param lease how long to hold it, 100 ms to 24 h
   * @param maxWait how long to keep trying, zero to 24 h
   * @return {@link AcquireResult.Acquired}, or {@link AcquireResult.Busy} when the wait ran out
   * @throws InterruptedException when the calling thread is interrupted
   * @throws IllegalArgumentException when the lease length or the wait is out of range
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  public AcquireResult acquire(LockKey key, Duration lease, Duration maxWait)
      throws InterruptedException {
    leaseMillis(lease);
    if (maxWait.isNegative() || maxWait.compareTo(MAX_WAIT) > 0) {
      throw new IllegalArgumentException("maxWait must be from zero to 24h: " + maxWait);
    }
    long startedAt = clock.nanos();
    long waitNanos = maxWait.toNanos();
    long backoffMs = BACKOFF_START_MS;
    while (true) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      AcquireResult result = tryAcquire(key, lease);
      if (result instanceof AcquireResult.Acquired) {
        return result;
      }
      long leftNanos = waitNanos - (clock.nanos() - startedAt);
      if (leftNanos <= 0) {
        return result;
      }
      long jittered = ThreadLocalRandom.current().nextLong(backoffMs / 2, backoffMs + 1);
      long leftMs = (leftNanos + 999_999L) / 1_000_000L;
      Thread.sleep(Math.max(1L, Math.min(jittered, leftMs)));
      backoffMs = Math.min(BACKOFF_MAX_MS, backoffMs * 2);
    }
  }

  // ---- refresh and release -----------------------------------------------------------------

  /**
   * Extends a lease. Acts only while the lease has not expired on the store's clock.
   *
   * @param lease a lease from this store
   * @param newLease the new lease length, counted from now, 100 ms to 24 h
   * @return {@link RefreshResult.Refreshed} with a new lease value, or {@link RefreshResult.Lost}
   * @throws IllegalArgumentException when the lease is not from this store, or the length is out
   *     of range
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  public RefreshResult refresh(Lease lease, Duration newLease) {
    LeaseValue held = own(lease);
    long leaseMillis = leaseMillis(newLease);
    long sentAt = clock.nanos();
    boolean ok =
        driver.refresh(held.key().stored(), held.ownerToken(), held.fencingToken(), leaseMillis);
    if (!ok) {
      return new RefreshResult.Lost();
    }
    return new RefreshResult.Refreshed(
        new LeaseValue(
            held.key(),
            held.ownerToken(),
            held.fencingToken(),
            sentAt + millisToNanos(leaseMillis),
            this));
  }

  /**
   * Releases a lease so the next acquire can succeed at once. Acts only while the lease has not
   * expired on the store's clock.
   *
   * @param lease a lease from this store
   * @return {@link ReleaseResult.Released} or {@link ReleaseResult.NotHeld}
   * @throws IllegalArgumentException when the lease is not from this store
   * @throws LockStoreUnavailableException on infrastructure failure
   */
  public ReleaseResult release(Lease lease) {
    LeaseValue held = own(lease);
    boolean ok = driver.release(held.key().stored(), held.ownerToken(), held.fencingToken());
    return ok ? new ReleaseResult.Released() : new ReleaseResult.NotHeld();
  }

  // ---- withLock ----------------------------------------------------------------------------

  /**
   * Runs an action while holding the lock, refreshing the lease every third of its length on a
   * virtual thread that is always stopped before this method returns.
   *
   * <p>If a refresh finds the lease lost, or keeps failing until the lease's local deadline has
   * passed, the lock is lost: the calling thread is interrupted. The result is then
   * {@link WithLockResult.Lost}, and the caller must treat the work as having run without
   * exclusion. An exception from the action is rethrown after cleanup: unchecked ones unchanged,
   * checked ones wrapped in {@link LockedActionException}. An {@link InterruptedException} thrown
   * by the action because of a loss is not rethrown.
   *
   * @param <T> the type the action returns
   * @param key what to lock
   * @param lease the lease length, 100 ms to 24 h
   * @param maxWait how long to wait for the lock, zero to 24 h
   * @param action the work
   * @return {@link WithLockResult.Completed}, {@link WithLockResult.NotAcquired} or
   *     {@link WithLockResult.Lost}
   * @throws InterruptedException when interrupted while waiting for the lock
   * @throws IllegalArgumentException when the lease length or the wait is out of range
   * @throws LockStoreUnavailableException on infrastructure failure while acquiring
   */
  public <T extends @Nullable Object> WithLockResult<T> withLock(
      LockKey key, Duration lease, Duration maxWait, LockedAction<T> action)
      throws InterruptedException {
    AcquireResult acquired = acquire(key, lease, maxWait);
    if (!(acquired instanceof AcquireResult.Acquired a)) {
      return new WithLockResult.NotAcquired<>();
    }
    return runHeld(a.lease(), lease, action);
  }

  /** State shared between the calling thread and its refresher. */
  private static final class Held {
    private final ReentrantLock lock = new ReentrantLock();
    private final Thread worker;
    private volatile Lease current;
    private boolean actionDone;
    private boolean lost;
    private boolean interruptSent;

    Held(Thread worker, Lease current) {
      this.worker = worker;
      this.current = current;
    }

    Lease current() {
      return current;
    }

    void setCurrent(Lease lease) {
      current = lease;
    }

    /** The refresher found the lock lost: interrupt the action, unless it already ended. */
    void signalLoss() {
      lock.lock();
      try {
        if (!actionDone && !lost) {
          lost = true;
          interruptSent = true;
          worker.interrupt();
        }
      } finally {
        lock.unlock();
      }
    }

    /** The action ended. From now on no loss is signalled. Returns whether it was lost before. */
    boolean endAction() {
      lock.lock();
      try {
        actionDone = true;
        return lost;
      } finally {
        lock.unlock();
      }
    }

    boolean interruptSent() {
      lock.lock();
      try {
        return interruptSent;
      } finally {
        lock.unlock();
      }
    }
  }

  private <T extends @Nullable Object> WithLockResult<T> runHeld(
      Lease first, Duration leaseLength, LockedAction<T> action) {
    Held held = new Held(Thread.currentThread(), first);
    Thread refresher =
        Thread.ofVirtual()
            .name("fw-lock-refresher")
            .unstarted(() -> refreshLoop(held, leaseLength));
    activeRefreshers.incrementAndGet();
    refresher.start();

    Outcome<T> outcome = invoke(action, first);

    boolean lost = held.endAction();
    refresher.interrupt();
    boolean interruptedWhileJoining = false;
    while (true) {
      try {
        refresher.join();
        break;
      } catch (InterruptedException e) {
        interruptedWhileJoining = true;
      }
    }
    if (held.interruptSent()) {
      // Our own loss interrupt may still be pending if the action never blocked.
      Thread.interrupted();
    }
    if (interruptedWhileJoining) {
      Thread.currentThread().interrupt();
    }

    if (!lost) {
      try {
        release(held.current());
      } catch (RuntimeException e) {
        LOG.warn("could not release lock {}", first.key(), e);
      }
    }

    return switch (outcome) {
      case Outcome.Threw<T> threw -> {
        Throwable thrown = threw.thrown();
        if (lost && thrown instanceof InterruptedException) {
          yield new WithLockResult.Lost<>(null);
        }
        if (thrown instanceof RuntimeException re) {
          throw re;
        }
        if (thrown instanceof Error err) {
          throw err;
        }
        if (thrown instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw new LockedActionException((Exception) thrown);
      }
      case Outcome.Returned<T> returned ->
          lost
              ? new WithLockResult.Lost<>(returned.value())
              : new WithLockResult.Completed<>(returned.value());
    };
  }

  /** What the action did: returned a value, or threw. */
  private sealed interface Outcome<T extends @Nullable Object> {
    record Returned<T extends @Nullable Object>(T value) implements Outcome<T> {}

    record Threw<T extends @Nullable Object>(Throwable thrown) implements Outcome<T> {}
  }

  private static <T extends @Nullable Object> Outcome<T> invoke(
      LockedAction<T> action, Lease lease) {
    try {
      return new Outcome.Returned<>(action.run(lease));
    } catch (Throwable t) {
      return new Outcome.Threw<>(t);
    }
  }

  private void refreshLoop(Held held, Duration leaseLength) {
    try {
      long periodMs = Math.max(1L, leaseLength.toMillis() / 3);
      long retryMs = Math.max(10L, Math.min(periodMs, REFRESH_RETRY_MAX_MS));
      long sleepMs = periodMs;
      while (true) {
        Thread.sleep(sleepMs); // an interrupt is the request to stop
        Lease current = held.current();
        try {
          switch (refresh(current, leaseLength)) {
            case RefreshResult.Refreshed r -> {
              held.setCurrent(r.lease());
              sleepMs = periodMs;
            }
            case RefreshResult.Lost l -> {
              held.signalLoss();
              return;
            }
          }
        } catch (RuntimeException e) {
          if (Thread.currentThread().isInterrupted()) {
            return;
          }
          if (current.expiredLocally()) {
            LOG.warn("lock {} lost: refresh kept failing until the lease deadline", current.key(), e);
            held.signalLoss();
            return;
          }
          LOG.warn("refreshing lock {} failed; will retry until its deadline", current.key(), e);
          sleepMs = retryMs;
        }
      }
    } catch (InterruptedException e) {
      // stop requested
    } finally {
      activeRefreshers.decrementAndGet();
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private LeaseValue own(Lease lease) {
    if (lease instanceof LeaseValue v && v.store() == this) {
      return v;
    }
    throw new IllegalArgumentException("this lease was not issued by this lock store");
  }

  private static long leaseMillis(Duration lease) {
    if (lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
      throw new IllegalArgumentException("lease must be from 100ms to 24h: " + lease);
    }
    return lease.toMillis();
  }

  private static long millisToNanos(long millis) {
    return millis * 1_000_000L;
  }

  private String newOwnerToken() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  @Override
  public String toString() {
    return "LockStore[" + driver.getClass().getSimpleName() + "]";
  }
}
