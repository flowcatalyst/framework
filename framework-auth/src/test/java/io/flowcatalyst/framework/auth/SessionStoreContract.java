package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The server-side store operations (contract section 6.3), for every driver: extend it and build
 * a fresh store on the given clock.
 */
public abstract class SessionStoreContract {

  /** The tests' clock. */
  protected MutableClock clock;

  /** The store under test. */
  protected SessionStore store;

  /**
   * A fresh, empty store reading time from the clock.
   *
   * @param clock the clock
   * @return the store
   */
  protected abstract SessionStore newStore(MutableClock clock);

  @BeforeEach
  void freshStore() {
    clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));
    store = newStore(clock);
  }

  private Instant in(Duration d) {
    return clock.instant().plus(d);
  }

  @Test
  void malformedHandlesAreNoneWithoutFailing() {
    for (String bad : List.of("", "x", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "!", "' OR 1=1 --", "v1.k1.abc")) {
      assertThat(store.read(SessionStore.SESSION, bad)).as(bad).isEmpty();
      assertThat(store.take(SessionStore.LOGIN, bad)).as(bad).isEmpty();
      store.delete(SessionStore.SESSION, bad);
    }
  }

  @Test
  void anUnknownHandleIsNone() {
    assertThat(store.read(SessionStore.SESSION, SessionHandles.newHandle())).isEmpty();
    assertThat(store.take(SessionStore.LOGIN, SessionHandles.newHandle())).isEmpty();
  }

  @Test
  void aRecordIsReadBackByItsHandleAndKindOnly() {
    String handle = store.create(SessionStore.SESSION, "{\"v\":1,\"a\":\"b\"}", in(Duration.ofMinutes(5)));
    assertThat(SessionHandles.isWellFormed(handle)).isTrue();
    assertThat(store.read(SessionStore.SESSION, handle)).map(this::tree).contains(tree("{\"v\":1,\"a\":\"b\"}"));
    assertThat(store.read(SessionStore.LOGIN, handle)).isEmpty();
    assertThat(store.take(SessionStore.LOGIN, handle)).isEmpty();
    assertThat(store.read(SessionStore.SESSION, handle)).isPresent();
  }

  @Test
  void aRecordIsGoneAtItsExpiry() {
    String handle = store.create(SessionStore.SESSION, "{}", in(Duration.ofMinutes(5)));
    clock.advance(Duration.ofMinutes(5).minusMillis(1));
    assertThat(store.read(SessionStore.SESSION, handle)).isPresent();
    clock.advance(Duration.ofMillis(1));
    assertThat(store.read(SessionStore.SESSION, handle)).isEmpty();
    assertThat(store.take(SessionStore.SESSION, handle)).isEmpty();
  }

  @Test
  void takeReturnsTheRecordOnce() {
    String handle = store.create(SessionStore.LOGIN, "{\"s\":1}", in(Duration.ofMinutes(10)));
    assertThat(store.take(SessionStore.LOGIN, handle)).isPresent();
    assertThat(store.take(SessionStore.LOGIN, handle)).isEmpty();
    assertThat(store.read(SessionStore.LOGIN, handle)).isEmpty();
  }

  @Test
  void concurrentTakesGiveTheRecordToExactlyOne() throws Exception {
    String handle = store.create(SessionStore.LOGIN, "{}", in(Duration.ofMinutes(10)));
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    List<Future<?>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(pool.submit(() -> {
        go.await();
        if (store.take(SessionStore.LOGIN, handle).isPresent()) {
          winners.incrementAndGet();
        }
        return null;
      }));
    }
    go.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();
    assertThat(winners.get()).isEqualTo(1);
  }

  @Test
  void updateReplacesTheRecordAndItsExpiryUnderTheSameHandle() {
    String handle = store.create(SessionStore.SESSION, "{\"n\":1}", in(Duration.ofMinutes(5)));
    String next = store.update(SessionStore.SESSION, handle, "{\"n\":2}", in(Duration.ofMinutes(30)));
    assertThat(next).isEqualTo(handle);
    clock.advance(Duration.ofMinutes(10));
    assertThat(store.read(SessionStore.SESSION, handle)).map(this::tree).contains(tree("{\"n\":2}"));
  }

  @Test
  void updateDoesNotBringADeletedRecordBack() {
    String handle = store.create(SessionStore.SESSION, "{\"n\":1}", in(Duration.ofMinutes(5)));
    store.delete(SessionStore.SESSION, handle);
    store.update(SessionStore.SESSION, handle, "{\"n\":2}", in(Duration.ofMinutes(30)));
    assertThat(store.read(SessionStore.SESSION, handle)).isEmpty();
  }

  @Test
  void deleteRevokesAndDeletingTwiceIsFine() {
    String handle = store.create(SessionStore.SESSION, "{}", in(Duration.ofMinutes(5)));
    store.delete(SessionStore.SESSION, handle);
    store.delete(SessionStore.SESSION, handle);
    assertThat(store.read(SessionStore.SESSION, handle)).isEmpty();
    assertThat(store.revocable()).isTrue();
  }

  @Test
  void handlesAreUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      assertThat(seen.add(store.create(SessionStore.SESSION, "{}", in(Duration.ofMinutes(1))))).isTrue();
    }
  }

  @Test
  void aSessionRecordSurvivesTheRoundTrip() {
    SessionManager.SessionData data =
        new SessionManager.SessionData(
            AuthTesting.clientUser("prn_1", "clt_1", "acme", "orders:clerk"),
            clock.instant(), clock.instant(), clock.instant(), in(Duration.ofHours(8)));
    String handle = store.create(SessionStore.SESSION, SessionManager.json(data), in(Duration.ofMinutes(30)));
    Optional<String> read = store.read(SessionStore.SESSION, handle);
    assertThat(read).isPresent();
    SessionManager.SessionData back = SessionManager.parseSession(read.get());
    assertThat(back).isNotNull();
    assertThat(back.principal().homeClientIdentifier()).contains("acme");
    assertThat(back.principal().roles()).containsExactly("orders:clerk");
  }

  /** The JSON as a tree, since a store may reorder members and drop whitespace (jsonb does). */
  protected tools.jackson.databind.JsonNode tree(String json) {
    return Records.MAPPER.readTree(json);
  }
}
