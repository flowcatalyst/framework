package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.usecase.AggregateId;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end over PostgreSQL (flowcatalyst contract section 4b): a use case run by a receiver
 * writes outbox rows that name the application's service principal and the delivered message as
 * the cause. The rows are read back from {@code outbox_messages}, as the platform's processor would
 * send them.
 */
class ReceiverOutboxTest {

  static final String TYPE = "orders:billing:invoice:created";
  static final String JOB = "dj_0HZXEQ5Y8JY5Z";
  static final EventType SENT = new EventType("orders", "billing", "invoice", "sent");
  static final JsonMapper JSON = JsonMapper.builder().build();

  record InvoiceId(String value) implements AggregateId {}

  record SendInvoice(String invoiceId) {}

  record InvoiceSent(EventMetadata metadata, String invoiceId) implements DomainEvent {}

  static PostgresFixture pg;

  @TempDir Path dir;

  FakePlatform platform;
  App app;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    platform = FakePlatform.start();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    platform.close();
  }

  /** Sends the invoice as whoever the context names; only a service principal may. */
  private static UseCase<SendInvoice, InvoiceSent> sendInvoice(Events events) {
    return UseCase.named("send-invoice", SendInvoice.class, InvoiceSent.class)
        .authorize(
            (ctx, cmd) ->
                ctx.principalKind() == PrincipalKind.SERVICE
                    ? Optional.empty()
                    : Optional.of(UseCaseError.authorization("NOT_A_SERVICE", "only the service sends invoices")))
        .execute(
            (ctx, cmd) ->
                Plan.emit(
                    new InvoiceSent(
                        events.metadata(ctx, SENT, new InvoiceId(cmd.invoiceId())), cmd.invoiceId())));
  }

  private void start(java.util.function.Consumer<Receivers> registrar) {
    app =
        Receiving.start(
            dir,
            Receiving.env(platform),
            () -> NOW,
            new DatabaseModule(),
            new OutboxModule(),
            new UseCaseModule(),
            Receiving.handlers(registrar));
  }

  private UseCaseResult<InvoiceSent> run(Context ctx, String invoiceId) {
    return app.get(UseCases.class).run(sendInvoice(app.get(Events.class)), ctx, new SendInvoice(invoiceId));
  }

  private static JsonNode row(String type) {
    return JSON.readTree(pg.query("SELECT payload FROM outbox_messages WHERE type = '" + type + "'"));
  }

  private static Map<String, String> contextData(JsonNode event) {
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode entry : event.path("contextData")) {
      out.put(entry.path("key").asString(), entry.path("value").asString());
    }
    return out;
  }

  @Test
  void aDispatchJobsUseCaseWritesTheServicePrincipalAndTheJobAsCause() {
    AtomicReference<UseCaseResult<InvoiceSent>> result = new AtomicReference<>();
    start(
        r ->
            r.onDispatch(
                TYPE,
                (ctx, d) -> {
                  result.set(run(ctx, "42"));
                  return DispatchResult.ack();
                }));
    String body = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1,\"correlationId\":\"corr-7\"}";
    TestClient client = Receiving.client(app);
    TestResponse r =
        client
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Client", "clt_1:acme")
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(result.get()).isInstanceOf(UseCaseResult.Committed.class);

    JsonNode event = row("EVENT");
    assertThat(event.path("type").asString()).isEqualTo("orders:billing:invoice:sent");
    assertThat(event.path("causationId").asString()).isEqualTo(JOB);
    assertThat(event.path("correlationId").asString()).isEqualTo("corr-7");
    assertThat(event.path("clientCode").asString()).isEqualTo("acme");
    assertThat(contextData(event))
        .containsEntry("principalId", FakePlatform.SERVICE_PRINCIPAL)
        .containsEntry("executionId", JOB);

    JsonNode audit = row("AUDIT_LOG");
    // the platform refuses an audit item without a principal (audit_batch.go, "principalId is required")
    assertThat(audit.path("principalId").asString()).isEqualTo(FakePlatform.SERVICE_PRINCIPAL);
    assertThat(audit.path("operation").asString()).isEqualTo("send-invoice");
    assertThat(audit.path("correlationId").asString()).isEqualTo("corr-7");
    assertThat(audit.path("clientCode").asString()).isEqualTo("acme");
    // the platform's audit item has no causation field; the event carries the job
    assertThat(audit.has("causationId")).isFalse();
  }

  @Test
  void aScheduledJobsUseCaseWritesTheServicePrincipalAndTheInstanceAsCause() throws Exception {
    start(
        r ->
            r.onScheduledJob(
                "nightly-invoices",
                (ctx, f) -> {
                  UseCaseResult<InvoiceSent> result = run(ctx, "43");
                  return result instanceof UseCaseResult.Committed<InvoiceSent>
                      ? JobResult.success()
                      : JobResult.failure(result.toString());
                },
                JobOptions.alwaysConcurrent()));
    String body =
        """
        {"jobId":"sj_1","jobCode":"nightly-invoices","instanceId":"sji_0HZXEQ5Y8JY5Z",\
        "firedAt":"2026-10-09T12:00:00Z","triggerKind":"CRON","correlationId":"corr-8",\
        "tracksCompletion":true,"concurrent":true}""";
    TestResponse r =
        Receiving.client(app)
            .post("/fc/scheduled-jobs")
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    assertThat(r.status()).isEqualTo(202);
    JsonNode completion = platform.awaitInstanceCalls("complete", 1, 10_000).getFirst().json();
    assertThat(completion.path("status").asString()).isEqualTo("SUCCESS");

    JsonNode event = row("EVENT");
    assertThat(event.path("causationId").asString()).isEqualTo("sji_0HZXEQ5Y8JY5Z");
    assertThat(event.path("correlationId").asString()).isEqualTo("corr-8");
    assertThat(event.path("clientCode").asString()).isEqualTo(Receivers.PLATFORM_CLIENT.value());
    assertThat(contextData(event))
        .containsEntry("principalId", FakePlatform.SERVICE_PRINCIPAL)
        .containsEntry("executionId", "sji_0HZXEQ5Y8JY5Z");
    assertThat(row("AUDIT_LOG").path("principalId").asString()).isEqualTo(FakePlatform.SERVICE_PRINCIPAL);
  }

  @Test
  void withoutTheServicePrincipalNothingIsWritten() {
    platform.tokenPrincipal("prn_0HUMANUSER001", "USER");
    start(
        r ->
            r.onDispatch(
                TYPE,
                (ctx, d) -> {
                  run(ctx, "44");
                  return DispatchResult.ack();
                }));
    String body = "{\"id\":\"" + JOB + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1}";
    TestResponse r =
        Receiving.client(app)
            .post("/fc/dispatch")
            .header("X-Dispatch-Job-Id", JOB)
            .header("X-Event-Type", TYPE)
            .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
            .header("X-FlowCatalyst-Timestamp", TS)
            .body(body)
            .send();
    assertThat(r.status()).isEqualTo(503);
    assertThat(pg.query("SELECT count(*) FROM outbox_messages")).isEqualTo("0");
  }
}
