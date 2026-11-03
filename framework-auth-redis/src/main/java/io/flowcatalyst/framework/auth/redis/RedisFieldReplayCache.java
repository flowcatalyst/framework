package io.flowcatalyst.framework.auth.redis;

import io.flowcatalyst.framework.auth.FieldReplayCache;
import io.flowcatalyst.framework.redis.RedisConnection;
import java.time.Duration;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;

/**
 * The field-worker proof replay cache in Redis (sessions-and-login contract section 15.4), shared by
 * every replica: key {@code fw:dpop:{jkt}:{jti}}, {@code SET NX PX}. A Redis failure is thrown, so
 * the request is answered {@code 503}: a proof is never accepted unchecked.
 *
 * <pre>{@code
 * AuthModule.builder()
 *     .application(new ApplicationCode("yard"))
 *     .fieldWorkers(FieldOptions.builder()
 *         .replayCache(lookup -> RedisFieldReplayCache.create(lookup.get(RedisConnections.class).defaultInstance()))
 *         .build())
 * }</pre>
 */
public final class RedisFieldReplayCache implements FieldReplayCache {

  /** The key prefix. */
  public static final String PREFIX = "fw:dpop:";

  private final RedisClient client;

  private RedisFieldReplayCache(RedisClient client) {
    this.client = client;
  }

  /**
   * Creates the cache.
   *
   * @param connection the Redis instance, from {@code RedisConnections}
   * @return the cache
   */
  public static RedisFieldReplayCache create(RedisConnection connection) {
    return new RedisFieldReplayCache(connection.client());
  }

  @Override
  public boolean firstUse(String jkt, String jti, Duration ttl) {
    try {
      return client.set(PREFIX + jkt + ":" + jti, "1", SetParams.setParams().nx().px(ttl.toMillis())) != null;
    } catch (JedisException e) {
      throw new IllegalStateException("the field proof replay cache could not be reached", e);
    }
  }
}
