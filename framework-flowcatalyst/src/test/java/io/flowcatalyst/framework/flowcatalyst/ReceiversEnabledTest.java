package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.LockStores;
import io.flowcatalyst.framework.locks.LocksModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which receiver routes answer: only those with a handler, and none when receivers are disabled. */
class ReceiversEnabledTest {

  static final String TYPE = "orders:billing:invoice:created";
  static final String DISPATCH_BODY =
      "{\"id\":\"dj_1\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1,\"data\":{}}";
  static final String JOB_BODY =
      "{\"jobId\":\"sj_1\",\"jobCode\":\"nightly\",\"instanceId\":\"inst_1\","
          + "\"firedAt\":\"2026-10-09T12:00:00Z\",\"triggerKind\":\"CRON\","
          + "\"tracksCompletion\":false,\"concurrent\":true}";

  private static final Consumer<Receivers> DISPATCH_ONLY =
      r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
  private static final Consumer<Receivers> JOBS_ONLY =
      r -> r.onScheduledJob("nightly", (ctx, f) -> JobResult.success());
  private static final Consumer<Receivers> BOTH =
      r -> {
        DISPATCH_ONLY.accept(r);
        JOBS_ONLY.accept(r);
      };

  @TempDir Path dir;

  FakePlatform platform;
  LogCapture logs;
  App app;

  @BeforeEach
  void setUp() throws IOException {
    platform = FakePlatform.start();
    logs = LogCapture.start();
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    platform.close();
    logs.close();
  }

  private TestClient start(Consumer<Receivers> registrar, String... env) {
    return start(registrar, UnaryOperator.identity(), env);
  }

  private TestClient start(Consumer<Receivers> registrar, UnaryOperator<ReceiverOptions> options, String... env) {
    app =
        Receiving.start(
            dir,
            Receiving.env(platform, env),
            () -> NOW,
            MonotonicClock.system(),
            Receiving.receivers(options),
            LocksModule.builder().store("local", LockStores.memory()).build(),
            Receiving.handlers(registrar));
    return Receiving.client(app);
  }

  private static TestResponse dispatch(TestClient client) {
    return client
        .post("/fc/dispatch")
        .header("Content-Type", "application/json")
        .header("X-Dispatch-Job-Id", "dj_1")
        .header("X-Event-Type", TYPE)
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, DISPATCH_BODY))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(DISPATCH_BODY)
        .send();
  }

  private static TestResponse job(TestClient client) {
    return client
        .post("/fc/scheduled-jobs")
        .header("Content-Type", "application/json")
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, JOB_BODY))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(JOB_BODY)
        .send();
  }

  private static TestResponse unsignedProbe(TestClient client, String path) {
    return client.post(path).header("Content-Type", "application/json").body("{}").send();
  }

  @Test
  void bothReceiversAnswerWhenBothHaveHandlers() {
    TestClient client = start(BOTH);
    assertThat(dispatch(client).status()).isEqualTo(200);
    assertThat(job(client).status()).isEqualTo(202);
  }

  @Test
  void aReceiverWithoutHandlersIsNotFoundEvenForAnUnsignedProbe() {
    TestClient client = start(DISPATCH_ONLY);
    assertThat(dispatch(client).status()).isEqualTo(200);
    TestResponse probe = unsignedProbe(client, "/fc/scheduled-jobs");
    assertThat(probe.status()).isEqualTo(404);
    assertThat(probe.tree().get("error").asString()).isEqualTo("NOT_FOUND");

    TestClient jobsOnly = start(JOBS_ONLY);
    assertThat(unsignedProbe(jobsOnly, "/fc/dispatch").status()).isEqualTo(404);
  }

  @Test
  void anApplicationWithNoHandlersAnswersNotFoundOnBoth() {
    TestClient client = start(r -> {});
    assertThat(unsignedProbe(client, "/fc/dispatch").status()).isEqualTo(404);
    assertThat(unsignedProbe(client, "/fc/scheduled-jobs").status()).isEqualTo(404);
  }

  @Test
  void disabledReceiversMountNothingEvenWithHandlers() {
    TestClient client = start(BOTH, o -> o.withEnabled(false));
    assertThat(dispatch(client).status()).isEqualTo(404);
    assertThat(job(client).status()).isEqualTo(404);
    // A route that does not exist has no Allow header either (an existing one would answer 405).
    assertThat(client.get("/fc/dispatch").send().status()).isEqualTo(404);
    assertThat(logs.text()).contains("handlers are registered").contains("disabled (ReceiverOptions)");
  }

  @Test
  void disabledReceiversNeedNoSecretAndNoWarningWithoutHandlers() {
    TestClient client =
        start(r -> {}, o -> o.withEnabled(false), "FLOWCATALYST_RECEIVERS_SIGNING_SECRET", null);
    assertThat(dispatch(client).status()).isEqualTo(404);
    assertThat(logs.text()).doesNotContain("handlers are registered");
    assertThat(logs.text()).contains("receiver routes are not mounted");
  }

  @Test
  void disabledReceiversSkipTheSecretCheckThatHandlersWouldOtherwiseFail() {
    // With handlers and no secret, enabled receivers fail start-up (DispatchReceiverTest); disabled ones start.
    TestClient client =
        start(
            DISPATCH_ONLY,
            o -> ReceiverOptions.disabled(),
            "FLOWCATALYST_RECEIVERS_SIGNING_SECRET",
            null);
    assertThat(unsignedProbe(client, "/fc/dispatch").status()).isEqualTo(404);
  }
}
