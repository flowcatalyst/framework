package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The public helper an application's tests use to deliver signed requests to the receivers. */
class ReceiverTestingTest {

  static final String TYPE = "orders:billing:invoice:created";
  static final String BODY = "{\"id\":\"dj_1\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1,\"data\":{\"invoiceId\":\"42\"}}";
  static final String JOB =
      "{\"jobId\":\"sj_1\",\"jobCode\":\"nightly\",\"instanceId\":\"inst_1\",\"firedAt\":\"2026-10-09T12:00:00Z\","
          + "\"triggerKind\":\"CRON\",\"tracksCompletion\":false,\"concurrent\":true}";

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;
  final AtomicReference<String> seenClient = new AtomicReference<>("none");
  final AtomicReference<String> seenJob = new AtomicReference<>("none");

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    app =
        Receiving.start(
            dir,
            Receiving.env(platform),
            () -> NOW,
            Receiving.handlers(
                r -> {
                  r.onDispatch(
                      TYPE,
                      (ctx, d) -> {
                        seenClient.set(ctx.client().value());
                        return DispatchResult.ack();
                      });
                  r.onScheduledJob(
                      "nightly",
                      (ctx, f) -> {
                        seenJob.set(f.instanceId());
                        return JobResult.success();
                      },
                      JobOptions.alwaysConcurrent());
                }));
    client = Receiving.client(app);
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  @Test
  void aSignedDispatchIsAcceptedAsTheClientItNames() {
    TestResponse r = ReceiverTesting.dispatch(client, SECRET, NOW, "dj_1", TYPE, "clt_1:acme", BODY);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"ack\":true}");
    assertThat(seenClient).hasValue("acme");
  }

  @Test
  void aPlatformScopedDispatchNamesNoClient() {
    TestResponse r = ReceiverTesting.dispatch(client, SECRET, NOW, "dj_1", TYPE, null, BODY);

    assertThat(r.status()).isEqualTo(200);
    assertThat(seenClient).hasValue(Receivers.PLATFORM_CLIENT.value());
  }

  @Test
  void aWrongSecretIsRefusedAndNoHandlerRuns() {
    TestResponse r = ReceiverTesting.dispatch(client, "not-the-secret", NOW, "dj_1", TYPE, "clt_1:acme", BODY);

    assertThat(r.status()).isEqualTo(401);
    assertThat(seenClient).hasValue("none");
  }

  @Test
  void aSignedFiringIsAcceptedAndRunsInTheBackground() throws Exception {
    TestResponse r = ReceiverTesting.scheduledJob(client, SECRET, NOW, JOB);

    assertThat(r.status()).isEqualTo(202);
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!"inst_1".equals(seenJob.get()) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(seenJob).hasValue("inst_1");
  }

  @Test
  void theSignatureIsTheOneTheFrameworksOwnTestsUse() {
    assertThat(ReceiverTesting.signature(SECRET, Receiving.TS, BODY)).isEqualTo(Receiving.sign(SECRET, Receiving.TS, BODY));
    assertThat(ReceiverTesting.timestamp(NOW)).isEqualTo(String.valueOf(NOW.getEpochSecond()));
  }
}
