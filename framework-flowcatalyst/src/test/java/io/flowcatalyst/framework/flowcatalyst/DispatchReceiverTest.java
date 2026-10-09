package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every response of {@code POST /fc/dispatch} (flowcatalyst contract, section 4). */
class DispatchReceiverTest {

  static final String TYPE = "orders:billing:invoice:created";
  static final String JOB = "dj_0HZXEQ5Y8JY5Z";
  static final String ENVELOPE =
      """
      {"id":"dj_0HZXEQ5Y8JY5Z","type":"orders:billing:invoice:created","attemptNumber":2,\
      "source":"orders:billing","subject":"billing.invoice.42","correlationId":"corr-1",\
      "messageGroup":"billing:invoice:42","clientId":"clt_1","clientCode":"acme",\
      "data":{"invoiceId":"42","amount":"10.00"}}""";

  @TempDir Path dir;

  FakePlatform platform;
  LogCapture logs;
  App app;

  @BeforeEach
  void setUp() {
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
    // No test may ever put the signing secret in a log line.
    assertThat(logs.text()).doesNotContain(SECRET);
  }

  private TestClient start(Consumer<Receivers> registrar, String... env) {
    app = Receiving.start(dir, Receiving.env(platform, env), () -> NOW, Receiving.handlers(registrar));
    return Receiving.client(app);
  }

  private TestClient start(DispatchHandler handler, String... env) {
    return start(r -> r.onDispatch(TYPE, handler), env);
  }

  /** A signed delivery as the platform sends it. */
  private static TestResponse deliver(TestClient client, String body) {
    return client
        .post("/fc/dispatch")
        .header("Content-Type", "application/json")
        .header("X-Dispatch-Job-Id", JOB)
        .header("X-Event-Type", TYPE)
        .header("X-FlowCatalyst-Client", "clt_1:acme")
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(body)
        .send();
  }

  private static TestResponse deliver(TestClient client) {
    return deliver(client, ENVELOPE);
  }

  private static TestClient.Call unsigned(TestClient client, String type) {
    return client
        .post("/fc/dispatch")
        .header("Content-Type", "application/json")
        .header("X-Dispatch-Job-Id", JOB)
        .header("X-Event-Type", type);
  }

  // ---- results -----------------------------------------------------------------------------

