package io.flowcatalyst.framework.http.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Where {@link Idempotency} keeps its keys. Every method is keyed by the {@link IdempotencyScope}
 * (client, caller, key), so one caller's key never meets another's.
 *
 * <p>A reservation is named by a token, a random string the middleware makes for each request that
 * tries to take a key: only the request that holds the key (its token) completes or frees it, so a
 * request whose reservation was taken over cannot overwrite or delete the new holder's.
 *
 * <p>Implementations: {@link MemoryIdempotencyStore} (one process, tests) and the database store in
 * {@code framework-idempotency-database} (table {@code fw_idempotency_keys}, the same migration set,
 * {@code http-idempotency}, as the Go framework's). They are safe for concurrent use, and {@link
 * #reserve} is atomic: of two concurrent reservations of one scope exactly one succeeds.
 */
public interface IdempotencyStore {

  /**
   * Takes a key for a request that is about to run: a key nobody holds, or the takeover of a key
   * created before {@code expiredBefore} (its retention ran out; kept answer or not) or of an
   * unfinished one created before {@code unfinishedBefore} (its request never completed or freed it,
   * for example because its process stopped). A takeover replaces the request hash and the token and
   * forgets any kept answer.
   *
   * @param scope the scope
   * @param requestHash the hash of the request's method, path, query and body
   * @param token the new reservation's token
   * @param now when; the key's creation time
   * @param expiredBefore a key created before this is expired
   * @param unfinishedBefore an unfinished key created before this is abandoned
   * @return true when this request took the key; false when it is held (in progress or completed)
   */
  boolean reserve(
      IdempotencyScope scope, String requestHash, String token, Instant now, Instant expiredBefore, Instant unfinishedBefore);

  /**
   * Reads a held key.
   *
   * @param scope the scope
   * @return the key's record, or empty when nobody holds it
   */
  Optional<IdempotencyRecord> find(IdempotencyScope scope);

  /**
   * Keeps the answer of the request that holds the key. Nothing happens when the key is not held
   * under this token, or already has an answer.
   *
   * @param scope the scope
   * @param token the reservation's token
   * @param answer the answer
   * @param now when
   */
  void complete(IdempotencyScope scope, String token, StoredAnswer answer, Instant now);

  /**
   * Frees a key whose request failed, so a retry does the work. Nothing happens when the key is not
   * held under this token, or is completed.
   *
   * @param scope the scope
   * @param token the reservation's token
   */
  void release(IdempotencyScope scope, String token);
}
