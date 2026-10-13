package io.flowcatalyst.framework.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Where sessions and login records live (contract section 6.3). A record has a kind ({@link
 * #SESSION} or {@link #LOGIN}), a JSON text and an expiry; the store hands back a handle that is
 * the cookie value. Drivers: {@code database} (the default, {@code framework-auth-database}),
 * {@code redis} ({@code framework-auth-redis}), {@link CookieSessionStore} and {@link
 * MemorySessionStore}.
 *
 * <p>A store failure throws {@link SessionStoreException}; it is never reported as "no session".
 */
public interface SessionStore {

  /** The kind of a browser session. */
  String SESSION = "session";

  /** The kind of a login in progress. */
  String LOGIN = "login";

  /**
   * Stores a new record.
   *
   * @param kind {@link #SESSION} or {@link #LOGIN}
   * @param json the record
   * @param expiresAt when it stops being readable
   * @return the handle, the value of the cookie
   */
  String create(String kind, String json, Instant expiresAt);

  /**
   * Reads a record.
   *
   * @param kind the kind
   * @param handle the cookie value
   * @return the record, or empty when absent, expired, of another kind or malformed
   */
  Optional<String> read(String kind, String handle);

  /**
   * Reads and deletes a record in one atomic step, so it can be used once.
   *
   * @param kind the kind
   * @param handle the cookie value
   * @return the record, or empty as for {@link #read}
   */
  Optional<String> take(String kind, String handle);

  /**
   * Replaces a record.
   *
   * @param kind the kind
   * @param handle the cookie value
   * @param json the new record
   * @param expiresAt the new expiry
   * @return the handle to send from now on: the same for server stores, a new one for {@link
   *     CookieSessionStore}
   */
  String update(String kind, String handle, String json, Instant expiresAt);

  /**
   * Removes a record; absent is not an error.
   *
   * @param kind the kind
   * @param handle the cookie value
   */
  void delete(String kind, String handle);

  /**
   * Whether {@link #delete} revokes: false for {@link CookieSessionStore}, which keeps nothing.
   *
   * @return true when a deleted record can no longer be read with its handle
   */
  default boolean revocable() {
    return true;
  }
}
