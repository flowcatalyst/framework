package io.flowcatalyst.framework.push.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.push.Entry;
import io.flowcatalyst.framework.push.Follower;
import io.flowcatalyst.framework.push.Holder;
import io.flowcatalyst.framework.push.LogException;
import io.flowcatalyst.framework.push.OutboxRow;
import io.flowcatalyst.framework.redis.RedisFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

/** The A2 driver on its own, against a real Redis: what only this driver has to prove. */
class RedisPushLogTest {

  static final Instant BEGIN = Instant.parse("2026-11-08T12:00:00Z");

  static RedisFixture redis;
  static RedisClient client;

  final String prefix = "t" + UUID.randomUUID().toString().replace("-", "") + ":";
  final RedisPushLog log = new RedisPushLog(client, prefix);
  final List<Runnable> cleanups = new ArrayList<>();

  @BeforeAll
  static void start() {
    redis = RedisFixture.start();
    client = RedisClient.create(redis.host(), redis.port());
  }

  @AfterAll
  static void stop() {
    client.close();
    redis.close();
  }

  @AfterEach
  void cleanup() {
    cleanups.reversed().forEach(Runnable::run);
  }

  String stream() {
    return prefix + "fw:push";
  }

  static OutboxRow row(long id) {
    return new OutboxRow(id, "t", "y", "", "{}", BEGIN);
  }

