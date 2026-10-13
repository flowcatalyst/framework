package io.flowcatalyst.framework.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code cookie} store (contract section 6.5): the record travels in the cookie, sealed with
 * {@link CookieKeys}. Nothing is kept on the server, so logout cannot revoke a copy of the cookie
 * taken before ({@link #revocable()} is false) and {@link #take} cannot be atomic. Prefer {@code
 * database} or {@code redis} where revocation matters.
 */
public final class CookieSessionStore implements SessionStore {

  /** Above this many characters a sealed record does not fit in a cookie with its attributes. */
  static final int MAX_VALUE_LENGTH = 3_800;

  private final CookieKeys keys;
  private final InstantSource clock;

  private CookieSessionStore(CookieKeys keys, InstantSource clock) {
    this.keys = keys;
    this.clock = clock;
  }

  /**
   * Creates the store.
   *
   * @param keys the sealing keys, from {@code auth.cookie-keys}
   * @param clock decides expiry
   * @return the store
   */
  public static CookieSessionStore create(CookieKeys keys, InstantSource clock) {
    return new CookieSessionStore(keys, clock);
  }

  @Override
  public String create(String kind, String json, Instant expiresAt) {
    JsonNode data;
    try {
      data = Records.MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw new SessionStoreException("a session record is not JSON", e);
    }
    ObjectNode envelope = Records.MAPPER.createObjectNode();
    envelope.put("exp", expiresAt.toString());
    envelope.set("data", data);
    String value = keys.seal(kind, Records.MAPPER.writeValueAsBytes(envelope));
    if (value.length() > MAX_VALUE_LENGTH) {
      throw new SessionStoreException(
          "a sealed " + kind + " record is " + value.length() + " characters, over " + MAX_VALUE_LENGTH
              + "; use the database or redis store");
    }
    return value;
  }

  @Override
  public Optional<String> read(String kind, String handle) {
    Optional<byte[]> opened = keys.open(kind, handle);
    if (opened.isEmpty()) {
      return Optional.empty();
    }
    try {
      JsonNode envelope = Records.MAPPER.readTree(new String(opened.get(), StandardCharsets.UTF_8));
      Instant exp = Instant.parse(envelope.path("exp").asString(""));
      JsonNode data = envelope.path("data");
      if (!data.isObject() || !clock.instant().isBefore(exp)) {
        return Optional.empty();
      }
      return Optional.of(Records.MAPPER.writeValueAsString(data));
    } catch (JacksonException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** Not atomic: nothing is stored, so the cookie is cleared instead (contract section 6.5). */
  @Override
  public Optional<String> take(String kind, String handle) {
    return read(kind, handle);
  }

  @Override
  public String update(String kind, String handle, String json, Instant expiresAt) {
    return create(kind, json, expiresAt);
  }

  @Override
  public void delete(String kind, String handle) {
    // nothing is stored; the cookie is cleared by the caller
  }

  @Override
  public boolean revocable() {
    return false;
  }
}
