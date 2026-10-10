package io.flowcatalyst.framework.http.ratelimit.redis;

import io.flowcatalyst.framework.http.ratelimit.Decision;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.http.ratelimit.RateLimitStoreException;
import io.flowcatalyst.framework.http.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/**
 * The {@code redis} driver: one GCRA decision per request in a Lua script, on the Redis server's
 * clock, in microseconds. An allowed request stores the new TAT with a TTL equal to the time until
 * the bucket is full again, so idle keys expire by themselves.
 */
public final class RedisRateLimiter implements RateLimiter {

  /** KEYS[1] the bucket; ARGV interval (µs), burst, cost. Returns {allowed, remaining|retry, reset}. */
  static final String SCRIPT =
      """
      local t = redis.call('TIME')
      local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
      local interval = tonumber(ARGV[1])
      local burst = tonumber(ARGV[2])
      local cost = tonumber(ARGV[3])
      local stored = redis.call('GET', KEYS[1])
      local tat = now
      if stored then
        tat = math.max(tonumber(stored), now)
      end
      local nxt = tat + interval * cost
      local allow_at = nxt - burst * interval
      if now < allow_at then
        return {0, allow_at - now, tat - now}
      end
      redis.call('SET', KEYS[1], string.format('%d', nxt), 'PX', math.ceil((nxt - now) / 1000) + 1)
      return {1, math.floor((now - allow_at) / interval), nxt - now}""";

  private final String name;
  private final RateLimitPolicy policy;
  private final RedisClient client;
  private final String prefix;
  private final long intervalMicros;
  private final AtomicReference<String> sha = new AtomicReference<>("");

  /**
   * Creates the limiter and loads its script.
   *
   * @param name the instance name
   * @param policy the policy
   * @param client a pooled client for one standalone server
   * @param prefix prepended to every key, for example {@code fw:rl:}
   * @throws RateLimitStoreException when the script cannot be loaded
   */
  public RedisRateLimiter(String name, RateLimitPolicy policy, RedisClient client, String prefix) {
    this.name = name;
    this.policy = policy;
    this.client = client;
    this.prefix = prefix;
    this.intervalMicros = policy.intervalNanos() / 1_000;
    try {
      sha.set(client.scriptLoad(SCRIPT));
    } catch (JedisException e) {
      throw new RateLimitStoreException("cannot load the rate limit script", e);
    }
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
    if (cost < 1 || cost > policy.burst()) {
      throw new IllegalArgumentException("cost must be 1 to the burst (" + policy.burst() + ")");
    }
    List<String> keys = List.of(prefix + name + ":" + key);
    List<String> args =
        List.of(Long.toString(intervalMicros), Integer.toString(policy.burst()), Integer.toString(cost));
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
