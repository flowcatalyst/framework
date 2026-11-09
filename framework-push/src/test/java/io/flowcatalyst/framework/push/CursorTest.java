package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.database.PostgresFixture;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The snapshot cursor (push contract 9.1) and the Last-Event-ID forms. */
class CursorTest {

  static PostgresFixture pg;

  @TempDir Path dir;
  TestNode n;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @BeforeEach
  void start() throws Exception {
    pg.reset();
    n = TestNode.start(pg, dir, TestNode.Config.defaults());
  }

  @AfterEach
  void close() {
    n.close();
  }

  @Test
  void theCursorIsTheNewestDispatchedPush() {
    assertThat(n.pushes.cursor()).as("cursor of an empty log").isEqualTo("0");
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", 1));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", 2));
    List<Long> ids = n.settle(2);
    assertThat(n.pushes.cursor()).isEqualTo(Long.toString(ids.get(1)));
  }

  @Test
  void aSnapshotCursorReplaysOnlyLaterPushes() {
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "A"));
    n.settle(1);
    String cursor = n.pushes.cursor(); // before the state read
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "B"));
    List<Long> ids = n.settle(2);
    Sse c = n.open("acme", List.of("test:order:1"), cursor);
    String f = c.next();
    assertThat(f).contains("\"v\":\"B\"");
    assertThat(Sse.idOf(f)).isEqualTo(ids.get(1));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "C"));
    assertThat(c.next()).as("C and no repeat of A").contains("\"v\":\"C\"");
  }

  @Test
  void aSnapshotCursorOfAnEmptyLogReplaysEverything() {
    String cursor = n.pushes.cursor();
    assertThat(cursor).isEqualTo("0");
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "A"));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "B"));
    List<Long> ids = n.settle(2);
    Sse c = n.open("acme", List.of("test:order:1"), cursor);
    String a = c.next();
    String b = c.next();
    assertThat(a).contains("\"v\":\"A\"");
    assertThat(Sse.idOf(a)).isEqualTo(ids.get(0));
    assertThat(b).contains("\"v\":\"B\"");
    assertThat(Sse.idOf(b)).isEqualTo(ids.get(1));
  }

  @Test
  void anInvalidLastEventIdGetsAResetThenLive() {
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "old"));
    List<Long> ids = n.settle(1);
    int round = 0;
    for (String bad : new String[] {"12-34", "abc", "-1"}) {
      Sse c = n.open("acme", List.of("test:order:1"), bad);
      assertThat(c.next()).as("Last-Event-ID %s", bad).isEqualTo(Wire.resetEvent(Wire.RESET_UNKNOWN_ID, ids.getLast()));
      n.publish(ACME, "test:order:1", "order.updated", Map.of("v", bad));
      assertThat(c.next()).as("after the reset, the live push").contains("\"v\":\"" + bad + "\"");
      c.close();
      ids = n.settle(++round + 1);
    }
  }

  @Test
  void aBlankLastEventIdIsNoCursor() {
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "old"));
    n.settle(1);
    Sse c = n.open("acme", List.of("test:order:1"), "  ");
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "live"));
    assertThat(c.next()).as("the live push first and no reset").contains("\"v\":\"live\"");
  }

  @Test
  void theLastEventIdMayComeInTheQuery() throws Exception {
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "A"));
    n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "B"));
    List<Long> ids = n.settle(2);
    String token = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
    HttpResponse<java.io.InputStream> res =
        n.http.send(
            HttpRequest.newBuilder(URI.create(n.base + "/push/stream?st=" + token + "&lastEventId=" + ids.get(0))).build(), HttpResponse.BodyHandlers.ofInputStream());
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Type")).contains("text/event-stream");
    assertThat(res.headers().firstValue("Cache-Control")).contains("no-cache");
    assertThat(res.headers().firstValue("X-Accel-Buffering")).contains("no");
    Sse c = Sse.open(n.http, n.base + "/push/stream?st=" + token + "&lastEventId=" + ids.get(0), null);
    c.greeting();
    assertThat(Sse.idOf(c.next())).isEqualTo(ids.get(1));
    assertThat(c.poll(Duration.ofMillis(200))).isNull();
    res.body().close();
    c.close();
  }
}