  static List<OutboxRow> rows(int from, int count) {
    List<OutboxRow> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(row(from + i));
    }
    return out;
  }

  @Test
  void idMapping() {
    long ms = 1794174533896L;
    Object[][] cases = {
      {"0-0", 0L},
      {"0-4", 4L},
      {"1-0", 1L << 20},
      {ms + "-0", ms << 20},
      {ms + "-1048575", (ms << 20) | 1048575}, // the largest sequence
    };
    for (Object[] c : cases) {
      assertThat(RedisPushLog.idToLong((String) c[0])).as((String) c[0]).isEqualTo((long) c[1]);
      assertThat(RedisPushLog.idToEntryId((long) c[1])).isEqualTo(c[0]);
    }
    for (String bad : new String[] {"5-1048576", "5-4294967296", "x-1", "5", "5-y", "9223372036854775807-0", "-5-1", "5--1"}) {
      assertThatThrownBy(() -> RedisPushLog.idToLong(bad)).as(bad).isInstanceOf(LogException.class);
    }
  }

  /** An entry whose sequence does not fit is an error to read, not a wrong id. */
  @Test
  void aSequenceOf2To20IsRefused() {
    Map<String, String> fields = Map.of("topic", "t", "type", "y", "client", "", "data", "{}", "outbox", "1", "logged_at", "1");
    client.xadd(stream(), XAddParams.xAddParams().id(new StreamEntryID("5-1048575")), fields);
    List<Entry> got = log.readAfter(0, 10);
    assertThat(got).extracting(Entry::id).containsExactly((5L << 20) | 1048575);
    client.xadd(stream(), XAddParams.xAddParams().id(new StreamEntryID("5-1048576")), fields);
    assertThatThrownBy(() -> log.readAfter(0, 10)).isInstanceOf(LogException.class).hasMessageContaining("sequence");
  }

  /** The fields and their order are the Go framework's, so a Go host reads what a Java host wrote. */
  @Test
  void appendWritesTheFieldsOfTheGoDriverInOrder() {
    Instant at = Instant.parse("2026-11-08T12:00:00.123456Z");
    log.append(null, List.of(new OutboxRow(7, "fulfil:order:1", "order.updated", "acme", "{\"n\":1}", BEGIN)), at);
    List<StreamEntry> raw = client.xrange(stream(), "-", "+");
    assertThat(raw).hasSize(1);
    assertThat(List.copyOf(raw.getFirst().getFields().entrySet()))
        .extracting(Map.Entry::getKey, Map.Entry::getValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("topic", "fulfil:order:1"),
            org.assertj.core.groups.Tuple.tuple("type", "order.updated"),
            org.assertj.core.groups.Tuple.tuple("client", "acme"),
            org.assertj.core.groups.Tuple.tuple("data", "{\"n\":1}"),
            org.assertj.core.groups.Tuple.tuple("outbox", "7"),
            org.assertj.core.groups.Tuple.tuple("logged_at", Long.toString(at.getEpochSecond() * 1_000_000 + 123456)));
    Entry e = log.readAfter(0, 10).getFirst();
    assertThat(e.loggedAt()).isEqualTo(at);
    assertThat(e.outboxId()).isEqualTo(7);
  }

  @Test
  void appendKeepsTheRelaysOrderAndReadAfterIsExclusive() {
    assertThat(log.latest()).as("empty latest").isZero();
    assertThat(log.trimmed()).as("empty trimmed").isZero();
    List<OutboxRow> rows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      rows.add(new OutboxRow(i + 1, "t", "y", "acme", "{\"n\":" + i + "}", BEGIN));
    }
    log.append(null, rows, BEGIN);
    List<Entry> all = log.readAfter(0, 10);
    assertThat(all).hasSize(5);
    assertThat(all).extracting(Entry::outboxId).containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(all.getFirst()).satisfies(e -> {
      assertThat(e.client()).isEqualTo("acme");
      assertThat(e.data()).isEqualTo("{\"n\":0}");
      assertThat(e.loggedAt()).isEqualTo(BEGIN);
    });
    assertThat(all).extracting(Entry::id).isSorted().doesNotHaveDuplicates();
    assertThat(log.readAfter(all.get(1).id(), 2)).extracting(Entry::id).containsExactly(all.get(2).id(), all.get(3).id());
    assertThat(log.latest()).isEqualTo(all.get(4).id());
    assertThat(log.readAfter(all.get(4).id(), 10)).isEmpty();
    assertThat(log.newest(2)).extracting(Entry::id).containsExactly(all.get(3).id(), all.get(4).id());
    assertThat(log.newest(100)).extracting(Entry::id).isEqualTo(all.stream().map(Entry::id).toList());
  }

  /** Prune never deletes the newest entry, even when every entry is older than the edge; Latest and Trimmed stay right. */
  @Test
  void pruneKeepsTheNewestAndTrimmedFollowsIt() {
    assertThat(log.prune(Instant.now().plus(Duration.ofHours(1)))).as("prune of nothing").isZero();
    for (int chunk = 0; chunk < 5; chunk++) { // 2500 entries: more than one batch of deletes
      log.append(null, rows(chunk * 500 + 1, 500), BEGIN);
    }
    List<Entry> all = log.readAfter(0, 5000);
    assertThat(all).hasSize(2500);
    assertThat(log.prune(Instant.now().minus(Duration.ofHours(1)))).as("an edge before everything").isZero();
    assertThat(log.prune(Instant.EPOCH.minus(Duration.ofHours(1)))).as("an edge before the epoch").isZero();
    assertThat(log.prune(Instant.EPOCH)).as("an edge at the epoch").isZero();
    assertThat(log.prune(Instant.now().plus(Duration.ofHours(1)))).as("all but the newest").isEqualTo(2499);
    assertThat(log.readAfter(0, 10)).extracting(Entry::id).containsExactly(all.get(2499).id());
    assertThat(log.latest()).isEqualTo(all.get(2499).id());
    assertThat(log.trimmed()).isEqualTo(all.get(2498).id());
    assertThat(log.prune(Instant.now().plus(Duration.ofHours(1)))).as("a second prune").isZero();
  }

  /** The epoch guard: with the first entry at the smallest possible id, an edge of 0 must not send "(0-0". */
  @Test
  void anEdgeAtOrBeforeTheEpochDeletesNothingEvenWhenIdsAreNearZero() {
    Map<String, String> fields = Map.of("topic", "t", "type", "y", "client", "", "data", "{}", "outbox", "1", "logged_at", "1");
    client.xadd(stream(), XAddParams.xAddParams().id(new StreamEntryID("0-1")), fields);
    client.xadd(stream(), XAddParams.xAddParams().id(new StreamEntryID("0-2")), fields);
    assertThat(log.prune(Instant.EPOCH)).isZero();
    assertThat(log.prune(Instant.ofEpochMilli(-5))).isZero();
    assertThat(log.readAfter(0, 10)).hasSize(2);
    assertThat(log.prune(Instant.ofEpochMilli(1))).as("below the newest, ids 0-1 only").isEqualTo(1);
    assertThat(log.readAfter(0, 10)).extracting(Entry::id).containsExactly(2L);
  }

  /** Follow calls grown when it starts and when an entry is added; an end reaches the follower of every log on the server. */
  @Test
  void followGrowsAndEndsAcrossInstances() throws Exception {
    RedisPushLog other = new RedisPushLog(client, prefix);
    Tap a = follow(log);
    Tap b = follow(other);
    await("both followers to start", () -> a.grown.get() == 1 && b.grown.get() == 1);
    log.append(null, List.of(row(1)), BEGIN);
    await("both followers to see the entry", () -> a.grown.get() >= 2 && b.grown.get() >= 2);
    Holder want = new Holder("prn_9", "", "s1");
    log.end(want);
    assertThat(a.ends.poll(10, TimeUnit.SECONDS)).isEqualTo(want);
    assertThat(b.ends.poll(10, TimeUnit.SECONDS)).isEqualTo(want);
  }

  /** The end message is the Holder's JSON, as the Go framework writes it. */
  @Test
  void theEndMessageIsTheHoldersJson() throws Exception {
    BlockingQueue<String> got = new LinkedBlockingQueue<>();
    JedisPubSub sub =
        new JedisPubSub() {
          @Override
          public void onMessage(String channel, String message) {
            got.add(channel + " " + message);
          }
        };
    Thread t = Thread.ofVirtual().start(() -> client.subscribe(sub, prefix + "fw:push:end"));
    cleanups.add(() -> {
      sub.unsubscribe();
    });
    await("the subscription", sub::isSubscribed);
    log.end(new Holder("prn_9", "dev_1", "s1"));
    assertThat(got.poll(10, TimeUnit.SECONDS)).isEqualTo(prefix + "fw:push:end {\"principal\":\"prn_9\",\"device\":\"dev_1\",\"session\":\"s1\"}");
    t.join(Duration.ofSeconds(5));
  }

  /** A log on another prefix does not see this one's entries or ends. */
  @Test
  void prefixesIsolate() throws Exception {
    RedisPushLog other = new RedisPushLog(client, prefix + "other:");
    Tap seen = follow(other);
    await("the follower to start", () -> seen.grown.get() == 1);
    log.append(null, List.of(row(1)), BEGIN);
    log.end(Holder.ofPrincipal("prn_1"));
    assertThat(other.readAfter(0, 10)).isEmpty();
    assertThat(seen.ends.poll(500, TimeUnit.MILLISECONDS)).isNull();
    assertThat(seen.grown.get()).isEqualTo(1);
    log.ping();
  }

  /** When the connections drop, follow throws (the hub retries); a new call follows again. */
  @Test
  void aFollowerThatLosesItsConnectionsThrowsAndFollowsAgain() throws Exception {
    // a server of its own: killing every connection would break the other tests' clients
    try (RedisFixture own = RedisFixture.start();
        RedisClient ownClient = RedisClient.create(own.host(), own.port())) {
      RedisPushLog mine = new RedisPushLog(ownClient, prefix);
      Tap first = new Tap();
      CompletableFuture<Throwable> outcome = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  mine.follow(first.follower(), () -> false);
                  outcome.complete(null);
                } catch (Throwable e) {
                  outcome.complete(e);
                }
              });
      await("the follower to start", () -> first.grown.get() == 1);
      killConnections(own);
      assertThat(outcome.get(15, TimeUnit.SECONDS)).isInstanceOf(LogException.class);

      Tap second = follow(mine);
      await("the new follower to start", () -> second.grown.get() == 1);
      mine.append(null, List.of(row(1)), BEGIN);
      await("the new follower to see the entry", () -> second.grown.get() >= 2);
      mine.end(Holder.ofPrincipal("prn_1"));
      assertThat(second.ends.poll(10, TimeUnit.SECONDS)).isEqualTo(Holder.ofPrincipal("prn_1"));
      second.stop();
    }
  }

  /** Stopping returns within about a second although the follower is blocked in XREAD. */
  @Test
  void aStoppedFollowerReturnsWithinTheBlock() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    Tap tap = new Tap();
    CompletableFuture<Void> done = CompletableFuture.runAsync(() -> log.follow(tap.follower(), stop::get));
    await("the follower to start", () -> tap.grown.get() == 1);
    long started = System.nanoTime();
    stop.set(true);
    done.get(5, TimeUnit.SECONDS);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
  }

  // ---- helpers ------------------------------------------------------------------------------

  /** What a follower saw. */
  static final class Tap {
    final AtomicInteger grown = new AtomicInteger();
    final BlockingQueue<Holder> ends = new LinkedBlockingQueue<>();
    final AtomicBoolean stopping = new AtomicBoolean();
    @Nullable CompletableFuture<Void> done;

    /** Stops the follower and waits for it to return. */
    void stop() {
      stopping.set(true);
      try {
        if (done != null) {
          done.get(10, TimeUnit.SECONDS);
        }
      } catch (Exception e) {
        throw new AssertionError("follow did not return after it was stopped", e);
      }
    }

    Follower follower() {
      return new Follower() {
        @Override
        public void grown() {
          grown.incrementAndGet();
        }

        @Override
        public void end(Holder holder) {
          ends.add(holder);
        }
      };
    }
  }

  Tap follow(RedisPushLog l) {
    Tap tap = new Tap();
    tap.done = CompletableFuture.runAsync(() -> l.follow(tap.follower(), tap.stopping::get));
    cleanups.add(tap::stop);
    return tap;
  }

  static void killConnections(RedisFixture server) {
    try (Jedis admin = new Jedis(server.host(), server.port())) {
      admin.sendCommand(Protocol.Command.CLIENT, "KILL", "TYPE", "normal", "SKIPME", "yes");
      admin.sendCommand(Protocol.Command.CLIENT, "KILL", "TYPE", "pubsub", "SKIPME", "yes");
    }
  }

  static void await(String what, BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for " + what);
      }
    }
    throw new AssertionError("timed out waiting for " + what);
  }
}
