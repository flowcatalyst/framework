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
  final AtomicReference<DispatchDelivery> seenDelivery = new AtomicReference<>();

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
                        seenDelivery.set(d);
                        return DispatchResult.ack();
                      });
                  r.onScheduledJob(
                      "nightly",
                      (ctx, f) -> {
                        seenJob.set(f.instanceId() + "/" + f.correlationId().value());
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
    while (!seenJob.get().startsWith("inst_1") && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(seenJob.get()).startsWith("inst_1/");
  }

  @Test
  void theSignatureIsTheOneTheFrameworksOwnTestsUse() {
    assertThat(ReceiverTesting.signature(SECRET, Receiving.TS, BODY)).isEqualTo(Receiving.sign(SECRET, Receiving.TS, BODY));
    assertThat(ReceiverTesting.timestamp(NOW)).isEqualTo(String.valueOf(NOW.getEpochSecond()));
  }

  static final String ITEM =
      "{\"id\":\"0HZXEQ5Y8JY5Z\",\"kind\":\"TASK\",\"code\":\"" + TYPE + "\",\"source\":\"orders\","
          + "\"targetUrl\":\"https://orders.example.com/fc/dispatch\",\"payload\":\"{\\\"invoiceId\\\":\\\"42\\\"}\","
          + "\"payloadContentType\":\"application/json\",\"dataOnly\":false,\"correlationId\":\"corr-9\","
          + "\"messageGroup\":\"invoice:42\",\"clientCode\":\"acme\"}";

  @Test
  void aJobFromTheOutboxIsDeliveredAsThePlatformDeliversIt() {
    ReceiverTesting.Delivery d = ReceiverTesting.Delivery.ofJob(ITEM);
    assertThat(d.jobId()).isEqualTo("0HZXEQ5Y8JY5Z");
    assertThat(d.eventType()).isEqualTo(TYPE);
    assertThat(d.clientHeader()).isEqualTo("clt_acme:acme");

    TestResponse r = ReceiverTesting.deliver(client, SECRET, NOW, d);

    assertThat(r.status()).isEqualTo(200);
    assertThat(seenClient).hasValue("acme");
    DispatchDelivery got = seenDelivery.get();
    assertThat(got.isDataOnly()).isFalse();
    assertThat(got.attemptNumber()).hasValue(1);
    assertThat(got.correlationId().value()).isEqualTo("corr-9");
    assertThat(got.messageGroup()).contains("invoice:42");
    assertThat(got.data().orElseThrow().get("invoiceId").asString()).isEqualTo("42");
  }

  @Test
  void aPlatformScopedJobHasNoClientHeader() {
    ReceiverTesting.Delivery d = ReceiverTesting.Delivery.ofJob(ITEM.replace(",\"clientCode\":\"acme\"", ""));
    assertThat(d.clientHeader()).isNull();
    assertThat(d.body()).doesNotContain("clientCode").doesNotContain("clientId");

    assertThat(ReceiverTesting.deliver(client, SECRET, NOW, d).status()).isEqualTo(200);
    assertThat(seenClient).hasValue(Receivers.PLATFORM_CLIENT.value());
  }

  @Test
  void aDataOnlyJobIsDeliveredAsItsPayloadAlone() {
    ReceiverTesting.Delivery d = ReceiverTesting.Delivery.ofJob(ITEM.replace("\"dataOnly\":false", "\"dataOnly\":true"));
    assertThat(d.body()).isEqualTo("{\"invoiceId\":\"42\"}");

    assertThat(ReceiverTesting.deliver(client, SECRET, NOW, d).status()).isEqualTo(200);
    assertThat(seenDelivery.get().isDataOnly()).isTrue();
    assertThat(seenClient).hasValue("acme");
  }

  @Test
  void anEventRowIsDeliveredAsTheSubscriptionsJob() {
    String event =
        "{\"specVersion\":\"1.0\",\"type\":\"" + TYPE + "\",\"source\":\"orders:billing\","
            + "\"subject\":\"billing.invoice.42\",\"data\":\"{\\\"invoiceId\\\":\\\"42\\\"}\","
            + "\"correlationId\":\"corr-e\",\"deduplicationId\":\"0EVT\",\"messageGroup\":\"billing:invoice:42\","
            + "\"clientCode\":\"globex\"}";
    ReceiverTesting.Delivery d = ReceiverTesting.Delivery.ofEvent(event, "dj_7");
    assertThat(d.clientHeader()).isEqualTo("clt_globex:globex");

    assertThat(ReceiverTesting.deliver(client, SECRET, NOW, d).status()).isEqualTo(200);
    assertThat(seenClient).hasValue("globex");
    assertThat(seenDelivery.get().jobId()).isEqualTo("dj_7");
    assertThat(seenDelivery.get().subject()).contains("billing.invoice.42");
    assertThat(seenDelivery.get().data().orElseThrow().get("invoiceId").asString()).isEqualTo("42");
  }

  @Test
  void anEventOfThePlatformsContextNamesNoClient() {
    String event = "{\"type\":\"" + TYPE + "\",\"data\":\"{}\",\"clientCode\":\"_platform\"}";
    assertThat(ReceiverTesting.Delivery.ofEvent(event, "dj_8").clientHeader()).isNull();
  }

  @Test
  void aDeliverySignedWithTheWrongSecretIsRefused() {
    assertThat(ReceiverTesting.deliver(client, "not-the-secret", NOW, ReceiverTesting.Delivery.ofJob(ITEM)).status())
        .isEqualTo(401);
    assertThat(seenClient).hasValue("none");
  }

  @Test
  void aFiringIsSignedAndCarriesTheJobAndInstance() throws Exception {
    assertThat(ReceiverTesting.firingBody(NOW, "nightly", "inst_9"))
        .isEqualTo(
            "{\"jobId\":\"sj_1\",\"jobCode\":\"nightly\",\"instanceId\":\"inst_9\","
                + "\"scheduledFor\":\"2026-10-09T12:00:00Z\",\"firedAt\":\"2026-10-09T12:00:00Z\","
                + "\"triggerKind\":\"CRON\",\"correlationId\":\"corr-inst_9\",\"tracksCompletion\":true,"
                + "\"concurrent\":false}");

    assertThat(ReceiverTesting.fire(client, SECRET, NOW, "nightly", "inst_9").status()).isEqualTo(202);
    // accepted and tracked: the instance named in the body is reported (this handler takes only
    // concurrent firings, so it is reported without running)
    assertThat(platform.awaitInstanceCalls("complete", 1, 10_000))
        .extracting(FakePlatform.Recorded::path)
        .containsExactly("/api/scheduled-jobs/instances/inst_9/complete");
    assertThat(ReceiverTesting.fire(client, "not-the-secret", NOW, "nightly", "inst_10").status()).isEqualTo(401);
  }
}
