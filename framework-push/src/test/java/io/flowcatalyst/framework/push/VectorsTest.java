package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.usecase.InvalidPushException;
import io.flowcatalyst.framework.usecase.Push;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The conformance vectors of conformance/push/ (push contract section 13). A driver's module runs
 * the wire and replay vectors again on its log by extending this class.
 */
class VectorsTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  /** Where the nodes keep the log; a driver's test class overrides this. */
  LogBackend newBackend() {
    return new PostgresBackend();
  }

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  static JsonNode vector(String name) {
    return TestNode.JSON.readTree(Conformance.file("push/" + name));
  }

  static String code(Throwable t) {
    return t instanceof InvalidPushException e ? e.reason() : "";
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(x -> out.add(x.asString()));
    return out;
  }

  // ---- topics.json ---------------------------------------------------------------------------

  @Test
  void topics() {
    JsonNode v = vector("topics.json");
    for (JsonNode c : v.path("topics")) {
      String text = c.has("topic") ? c.path("topic").asString() : "fulfil:" + "x".repeat(c.path("topicOfLength").asInt() - "fulfil:".length());
      Throwable t = catchThrowable(() -> Push.checkTopic(text));
      expect(text, c, t);
    }
    for (JsonNode c : v.path("types")) {
      String text = c.has("type") ? c.path("type").asString() : "a".repeat(c.path("typeOfLength").asInt());
      Throwable t = catchThrowable(() -> Push.checkType(text));
      expect(text, c, t);
    }
    for (JsonNode c : v.path("data")) {
      String json = c.has("json") ? c.path("json").asString() : "{\"s\":\"" + "a".repeat(c.path("stringOfLength").asInt()) + "\"}";
      int max = c.path("maxBytes").asInt();
      String[] stored = new String[1];
      Throwable t = catchThrowable(() -> stored[0] = Push.compact(json, max));
      if (c.has("code")) {
        assertThat(code(t)).as(json).isEqualTo(c.path("code").asString());
      } else {
        assertThat(t).as(json).isNull();
        if (c.has("stored")) {
          assertThat(stored[0]).isEqualTo(c.path("stored").asString());
        }
      }
    }
    for (JsonNode c : v.path("patterns")) {
      String pattern = c.path("pattern").asString();
      Throwable t =
          catchThrowable(
              () -> {
                if (c.path("public").asBoolean(false)) {
                  Rule.open(pattern);
                } else {
                  Rule.of(pattern, strings(c.path("requires")).toArray(String[]::new));
                }
              });
      assertThat(t != null).as("%s refused: %s", pattern, t).isEqualTo(c.path("refused").asBoolean());
    }
    for (JsonNode c : v.path("rules")) {
      JsonNode r = c.path("rule");
      String pattern = r.path("pattern").asString();
      Rule rule = r.path("public").asBoolean(false) ? Rule.open(pattern) : Rule.of(pattern, strings(r.path("requires")).toArray(String[]::new));
      assertThat(rule.allows(contextOf(c.path("context")), c.path("topic").asString()))
          .as("%s %s", pattern, c.path("topic").asString())
          .isEqualTo(c.path("allowed").asBoolean());
    }
  }

  private static void expect(String text, JsonNode c, Throwable t) {
    if (c.path("accepted").asBoolean()) {
      assertThat(t).as(text).isNull();
    } else {
      assertThat(code(t)).as(text).isEqualTo(c.path("code").asString());
    }
  }

  static Context contextOf(JsonNode m) {
    if (m == null || m.isNull() || m.isMissingNode()) {
      return null;
    }
    Set<String> permissions = new HashSet<>(strings(m.path("permissions")));
    String principal = m.path("principal").asString("");
    return new Context(
        new ClientId(m.path("client").asString()),
        principal.isEmpty() ? null : new PrincipalId(principal),
        null,
        CorrelationId.generate(),
        null,
        null,
        permissions);
  }

  // ---- tickets.json --------------------------------------------------------------------------

  @Test
  void tickets() throws Exception {
    JsonNode v = vector("tickets.json");
    JsonNode keys = v.path("keys");
    TicketKeys set =
        TicketKeys.parse(
            keys.path("current").path("kid").asString() + ":" + keys.path("current").path("seed").asString() + "," + keys.path("previous").path("kid").asString() + ":" + keys.path("previous").path("seed").asString());
    assertThat(TestNode.JSON.readTree(set.jwks())).isEqualTo(v.path("jwks"));
    Granter granter = new Granter(v.path("application").asString(), List.of(), set, Duration.ofSeconds(v.path("maxLifetimeSeconds").asLong()));
    Instant now = Instant.ofEpochSecond(v.path("now").asLong());
    for (JsonNode c : v.path("cases")) {
      String name = c.path("name").asString();
      Optional<Credentials> credentials = Optional.empty();
      JsonNode ctx = c.path("context");
      if (ctx.isObject()) {
        String proof = ctx.path("proofKey").asString("");
        credentials = Optional.of(new Credentials(contextOf(ctx), null, proof, "", ""));
      }
      JsonNode want = c.path("expect");
      List<String> topics = strings(c.path("topics"));
      List<String> tickets = strings(c.path("tickets"));
      if (want.has("error")) {
        Optional<Credentials> cred = credentials;
        Throwable t = catchThrowable(() -> granter.decide(topics, tickets, cred, now));
        assertThat(t).as(name).isInstanceOf(HttpException.class);
        assertThat(((HttpException) t).code()).as(name).isEqualTo(want.path("error").asString());
        continue;
      }
      Granter.Decision d = granter.decide(topics, tickets, credentials, now);
      if (want.path("ticketReasons").isArray()) {
        List<String> reasons = new ArrayList<>();
        want.path("ticketReasons").forEach(x -> reasons.add(x.isNull() ? null : x.asString()));
        assertThat(d.ticketReasons()).as(name + ": ticket reasons").isEqualTo(reasons);
      }
      assertThat(d.granted()).as(name).isEqualTo(strings(want.path("granted")));
      List<String> denied = new ArrayList<>();
      d.denied().forEach(x -> denied.add(x.topic() + "=" + x.code()));
      List<String> wantDenied = new ArrayList<>();
      want.path("denied").forEach(x -> wantDenied.add(x.path("topic").asString() + "=" + x.path("code").asString()));
      assertThat(denied).as(name).isEqualTo(wantDenied);
      assertThat(d.subscription().client()).as(name).isEqualTo(want.path("streamClient").asString());
      assertThat(d.subscription().principal()).as(name).isEqualTo(want.path("principal").isNull() ? "" : want.path("principal").asString());
      assertThat(d.expires().toString()).as(name).isEqualTo(want.path("expiresAt").asString());
    }
  }

  // ---- wire.json -----------------------------------------------------------------------------

  @Test
  void wire() {
    JsonNode v = vector("wire.json");
    assertThat(Wire.retryField()).isEqualTo(v.path("streamStart").path("bytes").asString());
    assertThat(Wire.HEARTBEAT).isEqualTo(v.path("heartbeat").path("bytes").asString());
    for (JsonNode c : v.path("events")) {
      JsonNode in = c.path("input");
      String name = c.path("name").asString();
      String got;
      if (name.startsWith("push")) {
        got = new String(Wire.pushEvent(new Entry(in.path("id").asLong(), in.path("topic").asString(), in.path("type").asString(), "", in.path("data").asString(), Instant.EPOCH)), java.nio.charset.StandardCharsets.UTF_8);
      } else if (name.startsWith("subscribed")) {
        got = Wire.subscribedEvent(strings(in.path("granted")), in.path("latest").asLong());
      } else if (name.startsWith("reset")) {
        got = Wire.resetEvent(in.path("reason").asString(), in.path("latest").asLong());
      } else if (name.equals("auth-expired")) {
        got = Wire.authExpiredEvent();
      } else {
        throw new AssertionError("unknown event " + name);
      }
      assertThat(got).as(name).isEqualTo(c.path("bytes").asString());
    }
  }

  /** A real stream's first frames and a push, byte for byte. */
  @Test
  void wireOverHttp() throws Exception {
    JsonNode v = vector("wire.json");
    pg.reset();
    LogBackend backend = newBackend();
    try (TestNode n = TestNode.start(pg, dir, TestNode.Config.defaults().withBackend(backend)); backend) {
      String token = n.subscribe(List.of("test:order:ord_1", "test:user:prn_1"), List.of(), "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view");
      Sse c = n.listen(token, null);
      assertThat(c.next()).isEqualTo(v.path("streamStart").path("bytes").asString());
      assertThat(c.next()).isEqualTo(Wire.subscribedEvent(List.of("test:order:ord_1", "test:user:prn_1"), 0));
      n.publish(TestNode.ACME, "test:order:ord_1", "order.updated", java.util.Map.of("orderId", "ord_1", "version", 7));
      long id = n.settle(1).get(0);
      assertThat(c.next()).isEqualTo("id: " + id + "\nevent: order.updated\ndata: {\"topic\":\"test:order:ord_1\",\"data\":{\"orderId\":\"ord_1\",\"version\":7}}\n\n");
      assertThat(c.response.headers().firstValue("Content-Type")).contains("text/event-stream");
      assertThat(c.response.headers().firstValue("Cache-Control")).contains("no-cache");
      assertThat(c.response.headers().firstValue("X-Accel-Buffering")).contains("no");
    }
  }

  // ---- replay.json ---------------------------------------------------------------------------

  @Test
  void replay() throws Exception {
    JsonNode v = vector("replay.json");
    long trimmed = v.path("trimmed").asLong();
    long latest = v.path("latest").asLong();
    for (JsonNode c : v.path("cases")) {
      pg.reset();
      // a host whose memory holds nothing, so the replay reads the log; one per case, so that the
      // live push that ends a case does not move the next case's latest. Its clock starts at the
      // epoch, so the retention loop's edge (the clock less push.retention) is before every entry:
      // the vector's A2 ids are milliseconds near 0, and a prune overlapping the load would delete
      // some of them and move trimmed
      LogBackend backend = newBackend();
      TestNode.Config config = TestNode.Config.defaults().withEnv("PUSH_RECENT", "0").withClock(new TestClock(Instant.EPOCH)).withBackend(backend);
      try (TestNode n = TestNode.start(pg, dir.resolve("case-" + c.path("name").asString().hashCode()), config);
          backend) {
        List<Entry> logged = new ArrayList<>();
        for (JsonNode r : v.path("log")) {
          String client = r.path("client").isNull() ? "" : r.path("client").asString();
          logged.add(new Entry(r.path("id").asLong(), r.path("topic").asString(), r.path("type").asString(), client, TestNode.JSON.writeValueAsString(r.path("data")), TestNode.BEGIN));
        }
        backend.load(n, logged, trimmed);
        TestNode.await("the hub to follow the log", () -> n.pushes.hub().lastId() == latest);
        assertThat(n.pushes.log().trimmed()).isEqualTo(trimmed);
        List<String> topics = strings(c.path("topics"));
        String last = c.path("lastEventId").isNull() ? null : c.path("lastEventId").asString();
        String token =
            n.pushes
                .tokens()
                .sign(new Subscription(topics, c.path("client").asString(), "", "", ""), n.clock.instant().plus(Duration.ofHours(1)).getEpochSecond());
        Sse s = n.listen(token, last);
        s.greeting();
        // a live push for every topic and client ends the replay: the first frame with an id above the log's is live
        for (String topic : topics) {
          n.publish(ClientId.PLATFORM, topic, "live", java.util.Map.of());
        }
        List<Long> ids = new ArrayList<>();
        String reset = null;
        while (true) {
          String f = s.next();
          if (f.contains("event: reset")) {
            reset = f;
            break;
          }
          if (f.contains("event: live")) {
            break;
          }
          ids.add(Sse.idOf(f));
        }
        JsonNode want = c.path("expect");
        String name = c.path("name").asString();
        if (want.has("reset")) {
          assertThat(reset).as(name).isNotNull();
          assertThat(reset).as(name).contains("\"reason\":\"" + want.path("reset").path("reason").asString() + "\"");
          assertThat(Sse.idOf(reset)).as(name).isEqualTo(want.path("reset").path("id").asLong());
        } else {
          assertThat(reset).as(name).isNull();
          List<Long> wantIds = new ArrayList<>();
          want.path("ids").forEach(x -> wantIds.add(x.asLong()));
          assertThat(ids).as(name).isEqualTo(wantIds);
        }
      }
    }
  }
}
