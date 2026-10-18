package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/receivers/dispatch.json} (flowcatalyst contract section 4
 * and 4a slice 4): what {@code POST /fc/dispatch} answers for a registered handler outcome and a
 * request. Java writes it; Go compares.
 */
class DispatchVectorConformanceTest {

  static final String FILE = "receivers/dispatch.json";
  static final String TYPE = "orders:billing:invoice:created";
  static final String JOB = "dj_0HZXEQ5Y8JY5Z";
  static final String ENVELOPE =
      "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":2,\"source\":\"orders:billing\","
          + "\"subject\":\"billing.invoice.42\",\"correlationId\":\"corr-1\",\"messageGroup\":\"billing:invoice:42\","
          + "\"clientId\":\"clt_1\",\"clientCode\":\"acme\",\"data\":{\"invoiceId\":\"42\",\"amount\":\"10.00\"}}";

  @TempDir Path dir;

  /** A registered handler: the pattern and what it does. kind: ack, defer, deferDefault, reject, throw, throwChecked, hang, null. */
  record H(String pattern, String kind, String arg) {
    static H of(String pattern, String kind, String arg) {
      return new H(pattern, kind, arg);
    }
  }

  record Case(String name, List<H> handlers, boolean ready, List<String[]> headers, String body) {}

  static final class B {
    final String name;
    final List<H> handlers = new ArrayList<>();
    boolean ready = true;
    final List<String[]> headers = new ArrayList<>();
    String body = ENVELOPE;

    B(String name) {
      this.name = name;
    }

    B handler(String kind, String arg) {
      handlers.add(H.of(TYPE, kind, arg));
      return this;
    }

    B handler(String pattern, String kind, String arg) {
      handlers.add(H.of(pattern, kind, arg));
      return this;
    }

    B notReady() {
      ready = false;
      return this;
    }

    B h(String n, String v) {
      headers.add(new String[] {n, v});
      return this;
    }

    B body(String b) {
      body = b;
      return this;
    }

    /** The headers of a well-formed delivery, signed over the current body. */
    B std(String type, String client) {
      h("Content-Type", "application/json");
      h("X-Dispatch-Job-Id", JOB);
      h("X-Event-Type", type);
      if (client != null) {
        h("X-FlowCatalyst-Client", client);
      }
      return this;
    }

    B sig() {
      h("X-FlowCatalyst-Signature", sign(SECRET, TS, body));
      h("X-FlowCatalyst-Timestamp", TS);
      return this;
    }

    Case build() {
      return new Case(name, handlers, ready, headers, body);
    }
  }

  static B ok(String name) {
    return new B(name).std(TYPE, "clt_1:acme");
  }

  static String envelopeOf(String type, String tail) {
    return "{\"id\":\"" + JOB + "\",\"type\":\"" + type + "\"" + tail + "}";
  }

