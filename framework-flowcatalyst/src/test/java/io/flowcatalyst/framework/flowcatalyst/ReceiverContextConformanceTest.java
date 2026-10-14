package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/receivers/context.json} (flowcatalyst contract section 4b):
 * for a delivery and the token the platform issues to the application, the {@code Context} the
 * receiver hands its handler, or the answer when it runs none. Java writes it; Go and Rust compare.
 */
class ReceiverContextConformanceTest {

  static final String FILE = "receivers/context.json";
  static final String DISPATCH_TYPE = "orders:billing:invoice:created";
  static final String JOB_CODE = "nightly-export";
  static final String GENERATED = "<generated>";

  @TempDir Path dir;

  /** How the fake platform's token endpoint answers. */
  record Token(String sub, String type, boolean opaque, int failStatus) {
    static Token service() {
      return new Token(FakePlatform.SERVICE_PRINCIPAL, "SERVICE", false, 0);
    }

    static Token claims(String sub, String type) {
      return new Token(sub, type, false, 0);
    }

    static Token opaqueToken() {
      return new Token(null, null, true, 0);
    }

    static Token failing(int status) {
      return new Token(null, null, false, status);
    }

    ObjectNode json() {
      ObjectNode node = JsonVectors.mapper().createObjectNode();
      if (failStatus != 0) {
        node.put("tokenEndpointStatus", failStatus);
      } else if (opaque) {
        node.put("opaque", true);
      } else {
        ObjectNode claims = node.putObject("claims");
        if (sub != null) {
          claims.put("sub", sub);
        }
        if (type != null) {
          claims.put("type", type);
        }
      }
      return node;
    }
  }

  /** One delivery: to which receiver, with which headers and body, under which token. */
  record Case(String name, String receiver, Map<String, String> headers, String body, Token token) {}

