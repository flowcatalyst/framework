package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract test suite for lock drivers (locks contract, section 6). A driver's test extends
 * this class and supplies a store, a way to let time pass, and a hook that expires a key's
 * current lease at once in the store itself. A driver is done when it passes every test here.
 */
public abstract class LockStoreContract {

  /** The store under test; a fresh one for every test. */
  protected LockStore store;

  /** Creates a new store on the same backend as the others (a fresh {@link LockStore} object). */
  protected abstract LockStore newStore();

  /**
   * Lets time pass for the store: moves the clock for the memory driver, sleeps for the others.
   * Not used where the refresher must run, because it sleeps in real time.
   */
  protected abstract void letTimePass(Duration d) throws InterruptedException;

  /** Test hook: expires the key's current lease at once, in the store itself. */
  protected abstract void expireNow(LockKey key);

  /** A long lease that never expires within a test. */
  protected static final Duration LONG = Duration.ofSeconds(60);

  /** A short lease for tests that need expiry or refresh. */
  protected static final Duration SHORT = Duration.ofMillis(800);

  @BeforeEach
  void createStore() {
    store = newStore();
  }

  private static LockKey key() {
    return LockKey.service("t-" + UUID.randomUUID());
  }

  private Lease acquire(LockKey key, Duration lease) {
    AcquireResult r = store.tryAcquire(key, lease);
    assertThat(r).isInstanceOf(AcquireResult.Acquired.class);
    return ((AcquireResult.Acquired) r).lease();
  }

  private static void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  // ---- mutual exclusion, tokens, ownership --------------------------------------------------

