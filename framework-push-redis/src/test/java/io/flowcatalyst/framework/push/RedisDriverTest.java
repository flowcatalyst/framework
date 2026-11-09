package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.push.redis.RedisPushLog;
import io.flowcatalyst.framework.redis.RedisFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

/** What only the Redis driver has to prove on real hosts: the A2 duplicate, and a Redis that drops its connections. */
class RedisDriverTest {

  static PostgresFixture pg;
  static RedisFixture redis;

  @TempDir Path dir;
  final List<TestNode> nodes = new ArrayList<>();
  final List<RedisBackend> backends = new ArrayList<>();

  @BeforeAll
  static void start() {
    pg = PostgresFixture.start();
    redis = RedisFixture.start();
  }

  @AfterAll
  static void stop() {
    redis.close();
    pg.close();
  }

  @BeforeEach
  void clean() {
    pg.reset();
  }

  @AfterEach
  void stopNodes() {
    nodes.forEach(TestNode::close);
    backends.forEach(RedisBackend::close);
  }

  TestNode node(RedisBackend backend, TestNode.Config config) throws Exception {
    Path sub = dir.resolve("n" + nodes.size());
    Files.createDirectories(sub);
    backends.add(backend);
    TestNode n = TestNode.start(pg, sub, config.withBackend(backend));
    nodes.add(n);
    return n;
  }

  static OutboxRow row(long id, String note) {
    return new OutboxRow(id, "test:order:1", "order.updated", "acme", "{\"note\":\"" + note + "\"}", TestNode.BEGIN);
  }

  /**
   * A row logged twice (the relay died between the append and the commit) reaches a stream once,
   * live and in a replay from the log.
   */
  @Test
  void aDuplicateOutboxIdIsDeliveredOnce() throws Exception {
    RedisBackend backend = new RedisBackend(redis);
    TestNode n = node(backend, TestNode.Config.defaults().withEnv("PUSH_RECENT", "0"));
    Sse c = n.open("acme", List.of("test:order:1"));
    RedisPushLog log = new RedisPushLog(backend.client(), backend.prefix);
    List<OutboxRow> batch = List.of(row(101, "a"), row(102, "b"));
    log.append(null, batch, TestNode.BEGIN);
    log.append(null, batch, TestNode.BEGIN); // the relay's second attempt appends the same rows
    log.append(null, List.of(row(103, "c")), TestNode.BEGIN);
    List<Long> entries = n.settle(5);
    List<String> frames = List.of(c.next(), c.next(), c.next());
    assertThat(frames.get(0)).contains("\"note\":\"a\"");
    assertThat(frames.get(1)).contains("\"note\":\"b\"");
    assertThat(frames.get(2)).contains("\"note\":\"c\"");
    assertThat(frames.stream().map(Sse::idOf).toList()).containsExactly(entries.get(0), entries.get(1), entries.get(4));

    // a replay from the log (the hub keeps nothing in memory here) drops the duplicates too
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
    Sse again = n.listen(token, "0");
    again.greeting();
    assertThat(again.ids(3)).containsExactly(entries.get(0), entries.get(1), entries.get(4));
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    assertThat(Sse.idOf(again.next())).as("a live push after the replay, not a leaked duplicate").isGreaterThan(entries.get(4));
  }

  /**
   * A Redis that drops every connection (a restart, a failover): the hub's follower fails and is
   * retried, the relay's append fails and is retried, and pushes and ends arrive again.
   */
  @Test
  void theHubRecoversWhenRedisDropsItsConnections() throws Exception {
    try (RedisFixture own = RedisFixture.start()) { // a server of its own: the kill takes every connection
      RedisBackend backend = new RedisBackend(own);
      TestNode n = node(backend, TestNode.Config.defaults());
      Sse c = n.open("acme", List.of("test:order:1"));
      n.publish(ACME, "test:order:1", "order.updated", Map.of("v", 1));
      assertThat(Sse.idOf(c.next())).isEqualTo(n.settle(1).get(0));

      try (Jedis admin = new Jedis(own.host(), own.port())) {
        admin.sendCommand(Protocol.Command.CLIENT, "KILL", "TYPE", "normal", "SKIPME", "yes");
        admin.sendCommand(Protocol.Command.CLIENT, "KILL", "TYPE", "pubsub", "SKIPME", "yes");
      }
      n.publish(ACME, "test:order:1", "order.updated", Map.of("v", 2));
      assertThat(c.next()).contains("\"v\":2");

      // the follower is back within its retry pause: an end of streams is heard again
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      boolean ended = false;
      while (!ended && System.nanoTime() < deadline) {
        n.pushes.endPrincipal("prn_acme");
        ended = c.ends(Duration.ofMillis(700));
      }
      assertThat(ended).as("the end of streams reached the hub again").isTrue();
    }
  }
}