  static List<Case> cases() {
    List<Case> c = new ArrayList<>();
    c.add(ok("ack").handler("ack", null).sig().build());
    c.add(ok("defer 1500 ms rounds up to 2 s").handler("defer", "1500").sig().build());
    c.add(ok("defer 30 s").handler("defer", "30000").sig().build());
    c.add(ok("defer with the default delay").handler("deferDefault", null).sig().build());
    c.add(ok("defer zero").handler("defer", "0").sig().build());
    c.add(ok("reject").handler("reject", "invoice 42 was deleted").sig().build());
    c.add(
        ok("reject reason is cleaned and cut")
            .handler("reject", "bad\r\nInjected: yes " + "x".repeat(1000))
            .sig()
            .build());
    c.add(ok("handler throws").handler("throw", "db password=hunter2 at Orders.java:42").sig().build());
    c.add(ok("handler throws a checked exception").handler("throwChecked", "disk").sig().build());
    c.add(ok("handler exceeds the dispatch timeout").handler("hang", null).sig().build());
    c.add(ok("handler returns null").handler("null", null).sig().build());
    c.add(ok("no handler for the type").handler("orders:shipping:*", "ack", null).sig().build());
    c.add(ok("no handlers at all: 404 before any signature work").sig().build());
    c.add(ok("not ready: 503").handler("ack", null).notReady().sig().build());
    // most specific pattern wins
    for (String type :
        List.of(
            TYPE,
            "orders:billing:invoice:paid",
            "orders:shipping:parcel:sent",
            "ordersx:billing:invoice:paid",
            "orders")) {
      String body = envelopeOf(type, ",\"attemptNumber\":1");
      B b = new B("most specific pattern for " + type).std(type, "clt_1:acme");
      b.handler("*", "reject", "star").handler("orders:*", "reject", "orders");
      b.handler("orders:billing:*", "reject", "billing").handler(TYPE, "reject", "exact");
      b.body(body).sig();
      c.add(b.build());
    }
    // bodies
    c.add(ok("envelope with every optional field").handler("ack", null).sig().build());
    String minimal = envelopeOf(TYPE, ",\"attemptNumber\":1");
    c.add(ok("minimal envelope").handler("ack", null).body(minimal).sig().build());
    c.add(
        ok("envelope with explicit nulls for the optional strings")
            .handler("ack", null)
            .body(envelopeOf(TYPE, ",\"attemptNumber\":1,\"source\":null,\"subject\":null,\"data\":null"))
            .sig()
            .build());
    c.add(
        ok("data-only body is the raw payload")
            .handler("ack", null)
            .body("{\"invoiceId\":\"42\"}")
            .sig()
            .build());
    c.add(ok("data-only body that is not JSON").handler("ack", null).body("plain text, not json").sig().build());
    c.add(ok("data-only empty body").handler("ack", null).body("").sig().build());
    c.add(
        ok("data-only: an envelope-shaped body whose id differs from the header")
            .handler("ack", null)
            .body(ENVELOPE.replace(JOB, "dj_OTHER"))
            .sig()
            .build());
    c.add(
        ok("data-only: a JSON array")
            .handler("ack", null)
            .body("[1,2,3]")
            .sig()
            .build());
    c.add(
        ok("correlation id with a space is replaced")
            .handler("ack", null)
            .body(ENVELOPE.replace("corr-1", "corr 1"))
            .sig()
            .build());
    String head = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\"";
    for (String tail :
        List.of(
            "}",
            ",\"attemptNumber\":\"2\"}",
            ",\"attemptNumber\":0}",
            ",\"attemptNumber\":-1}",
            ",\"attemptNumber\":1.5}",
            ",\"attemptNumber\":1,\"subject\":7}",
            ",\"attemptNumber\":1,\"source\":true}",
            ",\"attemptNumber\":1,\"messageGroup\":[]}",
            ",\"attemptNumber\":1,\"correlationId\":{}}",
            ",\"attemptNumber\":1,\"clientId\":1}",
            ",\"attemptNumber\":1,\"attemptNumber\":2}")) {
      c.add(ok("malformed envelope: " + tail).handler("ack", null).body(head + tail).sig().build());
    }
    // X-FlowCatalyst-Client forms
    for (String client :
        List.of("clt_1:acme", "clt_1", "acme:", ":acme", "a:b:c", "clt 1:acme", "a".repeat(100) + ":" + "b".repeat(100), "a".repeat(101) + ":b")) {
      c.add(
          new B("client header: " + (client.length() > 30 ? client.length() + " characters" : client))
              .std(TYPE, client)
              .handler("ack", null)
              .sig()
              .build());
    }
    c.add(new B("client header absent: a platform-scoped job").std(TYPE, null).handler("ack", null).sig().build());
    c.add(
        ok("client header sent twice")
            .h("X-FlowCatalyst-Client", "clt_2:other")
            .handler("ack", null)
            .sig()
            .build());
    // job headers
    c.add(
        new B("job id header missing")
            .h("X-Event-Type", TYPE)
            .handler("ack", null)
            .sig()
            .build());
    c.add(
        new B("event type header missing")
            .h("X-Dispatch-Job-Id", JOB)
            .handler("ack", null)
            .sig()
            .build());
    c.add(
        new B("job id with a line break")
            .h("X-Dispatch-Job-Id", "dj_1\r\n[main] ERROR forged line")
            .h("X-Event-Type", TYPE)
            .handler("ack", null)
            .sig()
            .build());
    c.add(
        new B("job id over 128 characters")
            .h("X-Dispatch-Job-Id", "j".repeat(129))
            .h("X-Event-Type", TYPE)
            .handler("ack", null)
            .sig()
            .build());
    c.add(
        new B("event type over 255 characters")
            .h("X-Dispatch-Job-Id", JOB)
            .h("X-Event-Type", "e".repeat(256))
            .handler("*", "ack", null)
            .sig()
            .build());
    c.add(
        new B("job id header sent twice")
            .h("X-Dispatch-Job-Id", JOB)
            .h("X-Dispatch-Job-Id", JOB)
            .h("X-Event-Type", TYPE)
            .handler("ack", null)
            .sig()
            .build());
    // content type is not checked
    c.add(
        new B("content type text/plain is still accepted")
            .h("Content-Type", "text/plain")
            .h("X-Dispatch-Job-Id", JOB)
            .h("X-Event-Type", TYPE)
            .h("X-FlowCatalyst-Client", "clt_1:acme")
            .handler("ack", null)
            .sig()
            .build());
    c.add(
        new B("no content type is still accepted")
            .h("X-Dispatch-Job-Id", JOB)
            .h("X-Event-Type", TYPE)
            .handler("ack", null)
            .sig()
            .build());
    // authentication (the full matrix is signature.json)
    c.add(
        ok("bad signature: 401 and the handler never runs")
            .handler("ack", null)
            .h("X-FlowCatalyst-Signature", sign("another-secret", TS, ENVELOPE))
            .h("X-FlowCatalyst-Timestamp", TS)
            .build());
    c.add(ok("unsigned: 401").handler("ack", null).build());
    return c;
  }

