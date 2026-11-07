package io.flowcatalyst.framework.http.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** What every {@link IdempotencyStore} must do; each store's test extends this. */
public abstract class IdempotencyStoreContract {

  /** A fixed time for the rows. */
  protected static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  /** Expired: created a day before {@link #NOW}. */
  protected static final Instant EXPIRED_BEFORE = NOW.minus(Duration.ofHours(24));

  /** Abandoned: unfinished and created 5 minutes before {@link #NOW}. */
  protected static final Instant UNFINISHED_BEFORE = NOW.minus(Duration.ofMinutes(5));

  /**
   * The store under test; keys are unique per test, so it may be shared.
   *
   * @return the store
   */
  protected abstract IdempotencyStore store();

  private static IdempotencyScope scope(String principal) {
    return new IdempotencyScope("acme", principal, "k-" + System.nanoTime());
  }

  /** Reserves at {@code at} with the default cut-offs: expired 24 h before it, abandoned 5 min before it. */
  protected static boolean reserve(IdempotencyStore s, IdempotencyScope k, String hash, String token, Instant at) {
    return s.reserve(k, hash, token, at, at.minus(Duration.ofHours(24)), at.minus(Duration.ofMinutes(5)));
  }

  /** Reserves at {@link #NOW}. */
  protected static boolean reserve(IdempotencyStore s, IdempotencyScope k, String hash, String token) {
    return reserve(s, k, hash, token, NOW);
  }

  @Test
  public void aKeyIsTakenOnceAndReadsInProgressUntilCompleted() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    assertThat(s.find(k)).isEmpty();
    assertThat(reserve(s, k, "hash-1", "t1")).isTrue();
    assertThat(reserve(s, k, "hash-1", "t2")).isFalse();
    assertThat(reserve(s, k, "hash-2", "t3")).isFalse();
    IdempotencyRecord running = s.find(k).orElseThrow();
    assertThat(running.requestHash()).isEqualTo("hash-1");
    assertThat(running.answer()).isNull();