  @Test
  void ackIs200WithAckTrue() {
    TestResponse r = deliver(start((ctx, d) -> DispatchResult.ack()));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).startsWith("application/json");
    assertThat(r.body()).isEqualTo("{\"ack\":true}");
  }

  @Test
  void deferIs200WithAckFalseAndTheDefaultDelay() {
    TestResponse r = deliver(start((ctx, d) -> DispatchResult.defer()));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"ack\":false,\"delaySeconds\":30}");
  }

  @Test
  void deferRoundsTheDelayUpToWholeSeconds() {
    TestResponse r = deliver(start((ctx, d) -> DispatchResult.defer(Duration.ofMillis(1500))));
    assertThat(r.body()).isEqualTo("{\"ack\":false,\"delaySeconds\":2}");
    assertThat(new DispatchResult.Defer(Duration.ZERO).delaySeconds()).isZero();
    assertThat(catchThrowable(() -> DispatchResult.defer(Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(catchThrowable(() -> DispatchResult.defer(Duration.ofSeconds(4_294_967_296L))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectIs422WithTheOutcomeHeaderAndTheReason() {
    TestResponse r = deliver(start((ctx, d) -> DispatchResult.reject("invoice 42 was deleted")));
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.header("FlowCatalyst-Outcome")).isEqualTo("reject");
    assertThat(r.tree().get("error").asString()).isEqualTo("REJECTED");
    assertThat(r.tree().get("message").asString()).isEqualTo("invoice 42 was deleted");
  }

  @Test
  void aRejectReasonCannotSplitALineOrGrowWithoutBound() {
    DispatchResult.Reject r = new DispatchResult.Reject("bad\r\nInjected: yes " + "x".repeat(1000));
    assertThat(r.reason()).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ");
    assertThat(r.reason()).hasSize(DispatchResult.MAX_REASON_LENGTH).startsWith("bad  Injected: yes ");
    assertThat(catchThrowable(() -> DispatchResult.reject(" "))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aThrowingHandlerIs503AndTheResponseCarriesNoneOfItsInternals() {
    TestResponse r =
        deliver(
            start(
                (ctx, d) -> {
                  throw new IllegalStateException("db password=hunter2 at Orders.java:42");
                }));
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAVAILABLE");
    assertThat(r.body()).doesNotContain("hunter2").doesNotContain("Orders.java").doesNotContain("IllegalState");
    // Logged for the operator, with the job id and the correlation id.
    assertThat(logs.text())
        .contains("dispatch handler for " + TYPE + " failed (job " + JOB + ", correlation corr-1)")
        .contains("IllegalStateException");
  }

  @Test
  void aCheckedExceptionIsAlso503() {
    TestResponse r =
        deliver(
            start(
                (ctx, d) -> {
                  throw new java.io.IOException("disk");
                }));
    assertThat(r.status()).isEqualTo(503);
  }

  @Test
  void aHandlerPastTheTimeoutIsInterruptedAnd503() throws InterruptedException {
    CountDownLatch interrupted = new CountDownLatch(1);
    TestClient client =
        start(
            (ctx, d) -> {
              try {
                Thread.sleep(30_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              return DispatchResult.ack();
            },
            "FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT",
            "200ms");
    long started = System.nanoTime();
    TestResponse r = deliver(client);
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().get("message").asString()).contains("did not finish in time");
    assertThat(tookMs).isLessThan(5_000);
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(logs.text()).contains("did not finish within PT0.2S (job " + JOB + ", correlation corr-1)");
  }

  @Test
  void aNullResultIs503() {
    TestResponse r = deliver(start((ctx, d) -> null));
    assertThat(r.status()).isEqualTo(503);
  }

  @Test
  void noHandlerForTheTypeIsATerminalReject() {
    TestClient client = start(r -> r.onDispatch("orders:shipping:*", (ctx, d) -> DispatchResult.ack()));
    TestResponse r = deliver(client);
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.header("FlowCatalyst-Outcome")).isEqualTo("reject");
    assertThat(r.tree().get("message").asString()).isEqualTo("no handler for " + TYPE);
  }

  // ---- authentication ----------------------------------------------------------------------

  @Test
  void aBadSignatureIs401AndTheHandlerNeverRuns() {
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
            (ctx, d) -> {
              calls.incrementAndGet();
              return DispatchResult.ack();
            });
    TestResponse r =
        unsigned(client, TYPE)
            .header("X-FlowCatalyst-Signature", sign("another-secret", TS, ENVELOPE))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(r.tree().get("details").get("reason").asString()).isEqualTo("INVALID_SIGNATURE");
    assertThat(calls).hasValue(0);
    assertThat(logs.text()).contains("refused a delivery to /fc/dispatch: INVALID_SIGNATURE");
  }

  @Test
  void aSignatureOverAReserialisedBodyIs401() {
    // Signed over the compact form, sent pretty-printed: the bytes differ, so it must fail.
    String compact = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1}";
    String pretty = "{ \"id\": \"" + JOB + "\", \"type\": \"" + TYPE + "\", \"attemptNumber\": 1 }";
    TestResponse r =
        unsigned(start((ctx, d) -> DispatchResult.ack()), TYPE)
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, compact))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(pretty)
            .send();
    assertThat(r.status()).isEqualTo(401);
  }

  @Test
  void theHandlerSeesExactlyTheBytesThatWereVerified() {
    AtomicReference<DispatchDelivery> seen = new AtomicReference<>();
    String odd =
        "{ \"type\" : \"" + TYPE + "\",\n \"id\":\"" + JOB + "\", \"attemptNumber\" : 1,"
            + " \"data\" : {\"name\":\"Zo\\u00eb\", \"city\":\"Zürich\", \"n\": 1.50} }";
    deliver(
        start(
            (ctx, d) -> {
              seen.set(d);
              return DispatchResult.ack();
            }),
        odd);
    assertThat(seen.get().rawBody()).isEqualTo(odd.getBytes(StandardCharsets.UTF_8));
    assertThat(seen.get().data().orElseThrow().get("name").asString()).isEqualTo("Zoë");
    assertThat(seen.get().data().orElseThrow().get("city").asString()).isEqualTo("Zürich");
  }

  @Test
  void anUnsignedDeliveryIs401WhenSignaturesAreRequired() {
    TestResponse r = unsigned(start((ctx, d) -> DispatchResult.ack()), TYPE).body(ENVELOPE).send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("details").get("reason").asString()).isEqualTo("MISSING_SIGNATURE");
  }

  @Test
  void anExpiredTimestampIs401() {
    String old = "2026-10-09T11:54:59.000Z";
    TestResponse r =
        unsigned(start((ctx, d) -> DispatchResult.ack()), TYPE)
            .header("X-FlowCatalyst-Signature", sign(SECRET, old, ENVELOPE))
            .header("X-FlowCatalyst-Timestamp", old)
            .body(ENVELOPE)
            .send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("details").get("reason").asString()).isEqualTo("TIMESTAMP_EXPIRED");
  }

  @Test
  void aRepeatedSignatureHeaderIs401() {
    String sig = sign(SECRET, TS, ENVELOPE);
    TestResponse r =
        unsigned(start((ctx, d) -> DispatchResult.ack()), TYPE)
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("details").get("reason").asString()).isEqualTo("DUPLICATE_HEADER");
  }

  @Test
  void unsignedIsAcceptedWhenNotRequiredAndStartUpWarns() {
    TestClient client =
        start(
            (ctx, d) -> DispatchResult.ack(),
            "FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE",
            "false",
            "FLOWCATALYST_RECEIVERS_SIGNING_SECRET",
            null);
    assertThat(unsigned(client, TYPE).body(ENVELOPE).send().status()).isEqualTo(200);
    assertThat(logs.text())
        .contains("flowcatalyst.receivers.require-signature is false: unsigned deliveries to /fc are accepted");
  }

  @Test
  void aSignedDeliveryIsStillVerifiedWhenSignaturesAreNotRequired() {
    TestClient client =
        start((ctx, d) -> DispatchResult.ack(), "FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE", "false");
    TestResponse r =
        unsigned(client, TYPE)
            .header("X-FlowCatalyst-Signature", sign("wrong", TS, ENVELOPE))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(deliver(client).status()).isEqualTo(200);
  }

  @Test
  void theBearerGateIsEnforcedWhenConfigured() {
    TestClient client =
        start((ctx, d) -> DispatchResult.ack(), "FLOWCATALYST_RECEIVERS_BEARER_TOKEN", "bearer-test-only");
    TestResponse missing = deliver(client);
    assertThat(missing.status()).isEqualTo(401);
    assertThat(missing.tree().get("details").get("reason").asString()).isEqualTo("MISSING_BEARER");
    TestResponse ok =
        client
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("Authorization", "Bearer bearer-test-only")
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, ENVELOPE))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    assertThat(ok.status()).isEqualTo(200);
    assertThat(logs.text()).doesNotContain("bearer-test-only");
  }

  @Test
  void aBodyOverTheLimitIsRefusedBeforeAnyHandler() {
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
                (ctx, d) -> {
                  calls.incrementAndGet();
                  return DispatchResult.ack();
                })
            .withMaxBodyBytes(16);
    assertThat(deliver(client).status()).isEqualTo(413);
    assertThat(calls).hasValue(0);
  }

  // ---- headers and envelope ----------------------------------------------------------------

  @Test
  void theDeliveryAndTheContextCarryTheEnvelopeAndTheClient() {
    AtomicReference<Context> ctxSeen = new AtomicReference<>();
    AtomicReference<DispatchDelivery> seen = new AtomicReference<>();
    deliver(
        start(
            (ctx, d) -> {
              ctxSeen.set(ctx);
              seen.set(d);
              return DispatchResult.ack();
            }));
    DispatchDelivery d = seen.get();
    assertThat(d.jobId()).isEqualTo(JOB);
    assertThat(d.eventType()).isEqualTo(TYPE);
    assertThat(d.attemptNumber()).hasValue(2);
    assertThat(d.source()).hasValue("orders:billing");
    assertThat(d.subject()).hasValue("billing.invoice.42");
    assertThat(d.messageGroup()).hasValue("billing:invoice:42");
    assertThat(d.correlationId().value()).isEqualTo("corr-1");
    assertThat(d.client()).contains(new DispatchDelivery.Client("clt_1", new ClientId("acme")));
    assertThat(d.isDataOnly()).isFalse();
    assertThat(d.data().orElseThrow().get("invoiceId").asString()).isEqualTo("42");
    Context ctx = ctxSeen.get();
    assertThat(ctx.client()).isEqualTo(new ClientId("acme"));
    assertThat(ctx.application().value()).isEqualTo("orders");
    assertThat(ctx.correlationId().value()).isEqualTo("corr-1");
    assertThat(ctx.executionId()).isEqualTo(JOB);
    assertThat(ctx.principal()).isNull();
  }

  @Test
  void aPlatformScopedJobHasThePlatformClientAndAGeneratedCorrelationId() {
    AtomicReference<Context> ctxSeen = new AtomicReference<>();
    TestClient client =
        start(
            (ctx, d) -> {
              ctxSeen.set(ctx);
              return DispatchResult.ack();
            });
    String body = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1}";
    TestResponse r =
        unsigned(client, TYPE)
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(ctxSeen.get().client()).isEqualTo(Receivers.PLATFORM_CLIENT);
    assertThat(ctxSeen.get().correlationId().value()).isNotBlank();
  }

  @Test
  void aDataOnlyBodyIsTheRawPayload() {
    AtomicReference<DispatchDelivery> seen = new AtomicReference<>();
    String payload = "{\"invoiceId\":\"42\"}";
    deliver(
        start(
            (ctx, d) -> {
              seen.set(d);
              return DispatchResult.ack();
            }),
        payload);
    assertThat(seen.get().isDataOnly()).isTrue();
    assertThat(seen.get().attemptNumber()).isEmpty();
    assertThat(seen.get().data().orElseThrow().get("invoiceId").asString()).isEqualTo("42");
    assertThat(seen.get().rawBody()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aDataOnlyBodyThatIsNotJsonHasNoData() {
    AtomicReference<DispatchDelivery> seen = new AtomicReference<>();
    deliver(
        start(
            (ctx, d) -> {
              seen.set(d);
              return DispatchResult.ack();
            }),
        "plain text, not json");
    assertThat(seen.get().isDataOnly()).isTrue();
    assertThat(seen.get().data()).isEmpty();
  }

  @Test
  void malformedEnvelopesNeverReachAHandler() {
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
            (ctx, d) -> {
              calls.incrementAndGet();
              return DispatchResult.ack();
            });
    String head = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\"";
    for (String body :
        List.of(
            head + "}",
            head + ",\"attemptNumber\":\"2\"}",
            head + ",\"attemptNumber\":0}",
            head + ",\"attemptNumber\":1.5}",
            head + ",\"attemptNumber\":1,\"subject\":7}",
            head + ",\"attemptNumber\":1,\"correlationId\":{}}",
            head + ",\"attemptNumber\":1,\"attemptNumber\":2}")) {
      TestResponse r = deliver(client, body);
      assertThat(r.status()).as(body).isEqualTo(400);
      assertThat(r.tree().get("error").asString()).isEqualTo("VALIDATION");
    }
    assertThat(calls).hasValue(0);
  }

  @Test
  void malformedJobHeadersAre400AndCannotForgeLogLines() {
    AtomicInteger calls = new AtomicInteger();
    TestClient client =
        start(
            (ctx, d) -> {
              calls.incrementAndGet();
              return DispatchResult.ack();
            });
    String sig = sign(SECRET, TS, ENVELOPE);
    TestResponse injected =
        client
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", "dj_1\r\n[main] ERROR forged line")
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    TestResponse missing =
        client
            .post("/fc/dispatch")
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    TestResponse badClient =
        client
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Client", "acme")
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    TestResponse badTimestamp =
        client
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sig)
            .header("X-FlowCatalyst-Timestamp", "x\r\n[main] ERROR forged line")
            .body(ENVELOPE)
            .send();
    assertThat(injected.status()).isEqualTo(400);
    assertThat(missing.status()).isEqualTo(400);
    assertThat(badClient.status()).isEqualTo(400);
    assertThat(badTimestamp.status()).isEqualTo(401);
    assertThat(injected.body()).doesNotContain("forged");
    assertThat(calls).hasValue(0);
    assertThat(logs.text()).doesNotContain("forged line");
  }

  // ---- handler table -----------------------------------------------------------------------

  @Test
  void theMostSpecificPatternWins() {
    TestClient client =
        start(
            r -> {
              r.onDispatch("*", (ctx, d) -> DispatchResult.reject("star"));
              r.onDispatch("orders:*", (ctx, d) -> DispatchResult.reject("orders"));
              r.onDispatch("orders:billing:*", (ctx, d) -> DispatchResult.reject("billing"));
              r.onDispatch(TYPE, (ctx, d) -> DispatchResult.reject("exact"));
            });
    assertThat(handledBy(client, TYPE)).isEqualTo("exact");
    assertThat(handledBy(client, "orders:billing:invoice:paid")).isEqualTo("billing");
    assertThat(handledBy(client, "orders:shipping:parcel:sent")).isEqualTo("orders");
    assertThat(handledBy(client, "ordersx:billing:invoice:paid")).isEqualTo("star");
    assertThat(handledBy(client, "orders")).isEqualTo("star");
  }

  private static String handledBy(TestClient client, String type) {
    String body = "{\"id\":\"" + JOB + "\",\"type\":\"" + type + "\",\"attemptNumber\":1}";
    TestResponse r =
        unsigned(client, type)
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    return r.tree().get("message").asString();
  }

  // ---- start-up ----------------------------------------------------------------------------

  private StartupException failure(Consumer<Receivers> registrar, String... env) {
    Throwable t =
        catchThrowable(
            () -> Receiving.start(dir, Receiving.env(platform, env), () -> NOW, Receiving.handlers(registrar)).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void aDuplicateRegistrationFailsStartUp() {
    StartupException e =
        failure(
            r -> {
              r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
              r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
            });
    assertThat(e.getMessage()).contains("already registered");
  }

  @Test
  void aMalformedPatternFailsStartUp() {
    for (String bad : List.of("", ":*", "orders:", "orders::x", "*:*", "orders:*:x", "orders billing")) {
      StartupException e = failure(r -> r.onDispatch(bad, (ctx, d) -> DispatchResult.ack()));
      assertThat(e.getMessage()).as(bad).contains("malformed");
    }
  }

  @Test
  void handlersWithoutASigningSecretFailStartUp() {
    StartupException e =
        failure(
            r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack()),
            "FLOWCATALYST_RECEIVERS_SIGNING_SECRET",
            null);
    assertThat(e.problems()).extracting(Problem::key).containsExactly("flowcatalyst.receivers.dispatch.signing-secret");
  }

  @Test
  void aBlankSigningSecretCountsAsNone() {
    StartupException e =
        failure(
            r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack()),
            "FLOWCATALYST_RECEIVERS_SIGNING_SECRET",
            "   ");
    assertThat(e.problems()).extracting(Problem::key).containsExactly("flowcatalyst.receivers.dispatch.signing-secret");
  }

  @Test
  void withoutHandlersNoSecretIsNeededAndDeliveriesAreStillRefused() {
    TestClient client = start(r -> {}, "FLOWCATALYST_RECEIVERS_SIGNING_SECRET", null);
    TestResponse r = deliver(client);
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("details").get("reason").asString()).isEqualTo("MISSING_SECRET");
  }

  @Test
  void handlersWithoutAnHttpModuleFailStartUp() {
    Throwable t =
        catchThrowable(
            () ->
                io.flowcatalyst.framework.core.App.builder()
                    .configDirectory(dir)
                    .environment(Receiving.env(platform))
                    .module(Receiving.handlers(r -> r.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack())))
                    .module(new FlowCatalystModule())
                    .start()
                    .close());
    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("no HTTP module");
  }

  @Test
  void aBadPrefixOrTimeoutFailsStartUp() {
    StartupException e =
        failure(
            r -> {},
            "FLOWCATALYST_RECEIVERS_PATH",
            "/fc/",
            "FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT",
            "0s");
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder("flowcatalyst.receivers.path", "flowcatalyst.receivers.dispatch-timeout");
  }

  @Test
  void thePrefixIsASetting() {
    TestClient client =
        start((ctx, d) -> DispatchResult.ack(), "FLOWCATALYST_RECEIVERS_PATH", "/hooks/flowcatalyst");
    TestResponse r =
        client
            .post("/hooks/flowcatalyst/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, ENVELOPE))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(ENVELOPE)
            .send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(deliver(client).status()).isEqualTo(404);
  }

  @Test
  void registeringAfterStartUpIsRefused() {
    start((ctx, d) -> DispatchResult.ack());
    assertThatIllegalStateException()
        .isThrownBy(() -> app.get(Receivers.class).onDispatch("orders:x:y:z", (ctx, d) -> DispatchResult.ack()));
  }

  @Test
  void deliveriesBeforeStartUpCompletesAre503() {
    Receivers receivers = new Receivers();
    receivers.onDispatch(TYPE, (ctx, d) -> DispatchResult.ack());
    Routes routes = new Routes();
    routes.post(
        "/fc/dispatch",
        new DispatchReceiver(
            receivers,
            new ReceiverGate(io.flowcatalyst.framework.config.Secret.of(SECRET), null, true, () -> NOW),
            new io.flowcatalyst.framework.context.ApplicationCode("orders"),
            Duration.ofSeconds(5),
            "/fc/dispatch"));
    TestClient client = TestClient.of(routes);
    assertThat(deliver(client).status()).isEqualTo(503);
    receivers.markReady();
    assertThat(deliver(client).status()).isEqualTo(200);
  }

  @Test
  void aDisabledModuleMountsNothing() {
    AtomicBoolean called = new AtomicBoolean();
    app =
        Receiving.start(
            dir,
            Map.of("FLOWCATALYST_ENABLED", "false"),
            () -> NOW,
            Receiving.handlers(
                r ->
                    r.onDispatch(
                        TYPE,
                        (ctx, d) -> {
                          called.set(true);
                          return DispatchResult.ack();
                        })));
    assertThat(deliver(Receiving.client(app)).status()).isEqualTo(404);
    assertThat(called).isFalse();
  }
}
