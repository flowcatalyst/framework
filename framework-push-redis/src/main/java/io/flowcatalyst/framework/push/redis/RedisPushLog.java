package io.flowcatalyst.framework.push.redis;

import io.flowcatalyst.framework.push.Entry;
import io.flowcatalyst.framework.push.Follower;
import io.flowcatalyst.framework.push.Holder;
import io.flowcatalyst.framework.push.Log;
import io.flowcatalyst.framework.push.LogException;
import io.flowcatalyst.framework.push.OutboxRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.Response;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamInfo;

/**
 * The A2 log driver (push contract section 4): the Redis stream {@code <prefix>fw:push} on one
 * standalone Redis or Valkey server (6.2 or later: exclusive range ids), log ids the entry id
 * {@code ms-seq} sent as {@code ms * 2^20 + seq}, followers woken by {@code XREAD BLOCK}, ends of
 * streams sent with {@code PUBLISH} on {@code <prefix>fw:push:end}. It is the Go framework's
 * {@code RedisStreamLog}, key for key and field for field, so a Java and a Go host can share one
 * stream.
 *
 * <p>It appends outside the relay's transaction, so a crash between the append and the commit logs
 * a row twice: each entry keeps its outbox id for the hub to drop the second.
 */
public final class RedisPushLog implements Log {

  /** The stream's key, under the prefix. */
  static final String STREAM_KEY = "fw:push";

  /** The channel that ends streams, under the prefix; the message is the holder's JSON. */
  static final String END_CHANNEL = "fw:push:end";

  /** How many low bits of a log id hold the entry id's sequence: {@code id = ms << 20 | seq}. */
  static final int SEQ_BITS = 20;

  static final long SEQ_MASK = (1L << SEQ_BITS) - 1;

  /** How long one XREAD BLOCK waits: the longest a stopped follower takes to return. */
  static final int FOLLOW_BLOCK_MILLIS = 1000;

  /** How many entries Prune deletes per XDEL. */
  static final int PRUNE_BATCH = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(RedisPushLog.class);

  private final RedisClient client;
  private final String stream;
  private final String channel;

  /**
   * The A2 driver on a client.
   *
   * @param client a pooled client for one standalone server
   * @param prefix put before the key and the channel ({@code ""} in production; tests give each
   *     test its own)
   */
  public RedisPushLog(RedisClient client, String prefix) {
    this.client = client;
    this.stream = prefix + STREAM_KEY;
    this.channel = prefix + END_CHANNEL;
  }

  /**
   * Converts a Redis entry id {@code ms-seq} to the log id {@code ms * 2^20 + seq}.
   *
   * @param id the entry id
   * @return the log id
   * @throws LogException when it is not {@code ms-seq}, or its sequence is 2^20 or more, or it is too
   *     far in the future for a log id
   */
  static long idToLong(String id) {
    int dash = id.indexOf('-');
    if (dash < 0) {
      throw new LogException("push: the Redis entry id " + id + " is not ms-seq");
    }
    long ms;
    long seq;
    try {
      ms = Long.parseUnsignedLong(id.substring(0, dash));
      seq = Long.parseUnsignedLong(id.substring(dash + 1));
    } catch (NumberFormatException e) {
      throw new LogException("push: the Redis entry id " + id + " is not ms-seq", e);
    }
    if (seq < 0 || seq > SEQ_MASK) {
      throw new LogException("push: the Redis entry id " + id + " has a sequence of 2^" + SEQ_BITS + " or more, which a log id cannot hold");
    }
    if (ms < 0 || ms > (Long.MAX_VALUE >> SEQ_BITS)) {
      throw new LogException("push: the Redis entry id " + id + " is too far in the future for a log id");
    }
    return (ms << SEQ_BITS) | seq;
  }

  /**
   * Converts a log id back to the Redis entry id {@code ms-seq}.
   *
   * @param id the log id
   * @return the entry id
   */
  static String idToEntryId(long id) {
    return (id >> SEQ_BITS) + "-" + (id & SEQ_MASK);
  }

