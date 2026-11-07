package io.flowcatalyst.framework.http.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keys in this process: for tests and a single instance. Keys are lost on restart and not shared
 * between instances; deployed services use the database store ({@code framework-idempotency-database}).
 */
public final class MemoryIdempotencyStore implements IdempotencyStore {

  private record Entry(String requestHash, Instant createdAt, @org.jspecify.annotations.Nullable StoredAnswer answer) {}

  private final ConcurrentHashMap<IdempotencyScope, Entry> entries = new ConcurrentHashMap<>();

  @Override
  public boolean reserve(IdempotencyScope scope, String requestHash, Instant now) {
    return entries.putIfAbsent(scope, new Entry(requestHash, now, null)) == null;
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
  public void complete(IdempotencyScope scope, StoredAnswer answer, Instant now) {
    entries.computeIfPresent(
        scope, (k, e) -> new Entry(e.requestHash(), e.createdAt(), new StoredAnswer(answer.status(), answer.contentType(), answer.body().clone())));
  }

  @Override
  public void release(IdempotencyScope scope) {
    entries.computeIfPresent(scope, (k, e) -> e.answer() == null ? null : e);
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
