package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static io.flowcatalyst.framework.push.TestNode.OTHER;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.Push;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The contract test suite of push contract section 14 on the Postgres driver (A1). */
class DeliveryTest {

  static PostgresFixture pg;

  @TempDir Path dir;
  final List<TestNode> nodes = new ArrayList<>();

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @BeforeEach
  void clean() {
    pg.reset();
  }

  @AfterEach
  void stopNodes() {
    nodes.forEach(TestNode::close);
  }

  TestNode node(TestNode.Config config) throws Exception {
    Path sub = dir.resolve("n" + nodes.size());
    java.nio.file.Files.createDirectories(sub);
    TestNode n = TestNode.start(pg, sub, config);
    nodes.add(n);
    return n;
  }

  TestNode node() throws Exception {
    return node(TestNode.Config.defaults());
  }

  Sse orderStream(TestNode n) {
    return n.open("acme", List.of("test:order:1"));
  }

  @Test
  void aRolledBackTransactionSendsNothing() throws Exception {
    TestNode n = node();
    Sse c = orderStream(n);
    try (Connection tx = n.dataSource.getConnection()) {
      tx.setAutoCommit(false);
      n.pushes.publish(io.flowcatalyst.framework.usecase.Tx.of(tx), Context.of(ACME), Push.of("test:order:1", "order.updated", Map.of("v", "rolled back")));
      tx.rollback();
    }
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "committed"));
    String f = c.next();
    List<Long> ids = n.settle(1);
    assertThat(f).contains("\"v\":\"committed\"");
    assertThat(Sse.idOf(f)).isEqualTo(ids.get(0));
    assertThat(n.query("SELECT count(*) FROM fw_push_outbox")).containsExactly("0");
  }

  @Test
  void commitsOutOfIdOrderArriveInLogOrderWithoutGap() throws Exception {
    TestNode n = node();
    Sse c = orderStream(n);
    Push first = Push.of("test:order:1", "order.updated", Map.of("tx", "first"));
    Push second = Push.of("test:order:1", "order.updated", Map.of("tx", "second"));
    try (Connection tx1 = n.dataSource.getConnection()) {
      tx1.setAutoCommit(false);
      n.pushes.publish(io.flowcatalyst.framework.usecase.Tx.of(tx1), Context.of(ACME), first); // the lower outbox id
      n.pushes.publishAlone(Context.of(ACME), second); // commits first
      String f = c.next();
      assertThat(f).contains("\"tx\":\"second\"");
      assertThat(n.settle(1).get(0)).isEqualTo(Sse.idOf(f));
      tx1.commit();
    }
    String f = c.next();
    assertThat(f).contains("\"tx\":\"first\"");
    assertThat(n.settle(2)).containsExactly(1L, 2L);
    assertThat(Sse.idOf(f)).isEqualTo(2L);
  }

  @Test
  void manyOutOfOrderCommitsLeaveNoGapAndKeepCommitOrder() throws Exception {
    TestNode n = node();
    Sse c = orderStream(n);
    for (int i = 0; i < 25; i++) {
      try (Connection a = n.dataSource.getConnection();
          Connection b = n.dataSource.getConnection()) {
        a.setAutoCommit(false);
        b.setAutoCommit(false);
        io.flowcatalyst.framework.usecase.Tx ta = io.flowcatalyst.framework.usecase.Tx.of(a);
        io.flowcatalyst.framework.usecase.Tx tb = io.flowcatalyst.framework.usecase.Tx.of(b);
        n.pushes.publish(ta, Context.of(ACME), Push.of("test:order:1", "order.updated", Map.of("n", "a" + i)));
        n.pushes.publish(tb, Context.of(ACME), Push.of("test:order:1", "order.updated", Map.of("n", "b" + i)));
        if (i % 2 == 0) {
          b.commit();
          a.commit();
        } else {
          a.commit();
          b.commit();
        }
      }
    }
    List<Long> ids = n.settle(50);
    assertThat(ids).isEqualTo(java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList());
    // every push arrives once, in log order
    List<Long> got = c.ids(50);
    assertThat(got).isEqualTo(ids);
  }

  @Test
  void theRelayDrainsTheOutboxAsAQueue() throws Exception {
    BareDb db = new BareDb(pg);
    Relay relay = db.relay(500);
    try (Connection tx1 = db.dataSource.getConnection();
        Connection tx2 = db.dataSource.getConnection()) {
      tx1.setAutoCommit(false);
      BareDb.insertPush(tx1, "a"); // outbox id 1, committed last
      tx2.setAutoCommit(false);
      BareDb.insertPush(tx2, "b");
      tx2.commit();
      assertThat(relay.once()).isEqualTo(1);
      tx1.commit();
      assertThat(relay.once()).as("the late row").isEqualTo(1);
    }
    assertThat(db.log.readAfter(0, 100)).extracting(Entry::data).containsExactly("{\"note\":\"b\"}", "{\"note\":\"a\"}");
    assertThat(db.count("fw_push_outbox")).isZero();
  }

  /** A relay that dies mid-run logs nothing; the replacement logs each push exactly once. */
  @Test
  void aRelayKilledMidRunNeitherLosesNorRepeats() throws Exception {
    runKilled(true);
  }

  /** A relay that is merely slow commits; the other has nothing left to repeat. */
  @Test
  void aSlowRelayCommitsAndTheOtherRepeatsNothing() throws Exception {
    runKilled(false);
  }

  private void runKilled(boolean dies) throws Exception {
    BareDb db = new BareDb(pg);
    Relay first = db.relay(500);
    Relay second = db.relay(500);
    try (Connection tx = db.dataSource.getConnection()) {
      tx.setAutoCommit(false);
      for (int i = 0; i < 3; i++) {
        BareDb.insertPush(tx, Integer.toString(i));
      }
      tx.commit();
    }
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    first.beforeCommit =
        () -> {
          inside.countDown();
          release.await();
          if (dies) {
            throw new IllegalStateException("killed");
          }
        };
    AtomicReference<Object> firstOutcome = new AtomicReference<>();
    AtomicReference<Object> secondOutcome = new AtomicReference<>();
    Thread t1 = Thread.ofVirtual().start(() -> firstOutcome.set(outcome(first)));
    inside.await();
    Thread t2 = Thread.ofVirtual().start(() -> secondOutcome.set(outcome(second))); // waits on the rows' locks
    Thread.sleep(100);
    release.countDown();
    t1.join();
    t2.join();
    if (dies) {
      assertThat(firstOutcome.get()).isInstanceOf(IllegalStateException.class);
      assertThat(secondOutcome.get()).isEqualTo(3);
    } else {
      assertThat(firstOutcome.get()).isEqualTo(3);
      assertThat(secondOutcome.get()).isEqualTo(0);
    }
    assertThat(second.once()).as("a further run").isZero();
    assertThat(db.log.readAfter(0, 100))
        .extracting(Entry::data)
        .containsExactly("{\"note\":\"0\"}", "{\"note\":\"1\"}", "{\"note\":\"2\"}");
    assertThat(db.log.readAfter(0, 100)).extracting(Entry::id).containsExactly(1L, 2L, 3L);
  }

  private static Object outcome(Relay relay) {
    try {
      return relay.once();
    } catch (Exception e) {
      return e;
    }
  }

  @Test
  void aPushPublishedOnOneHostReachesStreamsOnTheOther() throws Exception {
    TestNode a = node();
    TestNode b = node();
    Sse cb = orderStream(b);
    Sse ca = orderStream(a);
    a.publish(ACME, "test:order:1", "order.updated", Map.of("from", "a"));
    b.publish(ACME, "test:order:1", "order.updated", Map.of("from", "b"));
    List<Long> want = a.settle(2);
    assertThat(ca.ids(2)).isEqualTo(want);
    assertThat(cb.ids(2)).isEqualTo(want);
    // the relay runs on one host at a time
    assertThat(a.query("SELECT count(*) FROM fw_lock WHERE lock_key = 'svc:push-relay'")).containsExactly("1");
  }

  @Test
  void endPrincipalEndsItsStreamsOnEveryHost() throws Exception {
    TestNode a = node();
    TestNode b = node();
    Sse mine = orderStream(b);
    String token = b.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_2", "X-Permissions", "test:order:view");
    Sse other = b.listen(token, null);
    other.greeting();
    a.pushes.endPrincipal("prn_acme");
    assertThat(mine.next()).isEqualTo("event: auth-expired\ndata: {}\n\n");
    assertThat(mine.ends(Duration.ofSeconds(10))).isTrue();
    a.publish(ACME, "test:order:1", "order.updated", Map.of());
    assertThat(Sse.idOf(other.next())).isEqualTo(a.settle(1).get(0));
    assertThat(b.counter("fw_push_streams_ended_total", "revoked")).isEqualTo(1);
  }

  @Test
  void resumeAfterADropReplaysWhatWasMissedOnce() throws Exception {
    TestNode n = node();
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
    Sse first = n.listen(token, null);
    first.greeting();
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 1));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 2));
    List<Long> seen = first.ids(2);
    first.close();
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 3));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 4));
    List<Long> all = n.settle(4);
    Sse resumed = n.listen(token, seen.get(1).toString());
    resumed.greeting();
    assertThat(resumed.ids(2)).isEqualTo(all.subList(2, 4));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 5));
    assertThat(Sse.idOf(resumed.next())).isEqualTo(n.settle(5).get(4));
    assertThat(n.counter("fw_push_replay_source_total", "memory")).isEqualTo(1);
  }

  @Test
  void resetForAnIdOlderThanRetentionAndForAnUnknownId() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("PUSH_RECENT", "0")); // replays read the log
    for (int i = 0; i < 6; i++) {
      n.publish(ACME, "test:order:1", "order.updated", Map.of("n", i));
    }
    n.settle(6);
    n.execute("DELETE FROM fw_push_log WHERE id <= 3"); // retention deleted ids 1 to 3
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
    // older than retention: the log may have deleted a push after id 2
    Sse old = n.listen(token, "2");
    old.greeting();
    assertThat(old.next()).isEqualTo("id: 6\nevent: reset\ndata: {\"reason\":\"retention\",\"latest\":6}\n\n");
    // equal to the oldest kept minus one: nothing was lost, a plain replay
    Sse edge = n.listen(token, "3");
    edge.greeting();
    assertThat(edge.ids(3)).containsExactly(4L, 5L, 6L);
    // higher than the log has ever had
    Sse unknown = n.listen(token, "999");
    unknown.greeting();
    assertThat(unknown.next()).isEqualTo("id: 6\nevent: reset\ndata: {\"reason\":\"unknown-id\",\"latest\":6}\n\n");
    assertThat(n.counter("fw_push_resets_total", "retention")).isEqualTo(1);
    assertThat(n.counter("fw_push_resets_total", "unknown-id")).isEqualTo(1);
    assertThat(n.counter("fw_push_replay_source_total", "log")).isEqualTo(2);
  }

  @Test
  void anInvalidLastEventIdGetsAResetThenLive() throws Exception {
    TestNode n = node();
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    n.settle(1);
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
    for (String bad : new String[] {"12-34", "abc", "-1"}) {
      Sse c = n.listen(token, bad);
      c.greeting();
      assertThat(c.next()).as(bad).isEqualTo("id: 1\nevent: reset\ndata: {\"reason\":\"unknown-id\",\"latest\":1}\n\n");
      c.close();
    }
    // blank is no cursor
    Sse blank = n.listen(token, " ");
    blank.greeting();
    assertThat(blank.poll(Duration.ofMillis(300))).isNull();
  }

  @Test
  void pruneKeepsTheNewestSoIdsKeepIncreasing() throws Exception {
    TestClock clock = new TestClock(TestNode.BEGIN);
    TestNode n = node(TestNode.Config.defaults().withClock(clock));
    for (int i = 0; i < 3; i++) {
      n.publish(ACME, "test:order:1", "order.updated", Map.of("n", i));
    }
    n.settle(3);
    assertThat(n.pushes.log().prune(TestNode.BEGIN.plus(Duration.ofDays(2)))).isEqualTo(2);
    assertThat(n.logIds()).containsExactly(3L);
    assertThat(n.pushes.log().trimmed()).isEqualTo(2);
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 4));
    assertThat(n.settle(2)).containsExactly(3L, 4L);
    // nothing older than the cut-off is pruned
    assertThat(n.pushes.log().prune(TestNode.BEGIN.minus(Duration.ofDays(1)))).isZero();
  }

  @Test
  void authExpiredAtTheStreamTokensExpiry() throws Exception {
    TestNode n = node();
    long expires = TestNode.BEGIN.getEpochSecond() + 2;
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view", "X-Expires", Long.toString(expires));
    Sse c = n.listen(token, null);
    c.greeting();
    assertThat(c.next()).isEqualTo("event: auth-expired\ndata: {}\n\n");
    assertThat(c.ends(Duration.ofSeconds(10))).isTrue();
    assertThat(n.counter("fw_push_streams_ended_total", "expired")).isEqualTo(1);
    // a stream token presented after its expiry is a 401
    n.clock.advance(Duration.ofSeconds(5));
    TestNode.Answer a = n.call("GET", "/push/stream?st=" + token, null);
    assertThat(a.status()).isEqualTo(401);
    assertThat(a.code()).isEqualTo("STREAM_TOKEN_EXPIRED");
  }

  @Test
  void aSlowStreamIsEndedAndResumesLosslessly() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("PUSH_STREAM_BUFFER", "16"));
    Subscription sub = new Subscription(List.of("test:order:1"), "acme", "", "", "");
    Hub.Ok slow = (Hub.Ok) n.pushes.hub().open(sub, false, 0, false); // never read
    for (int i = 0; i < 20; i++) {
      n.publish(ACME, "test:order:1", "order.updated", Map.of("n", i));
    }
    TestNode.await("the slow stream to end", () -> slow.stream().isEnded());
    assertThat(slow.stream().reason()).isEqualTo(Hub.END_SLOW);
    List<Long> seen = new ArrayList<>();
    for (Frame f = slow.stream().next(); f != null; f = slow.stream().next()) {
      seen.add(f.id());
    }
    List<Long> ids = n.settle(20);
    assertThat(seen).isEqualTo(ids.subList(0, 16));
    Hub.Ok resumed = (Hub.Ok) n.pushes.hub().open(sub, false, seen.get(15), true);
    try {
      assertThat(resumed.opening().memory()).isTrue();
      assertThat(resumed.opening().replay()).extracting(Frame::id).isEqualTo(ids.subList(16, 20));
    } finally {
      n.pushes.hub().close(resumed.stream(), Hub.END_CLIENT);
    }
    assertThat(n.counter("fw_push_streams_ended_total", "slow")).isEqualTo(1);
  }

  @Test
  void theClientFilterIncludingThePlatformClient() throws Exception {
    TestNode n = node();
    Sse acme = n.open("acme", List.of("test:order:1"));
    Sse other = n.open("other", List.of("test:order:1"));
    Sse platform = n.open("_platform", List.of("test:order:1"));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("for", "acme")); // 1
    n.publish(OTHER, "test:order:1", "order.updated", Map.of()); // 2
    n.publish(ClientId.PLATFORM, "test:order:1", "order.updated", Map.of("all", 1)); // 3
    n.publish(ACME, "test:order:1", "order.updated", Map.of("for", "acme", "n", 2)); // 4
    List<Long> ids = n.settle(4);
    assertThat(acme.ids(3)).containsExactly(ids.get(0), ids.get(2), ids.get(3));
    assertThat(other.ids(2)).containsExactly(ids.get(1), ids.get(2));
    assertThat(platform.ids(4)).isEqualTo(ids);
    // nothing else arrives: other's next push is the sixth, not acme's fifth
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    n.publish(OTHER, "test:order:1", "order.updated", Map.of());
    assertThat(Sse.idOf(other.next())).isEqualTo(n.settle(6).get(5));
  }

  @Test
  void aNodeStoresThePlatformClientAsNullAndAnotherClientAsItself() throws Exception {
    TestNode n = node();
    n.publish(ClientId.PLATFORM, "test:public", "news", Map.of());
    n.publish(ACME, "test:public", "news", Map.of());
    n.settle(2);
    assertThat(n.query("SELECT coalesce(client, '<null>') FROM fw_push_log ORDER BY id")).containsExactly("<null>", "acme");
  }
}
