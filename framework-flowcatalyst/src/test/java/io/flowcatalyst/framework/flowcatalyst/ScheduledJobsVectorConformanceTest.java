package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStores;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LocksModule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/receivers/scheduled-jobs.json} (flowcatalyst contract
 * section 4 and 4a slice 5, follow-ups 3 and 4): what {@code POST /fc/scheduled-jobs} answers, what
 * the handler is handed, and which completion calls reach the platform. Java writes it; Go compares.
 */
class ScheduledJobsVectorConformanceTest {

  static final String FILE = "receivers/scheduled-jobs.json";
  static final String CODE = "nightly-export";
  static final LockKey KEY = LockKey.service("scheduled-job:" + CODE);

  @TempDir Path dir;

  /**
   * One case. handler kinds: success, result (arg = JSON), failure (arg = message), throw (arg =
   * message), throwBlank (a throw with no message), null, hang (blocks until interrupted),
   * longString (arg = length: a string result of that many characters), unserialisable (a result
   * whose JSON cannot be written). lock: free | held (taken by someone else) | down (the store is
   * unreachable) | none (no lock instance) | lost (the lease expires while running). options:
   * exclusive | alwaysConcurrent.
   */
  record Case(
      String name,
      String handler,
      String arg,
      String options,
      String lock,
      Map<String, String> env,
      int completionFailures,
      int completionCalls,
      boolean sign,
      Map<String, Object> envelope,
      String rawBody) {}

  static final class B {
    final String name;
    String handler = "success";
    String arg = null;
    String options = "exclusive";
    String lock = "free";
    final Map<String, String> env = new LinkedHashMap<>();
    int completionFailures = 0;
    int completionCalls = 0;
    boolean sign = true;
    Map<String, Object> envelope = ScheduledJobsVectorConformanceTest.envelope("i1", true, false);
    String rawBody = null;

    B(String name) {
      this.name = name;
    }

    B handler(String kind, String arg) {
      this.handler = kind;
      this.arg = arg;
      return this;
    }

    B options(String o) {
      options = o;
      return this;
    }

    B lock(String l) {
      lock = l;
      return this;
    }

    B env(String k, String v) {
      env.put(k, v);
      return this;
    }

    B failCompletions(int n) {
      completionFailures = n;
      return this;
    }

    B calls(int n) {
      completionCalls = n;
      return this;
    }

    B unsigned() {
      sign = false;
      return this;
    }

    B with(String key, Object value) {
      envelope.put(key, value);
      return this;
    }

    B without(String key) {
      envelope.remove(key);
      return this;
    }

    B raw(String body) {
      rawBody = body;
      return this;
    }

    B envelope(Map<String, Object> e) {
      envelope = e;
      return this;
    }

    Case build() {
      return new Case(name, handler, arg, options, lock, env, completionFailures, completionCalls, sign, envelope, rawBody);
    }
  }

