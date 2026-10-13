package io.flowcatalyst.framework.http.ratelimit.redis;

import io.flowcatalyst.framework.http.ratelimit.Decision;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.http.ratelimit.RateLimitStoreException;
import io.flowcatalyst.framework.http.ratelimit.RateLimiter;
import io.flowcatalyst.framework.redis.RedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/**
 * The {@code redis} driver: one GCRA decision per request in a Lua script, on the Redis server's
 * clock, in microseconds. An allowed request stores the new TAT with a TTL equal to the time until
 * the bucket is full again, so idle keys expire by themselves.
 *
 * <pre>{@code
 * RedisConnection redis = app.get(RedisConnections.class).defaultInstance();
 * RateLimiter login = RedisRateLimiter.create("login", redis, RateLimitPolicy.of(5, Duration.ofMinutes(1)));
 * }</pre>
 */
public final class RedisRateLimiter implements RateLimiter {

  /**
   * KEYS[1] the bucket; ARGV interval (µs), burst, cost: the policy of this one decision. The TAT is
   * capped at an empty bucket under that policy, and a capped TAT is written back even when limited
   * (contract section 3a). Returns {allowed, remaining|retry, reset}.
   */
  static final String SCRIPT =
      """
      local t = redis.call('TIME')
      local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
      local interval = tonumber(ARGV[1])
      local burst = tonumber(ARGV[2])
      local cost = tonumber(ARGV[3])
      local window = burst * interval
      local stored = redis.call('GET', KEYS[1])
      local tat = now
      if stored then
        stored = tonumber(stored)
        tat = math.min(math.max(stored, now), now + window)
      end
      local nxt = tat + interval * cost
      local allow_at = nxt - window
      if now < allow_at then
        if stored and tat < stored then
          redis.call('SET', KEYS[1], string.format('%d', tat), 'PX', math.ceil((tat - now) / 1000) + 1)
        end
        return {0, allow_at - now, tat - now}
      end
      redis.call('SET', KEYS[1], string.format('%d', nxt), 'PX', math.ceil((nxt - now) / 1000) + 1)
      return {1, math.floor((now - allow_at) / interval), nxt - now}""";

  /** The default key prefix. */
  public static final String DEFAULT_PREFIX = "fw:rl:";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,62}");

  private final String name;
  private final RateLimitPolicy policy;
  private final RedisClient client;
  private final String prefix;
  private final AtomicReference<String> sha = new AtomicReference<>("");

  private RedisRateLimiter(String name, RateLimitPolicy policy, RedisClient client, String prefix) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("a rate limiter name must match [a-z][a-z0-9_-]* (at most 63 characters): " + name);
    }
    this.name = name;
    this.policy = policy;
    this.client = client;
    this.prefix = prefix;
    try {
      sha.set(client.scriptLoad(SCRIPT));
    } catch (JedisException e) {
      throw new RateLimitStoreException("cannot load the rate limit script", e);
    }
  }

  /**
   * Builds a limiter with keys under {@value #DEFAULT_PREFIX} and loads its script.
   *
   * @param name {@code [a-z][a-z0-9_-]*}, at most 63 characters; the same in every replica
   * @param connection the Redis instance, from {@code RedisConnections}
   * @param policy the default policy
   * @return the limiter
   * @throws RateLimitStoreException when the script cannot be loaded
   */
  public static RedisRateLimiter create(String name, RedisConnection connection, RateLimitPolicy policy) {
    return create(name, connection, policy, DEFAULT_PREFIX);
  }

  /**
   * Builds a limiter and loads its script.
   *
   * @param name {@code [a-z][a-z0-9_-]*}, at most 63 characters; the same in every replica
   * @param connection the Redis instance, from {@code RedisConnections}
   * @param policy the default policy
   * @param prefix prepended to every key, for example {@code fw:rl:}
   * @return the limiter
   * @throws RateLimitStoreException when the script cannot be loaded
   */
  public static RedisRateLimiter create(String name, RedisConnection connection, RateLimitPolicy policy, String prefix) {
    return new RedisRateLimiter(name, policy, connection.client(), prefix);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public RateLimitPolicy policy() {
    return policy;
  }

  @Override
  public Decision acquire(String key, int cost) {
    return acquire(key, cost, policy);
  }

  @Override
  public boolean supportsPerRequestPolicy() {
    return true;
  }

  @Override
  public Decision acquire(String key, int cost, RateLimitPolicy given) {
    long intervalMicros = given.intervalNanos() / 1_000;
    if (cost < 1 || cost > given.burst()) {
      throw new IllegalArgumentException("cost must be 1 to the burst (" + given.burst() + ")");
    }
    List<String> keys = List.of(prefix + name + ":" + key);
    List<String> args =
        List.of(Long.toString(intervalMicros), Integer.toString(given.burst()), Integer.toString(cost));
    Object reply;
    try {
      try {
        reply = client.evalsha(sha.get(), keys, args);
      } catch (JedisNoScriptException e) {
        sha.set(client.scriptLoad(SCRIPT));
        reply = client.evalsha(sha.get(), keys, args);
      }
    } catch (JedisException e) {
      throw new RateLimitStoreException("Redis rate limit call failed for limiter " + name, e);
    }
    if (!(reply instanceof List<?> list) || list.size() != 3) {
      throw new RateLimitStoreException("unexpected Redis reply for limiter " + name, new IllegalStateException());
    }
    long allowed = number(list.get(0));
    long second = number(list.get(1));
    Duration reset = Duration.ofNanos(number(list.get(2)) * 1_000);
    return allowed == 1
        ? new Decision.Allowed(second, reset)
        : new Decision.Limited(Duration.ofNanos(second * 1_000), reset);
  }

  private static long number(Object o) {
    if (o instanceof Long l) {
      return l;
    }
    throw new RateLimitStoreException("unexpected Redis reply", new IllegalStateException(String.valueOf(o)));
  }
}
