package io.flowcatalyst.framework.http.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Where {@link Idempotency} keeps its keys. Every method is keyed by the {@link IdempotencyScope}
 * (client, caller, key), so one caller's key never meets another's.
 *
 * <p>Implementations: {@link MemoryIdempotencyStore} (one process, tests) and the database store in
 * {@code framework-idempotency-database}. They are safe for concurrent use, and {@link #reserve} is
 * atomic: of two concurrent reservations of one scope exactly one succeeds.
 */
public interface IdempotencyStore {

  /**
   * Takes a key for a request that is about to run.
   *
   * @param scope the scope
   * @param requestHash the hash of the request's method, path and body
   * @param now when
   * @return true when this request took the key; false when the key is already held (in progress or
   *     completed)
   */
  boolean reserve(IdempotencyScope scope, String requestHash, Instant now);

  /**
   * Reads a held key.
   *
   * @param scope the scope
   * @return the key's record, or empty when nobody holds it
   */
  Optional<IdempotencyRecord> find(IdempotencyScope scope);

  /**
   * Keeps the answer of the request that holds the key.
   *
   * @param scope the scope
   * @param answer the answer
   * @param now when
   */
  void complete(IdempotencyScope scope, StoredAnswer answer, Instant now);

  /**
   * Frees a key whose request failed, so a retry does the work. A completed key is not freed.
   *
   * @param scope the scope
   */
  void release(IdempotencyScope scope);
}