  static Map<String, Object> envelope(String instanceId, boolean tracks, boolean concurrent) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("jobId", "sj_1");
    e.put("jobCode", CODE);
    e.put("instanceId", instanceId);
    e.put("scheduledFor", "2026-10-09T12:00:00Z");
    e.put("firedAt", "2026-10-09T12:00:00.25Z");
    e.put("triggerKind", "CRON");
    e.put("correlationId", "corr-" + instanceId);
    e.put("tracksCompletion", tracks);
    e.put("concurrent", concurrent);
    return e;
  }

  static Map<String, Object> minimal() {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("jobId", "sj_1");
    e.put("jobCode", CODE);
    e.put("instanceId", "i1");
    e.put("firedAt", "2026-10-09T12:00:00+02:00");
    e.put("triggerKind", "MANUAL");
    e.put("tracksCompletion", true);
    return e;
  }

  static List<Case> cases() {
    List<Case> c = new ArrayList<>();
    // body and handed-over firing
    c.add(new B("every optional field present").with("payload", Map.of("region", "eu")).with("timeoutSeconds", 120).calls(1).build());
    c.add(new B("every optional field absent").envelope(minimal()).calls(1).build());
    c.add(new B("concurrent true: no lock, no fencing token").with("concurrent", true).calls(1).build());
    c.add(new B("concurrent absent means exclusive").without("concurrent").calls(1).build());
    c.add(new B("payload null counts as absent").with("payload", null).calls(1).build());
    c.add(new B("correlation id with a space is replaced").with("correlationId", "corr 1").calls(1).build());
    c.add(new B("untracked job runs and reports nothing").with("tracksCompletion", false).build());
    // completion bodies
    c.add(new B("result is reported").handler("result", "{\"rows\":42}").calls(1).build());
    c.add(new B("success without a result sends none").calls(1).build());
    c.add(new B("failure result").handler("failure", "nothing to export").calls(1).build());
    c.add(new B("throw reports the message").handler("throw", "export bucket missing").calls(1).build());
    c.add(new B("throw with no message reports the class name").handler("throwBlank", null).calls(1).build());
    c.add(new B("null result is a failure").handler("null", null).calls(1).build());
    c.add(new B("untracked throw reports nothing").handler("throw", "x").with("tracksCompletion", false).build());
    c.add(new B("result of 20000 characters is truncated").handler("longString", "20000").calls(1).build());
    c.add(new B("result of exactly 10000 JSON characters is sent as is").handler("longString", "9998").calls(1).build());
    c.add(new B("result of 10001 JSON characters is truncated").handler("longString", "9999").calls(1).build());
    c.add(new B("unserialisable result").handler("unserialisable", null).calls(1).build());
    // completion retry
    c.add(new B("completion failing three times is retried: four calls").failCompletions(3).calls(4).build());
    c.add(new B("completion failing for ever: four calls then give up").failCompletions(100).calls(4).build());
    // timeouts
    c.add(new B("envelope timeout").handler("hang", null).with("timeoutSeconds", 1).calls(1).build());
    c.add(new B("job-timeout setting applies without an envelope timeout").handler("hang", null).env("FLOWCATALYST_RECEIVERS_JOB_TIMEOUT", "300ms").calls(1).build());
    // lock
    c.add(new B("lock held: skipped, lock-held").lock("held").calls(1).build());
    c.add(new B("lock held, untracked: skipped silently").lock("held").with("tracksCompletion", false).build());
    c.add(new B("lock store down: the job does not run").lock("down").calls(1).build());
    c.add(new B("lock lost while running").lock("lost").handler("hang", null).env("FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "1s").calls(1).build());
    c.add(new B("concurrent job with the lock held runs").lock("held").with("concurrent", true).calls(1).build());
    // always concurrent
    c.add(new B("always-concurrent, exclusive firing is refused").options("alwaysConcurrent").calls(1).build());
    c.add(new B("always-concurrent, exclusive firing, untracked: refused without a report").options("alwaysConcurrent").with("tracksCompletion", false).build());
    c.add(new B("always-concurrent, concurrent firing runs").options("alwaysConcurrent").with("concurrent", true).calls(1).build());
    c.add(new B("always-concurrent without any lock instance").options("alwaysConcurrent").lock("none").with("concurrent", true).calls(1).build());
    // routing and authentication
    c.add(new B("unknown job code: 404").with("jobCode", "other-job").build());
    c.add(new B("bad signature: 401").unsigned().build());
    // validation
    for (String field : List.of("jobId", "jobCode", "instanceId", "firedAt", "triggerKind", "tracksCompletion")) {
      c.add(new B("invalid: " + field + " missing").without(field).build());
      c.add(new B("invalid: " + field + " is a number").with(field, 7).build());
    }
    c.add(new B("invalid: triggerKind HOURLY").with("triggerKind", "HOURLY").build());
    c.add(new B("invalid: triggerKind lower case").with("triggerKind", "cron").build());
    c.add(new B("invalid: tracksCompletion is a string").with("tracksCompletion", "true").build());
    c.add(new B("invalid: concurrent is a string").with("concurrent", "no").build());
    c.add(new B("invalid: timeoutSeconds 0").with("timeoutSeconds", 0).build());
    c.add(new B("invalid: timeoutSeconds 1.5").with("timeoutSeconds", 1.5).build());
    c.add(new B("invalid: timeoutSeconds is a string").with("timeoutSeconds", "60").build());
    c.add(new B("invalid: firedAt yesterday").with("firedAt", "yesterday").build());
    c.add(new B("invalid: scheduledFor soon").with("scheduledFor", "soon").build());
    c.add(new B("invalid: correlationId is a number").with("correlationId", 5).build());
    c.add(new B("invalid: instanceId is a path").with("instanceId", "../../api/admin").build());
    c.add(new B("invalid: instanceId has a line break").with("instanceId", "i1\r\nforged").build());
    c.add(new B("invalid: instanceId over 128 characters").with("instanceId", "i".repeat(129)).build());
    c.add(new B("invalid: jobCode has a space").with("jobCode", "has space").build());
    c.add(new B("invalid: duplicate key").raw(rawEnvelope().replace("{", "{\"jobCode\":\"other\",")).build());
    c.add(new B("invalid: a JSON array").raw("[]").build());
    c.add(new B("invalid: empty body").raw("").build());
    c.add(new B("invalid: not JSON").raw("not json").build());
    c.add(new B("valid: firedAt with an offset").with("firedAt", "2026-10-09T12:00:00+02:00").calls(1).build());
    return c;
  }

  static String rawEnvelope() {
    return JsonVectors.mapper().writeValueAsString(envelope("i1", true, false));
  }

  static String bodyOf(Case c) {
    return c.rawBody() != null ? c.rawBody() : JsonVectors.mapper().writeValueAsString(c.envelope());
  }

  @Test
  void theVectorMatchesWhatTheJavaScheduledJobReceiverDoes() throws Exception {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "POST {prefix}/scheduled-jobs (flowcatalyst contract section 4 and 4a slice 5, follow-ups 3 and 4)."
            + " Setup common to every case: application 'orders', signing secret '"
            + SECRET
            + "', clock "
            + NOW
            + ", one job handler registered under code '"
            + CODE
            + "', a lock instance (unless lock says otherwise), the service principal available, the platform"
            + " answering completion calls 204, the completion retry delay 10 ms. handler kinds: success; result"
            + " (arg = JSON the handler returns); failure (arg = message); throw (arg = message); throwBlank (throws"
            + " with no message); null (returns null); hang (blocks until interrupted); longString (arg = N: returns a"
            + " string of N 'x'); unserialisable (a result whose JSON cannot be written). options: exclusive (the"
            + " default) or alwaysConcurrent. lock: free | held (another owner holds scheduled-job:"
            + CODE
            + " with a long lease) | down (the lock store is unreachable) | none (no lock instance) | lost (the lease"
            + " expires, on the monotonic clock, while the handler runs). env overrides the receivers settings"
            + " (job-timeout, job-lock-lease). completionFailures: the first N completion calls are answered 500."
            + " completionCalls: how many completion calls the platform receives in total. The request body is"
            + " the JSON of 'envelope' (or rawBody when present), signed unless sign is false. expect: status and"
            + " body (parsed JSON; messageJava on a 400 is Java's wording and is not compared); handlerRuns; firing"
            + " (what the handler was handed, null when it did not run: fencingTokenPresent, timeoutSeconds,"
            + " contextClient, executionId, causationId, correlationId ('<generated>' = any non-blank value other than"
            + " the envelope's)); completions = the calls the platform received in order, each {path, body}. A"
            + " 400 or 404 or 401 runs nothing. The call is answered 202 before the handler finishes; completions are"
            + " read after they arrive.");
    doc.put(
        "howToRun",
        "Per case: start the receiver with the setup above, put the lock in the state named, POST the signed body,"
            + " wait for completionCalls completion requests (and, when 0, 300 ms of quiet), compare status, body,"
            + " handlerRuns, firing and completions. Regenerate from Java with -Dconformance.update=true"
            + " (ScheduledJobsVectorConformanceTest).");
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      item.put("handler", c.handler());
      if (c.arg() != null) {
        item.put("arg", c.arg());
      }
      item.put("options", c.options());
      item.put("lock", c.lock());
      ObjectNode env = item.putObject("env");
      c.env().forEach(env::put);
      item.put("completionFailures", c.completionFailures());
      item.put("completionCalls", c.completionCalls());
      item.put("sign", c.sign());
      if (c.rawBody() != null) {
        item.put("rawBody", c.rawBody());
      } else {
        item.set("envelope", JsonVectors.mapper().valueToTree(c.envelope()));
      }
      item.set("expect", run(c));
    }
    JsonVectors.check(FILE, doc);
  }

  /** What the handler was handed. */
  record Seen(ScheduledJobFiring firing, Context ctx) {}

  private ObjectNode run(Case c) throws Exception {
    List<Seen> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch inside = new CountDownLatch(1);
    AtomicLong skew = new AtomicLong();
    MonotonicClock monotonic = () -> System.nanoTime() + skew.get();
    LocksModule locks =
        switch (c.lock()) {
          case "down" ->
              LocksModule.builder()
                  .store("down", (name, lookup) -> new LockStore(new ScheduledJobReceiverTest.DownDriver(), lookup.get(MonotonicClock.class)))
                  .build();
          case "none" -> LocksModule.builder().build();
          default -> LocksModule.builder().store("local", LockStores.memory()).build();
        };
    try (FakePlatform platform = FakePlatform.start()) {
      if (c.completionFailures() > 0) {
        platform.failInstanceCalls("complete", c.completionFailures(), 500);
      }
      List<String> env = new ArrayList<>();
      env.add("FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY");
      env.add("10ms");
      c.env().forEach((k, v) -> {
        env.add(k);
        env.add(v);
      });
      JobOptions options = c.options().equals("alwaysConcurrent") ? JobOptions.alwaysConcurrent() : JobOptions.exclusive();
      try (App app =
          Receiving.start(
              dir,
              Receiving.env(platform, env.toArray(new String[0])),
              () -> NOW,
              monotonic,
              locks,
              Receiving.handlers(
                  r ->
                      r.onScheduledJob(
                          CODE,
                          (ctx, f) -> {
                            seen.add(new Seen(f, ctx));
                            inside.countDown();
                            return behave(c);
                          },
                          options)))) {
        if (c.lock().equals("held") || c.lock().equals("lost")) {
          // "lost" needs the lock to exist and be refreshed; "held" is taken before the firing.
        }
        if (c.lock().equals("held")) {
          LockStore store = app.get(Locks.class).defaultInstance();
          assertThat(store.tryAcquire(KEY, Duration.ofMinutes(5))).isInstanceOf(AcquireResult.Acquired.class);
        }
        TestClient client = Receiving.client(app);
        String body = bodyOf(c);
        TestClient.Call call = client.post("/fc/scheduled-jobs").header("Content-Type", "application/json");
        if (c.sign()) {
          call.header("X-FlowCatalyst-Signature", sign(SECRET, TS, body)).header("X-FlowCatalyst-Timestamp", TS);
        }
        TestResponse r = call.body(body).send();
        if (c.lock().equals("lost")) {
          assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
          skew.addAndGet(Duration.ofSeconds(5).toNanos());
        }
        List<FakePlatform.Recorded> calls;
        if (c.completionCalls() > 0) {
          platform.awaitInstanceCalls("complete", c.completionCalls(), 10_000);
        }
        Thread.sleep(c.completionCalls() > 0 ? 150 : 300);
        calls = platform.instanceCalls("complete");
        assertThat(calls).as(c.name()).hasSize(c.completionCalls());

        ObjectNode expect = JsonVectors.mapper().createObjectNode();
        expect.put("status", r.status());
        JsonNode rb = r.tree();
        if (r.status() == 400) {
          expect.putObject("body").put("error", rb.path("error").asString());
          expect.put("messageJava", rb.path("message").asString());
        } else {
          expect.set("body", rb);
        }
        expect.put("handlerRuns", seen.size());
        if (seen.isEmpty()) {
          expect.putNull("firing");
        } else {
          expect.set("firing", firing(seen.getFirst(), body));
        }
        ArrayNode completions = expect.putArray("completions");
        for (FakePlatform.Recorded rec : calls) {
          ObjectNode o = completions.addObject();
          o.put("path", rec.path());
          o.set("body", rec.json());
        }
        return expect;
      }
    }
  }

  private static JobResult behave(Case c) throws Exception {
    return switch (c.handler()) {
      case "success" -> JobResult.success();
      case "result" -> JobResult.of(JsonVectors.mapper().readTree(c.arg()));
      case "failure" -> JobResult.failure(c.arg());
      case "throw" -> throw new IllegalStateException(c.arg());
      case "throwBlank" -> throw new IllegalStateException();
      case "null" -> null;
      case "hang" -> {
        Thread.sleep(60_000);
        yield JobResult.success();
      }
      case "longString" -> JobResult.of("x".repeat(Integer.parseInt(c.arg())));
      case "unserialisable" -> JobResult.of(new ScheduledJobReceiverTest.Boom());
      default -> throw new IllegalArgumentException(c.handler());
    };
  }

  private static ObjectNode firing(Seen s, String body) {
    ScheduledJobFiring f = s.firing();
    ObjectNode n = JsonVectors.mapper().createObjectNode();
    n.put("jobId", f.jobId());
    n.put("jobCode", f.jobCode());
    n.put("instanceId", f.instanceId());
    if (f.scheduledFor().isPresent()) {
      n.put("scheduledFor", f.scheduledFor().get().toString());
    } else {
      n.putNull("scheduledFor");
    }
    n.put("firedAt", f.firedAt().toString());
    n.put("triggerKind", f.triggerKind().name());
    if (f.payload().isPresent()) {
      n.set("payload", f.payload().get());
    } else {
      n.putNull("payload");
    }
    n.put("tracksCompletion", f.tracksCompletion());
    if (f.timeout().isPresent()) {
      n.put("timeoutSeconds", f.timeout().get().toSeconds());
    } else {
      n.putNull("timeoutSeconds");
    }
    n.put("concurrent", f.concurrent());
    n.put("fencingTokenPresent", f.fencingToken().isPresent());
    n.put("contextClient", s.ctx().client().value());
    n.put("executionId", s.ctx().executionId());
    n.put("causationId", s.ctx().causationId());
    String correlation = s.ctx().correlationId().value();
    n.put("correlationId", body.contains("\"correlationId\":\"" + correlation + "\"") ? correlation : "<generated>");
    return n;
  }
}
