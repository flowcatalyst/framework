package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.LocksModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-receiver signing secrets and bearer tokens, and their fallback to the shared ones. */
class ReceiverSecretsTest {

  static final String SHARED = Receiving.SECRET;
  static final String DISPATCH = "dispatch-secret-test-only";
  static final String JOBS = "jobs-secret-test-only";

  static final String TYPE = "orders:billing:invoice:created";
  static final String DISPATCH_BODY = "{\"id\":\"dj_1\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1,\"data\":{}}";
  static final String JOB_BODY =
      "{\"jobId\":\"sj_1\",\"jobCode\":\"nightly\",\"instanceId\":\"inst_1\","
          + "\"firedAt\":\"2026-10-09T12:00:00Z\",\"triggerKind\":\"CRON\","
          + "\"tracksCompletion\":false,\"concurrent\":true}";

  static final String SHARED_KEY = "FLOWCATALYST_RECEIVERS_SIGNING_SECRET";
  static final String DISPATCH_KEY = "FLOWCATALYST_RECEIVERS_DISPATCH_SIGNING_SECRET";
  static final String JOBS_KEY = "FLOWCATALYST_RECEIVERS_SCHEDULED_JOBS_SIGNING_SECRET";

  @TempDir Path dir;

  FakePlatform platform;
  App app;

  @BeforeEach
  void setUp() throws IOException {
    platform = FakePlatform.start();
    Files.writeString(dir.resolve("application.yaml"), "locks:\n  instances:\n    local: { driver: memory }\n");
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    platform.close();
  }

  private static final Consumer<Receivers> BOTH =
      r -> {
        r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
        r.onScheduledJob("nightly", (ctx, f) -> JobResult.success());
      };

  private TestClient start(Consumer<Receivers> registrar, String... env) {
    app =
        Receiving.start(
            dir, Receiving.env(platform, env), () -> NOW, new LocksModule(), Receiving.handlers(registrar));
    return Receiving.client(app);
  }

  private StartupException failure(Consumer<Receivers> registrar, String... env) {
    Throwable t = catchThrowable(() -> start(registrar, env));
    app = null;
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  private static TestResponse dispatch(TestClient client, String secret, String... extraHeaders) {
    TestClient.Call call =
        client
            .post("/fc/dispatch")
            .header("Content-Type", "application/json")
            .header("X-Dispatch-Job-Id", "dj_1")
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sign(secret, TS, DISPATCH_BODY))
            .header("X-FlowCatalyst-Timestamp", TS);
    for (int i = 0; i < extraHeaders.length; i += 2) {
      call = call.header(extraHeaders[i], extraHeaders[i + 1]);
    }
    return call.body(DISPATCH_BODY).send();
  }

  private static TestResponse job(TestClient client, String secret, String... extraHeaders) {
    TestClient.Call call =
        client
            .post("/fc/scheduled-jobs")
            .header("Content-Type", "application/json")
            .header("X-FlowCatalyst-Signature", sign(secret, TS, JOB_BODY))
            .header("X-FlowCatalyst-Timestamp", TS);
    for (int i = 0; i < extraHeaders.length; i += 2) {
      call = call.header(extraHeaders[i], extraHeaders[i + 1]);
    }
    return call.body(JOB_BODY).send();
  }

  // ---- secrets -----------------------------------------------------------------------------

  @Test
  void onlyTheSharedSecretServesBothReceivers() {
    TestClient client = start(BOTH);
    assertThat(dispatch(client, SHARED).status()).isEqualTo(200);
    assertThat(job(client, SHARED).status()).isEqualTo(202);
    assertThat(dispatch(client, DISPATCH).status()).isEqualTo(401);
    assertThat(job(client, JOBS).status()).isEqualTo(401);
  }

  @Test
  void aDispatchSecretOverridesTheSharedOneForDispatchOnly() {
    TestClient client = start(BOTH, DISPATCH_KEY, DISPATCH);
    assertThat(dispatch(client, DISPATCH).status()).isEqualTo(200);
    assertThat(dispatch(client, SHARED).status()).isEqualTo(401);
    assertThat(job(client, SHARED).status()).isEqualTo(202);
    assertThat(job(client, DISPATCH).status()).isEqualTo(401);
  }

  @Test
  void aJobsSecretOverridesTheSharedOneForScheduledJobsOnly() {
    TestClient client = start(BOTH, JOBS_KEY, JOBS);
    assertThat(job(client, JOBS).status()).isEqualTo(202);
    assertThat(job(client, SHARED).status()).isEqualTo(401);
    assertThat(dispatch(client, SHARED).status()).isEqualTo(200);
    assertThat(dispatch(client, JOBS).status()).isEqualTo(401);
  }

  @Test
  void bothOwnSecretsNeedNoSharedSecret() {
    TestClient client = start(BOTH, SHARED_KEY, null, DISPATCH_KEY, DISPATCH, JOBS_KEY, JOBS);
    assertThat(dispatch(client, DISPATCH).status()).isEqualTo(200);
    assertThat(job(client, JOBS).status()).isEqualTo(202);
    assertThat(dispatch(client, JOBS).status()).isEqualTo(401);
    assertThat(job(client, DISPATCH).status()).isEqualTo(401);
  }