  @Override
  public void append(java.sql.Connection tx, List<OutboxRow> rows, Instant at) {
    if (rows.isEmpty()) {
      return;
    }
    String loggedAt = Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, at));
    try (AbstractTransaction t = client.multi()) {
      List<Response<StreamEntryID>> ids = new ArrayList<>(rows.size());
      for (OutboxRow r : rows) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", r.topic());
        fields.put("type", r.type());
        fields.put("client", r.client());
        fields.put("data", r.data());
        fields.put("outbox", Long.toString(r.id()));
        fields.put("logged_at", loggedAt);
        ids.add(t.xadd(stream, XAddParams.xAddParams(), fields));
      }
      t.exec();
      for (Response<StreamEntryID> id : ids) {
        idToLong(id.get().toString());
      }
    } catch (JedisException e) {
      throw new LogException("push: append to the log", e);
    }
  }

  @Override
  public List<Entry> readAfter(long after, int limit) {
    String start = after > 0 ? "(" + idToEntryId(after) : "-";
    try {
      return entriesOf(client.xrange(stream, start, "+", limit));
    } catch (JedisException e) {
      throw new LogException("push: read the log", e);
    }
  }

  @Override
  public List<Entry> newest(int limit) {
    try {
      List<StreamEntry> newestFirst = client.xrevrange(stream, "+", "-", limit);
      return entriesOf(newestFirst.reversed());
    } catch (JedisException e) {
      throw new LogException("push: read the log", e);
    }
  }

  private static List<Entry> entriesOf(List<StreamEntry> messages) {
    List<Entry> out = new ArrayList<>(messages.size());
    for (StreamEntry m : messages) {
      out.add(entryOf(m));
    }
    return out;
  }

  /** Reads a stream entry as the log's Entry. */
  static Entry entryOf(StreamEntry m) {
    String entryId = m.getID().toString();
    long id = idToLong(entryId);
    Map<String, String> f = m.getFields();
    long outbox;
    try {
      outbox = Long.parseLong(f.getOrDefault("outbox", ""));
    } catch (NumberFormatException e) {
      throw new LogException("push: the log entry " + entryId + " has no outbox id", e);
    }
    long micros;
    try {
      micros = Long.parseLong(f.getOrDefault("logged_at", ""));
    } catch (NumberFormatException e) {
      throw new LogException("push: the log entry " + entryId + " has no logged_at", e);
    }
    Instant loggedAt = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    return new Entry(
        id, f.getOrDefault("topic", ""), f.getOrDefault("type", ""), f.getOrDefault("client", ""), f.getOrDefault("data", ""), loggedAt, outbox);
  }

  /** XINFO STREAM; the stream does not exist (nothing was ever logged) is null. */
  private @Nullable StreamInfo info() {
    try {
      return client.xinfoStream(stream);
    } catch (JedisException e) {
      String message = e.getMessage();
      if (message != null && message.contains("no such key")) {
        return null;
      }
      throw e;
    }
  }

  /** The stream's last-generated-id, which stays right when the stream is empty or trimmed. */
  @Override
  public long latest() {
    try {
      StreamInfo info = info();
      return info == null ? 0 : idToLong(info.getLastGeneratedId().toString());
    } catch (JedisException e) {
      throw new LogException("push: read the log's last id", e);
    }
  }

  /** The stream's max-deleted-entry-id. Prune deletes with XDEL because XTRIM does not move it. */
  @Override
  public long trimmed() {
    try {
      StreamInfo info = info();
      if (info == null) {
        return 0;
      }
      Object deleted = info.getStreamInfo().get("max-deleted-entry-id");
      return deleted == null ? 0 : idToLong(deleted.toString());
    } catch (JedisException e) {
      throw new LogException("push: read the log's deleted id", e);
    }
  }

  /**
   * Deletes (XDEL, in batches) the entries whose id is below the millisecond of {@code before},
   * except the newest entry, so ids keep increasing. The ids are the Redis server's clock: retention
   * is minutes to hours, so a skew of seconds against the application's clock does not matter. An
   * edge at or before the epoch deletes nothing.
   */
  @Override
  public long prune(Instant before) {
    try {
      List<StreamEntry> newest = client.xrevrange(stream, "+", "-", 1);
      if (newest.isEmpty()) {
        return 0;
      }
      long top = idToLong(newest.getFirst().getID().toString());
      // delete ids below the edge, and below the newest entry
      long upto = Math.min(Math.max(before.toEpochMilli(), 0) << SEQ_BITS, top);
      if (upto == 0) { // nothing is below 0-0 (and Redis refuses the interval "(0-0")
        return 0;
      }
      String bound = "(" + idToEntryId(upto);
      long deleted = 0;
      while (true) {
        List<StreamEntry> batch = client.xrange(stream, "-", bound, PRUNE_BATCH);
        if (batch.isEmpty()) {
          return deleted;
        }
        StreamEntryID[] ids = new StreamEntryID[batch.size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = batch.get(i).getID();
        }
        deleted += client.xdel(stream, ids);
        if (batch.size() < PRUNE_BATCH) {
          return deleted;
        }
      }
    } catch (JedisException e) {
      throw new LogException("push: prune the log", e);
    }
  }

  /**
   * Subscribes to the end channel, calls {@code grown}, then blocks in XREAD on the stream from its
   * last id and calls {@code grown} whenever an entry was added. It returns when {@code stopping}
   * turns true (within about a second: the block), and throws {@link LogException} when its
   * connection fails: the hub calls it again after a pause and polls meanwhile, so the application
   * stays ready and pushes are late by at most {@code push.relay.interval}. An end sent while the
   * subscription is down is lost, as with LISTEN/NOTIFY.
   */
  @Override
  public void follow(Follower follower, BooleanSupplier stopping) {
    CountDownLatch subscribed = new CountDownLatch(1);
    AtomicReference<@Nullable RuntimeException> subscriptionFailed = new AtomicReference<>();
    JedisPubSub pubsub =
        new JedisPubSub() {
          @Override
          public void onSubscribe(String channelName, int subscribedChannels) {
            subscribed.countDown();
          }

          @Override
          public void onMessage(String channelName, String message) {
            Holder holder = Holder.fromJson(message);
            if (!holder.isEmpty()) {
              try {
                follower.end(holder);
              } catch (RuntimeException e) {
                LOG.warn("push: ending the streams of a holder failed", e);
              }
            }
          }
        };
    Thread subscription =
        Thread.ofVirtual()
            .name("push-redis-subscribe")
            .start(
                () -> {
                  try {
                    client.subscribe(pubsub, channel); // returns when unsubscribed
                  } catch (RuntimeException e) {
                    subscriptionFailed.set(e);
                  } finally {
                    subscribed.countDown();
                  }
                });
    try {
      while (!subscribed.await(250, TimeUnit.MILLISECONDS)) {
        if (stopping.getAsBoolean()) {
          return;
        }
      }
      throwIfFailed(subscriptionFailed);
      StreamEntryID cursor = cursor();
      follower.grown();
      XReadParams params = XReadParams.xReadParams().count(1).block(FOLLOW_BLOCK_MILLIS);
      while (!stopping.getAsBoolean()) {
        throwIfFailed(subscriptionFailed);
        List<Map.Entry<String, List<StreamEntry>>> result;
        try {
          result = client.xread(params, Map.of(stream, cursor));
        } catch (JedisException e) {
          throw new LogException("push: wait for the log to grow", e);
        }
        if (result == null || result.isEmpty() || result.getFirst().getValue().isEmpty()) {
          continue; // nothing came within the block
        }
        // move the cursor to the stream's end before waking the hub, so what is added after this
        // read is read next time and what was added before it is visible to the hub's read
        cursor = cursor();
        follower.grown();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      stop(pubsub, subscription);
    }
  }

  private static void throwIfFailed(AtomicReference<@Nullable RuntimeException> failure) {
    RuntimeException e = failure.get();
    if (e != null) {
      throw new LogException("push: subscribe to the end channel", e);
    }
  }

  private static void stop(JedisPubSub pubsub, Thread subscription) {
    try {
      if (pubsub.isSubscribed()) {
        pubsub.unsubscribe();
      }
    } catch (RuntimeException e) {
      LOG.debug("push: unsubscribing from the end channel failed", e);
    }
    try {
      if (!subscription.join(java.time.Duration.ofSeconds(2))) {
        subscription.interrupt();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The id XREAD continues after: the stream's last-generated-id ({@code 0-0} before the first push). */
  private StreamEntryID cursor() {
    try {
      StreamInfo info = info();
      return info == null ? new StreamEntryID(0, 0) : info.getLastGeneratedId();
    } catch (JedisException e) {
      throw new LogException("push: read the log's last id", e);
    }
  }

  /** PUBLISH of the holder as JSON on the end channel; every instance's follower receives it (this one included). */
  @Override
  public void end(Holder holder) {
    try {
      client.publish(channel, holder.toJson());
    } catch (JedisException e) {
      throw new LogException("push: publish the end of streams", e);
    }
  }

  @Override
  public void ping() {
    try {
      client.ping();
    } catch (JedisException e) {
      throw new LogException("push: ping the log", e);
    }
  }
}
