package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockDriver;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LocksModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /fc/scheduled-jobs}: every response, the overlap lock, timeouts and completion. */
class ScheduledJobReceiverTest {

  static final String CODE = "nightly-export";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final LockKey KEY = LockKey.service("scheduled-job:" + CODE);

  @TempDir Path dir;

  FakePlatform platform;
  LogCapture logs;
  App app;
  final AtomicLong skew = new AtomicLong();
  final MonotonicClock monotonic = () -> System.nanoTime() + skew.get();

  @BeforeEach
  void setUp() throws IOException {
    platform = FakePlatform.start();
    logs = LogCapture.start();
    Files.writeString(dir.resolve("application.yaml"), "locks:\n  instances:\n    local: { driver: memory }\n");
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    platform.close();
    logs.close();
    assertThat(logs.text()).doesNotContain(SECRET);
  }

  private TestClient start(Consumer<Receivers> registrar, String... env) {
    String[] all = new String[env.length + 2];
    all[0] = "FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY";
    all[1] = "10ms";
    System.arraycopy(env, 0, all, 2, env.length);
    app =
        Receiving.start(
            dir,
            Receiving.env(platform, all),
            () -> NOW,
            monotonic,
            new LocksModule(),
            Receiving.handlers(registrar));
    return Receiving.client(app);
  }

  private TestClient start(ScheduledJobHandler handler, String... env) {
    return start(r -> r.onScheduledJob(CODE, handler), env);
  }

  private static String envelope(String instanceId, boolean tracks, Map<String, Object> extra) {
    Map<String, Object> e = envelopeMap(instanceId, tracks);
    e.putAll(extra);
    return JSON.writeValueAsString(e);
  }

