package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.usecase.AggregateId;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.Tx;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DispatchDelivery#once} composed with a use case run in its transaction (write-path
 * contract, section 4a, friction F13): the handler's record, the use case's events and audit row
 * commit together or not at all.
 */
class DispatchOnceUseCaseTest {

  static final String TYPE = "orders:billing:invoice:created";
  static final EventType SENT = new EventType("orders", "billing", "invoice", "sent");

  record InvoiceId(String value) implements AggregateId {}

  record SendInvoice(String invoiceId) {}

  record InvoiceSent(EventMetadata metadata, String invoiceId) implements DomainEvent {}

  static PostgresFixture pg;

  @TempDir Path dir;

  final AtomicInteger useCaseRuns = new AtomicInteger();
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

  private UseCase<SendInvoice, InvoiceSent> sendInvoice(Events events) {
    return UseCase.named("send-invoice", SendInvoice.class, InvoiceSent.class)
        .authorize((ctx, cmd) -> java.util.Optional.empty())
        .execute(
            (ctx, cmd) -> {
              useCaseRuns.incrementAndGet();
              return Plan.emit(new InvoiceSent(events.metadata(ctx, SENT, new InvoiceId(cmd.invoiceId())), cmd.invoiceId()));
            });
  }

  /** The handler: once per job, the use case in the same transaction; optionally fails afterwards. */
  private void start(boolean failAfterTheUseCase) {
    AtomicInteger deliveries = new AtomicInteger();
    app =
        Receiving.start(
            dir,
            Receiving.env(platform),
            () -> NOW,
            new DatabaseModule(),
            new OutboxModule(),
            new UseCaseModule(),
            Receiving.handlers(
                r ->
                    r.onDispatch(
                        TYPE,
                        (ctx, d) -> {
                          OnceResult<UseCaseResult<InvoiceSent>> once =
                              d.once(
                                  app.get(Databases.class).defaultInstance(),
                                  "send-invoice",
                                  connection -> {
                                    UseCaseResult<InvoiceSent> result =
                                        app.get(UseCases.class)
                                            .run(sendInvoice(app.get(Events.class)), ctx, new SendInvoice("42"), Tx.of(connection));
                                    if (failAfterTheUseCase && deliveries.incrementAndGet() == 1) {
                                      throw new IllegalStateException("the notification service is down");
                                    }
                                    return result;
                                  });
                          return DispatchResult.ack();
                        })));
  }

  private int deliver(String job) {
    String body = "{\"id\":\"" + job + "\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1}";
    return Receiving.client(app)
        .post("/fc/dispatch")
        .header("X-Dispatch-Job-Id", job)
        .header("X-Event-Type", TYPE)
        .header("X-FlowCatalyst-Client", "clt_1:acme")
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(body)
        .send()
        .status();
  }

  private static String count(String table) {
    return pg.query("SELECT count(*) FROM " + table);
  }

  @Test
  void theRecordTheEventAndTheAuditRowCommitTogetherAndARedeliveryDoesNothing() {
    start(false);

    assertThat(deliver("dj_1")).isEqualTo(200);
    assertThat(count("fw_dispatch_once")).isEqualTo("1");
    assertThat(count("outbox_messages WHERE type = 'EVENT'")).isEqualTo("1");
    assertThat(count("outbox_messages WHERE type = 'AUDIT_LOG'")).isEqualTo("1");

    assertThat(deliver("dj_1")).isEqualTo(200); // the platform delivers it again
    assertThat(useCaseRuns).hasValue(1);
    assertThat(count("outbox_messages")).isEqualTo("2");
  }

  @Test
  void workThatFailsAfterTheUseCaseRollsTheUseCaseBackAndTheRetryDoesItOnce() {
    start(true);

    assertThat(deliver("dj_2")).isNotEqualTo(200); // the platform will retry
    assertThat(count("fw_dispatch_once")).isEqualTo("0");
    assertThat(count("outbox_messages")).as("the use case's rows were rolled back with the record").isEqualTo("0");

    assertThat(deliver("dj_2")).isEqualTo(200);
    assertThat(count("fw_dispatch_once")).isEqualTo("1");
    assertThat(count("outbox_messages WHERE type = 'EVENT'")).isEqualTo("1");
    assertThat(useCaseRuns).hasValue(2);
  }
}
