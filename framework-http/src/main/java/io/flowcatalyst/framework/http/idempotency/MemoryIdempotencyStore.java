package io.flowcatalyst.framework.http.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Keys in this process: for tests and a single instance. Keys are lost on restart and not shared
 * between instances; deployed services use the database store ({@code framework-idempotency-database}).
 */
public final class MemoryIdempotencyStore implements IdempotencyStore {

  private record Entry(String requestHash, String token, Instant createdAt, @Nullable StoredAnswer answer) {}

  private final ConcurrentHashMap<IdempotencyScope, Entry> entries = new ConcurrentHashMap<>();

  @Override
  public boolean reserve(
      IdempotencyScope scope, String requestHash, String token, Instant now, Instant expiredBefore, Instant unfinishedBefore) {
    boolean[] taken = {false};
    entries.compute(
        scope,
        (k, e) -> {
          if (e == null
              || e.createdAt().isBefore(expiredBefore)
              || (e.answer() == null && e.createdAt().isBefore(unfinishedBefore))) {
            taken[0] = true;
            return new Entry(requestHash, token, now, null);
          }
          return e;
        });
    return taken[0];
  }

  @Override
  public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
    Entry e = entries.get(scope);
    if (e == null) {
      return Optional.empty();
    }
    StoredAnswer a = e.answer();
    return Optional.of(
        new IdempotencyRecord(e.requestHash(), a == null ? null : new StoredAnswer(a.status(), a.contentType(), a.body().clone())));
  }

  @Override
  public void complete(IdempotencyScope scope, String token, StoredAnswer answer, Instant now) {
    StoredAnswer copy = new StoredAnswer(answer.status(), answer.contentType(), answer.body().clone());
    entries.computeIfPresent(
        scope,
        (k, e) ->
            e.token().equals(token) && e.answer() == null
                ? new Entry(e.requestHash(), e.token(), e.createdAt(), copy)
                : e);
  }

  @Override
  public void release(IdempotencyScope scope, String token) {
    entries.computeIfPresent(scope, (k, e) -> e.token().equals(token) && e.answer() == null ? null : e);
  }

  /**
   * Forgets keys taken before a time.
   *
   * @param before the cut-off
   * @return how many were forgotten
   */
  public int prune(Instant before) {
    int removed = 0;
    for (var e : entries.entrySet()) {
      if (e.getValue().createdAt().isBefore(before) && entries.remove(e.getKey(), e.getValue())) {
        removed++;
      }
    }
    return removed;
  }
}
