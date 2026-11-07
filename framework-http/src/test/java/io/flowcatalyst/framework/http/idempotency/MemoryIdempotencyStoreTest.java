package io.flowcatalyst.framework.http.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryIdempotencyStoreTest extends IdempotencyStoreContract {

  private final MemoryIdempotencyStore store = new MemoryIdempotencyStore();

  @Override
  protected IdempotencyStore store() {
    return store;
  }

  @Test
  void pruneForgetsKeysTakenBeforeTheCutOff() {
    IdempotencyScope old = new IdempotencyScope("acme", "u1", "old");
    IdempotencyScope fresh = new IdempotencyScope("acme", "u1", "fresh");
    store.reserve(old, "h", NOW.minus(Duration.ofHours(25)));
    store.reserve(fresh, "h", NOW.minus(Duration.ofHours(1)));
    assertThat(store.prune(NOW.minus(Duration.ofHours(24)))).isEqualTo(1);
    assertThat(store.find(old)).isEmpty();
    assertThat(store.find(fresh)).isPresent();
  }

  @Test
  void aKeptAnswerCannotBeChangedThroughTheArrays() {
    IdempotencyScope k = new IdempotencyScope("acme", "u1", "copy");
    store.reserve(k, "h", NOW);
    byte[] body = {1, 2};
    store.complete(k, new StoredAnswer(200, null, body), NOW);
    body[0] = 9;
    StoredAnswer a = store.find(k).orElseThrow().answer();
    assertThat(a).isNotNull();
    a.body()[1] = 9;
    StoredAnswer again = store.find(k).orElseThrow().answer();
    assertThat(again).isNotNull();
    assertThat(again.body()).containsExactly(1, 2);
  }
}
