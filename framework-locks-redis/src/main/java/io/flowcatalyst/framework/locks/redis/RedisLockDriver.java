package io.flowcatalyst.framework.locks.redis;

import io.flowcatalyst.framework.locks.LockDriver;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/**
 * Locks in one standalone Redis or Valkey server (locks contract, section 5).
 *
 * <p>Not Redlock: if the server loses data (a failover to a replica that missed writes, a restart
 * without persistence), a lock can be held twice and fencing tokens can repeat. Applications that
 * need more use the database driver.
 *
 * <p>The lock lives at {@code <prefix><stored key>} holding {@code <owner>:<fence>} with a
 * millisecond TTL; one counter at {@code <prefix>__fence} never expires. The three operations are
 * Lua scripts, loaded when the driver is created and run with {@code EVALSHA}; a script the server
 * has forgotten (after {@code SCRIPT FLUSH} or a restart) is loaded again.
 */
public final class RedisLockDriver implements LockDriver {

  private static final String ACQUIRE_LUA =
      """
      if redis.call('EXISTS', KEYS[1]) == 1 then return nil end
      local fence = redis.call('INCR', KEYS[2])
      redis.call('SET', KEYS[1], ARGV[1] .. ':' .. fence, 'PX', ARGV[2])
      return fence""";

  private static final String REFRESH_LUA =
      """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return 1
      end
      return 0""";

  private static final String RELEASE_LUA =
      """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        redis.call('DEL', KEYS[1])
        return 1
      end
      return 0""";

  /** A script and the digest the server knows it by. */
  private static final class Script {
    private final String body;
    private final AtomicReference<String> sha = new AtomicReference<>("");

    Script(String body) {
      this.body = body;
    }
  }

  private final RedisClient client;
  private final String prefix;
  private final String counterKey;
  private final Script acquire = new Script(ACQUIRE_LUA);
  private final Script refresh = new Script(REFRESH_LUA);
  private final Script release = new Script(RELEASE_LUA);

  /**
   * Creates a driver and loads its scripts.
   *
   * @param client a pooled client for one standalone server
   * @param prefix prepended to every key, for example {@code fw:lock:}
   * @throws LockStoreUnavailableException when the scripts cannot be loaded
   */
  public RedisLockDriver(RedisClient client, String prefix) {
    this.client = client;
    this.prefix = prefix;
    this.counterKey = prefix + "__fence";
    try {
      load(acquire);
      load(refresh);
      load(release);
    } catch (JedisException e) {
      throw new LockStoreUnavailableException("cannot load the Redis lock scripts", e);
    }
  }

  @Override
  public OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis) {
    Object reply =
        run(
            acquire,
            List.of(prefix + key, counterKey),
            List.of(ownerToken, Long.toString(leaseMillis)));
    return reply instanceof Long fence ? OptionalLong.of(fence) : OptionalLong.empty();
  }

  @Override
  public boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis) {
    Object reply =
        run(
            refresh,
            List.of(prefix + key),
            List.of(ownerToken + ":" + fencingToken, Long.toString(leaseMillis)));
    return Long.valueOf(1).equals(reply);
  }

  @Override
  public boolean release(String key, String ownerToken, long fencingToken) {
    Object reply =
        run(release, List.of(prefix + key), List.of(ownerToken + ":" + fencingToken));
    return Long.valueOf(1).equals(reply);
  }

  /**
   * Test hook: deletes the lock key, which expires its lease at once.
   *
   * @param key the stored key
   */
  public void expireNow(String key) {
    client.del(prefix + key);
  }

  private Object run(Script script, List<String> keys, List<String> args) {
    try {
      try {
        return client.evalsha(script.sha.get(), keys, args);
      } catch (JedisNoScriptException e) {
        load(script);
        return client.evalsha(script.sha.get(), keys, args);
      }
    } catch (JedisException e) {
      throw new LockStoreUnavailableException("Redis lock operation failed", e);
    }
  }

  private void load(Script script) {
    script.sha.set(client.scriptLoad(script.body));
  }
}
