package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static io.flowcatalyst.framework.push.TestNode.OTHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The three routes of push contract section 6 over real sockets. */
class HttpTest {

  static final String[] USER = {"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view"};

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

  static String granted(JsonNode m) {
    List<String> out = new ArrayList<>();
    m.path("granted").forEach(x -> out.add(x.asString()));
    return String.join(" ", out);
  }

  static String denials(JsonNode m) {
    List<String> out = new ArrayList<>();
    m.path("denied").forEach(d -> out.add(d.path("topic").asString() + "=" + d.path("code").asString()));
    return String.join(" ", out);
  }

  @Test
  void subscribeAnswers() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("PUSH_MAX_TOPICS", "5"));
    String ticket = n.pushes.ticket(Context.of(ACME), "test:order:{id}");
    record Case(String name, String body, String[] headers, int status, String code) {}
    List<Case> cases =
        List.of(
            new Case("malformed body", "{\"topics\":", USER, 400, "VALIDATION"),
            new Case("unknown member", "{\"topics\":[\"test:order:1\"],\"x\":1}", USER, 400, "VALIDATION"),
            new Case("no topics", "{\"topics\":[]}", USER, 400, "VALIDATION"),
            new Case("invalid topic", "{\"topics\":[\"Test:order\"]}", USER, 400, "VALIDATION"),
            new Case("too many topics", "{\"topics\":[\"test:a:1\",\"test:a:2\",\"test:a:3\",\"test:a:4\",\"test:a:5\",\"test:a:6\"]}", USER, 400, "VALIDATION"),
            new Case("malformed ticket", "{\"topics\":[\"test:order:1\"],\"tickets\":[\"not.a\"]}", USER, 400, "VALIDATION"),
            new Case("bad credentials", "{\"topics\":[\"test:order:1\"]}", new String[] {"X-Fail", "1"}, 401, "UNAUTHENTICATED"),
            new Case(
                "bad credentials with a good ticket",
                "{\"topics\":[\"test:order:1\"],\"tickets\":[\"" + ticket + "\"]}",
                new String[] {"X-Fail", "1"},
                401,
                "UNAUTHENTICATED"),
            new Case("no credentials, no ticket", "{\"topics\":[\"test:public\"]}", new String[0], 401, "UNAUTHENTICATED"),
            new Case("nothing granted", "{\"topics\":[\"test:order:1\"]}", new String[] {"X-Client", "acme"}, 403, "NO_TOPICS"),
            new Case("another application's topic", "{\"topics\":[\"other:order:1\"]}", USER, 403, "NO_TOPICS"));
    for (Case c : cases) {
      TestNode.Answer a = n.call("POST", "/push/subscriptions", c.body(), c.headers());
      assertThat(a.status()).as("%s: %s", c.name(), a.body()).isEqualTo(c.status());
      assertThat(a.code()).as(c.name()).isEqualTo(c.code());
    }

    // granted and denied together: a rule, a ticket, a forbidden topic, a broken ticket's topic
    String broken = ticket.substring(0, ticket.length() - 4) + "AAAA";
    String other = n.pushes.ticket(Context.of(ACME), "test:user:{id}");
    TestNode.Answer a =
        n.call(
            "POST",
            "/push/subscriptions",
            "{\"topics\":[\"test:user:prn_1\",\"test:order:7\",\"test:user:prn_2\",\"test:client:acme:news\",\"test:user:prn_1\"],\"tickets\":[\"" + broken + "\"]}",
            USER);
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    JsonNode m = a.json();
    assertThat(granted(m)).isEqualTo("test:user:prn_1 test:order:7");
    assertThat(denials(m)).isEqualTo("test:user:prn_2=TOPIC_FORBIDDEN test:client:acme:news=TOPIC_FORBIDDEN");
    assertThat(m.path("expiresAt").asString()).as("now + push.stream.max-lifetime").isEqualTo("2026-11-08T13:00:00Z");
    // a ticket with a bad signature: its topic is denied TICKET_INVALID, not TOPIC_FORBIDDEN
    a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\",\"test:user:x\"],\"tickets\":[\"" + broken + "\",\"" + other + "\"]}");
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    assertThat(denials(a.json())).isEqualTo("test:order:1=TICKET_INVALID");
  }

  @Test
  void streamTokenAnswers() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    String token = n.subscribe(List.of("test:order:1"), List.of(), USER);
    int dot = token.indexOf('.');
    String payload = token.substring(0, dot);
    String sig = token.substring(dot + 1);
    String forged =
        new StreamTokens("another secret of at least thirty-two characters")
            .sign(new Subscription(List.of("test:order:1"), "_platform", "", "", ""), TestNode.BEGIN.plus(Duration.ofHours(1)).getEpochSecond());
    Map<String, String> bad =
        Map.of(
            "missing", "",
            "garbage", "abc",
            "tampered payload", payload + "x." + sig,
            "tampered mac", payload + "." + sig.substring(0, sig.length() - 2) + "AA",
            "another secret", forged);
    bad.forEach(
        (name, st) -> {
          TestNode.Answer a = n.call("GET", "/push/stream?st=" + st, null);
          assertThat(a.status()).as("%s: %s", name, a.body()).isEqualTo(401);
          assertThat(a.code()).as(name).isEqualTo("STREAM_TOKEN_INVALID");
        });
    n.clock.advance(Duration.ofHours(1));
    TestNode.Answer a = n.call("GET", "/push/stream?st=" + token, null);
    assertThat(a.status()).isEqualTo(401);
    assertThat(a.code()).isEqualTo("STREAM_TOKEN_EXPIRED");
  }

  @Test
  void keysServeTheCurrentAndThePreviousKey() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    TestNode.Answer a = n.call("GET", "/push/keys", null);
    JsonNode want = TestNode.JSON.readTree(io.flowcatalyst.framework.conformance.Conformance.file("push/tickets.json")).path("jwks");
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.response().headers().firstValue("Content-Type")).contains("application/json");
    assertThat(a.json()).isEqualTo(want);
  }

  @Test
  void streamLimits() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("PUSH_MAX_STREAMS_PER_PRINCIPAL", "1", "PUSH_MAX_STREAMS", "2"));
    String token = n.subscribe(List.of("test:order:1"), List.of(), USER);
    Sse first = n.listen(token, null);
    first.greeting();
    TestNode.Answer a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"]}", USER);
    assertThat(a.status()).as("subscribe over the principal's limit: %s", a.body()).isEqualTo(429);
    assertThat(a.code()).isEqualTo("PUSH_LIMIT");
    a = n.call("GET", "/push/stream?st=" + token, null);
    assertThat(a.status()).as("a second stream on the token: %s", a.body()).isEqualTo(429);
    assertThat(a.code()).isEqualTo("PUSH_LIMIT");
    String other = n.subscribe(List.of("test:order:1"), List.of(), "X-Client", "acme", "X-Principal", "prn_2", "X-Permissions", "test:order:view");
    n.listen(other, null).greeting();
    a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"]}", "X-Client", "acme", "X-Principal", "prn_3", "X-Permissions", "test:order:view");
    assertThat(a.status()).as("subscribe over the instance's limit: %s", a.body()).isEqualTo(429);
    first.close();
    // the server notices a closed client at its next write; a push makes it write
    TestNode.await(
        "the stream to close",
        () -> {
          n.publish(ACME, "test:order:1", "order.updated", Map.of());
          return n.pushes.hub().openCount() == 1;
        });
    a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"]}", USER);
    assertThat(a.status()).as("after the stream closed: %s", a.body()).isEqualTo(200);
  }

  @Test
  void theStreamTokenIsNeverLogged() throws Exception {
    try (LogCapture logs = LogCapture.start()) {
      TestNode n = node(TestNode.Config.defaults());
      String token = n.subscribe(List.of("test:order:1"), List.of(), USER);
      Sse c = n.listen(token, null);
      c.greeting();
      n.call("GET", "/push/stream?st=" + token + "x", null);
      c.close();
      TestNode.await("the access log lines", () -> logs.text().split("/push/stream", -1).length - 1 >= 2);
      String sig = token.substring(token.indexOf('.') + 1);
      assertThat(logs.text()).doesNotContain(sig).doesNotContain("st=").doesNotContain(token.substring(0, token.indexOf('.')));
    }
  }

  @Test
  void drainEndsStreamsAndRefusesSubscribes() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    Sse c = n.open("acme", List.of("test:order:1"));
    Thread shutdown = Thread.ofVirtual().start(n.app::close);
    assertThat(c.ends(Duration.ofSeconds(10))).as("a drained stream ends without an event").isTrue();
    shutdown.join(Duration.ofSeconds(20));
    assertThat(n.counter("fw_push_streams_ended_total", "drain")).isEqualTo(1);
    assertThat(n.pushes.hub().isDraining()).isTrue();
  }

  @Test
  void aSubscribeWhileDrainingIsRefused() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    n.pushes.hub().drain();
    TestNode.Answer a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"]}", USER);
    assertThat(a.status()).isEqualTo(503);
    assertThat(a.code()).isEqualTo("UNAVAILABLE");
    a = n.call("GET", "/push/stream?st=x", null);
    assertThat(a.status()).isEqualTo(503);
  }

  @Test
  void shutdownWithManyStreamsEndsThemWithinTheDrain() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("PUSH_MAX_STREAMS_PER_PRINCIPAL", "1000"));
    String token = n.subscribe(List.of("test:order:1"), List.of(), USER);
    int streams = 300;
    List<Sse> clients = new ArrayList<>();
    for (int i = 0; i < streams; i++) {
      Sse c = n.listen(token, null);
      c.greeting();
      clients.add(c);
    }
    long start = System.nanoTime();
    n.app.close();
    long millis = (System.nanoTime() - start) / 1_000_000;
    for (Sse c : clients) {
      assertThat(c.ends(Duration.ofSeconds(1))).isTrue();
    }
    assertThat(millis).as("the shutdown with %d streams (app.shutdown.drain is 10s)", streams).isLessThan(5000);
    assertThat(n.pushes.hub().isDraining()).isTrue();
    assertThat(n.counter("fw_push_streams_ended_total", "drain")).isEqualTo(streams);
  }

  @Test
  void ruleDeclarationRefusesAnOpenRule() {
    Throwable e =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                node(
                    TestNode.Config.defaults()
                        .withPush(
                            b -> {
                              b.topic("test:order:{id}");
                              b.topic("other:user:{principal}");
                              b.topic("test:bad seg:{principal}");
                            })));
    assertThat(e).isInstanceOf(StartupException.class);
    assertThat(e.getMessage())
        .contains("test:order:{id}: a push rule needs a {principal} placeholder or a required permission")
        .contains("push rule other:user:{principal}: a topic starts with the application's code")
        .contains("a pattern segment");
  }

  @Test
  void settingsOutsideTheirRangeFailStartUp() {
    for (String[] c :
        new String[][] {
          {"PUSH_RELAY_LEASE", "61s", "push.relay.lease"},
          {"PUSH_RETENTION", "5m", "push.retention"},
          {"PUSH_MAX_DATA_BYTES", "70000", "push.max-data-bytes"},
          {"PUSH_STREAM_BUFFER", "8", "push.stream.buffer"},
          {"PUSH_STREAM_SECRET", "short", "push.stream.secret"},
          {"PUSH_TICKET_KEY", "no-colon", "push.ticket.key"},
          {"PUSH_DRIVER", "redis", "push.driver"},
        }) {
      Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> node(TestNode.Config.defaults().withEnv(c[0], c[1])));
      assertThat(e).as(c[0]).isInstanceOf(StartupException.class);
      assertThat(e.getMessage()).as(c[0]).contains(c[2]);
    }
  }

  @Test
  void ticketsOverHttp() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    // an anonymous subscribe with a ticket: the stream's client is the ticket's
    String ticket = n.pushes.ticket(Context.of(ACME).withPrincipal(new PrincipalId("prn_9")), "test:order:{id}", "test:public");
    String token = n.subscribe(List.of("test:order:1", "test:public"), List.of(ticket));
    TestNode.Answer both = n.call("POST", "/push/subscriptions", TestNode.subscribeBody(List.of("test:order:1", "test:public"), List.of(ticket)));
    assertThat(granted(both.json())).isEqualTo("test:order:1 test:public");
    StreamTokens.Valid claims = (StreamTokens.Valid) n.pushes.tokens().verify(token, n.clock.instant());
    assertThat(claims.claims().subscription().client()).isEqualTo("acme");
    assertThat(claims.claims().subscription().principal()).isEqualTo("prn_9");
    Sse c = n.listen(token, null);
    c.greeting();
    n.publish(OTHER, "test:order:1", "order.updated", Map.of());
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    assertThat(Sse.idOf(c.next())).as("ticket stream got acme's push 2").isEqualTo(2);
    // a ticket of another client than the authenticated one grants nothing
    TestNode.Answer a = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"],\"tickets\":[\"" + ticket + "\"]}", "X-Client", "other");
    assertThat(a.status()).isEqualTo(403);
    assertThat(a.code()).isEqualTo("NO_TOPICS");
    // a ticket bound to a key needs a request that proved it
    String bound = n.pushes.ticketWith(Context.of(ACME), new Pushes.TicketOptions("jkt-1", null), "test:order:{id}");
    Map<String, Integer> want = Map.of("", 403, "jkt-2", 403, "jkt-1", 200);
    want.forEach(
        (key, status) -> {
          String[] headers = key.isEmpty() ? new String[] {"X-Client", "acme"} : new String[] {"X-Client", "acme", "X-Proof-Key", key};
          TestNode.Answer r = n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:order:1\"],\"tickets\":[\"" + bound + "\"]}", headers);
          assertThat(r.status()).as("proof key '%s': %s", key, r.body()).isEqualTo(status);
        });
    assertThatThrownBy(() -> n.pushes.ticket(Context.of(ACME), "other:order:{id}")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRuleAppliesToTheContextOfTheRequest() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    TestNode.Answer a =
        n.call("POST", "/push/subscriptions", "{\"topics\":[\"test:client:acme:news\",\"test:client:other:news\",\"test:public\"]}", "X-Client", "acme", "X-Permissions", "test:news:view");
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    assertThat(granted(a.json())).isEqualTo("test:client:acme:news test:public");
    assertThat(denials(a.json())).isEqualTo("test:client:other:news=TOPIC_FORBIDDEN");
    assertThat(n.pushes.rules()).extracting(Rule::pattern).contains("test:order:{id}", "test:public");
    assertThat(Optional.of(n.pushes.rules().getFirst().required())).hasValueSatisfying(r -> assertThat(r).containsExactly("test:order:view"));
  }

  @Test
  void anIdleStreamHeartbeatsAsConfigured() throws Exception {
    TestNode n = node(TestNode.Config.defaults().withEnv("HTTP_SSE_HEARTBEAT", "200ms"));
    Sse c = n.open("acme", List.of("test:order:1"));
    assertThat(c.next()).isEqualTo(Wire.HEARTBEAT).isEqualTo(": ping\n\n");
    assertThat(c.next()).isEqualTo(": ping\n\n");
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    TestNode.await("the push among the heartbeats", () -> c.next().startsWith("id: 1\n"));
  }

  @Test
  void healthAndMetricsAreAsTheContractNamesThem() throws Exception {
    TestNode n = node(TestNode.Config.defaults());
    var report = n.app.health().check();
    assertThat(report.results()).containsKey("push:log").containsKey("background:push-relay").containsKey("background:push-retention");
    assertThat(report.results().get("push:log")).isInstanceOf(io.flowcatalyst.framework.core.HealthResult.Up.class);
    assertThat(report.criticality("push:log")).isEqualTo(io.flowcatalyst.framework.core.Criticality.INFORMATIONAL);
    Sse c = n.open("acme", List.of("test:order:1"));
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    n.publish(ACME, "test:order:1", "order.updated", Map.of());
    c.ids(2);
    n.settle(2);
    assertThat(n.counter("fw_push_published_total")).isEqualTo(2);
    assertThat(n.counter("fw_push_delivered_total")).isEqualTo(2);
    assertThat(n.counter("fw_push_streams_open")).isEqualTo(1);
    assertThat(n.counter("fw_push_log_id")).isEqualTo(2);
    var families = n.app.get(io.flowcatalyst.framework.metrics.Metrics.class).snapshot().families().stream().map(f -> f.name()).toList();
    assertThat(families)
        .contains(
            "fw_push_published_total", "fw_push_relay_lag_seconds", "fw_push_log_id", "fw_push_streams_open", "fw_push_delivered_total",
            "fw_push_streams_ended_total", "fw_push_resets_total", "fw_push_replay_source_total");
  }
}
