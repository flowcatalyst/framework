package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.database.PostgresFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The failover, restart and shutdown behaviour of push contract amendment 11, on real hosts. */
class HardeningTest {

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
    Files.createDirectories(sub);
    TestNode n = TestNode.start(pg, sub, config);
    nodes.add(n);
    return n;
  }

  /** How long the relay's lock has left; negative when nobody holds it. */
  static double relayLockLeft(TestNode n) {
    List<String> left =
        n.query(
            "SELECT CASE WHEN owner_token IS NULL THEN -1 ELSE extract(epoch FROM expires_at - clock_timestamp())::float8 END FROM fw_lock WHERE lock_key = 'svc:push-relay'");
    return left.isEmpty() ? -1 : Double.parseDouble(left.getFirst());
  }

  /** The relay's lock is taken with push.relay.lease (default 5 s): refreshed every third of it. */
  @Test
  void theRelayLockLeaseIsTheSetting() throws Exception {
    for (Object[] c : new Object[][] {{null, 5.0}, {"2s", 2.0}}) {
      TestNode n = c[0] == null ? node(TestNode.Config.defaults()) : node(TestNode.Config.defaults().withEnv("PUSH_RELAY_LEASE", (String) c[0]));
      double lease = (Double) c[1];
      TestNode.await("the relay to hold its lock", () -> relayLockLeft(n) > 0);
      double left = relayLockLeft(n);
      assertThat(left).as("lease %s", lease).isLessThanOrEqualTo(lease).isGreaterThanOrEqualTo(lease / 3);
      n.close();
      pg.reset();
    }
  }

  /** A relay that stops cleanly releases its lock, so another host's relay takes over at once. */
  @Test
  void aCleanlyStoppedRelayHandsOverAtOnce() throws Exception {
    TestNode.Config config = TestNode.Config.defaults().withEnv("PUSH_RELAY_LEASE", "60s");
    TestNode a = node(config);
    a.publish(ACME, "test:order:1", "order.updated", Map.of("from", "a"));
    a.settle(1); // a's relay holds the lock
    TestNode b = node(config);
    Sse c = b.open("acme", List.of("test:order:1"));
    a.close();
    b.publish(ACME, "test:order:1", "order.updated", Map.of("from", "b"));
    // within 10 s, a sixth of the lease
    assertThat(Sse.idOf(c.next())).isEqualTo(b.settle(2).get(1));
  }

  /** A relay run under way when the loop stops (or the lock is lost) is let finish. */
  @Test
  void aRelayRunIsNotInterruptedByTheStop() throws Exception {
    BareDb db = new BareDb(pg);
    Relay relay = db.relay(500);
    try (Connection tx = db.dataSource.getConnection()) {
      tx.setAutoCommit(false);
      for (int i = 0; i < 3; i++) {
        BareDb.insertPush(tx, Integer.toString(i));
      }
      tx.commit();
    }
    AtomicReference<Thread> caller = new AtomicReference<>();
    relay.beforeCommit = () -> caller.get().interrupt(); // the loop is stopped between the delete and the commit
    AtomicReference<Object> outcome = new AtomicReference<>();
    AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
    Thread t =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    outcome.set(relay.onceUninterrupted());
                  } catch (Exception e) {
                    outcome.set(e);
                  }
                  interruptedAfter.set(Thread.currentThread().isInterrupted());
                });
    caller.set(t);
    t.join();
    assertThat(outcome.get()).as("all three committed").isEqualTo(3);
    assertThat(interruptedAfter.get()).as("the caller still sees the interrupt").isTrue();
    assertThat(db.log.readAfter(0, 10)).hasSize(3);
  }

  /** A new host starts with the newest push.recent pushes in memory, so its clients resume from memory. */
  @Test
  void aRestartedHostResumesFromMemory() throws Exception {
    TestNode first = node(TestNode.Config.defaults());
    for (int i = 0; i < 30; i++) {
      first.publish(ACME, "test:order:" + (1 + i % 2), "order.updated", Map.of("n", i));
    }
    List<Long> ids = first.settle(30);
    first.close();
    java.util.function.IntFunction<List<Long>> mine =
        from -> { // the pushes of test:order:1 after the from-th
          List<Long> out = new ArrayList<>();
          for (int i = from + 1; i < 30; i++) {
            if (i % 2 == 0) {
              out.add(ids.get(i));
            }
          }
          return out;
        };

    TestNode restarted = node(TestNode.Config.defaults());
    Sse c = restarted.open("acme", List.of("test:order:1"), ids.get(9).toString());
    assertThat(c.ids(mine.apply(9).size())).isEqualTo(mine.apply(9));
    assertThat(restarted.counter("fw_push_replay_source_total", "memory")).isEqualTo(1);
    assertThat(restarted.counter("fw_push_replay_source_total", "log")).isZero();

    TestNode small = node(TestNode.Config.defaults().withEnv("PUSH_RECENT", "10")); // holds the newest 10
    c = small.open("acme", List.of("test:order:1"), ids.get(25).toString());
    assertThat(c.ids(mine.apply(25).size())).as("from memory").isEqualTo(mine.apply(25));
    c = small.open("acme", List.of("test:order:1"), ids.get(5).toString());
    assertThat(c.ids(mine.apply(5).size())).as("from the log").isEqualTo(mine.apply(5));
    assertThat(small.counter("fw_push_replay_source_total", "memory")).isEqualTo(1);
    assertThat(small.counter("fw_push_replay_source_total", "log")).isEqualTo(1);

    small.execute("DELETE FROM fw_push_log WHERE id <= " + ids.get(1)); // retention took the first two
    TestNode trimmed = node(TestNode.Config.defaults());
    Sse reset = trimmed.open("acme", List.of("test:order:1"), ids.get(0).toString());
    long latest = ids.get(29);
    assertThat(reset.next()).isEqualTo("id: " + latest + "\nevent: reset\ndata: {\"reason\":\"retention\",\"latest\":" + latest + "}\n\n");
    c = trimmed.open("acme", List.of("test:order:1"), ids.get(1).toString());
    assertThat(c.ids(mine.apply(1).size())).as("after the oldest kept").isEqualTo(mine.apply(1));
    assertThat(trimmed.counter("fw_push_replay_source_total", "memory"))
        .as("everything after the deleted pushes is held")
        .isEqualTo(1);
  }

  @Test
  void theRetentionLoopPrunesTheLog() throws Exception {
    TestClock clock = new TestClock(TestNode.BEGIN);
    TestNode n = node(TestNode.Config.defaults().withClock(clock).withEnv("PUSH_RETENTION", "10m"));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 1));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 2));
    n.settle(2);
    clock.advance(Duration.ofMinutes(11));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("n", 3));
    n.settle(3);
    // the loop runs every minute: the same step the loop takes
    n.pushes.log().prune(clock.instant().minus(n.pushes.settings().retention()));
    assertThat(n.logIds()).containsExactly(3L);
  }
}
