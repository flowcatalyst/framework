package io.flowcatalyst.framework.http.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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

  /**
   * The store under test; keys are unique per test, so it may be shared.
   *
   * @return the store
   */
  protected abstract IdempotencyStore store();

  private static IdempotencyScope scope(String principal) {
    return new IdempotencyScope("acme", principal, "k-" + System.nanoTime());
  }

  @Test
  public void aKeyIsTakenOnceAndReadsInProgressUntilCompleted() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    assertThat(s.find(k)).isEmpty();
    assertThat(s.reserve(k, "hash-1", NOW)).isTrue();
    assertThat(s.reserve(k, "hash-1", NOW)).isFalse();
    assertThat(s.reserve(k, "hash-2", NOW)).isFalse();
    IdempotencyRecord running = s.find(k).orElseThrow();
    assertThat(running.requestHash()).isEqualTo("hash-1");
    assertThat(running.answer()).isNull();

    s.complete(k, new StoredAnswer(201, "application/json", "{\"id\":7}".getBytes(StandardCharsets.UTF_8)), NOW);
    StoredAnswer answer = s.find(k).orElseThrow().answer();
    assertThat(answer).isNotNull();
    assertThat(answer.status()).isEqualTo(201);
    assertThat(answer.contentType()).isEqualTo("application/json");
    assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":7}");
    assertThat(s.reserve(k, "hash-1", NOW)).isFalse();
  }

  @Test
  public void anAnswerWithoutAContentTypeOrABodyIsKept() {
    IdempotencyStore s = store();
    IdempotencyScope k = scope("u1");
    s.reserve(k, "h", NOW);
    s.complete(k, new StoredAnswer(204, null, new byte[0]), NOW);
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
    s.reserve(running, "h", NOW);
    s.release(running);
    assertThat(s.find(running)).isEmpty();
    assertThat(s.reserve(running, "h", NOW)).isTrue();

    IdempotencyScope done = scope("u1");
    s.reserve(done, "h", NOW);
    s.complete(done, new StoredAnswer(200, "text/plain", new byte[] {1}), NOW);
    s.release(done);
    assertThat(s.find(done).orElseThrow().answer()).isNotNull();
  }

  @Test
  public void oneKeyOfTwoCallersOrTwoClientsIsTwoKeys() {
    IdempotencyStore s = store();
    String key = "shared-" + System.nanoTime();
    assertThat(s.reserve(new IdempotencyScope("acme", "u1", key), "h", NOW)).isTrue();
    assertThat(s.reserve(new IdempotencyScope("acme", "u2", key), "h", NOW)).isTrue();
    assertThat(s.reserve(new IdempotencyScope("globex", "u1", key), "h", NOW)).isTrue();
    s.complete(new IdempotencyScope("acme", "u2", key), new StoredAnswer(200, null, new byte[0]), NOW);
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
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return s.reserve(k, "h", NOW);
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