  @Test
  void oneOfFiftyVirtualThreadsRacingOnAFreeKeyWins() throws Exception {
    LockKey key = key();
    int n = 50;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger acquired = new AtomicInteger();
    AtomicInteger busy = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      return;
                    }
                    switch (store.tryAcquire(key, LONG)) {
                      case AcquireResult.Acquired a -> acquired.incrementAndGet();
                      case AcquireResult.Busy b -> busy.incrementAndGet();
                    }
                  }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }
    assertThat(acquired).hasValue(1);
    assertThat(busy).hasValue(n - 1);
  }

  @Test
  void twentyAcquireReleaseCyclesGiveStrictlyIncreasingTokens() {
    LockKey key = key();
    long previous = Long.MIN_VALUE;
    for (int i = 0; i < 20; i++) {
      Lease lease = acquire(key, LONG);
      assertThat(lease.fencingToken()).isGreaterThan(previous);
      previous = lease.fencingToken();
      assertThat(store.release(lease)).isInstanceOf(ReleaseResult.Released.class);
    }
  }

  @Test
  void twentyCyclesEndingByExpiryGiveStrictlyIncreasingTokens() {
    LockKey key = key();
    long previous = Long.MIN_VALUE;
    for (int i = 0; i < 20; i++) {
      Lease lease = acquire(key, LONG);
      assertThat(lease.fencingToken()).isGreaterThan(previous);
      previous = lease.fencingToken();
      expireNow(key);
    }
  }

  @Test
  void tokensStillIncreaseAfterARealExpiry() throws Exception {
    LockKey key = key();
    Lease first = acquire(key, SHORT);
    letTimePass(SHORT.plusMillis(300));
    Lease second = acquire(key, LONG);
    assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
  }

  @Test
  void aKeyIsFreeAtOnceAfterReleaseAndAfterExpiry() {
    LockKey key = key();
    Lease a = acquire(key, LONG);
    store.release(a);
    Lease b = acquire(key, LONG);
    expireNow(key);
    Lease c = acquire(key, LONG);
    assertThat(List.of(a.fencingToken(), b.fencingToken(), c.fencingToken())).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void acquiringAKeyYouAlreadyHoldIsBusy() {
    LockKey key = key();
    Lease held = acquire(key, LONG);
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Busy.class);
    // and the first lease is unharmed
    assertThat(store.refresh(held, LONG)).isInstanceOf(RefreshResult.Refreshed.class);
  }

  @Test
  void refreshExtendsTheLeaseAndKeepsTheTokens() throws Exception {
    LockKey key = key();
    Lease first = acquire(key, SHORT);
    letTimePass(Duration.ofMillis(500));
    RefreshResult r = store.refresh(first, SHORT);
    assertThat(r).isInstanceOf(RefreshResult.Refreshed.class);
    Lease second = ((RefreshResult.Refreshed) r).lease();
    assertThat(second.fencingToken()).isEqualTo(first.fencingToken());
    assertThat(second.ownerToken()).isEqualTo(first.ownerToken());
    assertThat(second.key()).isEqualTo(key);
    assertThat(second.deadlineNanos()).isGreaterThan(first.deadlineNanos());
    letTimePass(Duration.ofMillis(500)); // past the first lease's expiry, inside the second's
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Busy.class);
    assertThat(store.release(second)).isInstanceOf(ReleaseResult.Released.class);
  }

  @Test
  void refreshAndReleaseAfterExpiryAreLostEvenWhenNobodyElseTookTheKey() {
    LockKey key = key();
    Lease lease = acquire(key, LONG);
    expireNow(key);
    assertThat(store.refresh(lease, LONG)).isInstanceOf(RefreshResult.Lost.class);
    assertThat(store.release(lease)).isInstanceOf(ReleaseResult.NotHeld.class);
    // nothing changed: the key is simply free
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void refreshAndReleaseAfterARealExpiryAreLost() throws Exception {
    LockKey key = key();
    Lease lease = acquire(key, SHORT);
    letTimePass(SHORT.plusMillis(300));
    assertThat(store.refresh(lease, LONG)).isInstanceOf(RefreshResult.Lost.class);
    assertThat(store.release(lease)).isInstanceOf(ReleaseResult.NotHeld.class);
  }

  @Test
  void aStaleLeaseCannotReleaseOrRefreshANewerHoldersLock() {
    LockKey key = key();
    Lease old = acquire(key, LONG);
    expireNow(key);
    Lease current = acquire(key, LONG);
    assertThat(current.fencingToken()).isGreaterThan(old.fencingToken());
    assertThat(store.release(old)).isInstanceOf(ReleaseResult.NotHeld.class);
    assertThat(store.refresh(old, LONG)).isInstanceOf(RefreshResult.Lost.class);
    // the newer holder still holds it
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Busy.class);
    assertThat(store.refresh(current, LONG)).isInstanceOf(RefreshResult.Refreshed.class);
  }

  @Test
  void releasingTwiceIsNotHeldTheSecondTime() {
    Lease lease = acquire(key(), LONG);
    assertThat(store.release(lease)).isInstanceOf(ReleaseResult.Released.class);
    assertThat(store.release(lease)).isInstanceOf(ReleaseResult.NotHeld.class);
  }

  @Test
  void ownerTokensAreUniqueAndAtLeast128Bits() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      Lease lease = acquire(key(), LONG);
      assertThat(lease.ownerToken().length()).isGreaterThanOrEqualTo(32);
      assertThat(tokens.add(lease.ownerToken())).isTrue();
    }
  }

  @Test
  void theLocalDeadlineIsInTheFutureAndNotBeyondTheLease() throws Exception {
    Lease lease = acquire(key(), SHORT);
    assertThat(lease.remaining()).isPositive().isLessThanOrEqualTo(SHORT);
    assertThat(lease.expiredLocally()).isFalse();
    letTimePass(SHORT.plusMillis(100));
    assertThat(lease.expiredLocally()).isTrue();
    assertThat(lease.remaining().isPositive()).isFalse();
  }

  @Test
  void aLeaseFromAnotherStoreInstanceIsRejected() {
    LockKey key = key();
    Lease mine = acquire(key, LONG);
    LockStore other = newStore();
    assertThatIllegalArgumentException().isThrownBy(() -> other.refresh(mine, LONG));
    assertThatIllegalArgumentException().isThrownBy(() -> other.release(mine));
    // and it did nothing to the lock
    assertThat(store.refresh(mine, LONG)).isInstanceOf(RefreshResult.Refreshed.class);
  }

  @Test
  void leasesCannotBeForgedOrImplementedByCallers() {
    // Lease is sealed and its only implementation is package-private, so a caller cannot build
    // one; this documents it by checking the sealing.
    assertThat(Lease.class.isSealed()).isTrue();
    assertThat(Lease.class.getPermittedSubclasses()).hasSize(1);
  }

  // ---- keys --------------------------------------------------------------------------------

  @Test
  void serviceAndClientKeysWithTheSameNameDoNotCollide() {
    String name = "same-" + UUID.randomUUID();
    Lease service = acquire(LockKey.service(name), LONG);
    Lease client = acquire(LockKey.client(new ClientId("clt1"), name), LONG);
    assertThat(service.key()).isNotEqualTo(client.key());
  }

  @Test
  void keysOfDifferentClientsDoNotCollide() {
    String name = "same-" + UUID.randomUUID();
    acquire(LockKey.client(new ClientId("clt1"), name), LONG);
    acquire(LockKey.client(new ClientId("clt2"), name), LONG);
    assertThat(store.tryAcquire(LockKey.client(new ClientId("clt1"), name), LONG))
        .isInstanceOf(AcquireResult.Busy.class);
  }

  // ---- acquire with a wait -----------------------------------------------------------------

  @Test
  void acquireWithAWaitSucceedsOnceTheHolderReleases() throws Exception {
    LockKey key = key();
    Lease holder = acquire(key, LONG);
    Thread releaser =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    sleep(250);
                  } catch (InterruptedException e) {
                    return;
                  }
                  store.release(holder);
                });
    long t0 = System.nanoTime();
    AcquireResult r = store.acquire(key, LONG, Duration.ofSeconds(10));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
    releaser.join();
    assertThat(r).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(200).isLessThan(5_000);
    assertThat(((AcquireResult.Acquired) r).lease().fencingToken())
        .isGreaterThan(holder.fencingToken());
  }

  @Test
  void acquireWithAWaitReturnsBusyWhenTheWaitRunsOut() throws Exception {
    LockKey key = key();
    acquire(key, LONG);
    long t0 = System.nanoTime();
    AcquireResult r = store.acquire(key, LONG, Duration.ofMillis(400));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
    assertThat(r).isInstanceOf(AcquireResult.Busy.class);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(350).isLessThan(3_000);
  }

  @Test
  void acquireWithAZeroWaitMakesOneAttempt() throws Exception {
    LockKey key = key();
    acquire(key, LONG);
    long t0 = System.nanoTime();
    assertThat(store.acquire(key, LONG, Duration.ZERO)).isInstanceOf(AcquireResult.Busy.class);
    assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_000);
    assertThat(store.acquire(key(), LONG, Duration.ZERO)).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void aWaitingAcquireStopsWithInterruptedExceptionWhenInterrupted() throws Exception {
    LockKey key = key();
    acquire(key, LONG);
    AtomicReference<Throwable> seen = new AtomicReference<>();
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    store.acquire(key, LONG, Duration.ofSeconds(30));
                  } catch (Throwable t) {
                    seen.set(t);
                  }
                });
    sleep(200);
    waiter.interrupt();
    waiter.join(5_000);
    assertThat(waiter.isAlive()).isFalse();
    assertThat(seen.get()).isInstanceOf(InterruptedException.class);
  }

  @Test
  void waitingProcessesEachTakeTheLockInTurnWithIncreasingTokens() throws Exception {
    LockKey key = key();
    int workers = 8;
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    List<Long> tokens = Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      for (int j = 0; j < 3; j++) {
                        AcquireResult r = store.acquire(key, LONG, Duration.ofSeconds(30));
                        Lease lease = ((AcquireResult.Acquired) r).lease();
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        tokens.add(lease.fencingToken());
                        sleep(5);
                        inside.decrementAndGet();
                        store.release(lease);
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(maxInside).hasValue(1);
    assertThat(tokens).hasSize(workers * 3).isSorted().doesNotHaveDuplicates();
  }

  // ---- argument limits ---------------------------------------------------------------------

  @Test
  void argumentLimitsThrowIllegalArgumentException() {
    LockKey key = key();
    assertThatIllegalArgumentException().isThrownBy(() -> store.tryAcquire(key, Duration.ofMillis(99)));
    assertThatIllegalArgumentException().isThrownBy(() -> store.tryAcquire(key, Duration.ZERO));
    assertThatIllegalArgumentException().isThrownBy(() -> store.tryAcquire(key, Duration.ofMillis(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.tryAcquire(key, Duration.ofHours(24).plusMillis(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.acquire(key, Duration.ofMillis(99), Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.acquire(key, LONG, Duration.ofMillis(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.acquire(key, LONG, Duration.ofHours(24).plusMillis(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.withLock(key, Duration.ofMillis(99), Duration.ZERO, l -> "x"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.withLock(key, LONG, Duration.ofHours(25), l -> "x"));
    Lease held = acquire(key, LONG);
    assertThatIllegalArgumentException().isThrownBy(() -> store.refresh(held, Duration.ofMillis(99)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.refresh(held, Duration.ofHours(24).plusMillis(1)));
  }

  @Test
  void theBoundsThemselvesAreAccepted() throws Exception {
    Lease min = acquire(key(), Duration.ofMillis(100));
    assertThat(min).isNotNull();
    Lease max = acquire(key(), Duration.ofHours(24));
    assertThat(store.release(max)).isInstanceOf(ReleaseResult.Released.class);
    assertThat(store.acquire(key(), LONG, Duration.ofHours(24))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void invalidKeysAreRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> LockKey.service(""));
    assertThatIllegalArgumentException().isThrownBy(() -> LockKey.service("has space"));
    assertThatIllegalArgumentException().isThrownBy(() -> LockKey.service("x".repeat(201)));
    LockKey.service("x".repeat(200));
    LockKey.service("A.b_c:d-e".replace('_', '.'));
  }

  // ---- withLock ----------------------------------------------------------------------------

  @Test
  void withLockRunsTheActionHoldingTheLockAndReleasesAfter() throws Exception {
    LockKey key = key();
    WithLockResult<String> r =
        store.withLock(
            key,
            LONG,
            Duration.ZERO,
            lease -> {
              assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Busy.class);
              assertThat(lease.key()).isEqualTo(key);
              return "ok:" + lease.fencingToken();
            });
    assertThat(r).isInstanceOf(WithLockResult.Completed.class);
    assertThat(((WithLockResult.Completed<String>) r).value()).startsWith("ok:");
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockKeepsTheLockBeyondTheLeaseLengthByRefreshing() throws Exception {
    LockKey key = key();
    Duration lease = Duration.ofMillis(600);
    AtomicReference<Object> busyAtEnd = new AtomicReference<>();
    WithLockResult<Integer> r =
        store.withLock(
            key,
            lease,
            Duration.ZERO,
            held -> {
              assertThat(store.activeRefreshers()).isEqualTo(1);
              sleep(1_700); // almost three lease lengths
              busyAtEnd.set(store.tryAcquire(key, LONG));
              return 7;
            });
    assertThat(r).isEqualTo(new WithLockResult.Completed<>(7));
    assertThat(busyAtEnd.get()).isInstanceOf(AcquireResult.Busy.class);
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockReturnsNotAcquiredAndSkipsTheActionWhenBusy() throws Exception {
    LockKey key = key();
    acquire(key, LONG);
    AtomicInteger ran = new AtomicInteger();
    WithLockResult<String> r =
        store.withLock(key, LONG, Duration.ofMillis(100), lease -> {
          ran.incrementAndGet();
          return "x";
        });
    assertThat(r).isInstanceOf(WithLockResult.NotAcquired.class);
    assertThat(ran).hasValue(0);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockReportsLostAndInterruptsTheActionWhenTheLeaseIsTakenFromIt() throws Exception {
    LockKey key = key();
    CountDownLatch inAction = new CountDownLatch(1);
    AtomicReference<Lease> taker = new AtomicReference<>();
    Thread thief =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    inAction.await();
                    expireNow(key);
                    taker.set(((AcquireResult.Acquired) store.tryAcquire(key, LONG)).lease());
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    long t0 = System.nanoTime();
    WithLockResult<String> r =
        store.withLock(
            key,
            Duration.ofMillis(600),
            Duration.ZERO,
            lease -> {
              inAction.countDown();
              sleep(30_000);
              return "never";
            });
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
    thief.join();
    assertThat(r).isInstanceOf(WithLockResult.Lost.class);
    assertThat(((WithLockResult.Lost<String>) r).value()).isEmpty();
    assertThat(elapsedMs).isLessThan(5_000);
    assertThat(Thread.currentThread().isInterrupted()).as("no stray interrupt left behind").isFalse();
    // withLock must not have released the lock now held by someone else
    assertThat(store.refresh(taker.get(), LONG)).isInstanceOf(RefreshResult.Refreshed.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockKeepsTheValueWhenTheActionSwallowsTheLossInterrupt() throws Exception {
    LockKey key = key();
    CountDownLatch inAction = new CountDownLatch(1);
    Thread thief =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    inAction.await();
                    expireNow(key);
                    store.tryAcquire(key, LONG);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    WithLockResult<String> r =
        store.withLock(
            key,
            Duration.ofMillis(600),
            Duration.ZERO,
            lease -> {
              inAction.countDown();
              try {
                sleep(30_000);
              } catch (InterruptedException e) {
                return "swallowed";
              }
              return "never";
            });
    thief.join();
    assertThat(r).isInstanceOf(WithLockResult.Lost.class);
    assertThat(((WithLockResult.Lost<String>) r).value()).contains("swallowed");
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockReleasesAfterAnUncheckedExceptionAndRethrowsItUnchanged() throws Exception {
    LockKey key = key();
    IllegalStateException boom = new IllegalStateException("boom");
    assertThatThrownBy(
            () ->
                store.withLock(
                    key,
                    LONG,
                    Duration.ZERO,
                    lease -> {
                      throw boom;
                    }))
        .isSameAs(boom);
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockWrapsACheckedExceptionAndReleases() throws Exception {
    LockKey key = key();
    IOException io = new IOException("disk");
    assertThatThrownBy(
            () ->
                store.withLock(
                    key,
                    LONG,
                    Duration.ZERO,
                    lease -> {
                      throw io;
                    }))
        .isInstanceOf(LockedActionException.class)
        .hasCause(io);
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void withLockRethrowsErrorsAndStillCleansUp() throws Exception {
    LockKey key = key();
    AssertionError err = new AssertionError("fatal");
    assertThatThrownBy(
            () ->
                store.withLock(
                    key,
                    LONG,
                    Duration.ZERO,
                    lease -> {
                      throw err;
                    }))
        .isSameAs(err);
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void anInterruptOfTheCallerDuringTheActionStopsTheRefresherAndReleases() throws Exception {
    LockKey key = key();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>();
    CountDownLatch inAction = new CountDownLatch(1);
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    store.withLock(
                        key,
                        LONG,
                        Duration.ZERO,
                        lease -> {
                          inAction.countDown();
                          sleep(30_000);
                          return "never";
                        });
                  } catch (Throwable t) {
                    thrown.set(t);
                    flag.set(Thread.currentThread().isInterrupted());
                  }
                });
    inAction.await();
    caller.interrupt();
    caller.join(5_000);
    assertThat(thrown.get()).isInstanceOf(LockedActionException.class);
    assertThat(thrown.get().getCause()).isInstanceOf(InterruptedException.class);
    assertThat(flag.get()).as("the interrupt status is restored").isTrue();
    assertThat(store.tryAcquire(key, LONG)).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(store.activeRefreshers()).isZero();
  }

  @Test
  void noRefresherThreadsLeakAcrossManyWithLockCalls() throws Exception {
    for (int i = 0; i < 25; i++) {
      LockKey key = key();
      int n = i;
      try {
        store.withLock(
            key,
            LONG,
            Duration.ZERO,
            lease -> {
              if (n % 3 == 1) {
                throw new IllegalStateException("x");
              }
              if (n % 3 == 2) {
                throw new IOException("y");
              }
              return n;
            });
      } catch (IllegalStateException | LockedActionException expected) {
        // part of the scenario
      }
      assertThat(store.activeRefreshers()).isZero();
    }
  }
}