  private static Map<String, Object> envelopeMap(String instanceId, boolean tracks) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("jobId", "sj_1");
    e.put("jobCode", CODE);
    e.put("instanceId", instanceId);
    e.put("scheduledFor", "2026-10-09T12:00:00Z");
    e.put("firedAt", "2026-10-09T12:00:00.25Z");
    e.put("triggerKind", "CRON");
    e.put("correlationId", "corr-" + instanceId);
    e.put("tracksCompletion", tracks);
    e.put("concurrent", false);
    return e;
  }

  private static String envelope(String instanceId) {
    return envelope(instanceId, true, Map.of());
  }

  private static TestResponse fire(TestClient client, String body) {
    return client
        .post("/fc/scheduled-jobs")
        .header("Content-Type", "application/json")
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(body)
        .send();
  }

  private void awaitLog(String text) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!logs.text().contains(text) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(logs.text()).contains(text);
  }

  private JsonNode completion(int index) throws InterruptedException {
    List<FakePlatform.Recorded> calls = platform.awaitInstanceCalls("complete", index + 1, 10_000);
    assertThat(calls).hasSizeGreaterThan(index);
    return calls.get(index).json();
  }

  private JsonNode completionOf(String instanceId) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      for (FakePlatform.Recorded r : platform.instanceCalls("complete")) {
        if (r.path().equals("/api/scheduled-jobs/instances/" + instanceId + "/complete")) {
          return r.json();
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("no completion for " + instanceId);
  }

  private LockStore lockStore() {
    return app.get(Locks.class).defaultInstance();
  }

  /** A value Jackson cannot write. */
  public static final class Boom {
    /**
     * Always fails.
     *
     * @return never
     */
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }

  // ---- the response ------------------------------------------------------------------------

  @Test
  void answers202AtOnceAndRunsTheHandlerInTheBackgroundOnAVirtualThread() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Boolean> virtual = new AtomicReference<>();
    TestClient client =
        start(
            (ctx, f) -> {
              virtual.set(Thread.currentThread().isVirtual());
              release.await(10, TimeUnit.SECONDS);
              return JobResult.success();
            });
    TestResponse r = fire(client, envelope("i1"));
    assertThat(r.status()).isEqualTo(202);
    assertThat(r.body()).isEqualTo("{\"ok\":true}");
    assertThat(platform.instanceCalls("complete")).isEmpty(); // still running
    release.countDown();
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    assertThat(virtual.get()).isTrue();
  }

  @Test
  void theContextAndTheFiringCarryTheEnvelope() throws Exception {
    AtomicReference<Context> ctxSeen = new AtomicReference<>();
    AtomicReference<ScheduledJobFiring> seen = new AtomicReference<>();
    TestClient client =
        start(
            (ctx, f) -> {
              ctxSeen.set(ctx);
              seen.set(f);
              return JobResult.success();
            });
    fire(client, envelope("i1", true, Map.of("payload", Map.of("region", "eu"), "timeoutSeconds", 120)));
    completion(0);
    ScheduledJobFiring f = seen.get();
    assertThat(f.jobId()).isEqualTo("sj_1");
    assertThat(f.jobCode()).isEqualTo(CODE);
    assertThat(f.instanceId()).isEqualTo("i1");
    assertThat(f.scheduledFor()).contains(Instant.parse("2026-10-09T12:00:00Z"));
    assertThat(f.firedAt()).isEqualTo(Instant.parse("2026-10-09T12:00:00.25Z"));
    assertThat(f.triggerKind()).isEqualTo(TriggerKind.CRON);
    assertThat(f.payload().orElseThrow().get("region").asString()).isEqualTo("eu");
    assertThat(f.tracksCompletion()).isTrue();
    assertThat(f.timeout()).contains(Duration.ofSeconds(120));
    assertThat(f.concurrent()).isFalse();
    assertThat(f.fencingToken()).isPresent();
    Context ctx = ctxSeen.get();
    assertThat(ctx.client()).isEqualTo(Receivers.PLATFORM_CLIENT);
    assertThat(ctx.application().value()).isEqualTo("orders");
    assertThat(ctx.correlationId().value()).isEqualTo("corr-i1");
    assertThat(ctx.executionId()).isEqualTo("i1");
    // contract section 4b: the service principal acts; the delivered instance is the cause
    assertThat(ctx.principal()).isEqualTo(new PrincipalId(FakePlatform.SERVICE_PRINCIPAL));
    assertThat(ctx.principalKind()).isEqualTo(PrincipalKind.SERVICE);
    assertThat(ctx.causationId()).isEqualTo("i1");
    assertThat(ctx.permissions()).isEmpty();
  }

  @Test
  void withoutTheServicePrincipalTheFiringIs503AndNothingRunsOrIsReported() throws Exception {
    platform.tokenPrincipal("prn_0HUMANUSER001", "USER");
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              calls.incrementAndGet();
              return JobResult.success();
            });
    TestResponse r = fire(client, envelope("i1"));
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAVAILABLE");
    assertThat(r.tree().path("message").asString()).isEqualTo(DispatchReceiver.NO_PRINCIPAL);
    Thread.sleep(100);
    assertThat(calls).hasValue(0);
    assertThat(platform.instanceCalls("complete")).isEmpty();
    assertThat(logs.text()).contains("instance i1").contains("type claim is not SERVICE");
  }

  @Test
  void aPlatformThatIssuesNoTokenGets503ForTheFiringAndTheRedeliveryRuns() throws Exception {
    platform.failTokens(503);
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              calls.incrementAndGet();
              return JobResult.success();
            });
    assertThat(fire(client, envelope("i1")).status()).isEqualTo(503);
    assertThat(calls).hasValue(0);
    platform.failTokens(0);
    assertThat(fire(client, envelope("i1")).status()).isEqualTo(202);
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    assertThat(calls).hasValue(1);
  }

  @Test
  void anUnknownJobIs404EvenWithoutTheServicePrincipal() {
    platform.failTokens(503);
    TestClient client = start(r -> r.onScheduledJob("other-job", (ctx, f) -> JobResult.success()));
    assertThat(fire(client, envelope("i1")).status()).isEqualTo(404);
  }

  @Test
  void anUnknownJobIs404() {
    TestClient client = start(r -> r.onScheduledJob("other-job", (ctx, f) -> JobResult.success()));
    TestResponse r = fire(client, envelope("i1"));
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.tree().get("error").asString()).isEqualTo("NOT_FOUND");
    assertThat(r.tree().get("message").asString()).isEqualTo("no handler for scheduled job " + CODE);
  }

  @Test
  void anUnsignedOrBadlySignedFiringIs401AndNothingRuns() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              return JobResult.success();
            });
    String body = envelope("i1");
    TestResponse unsigned = client.post("/fc/scheduled-jobs").body(body).send();
    TestResponse bad =
        client
            .post("/fc/scheduled-jobs")
            .header("X-FlowCatalyst-Signature", sign("nope", TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    assertThat(unsigned.status()).isEqualTo(401);
    assertThat(bad.status()).isEqualTo(401);
    Thread.sleep(100);
    assertThat(runs).hasValue(0);
    assertThat(platform.instanceCalls("complete")).isEmpty();
  }

  @Test
  void malformedEnvelopesAre400AndNeverReachAHandler() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              return JobResult.success();
            });
    List<String> bodies = new ArrayList<>();
    for (String field : List.of("jobId", "jobCode", "instanceId", "firedAt", "triggerKind", "tracksCompletion")) {
      Map<String, Object> e = envelopeMap("i1", true);
      e.remove(field);
      bodies.add(JSON.writeValueAsString(e));
      e.put(field, 7);
      bodies.add(JSON.writeValueAsString(e));
    }
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("triggerKind", "HOURLY");
    bodies.add(envelope("i1", true, bad));
    bodies.add(envelope("i1", true, Map.of("tracksCompletion", "true")));
    bodies.add(envelope("i1", true, Map.of("concurrent", "no")));
    bodies.add(envelope("i1", true, Map.of("timeoutSeconds", 0)));
    bodies.add(envelope("i1", true, Map.of("timeoutSeconds", 1.5)));
    bodies.add(envelope("i1", true, Map.of("timeoutSeconds", "60")));
    bodies.add(envelope("i1", true, Map.of("firedAt", "yesterday")));
    bodies.add(envelope("i1", true, Map.of("scheduledFor", "soon")));
    bodies.add(envelope("i1", true, Map.of("correlationId", 5)));
    bodies.add(envelope("../../api/admin", true, Map.of()));
    bodies.add(envelope("i1\r\nforged", true, Map.of()));
    bodies.add(envelope("i1", true, Map.of("jobCode", "has space")));
    bodies.add(envelope("i1").replace("{", "{\"jobCode\":\"other\","));
    bodies.add("[]");
    bodies.add("");
    bodies.add("not json");
    for (String body : bodies) {
      TestResponse r = fire(client, body);
      assertThat(r.status()).as(body).isEqualTo(400);
      assertThat(r.tree().get("error").asString()).isEqualTo("VALIDATION");
      assertThat(r.body()).doesNotContain("forged");
    }
    Thread.sleep(100);
    assertThat(runs).hasValue(0);
    assertThat(logs.text()).doesNotContain("forged");
  }

  @Test
  void absentOptionalFieldsAreFine() throws Exception {
    AtomicReference<ScheduledJobFiring> seen = new AtomicReference<>();
    TestClient client =
        start(
            (ctx, f) -> {
              seen.set(f);
              return JobResult.success();
            });
    String minimal =
        "{\"jobId\":\"sj_1\",\"jobCode\":\"" + CODE + "\",\"instanceId\":\"i1\","
            + "\"firedAt\":\"2026-10-09T12:00:00+02:00\",\"triggerKind\":\"MANUAL\",\"tracksCompletion\":true}";
    assertThat(fire(client, minimal).status()).isEqualTo(202);
    completion(0);
    ScheduledJobFiring f = seen.get();
    assertThat(f.firedAt()).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
    assertThat(f.scheduledFor()).isEmpty();
    assertThat(f.payload()).isEmpty();
    assertThat(f.timeout()).isEmpty();
    assertThat(f.concurrent()).isFalse(); // absent means lock, the safe direction
    assertThat(f.fencingToken()).isPresent();
    assertThat(f.triggerKind()).isEqualTo(TriggerKind.MANUAL);
  }

  // ---- completion --------------------------------------------------------------------------

  @Test
  void successIsReportedWithTheResult() throws Exception {
    TestClient client = start((ctx, f) -> JobResult.of(Map.of("rows", 42)));
    fire(client, envelope("i1"));
    JsonNode c = completion(0);
    assertThat(platform.instanceCalls("complete").getFirst().path())
        .isEqualTo("/api/scheduled-jobs/instances/i1/complete");
    assertThat(c.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(c.get("result").get("rows").asInt()).isEqualTo(42);
  }

  @Test
  void successWithoutAResultSendsNoResult() throws Exception {
    TestClient client = start((ctx, f) -> JobResult.success());
    fire(client, envelope("i1"));
    assertThat(completion(0).toString()).isEqualTo("{\"status\":\"SUCCESS\"}");
  }

  @Test
  void aThrowIsFailureWithTheMessage() throws Exception {
    TestClient client =
        start(
            (ctx, f) -> {
              throw new IllegalStateException("export bucket missing");
            });
    fire(client, envelope("i1"));
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).isEqualTo("export bucket missing");
    assertThat(logs.text()).contains("scheduled job " + CODE + " instance i1 failed (correlation corr-i1)");
  }

  @Test
  void aFailureResultAndANullAreFailures() throws Exception {
    TestClient client =
        start(
            r -> {
              r.onScheduledJob(CODE, (ctx, f) -> JobResult.failure("nothing to export"));
              r.onScheduledJob("returns-null", (ctx, f) -> null);
            });
    fire(client, envelope("i1"));
    fire(client, envelope("i2").replace(CODE, "returns-null"));
    assertThat(completionOf("i1").get("result").get("error").asString()).isEqualTo("nothing to export");
    assertThat(completionOf("i2").get("status").asString()).isEqualTo("FAILURE");
  }

  @Test
  void anUntrackedJobIsNeverCompleted() throws Exception {
    CountDownLatch ran = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, f) -> {
              ran.countDown();
              throw new IllegalStateException("x");
            });
    fire(client, envelope("i1", false, Map.of()));
    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    assertThat(platform.instanceCalls("complete")).isEmpty();
  }

  @Test
  void aLongResultIsTruncatedTo10000Characters() throws Exception {
    TestClient client = start((ctx, f) -> JobResult.of("x".repeat(20_000)));
    fire(client, envelope("i1"));
    JsonNode result = completion(0).get("result");
    assertThat(result.get("truncated").asBoolean()).isTrue();
    assertThat(result.get("preview").asString()).hasSize(10_000).startsWith("\"xxx");
  }

  @Test
  void aResultOfExactlyTheLimitIsSentAsIs() {
    String exactly = "x".repeat(JobResult.MAX_RESULT_CHARS - 2); // plus two quotes
    JsonNode node = ScheduledJobRunner.sanitise(exactly);
    assertThat(node.isString()).isTrue();
    assertThat(ScheduledJobRunner.sanitise(exactly + "x").get("truncated").asBoolean()).isTrue();
    assertThat(ScheduledJobRunner.sanitise(new Boom()).get("unserialisable").asBoolean()).isTrue();
    List<Object> cycle = new ArrayList<>();
    cycle.add(cycle);
    assertThat(ScheduledJobRunner.sanitise(cycle).get("unserialisable").asBoolean()).isTrue();
  }

  @Test
  void aFailedCompletionIsRetriedThreeTimes() throws Exception {
    platform.failInstanceCalls("complete", 3, 500);
    TestClient client = start((ctx, f) -> JobResult.success());
    fire(client, envelope("i1"));
    List<FakePlatform.Recorded> calls = platform.awaitInstanceCalls("complete", 4, 10_000);
    Thread.sleep(200);
    assertThat(platform.instanceCalls("complete")).hasSize(4);
    assertThat(calls.getLast().json().get("status").asString()).isEqualTo("SUCCESS");
    assertThat(logs.text()).contains("failed (attempt 3 of 4)").doesNotContain("could not report");
  }

  @Test
  void aCompletionThatKeepsFailingIsLoggedAtErrorAfterFourAttempts() throws Exception {
    platform.failInstanceCalls("complete", 100, 500);
    TestClient client = start((ctx, f) -> JobResult.success());
    fire(client, envelope("i1"));
    platform.awaitInstanceCalls("complete", 4, 10_000);
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!logs.text().contains("could not report SUCCESS") && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(logs.text()).contains("could not report SUCCESS for scheduled job " + CODE + " instance i1");
    assertThat(platform.instanceCalls("complete")).hasSize(4);
  }

  @Test
  void theSdksOwnRetriesDoNotMultiplyOurs() throws Exception {
    // 408, 429, 502, 503 and 504 are retried inside every SDK call; reports must not be.
    int total = 0;
    for (int status : new int[] {408, 429, 502, 503, 504}) {
      platform.failInstanceCalls("complete", 100, status);
      TestClient client = start((ctx, f) -> JobResult.success());
      String id = "i" + status;
      fire(client, envelope(id));
      total += 1 + ScheduledJobRunner.COMPLETION_RETRIES;
      platform.awaitInstanceCalls("complete", total, 10_000);
      String reported = "could not report SUCCESS for scheduled job " + CODE + " instance " + id;
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (!logs.text().contains(reported) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(logs.text()).as("status %d", status).contains(reported);
      Thread.sleep(100);
      assertThat(platform.instanceCalls("complete")).as("status %d", status).hasSize(total);
      app.close();
    }
    assertThat(total).isEqualTo(20);
  }

  @Test
  void aFailingLogLineIsOneCallEvenForARetryableStatus() throws Exception {
    platform.failInstanceCalls("log", 100, 503);
    TestClient client =
        start(
            (ctx, f) -> {
              f.log("one line");
              return JobResult.success();
            });
    fire(client, envelope("i1"));
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    Thread.sleep(100);
    assertThat(platform.instanceCalls("log")).hasSize(1);
  }

  @Test
  void theLogHookPostsToTheInstanceLogAndNeverThrows() throws Exception {
    platform.failInstanceCalls("log", 1, 500);
    TestClient client =
        start(
            (ctx, f) -> {
              f.log(ScheduledJobFiring.LogLevel.WARN, "first line fails on the platform");
              f.log("second line");
              return JobResult.success();
            });
    fire(client, envelope("i1"));
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    List<FakePlatform.Recorded> lines = platform.instanceCalls("log");
    assertThat(lines).hasSize(2);
    assertThat(lines.get(1).path()).isEqualTo("/api/scheduled-jobs/instances/i1/log");
    assertThat(lines.get(1).json().get("level").asString()).isEqualTo("INFO");
    assertThat(lines.get(1).json().get("message").asString()).isEqualTo("second line");
    assertThat(lines.get(0).json().get("level").asString()).isEqualTo("WARN");
  }

  // ---- timeouts ----------------------------------------------------------------------------

  @Test
  void theEnvelopeTimeoutInterruptsTheHandlerAndReportsFailure() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, f) -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              return JobResult.success();
            });
    fire(client, envelope("i1", true, Map.of("timeoutSeconds", 1)));
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).isEqualTo("timed out after 1s");
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void withoutAnEnvelopeTimeoutTheSettingApplies() throws Exception {
    TestClient client =
        start(
            (ctx, f) -> {
              Thread.sleep(60_000);
              return JobResult.success();
            },
            "FLOWCATALYST_RECEIVERS_JOB_TIMEOUT",
            "300ms");
    long started = System.nanoTime();
    fire(client, envelope("i1"));
    JsonNode c = completion(0);
    assertThat(c.get("result").get("error").asString()).isEqualTo("timed out after 300ms");
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
  }

  @Test
  void aTimedOutHandlerThatIgnoresTheInterruptKeepsTheLockUntilItStops() throws Exception {
    CountDownLatch stop = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              if (f.instanceId().equals("i1")) {
                // Ignores interrupts: only the latch stops it.
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (stop.getCount() > 0 && System.nanoTime() < deadline) {
                  Thread.onSpinWait();
                }
              }
              return JobResult.success();
            },
            "FLOWCATALYST_RECEIVERS_JOB_TIMEOUT",
            "200ms");
    fire(client, envelope("i1"));
    assertThat(completionOf("i1").get("result").get("error").asString()).startsWith("timed out");
    // Timed out and reported, but still running: the lock stays held, so the next firing skips.
    fire(client, envelope("i2"));
    assertThat(completionOf("i2").get("result").get("reason").asString()).isEqualTo("lock-held");
    assertThat(lockStore().tryAcquire(KEY, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Busy.class);
    stop.countDown();
    awaitLockFree();
    fire(client, envelope("i3"));
    assertThat(completionOf("i3").get("status").asString()).isEqualTo("SUCCESS");
    assertThat(runs).hasValue(2);
  }

  private void awaitLockFree() throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (lockStore().tryAcquire(KEY, Duration.ofSeconds(1)) instanceof AcquireResult.Acquired a) {
        lockStore().release(a.lease());
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("the overlap lock was never released");
  }

  // ---- the overlap lock --------------------------------------------------------------------

  @Test
  void aHeldLockSkipsTheRunAndReportsLockHeld() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              return JobResult.success();
            });
    AcquireResult held = lockStore().tryAcquire(KEY, Duration.ofMinutes(1));
    assertThat(held).isInstanceOf(AcquireResult.Acquired.class);
    assertThat(fire(client, envelope("i1")).status()).isEqualTo(202);
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").toString()).isEqualTo("{\"skipped\":true,\"reason\":\"lock-held\"}");
    assertThat(runs).hasValue(0);
    assertThat(logs.text()).contains("instance i1 skipped: another run holds its lock");
  }

  @Test
  void aHeldLockSkipsAnUntrackedRunSilently() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              return JobResult.success();
            });
    lockStore().tryAcquire(KEY, Duration.ofMinutes(1));
    fire(client, envelope("i1", false, Map.of()));
    Thread.sleep(200);
    assertThat(runs).hasValue(0);
    assertThat(platform.instanceCalls("complete")).isEmpty();
  }

  @Test
  void ofTenFiringsAtOnceExactlyOneRuns() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    TestClient client =
        start(
            (ctx, f) -> {
              runs.incrementAndGet();
              maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
              try {
                release.await(10, TimeUnit.SECONDS);
              } finally {
                concurrent.decrementAndGet();
              }
              return JobResult.success();
            });
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String id = "i" + i;
      threads.add(Thread.ofVirtual().start(() -> assertThat(fire(client, envelope(id)).status()).isEqualTo(202)));
    }
    for (Thread t : threads) {
      t.join();
    }
    List<FakePlatform.Recorded> skipped = platform.awaitInstanceCalls("complete", 9, 10_000);
    assertThat(skipped).hasSize(9);
    assertThat(skipped).allSatisfy(r -> assertThat(r.json().get("result").get("reason").asString()).isEqualTo("lock-held"));
    release.countDown();
    List<FakePlatform.Recorded> all = platform.awaitInstanceCalls("complete", 10, 10_000);
    assertThat(all.getLast().json().get("status").asString()).isEqualTo("SUCCESS");
    assertThat(runs).hasValue(1);
    assertThat(maxConcurrent).hasValue(1);
  }

  @Test
  void theLockIsHeldWhileRunningAndReleasedAfterwardsWithAGrowingFencingToken() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<Long> tokens = new java.util.concurrent.CopyOnWriteArrayList<>();
    TestClient client =
        start(
            (ctx, f) -> {
              tokens.add(f.fencingToken().orElseThrow());
              if (f.instanceId().equals("i1")) {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
              }
              return JobResult.success();
            });
    fire(client, envelope("i1"));
    assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(lockStore().tryAcquire(KEY, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Busy.class);
    release.countDown();
    completionOf("i1");
    awaitLockFree();
    fire(client, envelope("i2"));
    completionOf("i2");
    assertThat(tokens).hasSize(2);
    assertThat(tokens.get(1)).isGreaterThan(tokens.get(0));
  }

  @Test
  void aConcurrentJobTakesNoLock() throws Exception {
    AtomicReference<ScheduledJobFiring> seen = new AtomicReference<>();
    TestClient client =
        start(
            (ctx, f) -> {
              seen.set(f);
              return JobResult.success();
            });
    lockStore().tryAcquire(KEY, Duration.ofMinutes(1));
    fire(client, envelope("i1", true, Map.of("concurrent", true)));
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    assertThat(seen.get().fencingToken()).isEmpty();
  }

  @Test
  void aLostLockInterruptsTheHandlerAndReportsFailure() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, f) -> {
              inside.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              return JobResult.success();
            },
            "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE",
            "1s");
    fire(client, envelope("i1"));
    assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
    // The lease expires on the store's clock: the next refresh (every ~333 ms) finds it lost.
    skew.addAndGet(Duration.ofSeconds(5).toNanos());
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).isEqualTo("the overlap lock was lost while the job ran");
    assertThat(logs.text()).contains("lost its overlap lock while running");
  }

  @Test
  void theLeaseIsRefreshedSoALongRunKeepsTheLock() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, f) -> {
              release.await(10, TimeUnit.SECONDS);
              return JobResult.success();
            },
            "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE",
            "1s");
    fire(client, envelope("i1"));
    Thread.sleep(2_500); // well past the 1 s lease
    assertThat(lockStore().tryAcquire(KEY, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Busy.class);
    release.countDown();
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void aLockStoreThatIsDownMeansTheJobDoesNotRun() throws Exception {
    Files.writeString(dir.resolve("application.yaml"), "locks:\n  instances:\n    down: { driver: broken }\n");
    AtomicInteger runs = new AtomicInteger();
    Module broken =
        new Module() {
          @Override
          public String id() {
            return "broken-locks";
          }

          @Override
          public void register(AppBuilder builder) {
            builder
                .drivers()
                .register(
                    LockStore.class,
                    "broken",
                    (name, config, lookup, built) ->
                        new LockStore(new DownDriver(), lookup.get(MonotonicClock.class)));
          }
        };
    app =
        Receiving.start(
            dir,
            Receiving.env(platform, "FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY", "10ms"),
            () -> NOW,
            new LocksModule(),
            broken,
            Receiving.handlers(
                r ->
                    r.onScheduledJob(
                        CODE,
                        (ctx, f) -> {
                          runs.incrementAndGet();
                          return JobResult.success();
                        })));
    fire(Receiving.client(app), envelope("i1"));
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).isEqualTo("the overlap lock could not be taken; the job did not run");
    assertThat(runs).hasValue(0);
  }

  /** A lock store whose backend is unreachable. */
  static final class DownDriver implements LockDriver {
    @Override
    public OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis) {
      throw new LockStoreUnavailableException("down", new java.io.IOException("connection refused"));
    }

    @Override
    public boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis) {
      throw new LockStoreUnavailableException("down", new java.io.IOException("connection refused"));
    }

    @Override
    public boolean release(String key, String ownerToken, long fencingToken) {
      throw new LockStoreUnavailableException("down", new java.io.IOException("connection refused"));
    }
  }

  // ---- always-concurrent jobs (no lock instance needed) --------------------------------------

  @Test
  void anAlwaysConcurrentJobNeedsNoLockInstanceAndRunsWithoutOne() throws Exception {
    Files.delete(dir.resolve("application.yaml"));
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            r ->
                r.onScheduledJob(
                    CODE,
                    (ctx, f) -> {
                      runs.incrementAndGet();
                      return JobResult.success();
                    },
                    JobOptions.alwaysConcurrent()));
    assertThat(fire(client, envelope("i1", true, Map.of("concurrent", true))).status()).isEqualTo(202);
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    assertThat(runs).hasValue(1);
  }

  @Test
  void anAlwaysConcurrentJobTakesNoLockEvenWhenOneIsConfigured() throws Exception {
    AtomicReference<ScheduledJobFiring> seen = new AtomicReference<>();
    TestClient client =
        start(
            r ->
                r.onScheduledJob(
                    CODE,
                    (ctx, f) -> {
                      seen.set(f);
                      return JobResult.success();
                    },
                    JobOptions.alwaysConcurrent()));
    lockStore().tryAcquire(KEY, Duration.ofMinutes(1));
    fire(client, envelope("i1", true, Map.of("concurrent", true)));
    assertThat(completion(0).get("status").asString()).isEqualTo("SUCCESS");
    assertThat(seen.get().fencingToken()).isEmpty();
  }

  @Test
  void anExclusiveFiringForAnAlwaysConcurrentJobIsRefusedAndReported() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            r ->
                r.onScheduledJob(
                    CODE,
                    (ctx, f) -> {
                      runs.incrementAndGet();
                      return JobResult.success();
                    },
                    JobOptions.alwaysConcurrent()));
    // concurrent=false, and an absent concurrent means false too
    assertThat(fire(client, envelope("i1")).status()).isEqualTo(202);
    JsonNode c = completionOf("i1");
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").toString())
        .isEqualTo("{\"skipped\":true,\"reason\":\"not-registered-for-exclusive-run\"}");
    assertThat(runs).hasValue(0);
    awaitLog("instance i1 was not run");
    assertThat(logs.text()).contains("always-concurrent");
  }

  @Test
  void anExclusiveFiringForAnUntrackedAlwaysConcurrentJobIsRefusedWithoutAReport() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        start(
            r ->
                r.onScheduledJob(
                    CODE,
                    (ctx, f) -> {
                      runs.incrementAndGet();
                      return JobResult.success();
                    },
                    JobOptions.alwaysConcurrent()));
    assertThat(fire(client, envelope("i1", false, Map.of())).status()).isEqualTo(202);
    awaitLog("instance i1 was not run");
    assertThat(runs).hasValue(0);
    assertThat(platform.awaitInstanceCalls("complete", 1, 300)).isEmpty();
  }

  @Test
  void onlyExclusiveJobsNeedTheLockAtStartUp() throws IOException {
    Files.delete(dir.resolve("application.yaml"));
    Throwable t =
        catchThrowable(
            () ->
                start(
                    r -> {
                      r.onScheduledJob("free", (ctx, f) -> JobResult.success(), JobOptions.alwaysConcurrent());
                      r.onScheduledJob("guarded", (ctx, f) -> JobResult.success());
                    }));
    assertThat(t)
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("[guarded]")
        .hasMessageNotContaining("free")
        .hasMessageContaining("JobOptions.alwaysConcurrent()");
    app = null;
  }

  // ---- start-up and shutdown ---------------------------------------------------------------

  @Test
  void aJobHandlerWithoutALockInstanceFailsStartUp() throws IOException {
    Files.delete(dir.resolve("application.yaml"));
    Throwable withoutInstances = catchThrowable(() -> start((ctx, f) -> JobResult.success()));
    assertThat(withoutInstances).isInstanceOf(StartupException.class).hasMessageContaining("no lock instance");
    Throwable withoutModule =
        catchThrowable(
            () ->
                Receiving.start(
                        dir,
                        Receiving.env(platform),
                        () -> NOW,
                        Receiving.handlers(r -> r.onScheduledJob(CODE, (ctx, f) -> JobResult.success())))
                    .close());
    assertThat(withoutModule).isInstanceOf(StartupException.class).hasMessageContaining("no lock instance");
    app = null;
  }

  @Test
  void aDuplicateOrMalformedJobCodeFailsStartUp() {
    Throwable dup =
        catchThrowable(
            () ->
                start(
                    r -> {
                      r.onScheduledJob(CODE, (ctx, f) -> JobResult.success());
                      r.onScheduledJob(CODE, (ctx, f) -> JobResult.success());
                    }));
    assertThat(dup).isInstanceOf(StartupException.class).hasMessageContaining("already registered");
    Throwable bad = catchThrowable(() -> start(r -> r.onScheduledJob("bad code", (ctx, f) -> JobResult.success())));
    assertThat(bad).isInstanceOf(StartupException.class).hasMessageContaining("malformed");
    Throwable tooLong =
        catchThrowable(() -> start(r -> r.onScheduledJob("x".repeat(187), (ctx, f) -> JobResult.success())));
    assertThat(tooLong).isInstanceOf(StartupException.class).hasMessageContaining("malformed");
    app = null;
  }

  @Test
  void shutdownInterruptsARunningJobAndReportsIt() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, f) -> {
              inside.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              return JobResult.success();
            });
    fire(client, envelope("i1"));
    assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
    app.close();
    app = null;
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    JsonNode c = completion(0);
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).contains("stopping");
  }
}
