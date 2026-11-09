package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.push.redis.RedisPushLog;
import io.flowcatalyst.framework.push.redis.RedisPushLogs;
import io.flowcatalyst.framework.redis.RedisFixture;
import io.flowcatalyst.framework.redis.RedisModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * The A2 backend: the Redis stream under a prefix of the test's own, so tests share one container.
 * The ids are converted here by hand ({@code ms-seq}, 20 bits of sequence), independent of the driver.
 */
final class RedisBackend implements LogBackend {

  private final RedisFixture redis;
  final String prefix;
  private final RedisClient client;

  RedisBackend(RedisFixture redis) {
    this.redis = redis;
    this.prefix = "t" + UUID.randomUUID().toString().replace("-", "") + ":";
    this.client = RedisClient.create(redis.host(), redis.port());
  }

  /** The test's own client, for what the tests do to the stream behind the driver's back. */
  RedisClient client() {
    return client;
  }

  String stream() {
    return prefix + "fw:push";
  }

  static String entryId(long id) {
    return (id >> 20) + "-" + (id & 0xFFFFF);
  }

  @Override
  public String yaml() {
    return "redis:\n  instances:\n    default: { url: \"" + redis.url() + "\" }\n";
  }

  @Override
  public Map<String, String> env() {
    return Map.of("PUSH_DRIVER", "redis");
  }

  @Override
  public List<Module> modules() {
    return List.of(new RedisModule());
  }

  @Override
  public void builder(PushModule.Builder builder) {
    builder.redis(RedisPushLogs.redis(prefix));
  }

  @Override
  public Log bare(DataSource database) {
    return new RedisPushLog(client, prefix);
  }

  @Override
  public LogBackend fresh() {
    return new RedisBackend(redis);
  }

  @Override
  public boolean contiguousIds() {
    return false;
  }

  @Override
  public String platformClientStored() {
    return "";
  }

  @Override
  public List<String> storedClients(TestNode node) {
    List<String> out = new ArrayList<>();
    for (StreamEntry e : client.xrange(stream(), "-", "+")) {
      out.add(e.getFields().get("client"));
    }
    return out;
  }

  @Override
  public void deleteUpTo(TestNode node, long id) {
    for (StreamEntry e : client.xrange(stream(), "-", entryId(id))) {
      client.xdel(stream(), e.getID());
    }
  }

  /** The entry is added and deleted, which is what the stream's max-deleted-entry-id records. */
  @Override
  public void load(TestNode node, List<Entry> entries, long trimmed) {
    if (trimmed > 0) {
      StreamEntryID id = new StreamEntryID(entryId(trimmed));
      client.xadd(stream(), XAddParams.xAddParams().id(id), Map.of("outbox", "0"));
      client.xdel(stream(), id);
    }
    for (Entry e : entries) {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("topic", e.topic());
      fields.put("type", e.type());
      fields.put("client", e.client());
      fields.put("data", e.data());
      fields.put("outbox", "0");
      fields.put("logged_at", Long.toString(java.time.temporal.ChronoUnit.MICROS.between(Instant.EPOCH, e.loggedAt())));
      client.xadd(stream(), XAddParams.xAddParams().id(new StreamEntryID(entryId(e.id()))), fields);
    }
  }

  @Override
  public Instant clockStart() {
    return Instant.now();
  }

  @Override
  public void close() {
    client.close();
  }
}