  @Test
  void theVectorMatchesWhatTheJavaDispatchReceiverAnswers() throws Exception {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "POST {prefix}/dispatch (flowcatalyst contract section 4 and 4a slice 4). Setup common to every case:"
            + " application 'orders', signing secret '"
            + SECRET
            + "', require-signature true, clock "
            + NOW
            + ", dispatch timeout 200 ms, the service principal available. handlers: the registrations in order"
            + " (pattern; kind = ack | defer (arg = delay in ms) | deferDefault (Defer with no delay) | reject"
            + " (arg = reason) | throw (arg = message) | throwChecked | hang (blocks until interrupted) | null"
            + " (returns null)). ready false = the receivers are answering before start-up finished. headers is an"
            + " ordered list of [name, value]. expect: status; outcome = the FlowCatalyst-Outcome header or null;"
            + " body = the response body as parsed JSON (messageJava on a 400 is Java's wording and is not"
            + " compared: status and error are); handler = the pattern of the registration that ran, or null;"
            + " delivery = what that handler was handed (null when none ran): jobId, eventType, attemptNumber"
            + " (null when data-only), dataOnly, source, subject, messageGroup, data (parsed JSON or null),"
            + " client {id, code} or null, rawBody; contextClient = the client code in the handler's Context"
            + " ('_platform' when the job is platform scoped). Only a 400 body's error and the status are"
            + " compared for malformed input; 401 bodies are in signature.json.");
    doc.put(
        "howToRun",
        "Per case: start the receiver with the setup above, register the handlers (each kind behaves as"
            + " described), POST the headers and body, and compare status, outcome, body, handler, delivery and"
            + " contextClient. Regenerate from Java with -Dconformance.update=true"
            + " (DispatchVectorConformanceTest).");
    doc.put("dispatchTimeoutMs", 200);
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      ArrayNode hs = item.putArray("handlers");
      for (H h : c.handlers()) {
        ObjectNode o = hs.addObject();
        o.put("pattern", h.pattern());
        o.put("kind", h.kind());
        if (h.arg() != null) {
          o.put("arg", h.arg());
        }
      }
      item.put("ready", c.ready());
      ArrayNode headers = item.putArray("headers");
      for (String[] h : c.headers()) {
        headers.addArray().add(h[0]).add(h[1]);
      }
      item.put("body", c.body());
      item.set("expect", run(c));
    }
    JsonVectors.check(FILE, doc);
  }

  /** What one handler saw. */
  record Seen(String pattern, DispatchDelivery delivery, String clientCode) {}

  private ObjectNode run(Case c) throws Exception {
    List<Seen> seen = new CopyOnWriteArrayList<>();
    try (FakePlatform platform = FakePlatform.start()) {
      TestClient client;
      App app = null;
      try {
        if (c.ready()) {
          app =
              Receiving.start(
                  dir,
                  Receiving.env(platform, "FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT", "200ms"),
                  () -> NOW,
                  Receiving.handlers(r -> register(r, c, seen)));
          client = Receiving.client(app);
        } else {
          Receivers receivers = new Receivers();
          register(receivers, c, seen);
          Routes routes = new Routes();
          routes.post(
              "/fc/dispatch",
              new DispatchReceiver(
                  receivers,
                  new ReceiverGate(io.flowcatalyst.framework.config.Secret.of(SECRET), null, true, () -> NOW),
                  new io.flowcatalyst.framework.context.ApplicationCode("orders"),
                  () -> new io.flowcatalyst.framework.context.PrincipalId(FakePlatform.SERVICE_PRINCIPAL),
                  Duration.ofMillis(200),
                  "/fc/dispatch"));
          client = TestClient.of(routes);
        }
        TestClient.Call call = client.post("/fc/dispatch");
        c.headers().forEach(h -> call.header(h[0], h[1]));
        TestResponse r = call.body(c.body()).send();
        Thread.sleep(30);
        ObjectNode expect = JsonVectors.mapper().createObjectNode();
        expect.put("status", r.status());
        String outcome = r.header("FlowCatalyst-Outcome");
        if (outcome == null) {
          expect.putNull("outcome");
        } else {
          expect.put("outcome", outcome);
        }
        JsonNode body = r.tree();
        if (r.status() == 400) {
          ObjectNode b = expect.putObject("body");
          b.put("error", body.path("error").asString());
          expect.put("messageJava", body.path("message").asString());
        } else {
          expect.set("body", body);
        }
        assertThat(seen.size()).as(c.name()).isLessThanOrEqualTo(1);
        if (seen.isEmpty()) {
          expect.putNull("handler");
          expect.putNull("delivery");
          expect.putNull("contextClient");
        } else {
          Seen s = seen.getFirst();
          expect.put("handler", s.pattern());
          expect.set("delivery", delivery(s.delivery()));
          expect.put("contextClient", s.clientCode());
        }
        return expect;
      } finally {
        if (app != null) {
          app.close();
        }
      }
    }
  }

  private static void register(Receivers r, Case c, List<Seen> seen) {
    for (H h : c.handlers()) {
      r.onDispatch(
          h.pattern(),
          (ctx, d) -> {
            seen.add(new Seen(h.pattern(), d, ctx.client().value()));
            return switch (h.kind()) {
              case "ack" -> DispatchResult.ack();
              case "defer" -> DispatchResult.defer(Duration.ofMillis(Long.parseLong(h.arg())));
              case "deferDefault" -> DispatchResult.defer();
              case "reject" -> DispatchResult.reject(h.arg());
              case "throw" -> throw new IllegalStateException(h.arg());
              case "throwChecked" -> throw new java.io.IOException(h.arg());
              case "hang" -> {
                Thread.sleep(30_000);
                yield DispatchResult.ack();
              }
              case "null" -> null;
              default -> throw new IllegalArgumentException(h.kind());
            };
          });
    }
  }

  private static ObjectNode delivery(DispatchDelivery d) {
    ObjectNode n = JsonVectors.mapper().createObjectNode();
    n.put("jobId", d.jobId());
    n.put("eventType", d.eventType());
    if (d.attemptNumber().isPresent()) {
      n.put("attemptNumber", d.attemptNumber().getAsInt());
    } else {
      n.putNull("attemptNumber");
    }
    n.put("dataOnly", d.isDataOnly());
    putOpt(n, "source", d.source().orElse(null));
    putOpt(n, "subject", d.subject().orElse(null));
    putOpt(n, "messageGroup", d.messageGroup().orElse(null));
    JsonNode data = d.data().orElse(null);
    if (data == null) {
      n.putNull("data");
    } else {
      n.set("data", data);
    }
    if (d.client().isPresent()) {
      ObjectNode cl = n.putObject("client");
      cl.put("id", d.client().get().id());
      cl.put("code", d.client().get().code().value());
    } else {
      n.putNull("client");
    }
    n.put("rawBody", new String(d.rawBody(), java.nio.charset.StandardCharsets.UTF_8));
    return n;
  }

  private static void putOpt(ObjectNode n, String key, String value) {
    if (value == null) {
      n.putNull(key);
    } else {
      n.put(key, value);
    }
  }
}