    s.complete(k, "t1", new StoredAnswer(201, "application/json", "{\"id\":7}".getBytes(StandardCharsets.UTF_8)), NOW);
    StoredAnswer answer = s.find(k).orElseThrow().answer();
    assertThat(answer).isNotNull();
    assertThat(answer.status()).isEqualTo(201);
    assertThat(answer.contentType()).isEqualTo("application/json");
    assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":7}");
    assertThat(reserve(s, k, "hash-1", "t4")).isFalse();
  }

  @Test
  public void anAnswerWithoutAContentTypeOrABodyIsKept() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    reserve(s, k, "h", "t1");
    s.complete(k, "t1", new StoredAnswer(204, null, new byte[0]), NOW);
    StoredAnswer answer = s.find(k).orElseThrow().answer();
    assertThat(answer).isNotNull();
    assertThat(answer.status()).isEqualTo(204);
    assertThat(answer.contentType()).isNull();
    assertThat(answer.body()).isEmpty();
  }

  @Test
  public void releaseFreesAKeyInProgressButNeverACompletedOne() {
    IdempotencyStore s = store();
    IdempotencyScope running = scope("u1");
    reserve(s, running, "h", "t1");
    s.release(running, "t1");
    assertThat(s.find(running)).isEmpty();
    assertThat(reserve(s, running, "h", "t2")).isTrue();

    IdempotencyScope done = scope("u1");
    reserve(s, done, "h", "t3");
    s.complete(done, "t3", new StoredAnswer(200, "text/plain", new byte[] {1}), NOW);
    s.release(done, "t3");
    assertThat(s.find(done).orElseThrow().answer()).isNotNull();
  }

  @Test
  public void onlyTheReservationsOwnTokenCompletesOrFreesIt() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    reserve(s, k, "h", "mine");
    s.release(k, "another");
    assertThat(s.find(k)).isPresent();
    s.complete(k, "another", new StoredAnswer(200, "text/plain", new byte[] {9}), NOW);
    assertThat(s.find(k).orElseThrow().answer()).isNull();
    s.complete(k, "mine", new StoredAnswer(201, "text/plain", new byte[] {1}), NOW);
    StoredAnswer kept = s.find(k).orElseThrow().answer();
    assertThat(kept).isNotNull();
    assertThat(kept.status()).isEqualTo(201);
  }

  @Test
  public void aSecondCompleteDoesNotOverwriteTheKeptAnswer() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    reserve(s, k, "h", "t1");
    s.complete(k, "t1", new StoredAnswer(201, "text/plain", new byte[] {1}), NOW);
    s.complete(k, "t1", new StoredAnswer(200, "text/plain", new byte[] {2}), NOW);
    StoredAnswer kept = s.find(k).orElseThrow().answer();
    assertThat(kept).isNotNull();
    assertThat(kept.status()).isEqualTo(201);
  }

  @Test
  public void anUnfinishedReservationIsTakenOverAfterTheInFlightTimeoutAndTheOldTokenNoLongerCounts() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    Instant start = NOW.minus(Duration.ofMinutes(5));
    assertThat(reserve(s, k, "old-hash", "stuck", start)).isTrue();
    // exactly at the timeout it still holds; after it, it is taken over, by another request too
    assertThat(s.reserve(k, "new-hash", "late", NOW, EXPIRED_BEFORE, start)).isFalse();
    assertThat(s.reserve(k, "new-hash", "new", NOW, EXPIRED_BEFORE, UNFINISHED_BEFORE.plusMillis(1))).isTrue();
    IdempotencyRecord now = s.find(k).orElseThrow();
    assertThat(now.requestHash()).isEqualTo("new-hash");
    assertThat(now.answer()).isNull();
    // the stuck request finishes late: it neither completes nor frees the new holder's key
    s.complete(k, "stuck", new StoredAnswer(200, "text/plain", new byte[] {1}), NOW);
    s.release(k, "stuck");
    assertThat(s.find(k).orElseThrow().answer()).isNull();
    s.complete(k, "new", new StoredAnswer(201, "text/plain", new byte[] {2}), NOW);
    assertThat(s.find(k).orElseThrow().answer()).isNotNull();
  }

  @Test
  public void aCompletedKeyIsNotTakenOverBeforeItExpiresAndIsAfter() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    Instant start = NOW.minus(Duration.ofHours(24));
    reserve(s, k, "h", "t1", start);
    s.complete(k, "t1", new StoredAnswer(200, "text/plain", new byte[] {1}), start);
    // completed: the in-flight timeout does not apply; the retention does
    assertThat(s.reserve(k, "h2", "t2", NOW, start, UNFINISHED_BEFORE)).isFalse();
    assertThat(s.find(k).orElseThrow().answer()).isNotNull();
    assertThat(s.reserve(k, "h2", "t3", NOW, start.plusMillis(1), UNFINISHED_BEFORE)).isTrue();
    IdempotencyRecord taken = s.find(k).orElseThrow();
    assertThat(taken.requestHash()).isEqualTo("h2");
    assertThat(taken.answer()).as("a takeover forgets the old answer").isNull();
  }

  @Test
  public void oneKeyOfTwoCallersOrTwoClientsIsTwoKeys() {
    IdempotencyStore s = store();
    String key = "shared-" + System.nanoTime();
    assertThat(reserve(s, new IdempotencyScope("acme", "u1", key), "h", "a")).isTrue();
    assertThat(reserve(s, new IdempotencyScope("acme", "u2", key), "h", "b")).isTrue();
    assertThat(reserve(s, new IdempotencyScope("globex", "u1", key), "h", "c")).isTrue();
    s.complete(new IdempotencyScope("acme", "u2", key), "b", new StoredAnswer(200, null, new byte[0]), NOW);
    assertThat(s.find(new IdempotencyScope("acme", "u1", key)).orElseThrow().answer()).isNull();
  }

  @Test
  public void ofConcurrentReservationsExactlyOneWins() throws Exception {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    int racers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        String token = "t" + i;
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return reserve(s, k, "h", token);
                }));
      }
      go.countDown();
      int won = 0;
      for (Future<Boolean> f : results) {
        won += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(won).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void ofConcurrentTakeoversOfAnAbandonedKeyExactlyOneWins() throws Exception {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    reserve(s, k, "h", "stuck", NOW.minus(Duration.ofHours(1)));
    int racers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        String token = "t" + i;
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return reserve(s, k, "h", token);
                }));
      }
      go.countDown();
      int won = 0;
      for (Future<Boolean> f : results) {
        won += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(won).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
