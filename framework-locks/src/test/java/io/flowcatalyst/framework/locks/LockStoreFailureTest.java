package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** How {@code withLock} behaves when the store fails, using a driver that can be told to. */
class LockStoreFailureTest {

  /** A memory driver that fails on demand. */
  private static final class FlakyDriver implements LockDriver {
    private final MemoryLockDriver inner = new MemoryLockDriver(System::nanoTime);
    final AtomicBoolean refreshFails = new AtomicBoolean();
    final AtomicBoolean releaseFails = new AtomicBoolean();
    final AtomicInteger refreshCalls = new AtomicInteger();

    @Override
    public OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis) {
      return inner.tryAcquire(key, ownerToken, leaseMillis);
    }

    @Override
    public boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis) {
      refreshCalls.incrementAndGet();
      if (refreshFails.get()) {
        throw new LockStoreUnavailableException("down", new IllegalStateException("connection refused"));
      }
      return inner.refresh(key, ownerToken, fencingToken, leaseMillis);
    }

    @Override
    public boolean release(String key, String ownerToken, long fencingToken) {
      if (releaseFails.get()) {
        throw new LockStoreUnavailableException("down", new IllegalStateException("connection refused"));
      }
      return inner.release(key, ownerToken, fencingToken);
    }
  }

  private final FlakyDriver driver = new FlakyDriver();
  private final LockStore store = new LockStore(driver, System::nanoTime);
  private final LockKey key = LockKey.service("flaky");

  @Test
  void aRefreshThatKeepsFailingUntilTheDeadlineLosesTheLock() throws Exception {
    long t0 = System.nanoTime();
    WithLockResult<String> r =
        store.withLock(
            key,
            Duration.ofMillis(600),
            Duration.ZERO,
            lease -> {
              driver.refreshFails.set(true);
              Thread.sleep(30_000);
              return "never";
            });
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r).isInstanceOf(WithLockResult.Lost.class);
    // lost once the local deadline (600ms) passed, not before and not much later
    assertThat(elapsedMs).isBetween(500L, 3_000L);
    assertThat(driver.refreshCalls.get()).isGreaterThan(1);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void aRefreshThatFailsBrieflyIsRetriedAndTheLockIsKept() throws Exception {
    WithLockResult<String> r =
        store.withLock(
            key,
            Duration.ofMillis(900),
            Duration.ZERO,
            lease -> {
              driver.refreshFails.set(true);
              Thread.sleep(400); // one or two failed refreshes, well before the deadline
              driver.refreshFails.set(false);
              Thread.sleep(1_200); // beyond the original lease: only a later refresh keeps it
              return "kept";
            });
    assertThat(r).isEqualTo(new WithLockResult.Completed<>("kept"));
  }

  @Test
  void aFailedReleaseIsLoggedAndDoesNotChangeTheResult() throws Exception {
    WithLockResult<String> r =
        store.withLock(
            key,
            Duration.ofSeconds(30),
            Duration.ZERO,
            lease -> {
              driver.releaseFails.set(true);
              return "done";
            });
    assertThat(r).isEqualTo(new WithLockResult.Completed<>("done"));
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void aFailedReleaseDoesNotHideTheActionsException() {
    IllegalStateException boom = new IllegalStateException("boom");
    assertThatThrownBy(
            () ->
                store.withLock(
                    key,
                    Duration.ofSeconds(30),
                    Duration.ZERO,
                    lease -> {
                      driver.releaseFails.set(true);
                      throw boom;
                    }))
        .isSameAs(boom);
  }

  @Test
  void tryAcquireSurfacesInfrastructureFailureAsAnException() {
    LockStore broken =
        new LockStore(
            new LockDriver() {
              @Override
              public OptionalLong tryAcquire(String k, String o, long ms) {
                throw new LockStoreUnavailableException("no store", new RuntimeException("x"));
              }

              @Override
              public boolean refresh(String k, String o, long f, long ms) {
                return false;
              }

              @Override
              public boolean release(String k, String o, long f) {
                return false;
              }
            },
            System::nanoTime);
    assertThatThrownBy(() -> broken.tryAcquire(key, Duration.ofSeconds(1)))
        .isInstanceOf(LockStoreUnavailableException.class)
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void anInterruptThatLandsInsideTheDriverStillStopsAWaitingAcquire() {
    LockStore interrupting =
        new LockStore(
            new LockDriver() {
              @Override
              public OptionalLong tryAcquire(String k, String o, long ms) {
                Thread.currentThread().interrupt(); // as a JDBC call does when interrupted
                throw new LockStoreUnavailableException("interrupted", new RuntimeException("x"));
              }

              @Override
              public boolean refresh(String k, String o, long f, long ms) {
                return false;
              }

              @Override
              public boolean release(String k, String o, long f) {
                return false;
              }
            },
            System::nanoTime);
    assertThatThrownBy(() -> interrupting.acquire(key, Duration.ofSeconds(1), Duration.ofSeconds(5)))
        .isInstanceOf(InterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).as("the status was consumed by the throw").isFalse();
  }

  @Test
  void aLeaseDoesNotPrintItsOwnerToken() {
    Lease lease = ((AcquireResult.Acquired) store.tryAcquire(key, Duration.ofSeconds(5))).lease();
    assertThat(lease.toString()).doesNotContain(lease.ownerToken()).contains("flaky");
  }
}