  @Test
  void aBlankOwnSecretFallsBackToTheSharedOne() {
    TestClient client = start(BOTH, DISPATCH_KEY, "  ", JOBS_KEY, "");
    assertThat(dispatch(client, SHARED).status()).isEqualTo(200);
    assertThat(job(client, SHARED).status()).isEqualTo(202);
  }

  // ---- bearer tokens -----------------------------------------------------------------------

  @Test
  void theSharedBearerTokenAppliesToBothReceivers() {
    TestClient client = start(BOTH, "FLOWCATALYST_RECEIVERS_BEARER_TOKEN", "shared-bearer-test");
    assertThat(dispatch(client, SHARED).status()).isEqualTo(401);
    assertThat(job(client, SHARED).status()).isEqualTo(401);
    assertThat(dispatch(client, SHARED, "Authorization", "Bearer shared-bearer-test").status()).isEqualTo(200);
    assertThat(job(client, SHARED, "Authorization", "Bearer shared-bearer-test").status()).isEqualTo(202);
  }

  @Test
  void aReceiversOwnBearerTokenReplacesTheSharedOneThere() {
    TestClient client =
        start(
            BOTH,
            "FLOWCATALYST_RECEIVERS_BEARER_TOKEN",
            "shared-bearer-test",
            "FLOWCATALYST_RECEIVERS_DISPATCH_BEARER_TOKEN",
            "dispatch-bearer-test");
    assertThat(dispatch(client, SHARED, "Authorization", "Bearer dispatch-bearer-test").status()).isEqualTo(200);
    assertThat(dispatch(client, SHARED, "Authorization", "Bearer shared-bearer-test").status()).isEqualTo(401);
    // scheduled jobs fall back to the shared token
    assertThat(job(client, SHARED, "Authorization", "Bearer shared-bearer-test").status()).isEqualTo(202);
    assertThat(job(client, SHARED, "Authorization", "Bearer dispatch-bearer-test").status()).isEqualTo(401);
  }

  @Test
  void aJobsBearerTokenWorksWithoutASharedOne() {
    TestClient client =
        start(BOTH, "FLOWCATALYST_RECEIVERS_SCHEDULED_JOBS_BEARER_TOKEN", "jobs-bearer-test");
    assertThat(job(client, SHARED).status()).isEqualTo(401);
    assertThat(job(client, SHARED, "Authorization", "Bearer jobs-bearer-test").status()).isEqualTo(202);
    // dispatch has no bearer gate at all
    assertThat(dispatch(client, SHARED).status()).isEqualTo(200);
  }

  // ---- start-up validation -----------------------------------------------------------------

  @Test
  void aReceiverWithHandlersNeedsAnEffectiveSecret() {
    // Dispatch handlers; no shared, no own: names the dispatch key.
    StartupException e =
        failure(
            r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack()),
            SHARED_KEY,
            null,
            JOBS_KEY,
            JOBS);
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactly("flowcatalyst.receivers.dispatch.signing-secret");
    // Scheduled-job handlers; no shared, no own: names the jobs key.
    StartupException j =
        failure(
            r -> r.onScheduledJob("nightly", (ctx, f) -> JobResult.success()),
            SHARED_KEY,
            null,
            DISPATCH_KEY,
            DISPATCH);
    assertThat(j.problems())
        .extracting(Problem::key)
        .containsExactly("flowcatalyst.receivers.scheduled-jobs.signing-secret");
  }

  @Test
  void bothReceiversWithoutSecretsReportBothProblems() {
    StartupException e = failure(BOTH, SHARED_KEY, null);
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder(
            "flowcatalyst.receivers.dispatch.signing-secret",
            "flowcatalyst.receivers.scheduled-jobs.signing-secret");
  }

  @Test
  void aReceiverWithoutHandlersNeedsNoSecretOfItsOwn() {
    // Only dispatch has handlers and its own secret; jobs have no handler and no secret.
    TestClient client =
        start(
            r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack()),
            SHARED_KEY,
            null,
            DISPATCH_KEY,
            DISPATCH);
    assertThat(dispatch(client, DISPATCH).status()).isEqualTo(200);
    TestResponse refused = job(client, DISPATCH);
    assertThat(refused.status()).isEqualTo(401);
    assertThat(refused.tree().get("details").get("reason").asString()).isEqualTo("MISSING_SECRET");
  }

  @Test
  void withRequireSignatureFalseNoSecretIsNeeded() {
    TestClient client = start(BOTH, SHARED_KEY, null, "FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE", "false");
    TestResponse r =
        client
            .post("/fc/dispatch")
            .header("Content-Type", "application/json")
            .header("X-Dispatch-Job-Id", "dj_1")
            .header("X-Event-Type", TYPE)
            .body(DISPATCH_BODY)
            .send();
    assertThat(r.status()).isEqualTo(200);
  }
}
