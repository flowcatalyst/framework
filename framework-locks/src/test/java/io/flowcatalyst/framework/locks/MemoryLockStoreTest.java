package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The memory driver against the shared contract suite, plus its own clock behaviour. */
class MemoryLockStoreTest extends LockStoreContract {

  private final AdjustableClock clock = new AdjustableClock();
  private MemoryLockDriver driver;

  @Override
  protected LockStore newStore() {
    MemoryLockDriver d = new MemoryLockDriver(clock::nanos);
    if (store == null) {
      driver = d;
    }
    return new LockStore(d, clock);
  }

  @Override
  protected void letTimePass(Duration d) {
    clock.advance(d);
  }

  @Override
  protected void expireNow(LockKey key) {
    driver.expireNow(key.stored());
  }

  @Test
  void expiryFollowsTheInjectedClockExactlyWithoutSleeping() {
    long[] now = {1_000_000_000L};
    MemoryLockDriver d = new MemoryLockDriver(() -> now[0]);
    LockStore s = new LockStore(d, () -> now[0]);
    LockKey key = LockKey.service("clock");
    Lease lease = ((AcquireResult.Acquired) s.tryAcquire(key, Duration.ofSeconds(1))).lease();
    now[0] += 999_999_999L; // one nanosecond before expiry
    assertThat(s.tryAcquire(key, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Busy.class);
    assertThat(s.refresh(lease, Duration.ofSeconds(1))).isInstanceOf(RefreshResult.Refreshed.class);
    now[0] += 1_000_000_000L; // exactly at the new expiry: expired
    assertThat(s.release(lease)).isInstanceOf(ReleaseResult.NotHeld.class);
    assertThat(s.tryAcquire(key, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void tokensComeFromOneCounterPerStoreInstance() {
    LockStore one = new LockStore(new MemoryLockDriver(System::nanoTime), System::nanoTime);
    LockStore two = new LockStore(new MemoryLockDriver(System::nanoTime), System::nanoTime);
    assertThat(token(one, "a")).isEqualTo(1);
    assertThat(token(one, "b")).isEqualTo(2);
    assertThat(token(two, "a")).isEqualTo(1);
  }

  private static long token(LockStore s, String name) {
    return ((AcquireResult.Acquired) s.tryAcquire(LockKey.service(name), Duration.ofSeconds(5)))
        .lease()
        .fencingToken();
  }
}
