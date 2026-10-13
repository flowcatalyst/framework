package io.flowcatalyst.framework.auth.redis;

import io.flowcatalyst.framework.auth.SessionHandles;
import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.auth.SessionStoreException;
import io.flowcatalyst.framework.redis.RedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;

/**
 * Sessions in Redis (contract section 6.3): key {@code fw:sess:<kind>:<hash>}, the record with its
 * expiry in front so the application's clock decides, and {@code PX} so Redis drops it as well.
 * {@code take} is {@code GETDEL} (Redis 6.2 or later); {@code update} is {@code SET ... XX}, so a
 * deleted record is not brought back.
 *
 * <pre>{@code
 * AuthModule.builder()
 *     .sessions(lookup -> RedisSessionStore.create(
 *         lookup.get(RedisConnections.class).defaultInstance(), lookup.get(InstantSource.class)))
 * }</pre>
 */
public final class RedisSessionStore implements SessionStore {

  /** The key prefix. */
  public static final String PREFIX = "fw:sess:";

  private final RedisClient client;
  private final InstantSource clock;

  private RedisSessionStore(RedisClient client, InstantSource clock) {
    this.client = client;
    this.clock = clock;
  }

  /**
   * Creates the store.
   *
   * @param connection the Redis instance, from {@code RedisConnections}
   * @param clock the application's clock
   * @return the store
   */
  public static RedisSessionStore create(RedisConnection connection, InstantSource clock) {
    return new RedisSessionStore(connection.client(), clock);
  }

  @Override
  public String create(String kind, String json, Instant expiresAt) {
    String handle = SessionHandles.newHandle();
    try {
      client.set(key(kind, handle), value(json, expiresAt), SetParams.setParams().px(ttl(expiresAt)));
      return handle;
    } catch (JedisException e) {
      throw failed("create", e);
    }
  }

  @Override
  public Optional<String> read(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    try {
      return live(client.get(key(kind, handle)));
    } catch (JedisException e) {
      throw failed("read", e);
    }
  }

  @Override
  public Optional<String> take(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    try {
      return live(client.getDel(key(kind, handle)));
    } catch (JedisException e) {
      throw failed("take", e);
    }
  }

  @Override
  public String update(String kind, String handle, String json, Instant expiresAt) {
    if (SessionHandles.isWellFormed(handle)) {
      try {
        client.set(key(kind, handle), value(json, expiresAt), SetParams.setParams().px(ttl(expiresAt)).xx());
      } catch (JedisException e) {
        throw failed("update", e);
      }
    }
    return handle;
  }

  @Override
  public void delete(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return;
    }
    try {
      client.del(key(kind, handle));
    } catch (JedisException e) {
      throw failed("delete", e);
    }
  }

  private Optional<String> live(@org.jspecify.annotations.Nullable String value) {
    if (value == null) {
      return Optional.empty();
    }
    int newline = value.indexOf('\n');
    if (newline < 0) {
      return Optional.empty();
    }
    long expiresAt;
    try {
      expiresAt = Long.parseLong(value.substring(0, newline));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    if (clock.instant().toEpochMilli() >= expiresAt) {
      return Optional.empty();
    }
    return Optional.of(value.substring(newline + 1));
  }

  private static String value(String json, Instant expiresAt) {
    return expiresAt.toEpochMilli() + "\n" + json;
  }

  private long ttl(Instant expiresAt) {
    return Math.max(1, Duration.between(clock.instant(), expiresAt).toMillis());
  }

  private static String key(String kind, String handle) {
    return PREFIX + kind + ":" + SessionHandles.hash(handle);
  }

  private static SessionStoreException failed(String what, JedisException e) {
    return new SessionStoreException("the redis session store could not " + what, e);
  }
}