  static Case dispatch(String name, String type, String client, String body, Token token) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Dispatch-Job-Id", "dj_0HZXEQ5Y8JY5Z");
    h.put("X-Event-Type", type);
    if (client != null) {
      h.put("X-FlowCatalyst-Client", client);
    }
    h.put("X-FlowCatalyst-Signature", sign(SECRET, TS, body));
    h.put("X-FlowCatalyst-Timestamp", TS);
    return new Case(name, "dispatch", h, body, token);
  }

  static Case firing(String name, String body, Token token) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-FlowCatalyst-Signature", sign(SECRET, TS, body));
    h.put("X-FlowCatalyst-Timestamp", TS);
    return new Case(name, "scheduled-job", h, body, token);
  }

  static final String ENVELOPE =
      "{\"id\":\"dj_0HZXEQ5Y8JY5Z\",\"type\":\"" + DISPATCH_TYPE + "\",\"attemptNumber\":3,"
          + "\"correlationId\":\"corr-1\",\"clientId\":\"clt_1\",\"clientCode\":\"acme\",\"data\":{\"n\":1}}";

  static final String FIRING =
      "{\"jobId\":\"sj_1\",\"jobCode\":\"" + JOB_CODE + "\",\"instanceId\":\"sji_0HZXEQ5Y8JY5Z\","
          + "\"firedAt\":\"2026-10-09T12:00:00Z\",\"triggerKind\":\"CRON\",\"correlationId\":\"corr-2\","
          + "\"tracksCompletion\":false,\"concurrent\":true}";

  static List<Case> cases() {
    List<Case> c = new ArrayList<>();
    c.add(dispatch("dispatch envelope with a client", DISPATCH_TYPE, "clt_1:acme", ENVELOPE, Token.service()));
    c.add(dispatch("dispatch data-only body, no client header", DISPATCH_TYPE, null, "{\"n\":1}", Token.service()));
    c.add(
        dispatch(
            "dispatch envelope whose correlation id has a space",
            DISPATCH_TYPE,
            "clt_1:acme",
            ENVELOPE.replace("corr-1", "corr 1"),
            Token.service()));
    c.add(firing("scheduled-job firing", FIRING, Token.service()));
    c.add(
        dispatch(
            "token for a user: no handler runs",
            DISPATCH_TYPE,
            "clt_1:acme",
            ENVELOPE,
            Token.claims("prn_0HUMANUSER001", "USER")));
    c.add(
        dispatch(
            "token without sub: no handler runs", DISPATCH_TYPE, "clt_1:acme", ENVELOPE, Token.claims(null, "SERVICE")));
    c.add(
        dispatch(
            "token without type: no handler runs",
            DISPATCH_TYPE,
            "clt_1:acme",
            ENVELOPE,
            Token.claims(FakePlatform.SERVICE_PRINCIPAL, null)));
    c.add(dispatch("opaque token: no handler runs", DISPATCH_TYPE, "clt_1:acme", ENVELOPE, Token.opaqueToken()));
    c.add(dispatch("token endpoint refuses: no handler runs", DISPATCH_TYPE, "clt_1:acme", ENVELOPE, Token.failing(401)));
    c.add(firing("scheduled job, token for a user: not accepted", FIRING, Token.claims("prn_0HUMANUSER001", "USER")));
    c.add(
        dispatch(
            "no handler for the type: rejected without asking for the principal",
            "orders:billing:invoice:voided",
            "clt_1:acme",
            ENVELOPE.replace("invoice:created", "invoice:voided"),
            Token.failing(503)));
    return c;
  }

  @Test
  void theVectorMatchesTheContextsTheJavaReceiversBuild() throws Exception {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The Context a FlowCatalyst receiver hands its handler (flowcatalyst contract section 4b), or the answer"
            + " when it runs none. Setup: application 'orders'; a dispatch handler for "
            + DISPATCH_TYPE
            + " only; a scheduled-job handler for "
            + JOB_CODE
            + " registered always-concurrent; signing secret and clock below. token: what the platform's"
            + " token endpoint answers the application's client-credentials request: a JWT with these"
            + " claims (other claims and the signature are irrelevant), an opaque string, or an error"
            + " status. expect.context is null when no handler ran. correlationId "
            + GENERATED
            + " means generated: any non-blank value other than the envelope's.");
    doc.put("application", "orders");
    doc.put("signingSecret", SECRET);
    doc.put("now", NOW.toString());
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      item.put("receiver", c.receiver());
      item.set("token", c.token().json());
      ObjectNode req = item.putObject("request");
      req.put("method", "POST");
      req.put("path", "/fc/" + (c.receiver().equals("dispatch") ? "dispatch" : "scheduled-jobs"));
      ObjectNode rh = req.putObject("headers");
      c.headers().forEach(rh::put);
      req.put("body", c.body());
      item.set("expect", run(c));
    }
    JsonVectors.check(FILE, doc);
  }

  private ObjectNode run(Case c) throws Exception {
    AtomicReference<Context> seen = new AtomicReference<>();
    CountDownLatch ran = new CountDownLatch(1);
    try (FakePlatform platform = FakePlatform.start()) {
      Token t = c.token();
      if (t.failStatus() != 0) {
        platform.failTokens(t.failStatus());
      } else if (t.opaque()) {
        platform.opaqueTokens(true);
      } else {
        platform.tokenPrincipal(t.sub(), t.type());
      }
      try (App app =
          Receiving.start(
              dir,
              Receiving.env(platform),
              () -> NOW,
              Receiving.handlers(
                  r -> {
                    r.onDispatch(
                        DISPATCH_TYPE,
                        (ctx, d) -> {
                          seen.set(ctx);
                          ran.countDown();
                          return DispatchResult.ack();
                        });
                    r.onScheduledJob(
                        JOB_CODE,
                        (ctx, f) -> {
                          seen.set(ctx);
                          ran.countDown();
                          return JobResult.success();
                        },
                        JobOptions.alwaysConcurrent());
                  }))) {
        TestClient client = Receiving.client(app);
        TestClient.Call call =
            client.post(c.receiver().equals("dispatch") ? "/fc/dispatch" : "/fc/scheduled-jobs");
        c.headers().forEach(call::header);
        TestResponse response = call.body(c.body()).send();
        ObjectNode expect = JsonVectors.mapper().createObjectNode();
        expect.put("status", response.status());
        if (response.status() >= 400) {
          expect.put("error", response.tree().path("error").asString());
          expect.put("message", response.tree().path("message").asString());
        }
        if (response.status() < 300) {
          assertThat(ran.await(10, TimeUnit.SECONDS)).as(c.name()).isTrue();
        } else {
          Thread.sleep(50);
          assertThat(ran.getCount()).as(c.name()).isEqualTo(1);
        }
        Context ctx = seen.get();
        if (ctx == null) {
          expect.putNull("context");
        } else {
          expect.set("context", context(ctx, c.body()));
        }
        return expect;
      }
    }
  }

  private static ObjectNode context(Context ctx, String body) {
    ObjectNode node = JsonVectors.mapper().createObjectNode();
    node.put("client", ctx.client().value());
    node.put("principal", ctx.principal() == null ? null : ctx.principal().value());
    node.put("principalKind", ctx.principalKind() == null ? null : ctx.principalKind().name());
    node.put("application", ctx.application() == null ? null : ctx.application().value());
    String correlation = ctx.correlationId().value();
    boolean fromEnvelope = body.contains("\"correlationId\":\"" + correlation + "\"");
    node.put("correlationId", fromEnvelope ? correlation : GENERATED);
    node.put("causationId", ctx.causationId());
    node.put("executionId", ctx.executionId());
    ArrayNode permissions = node.putArray("permissions");
    new TreeSet<>(ctx.permissions()).forEach(permissions::add);
    return node;
  }
}
