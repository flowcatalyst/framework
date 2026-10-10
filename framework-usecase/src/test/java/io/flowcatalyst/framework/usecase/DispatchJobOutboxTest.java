package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dispatch-job rows in the real {@code outbox_messages} table (write-path contract, section 7a),
 * checked against the platform's own shapes: the outbox processor's claim query, its request body
 * ({@code {"items":[payload...]}} with each payload unchanged) and the Go {@code BatchItem} it is
 * decoded into.
 */
class DispatchJobOutboxTest {

  static final EventType CREATED = new EventType("orders", "billing", "thing", "created");
  static final Instant NOW = Instant.parse("2026-10-10T08:00:00.000Z");
  static final URI TARGET = URI.create("https://billing.example.com/hooks/export");

  /**
   * The Go platform's {@code BatchItem} (internal/platform/shared/sdk/dispatch_jobs_batch.go), field
   * for field, with Go's JSON names and types: {@code *string} as nullable String, {@code int32} and
   * {@code uint32} as Integer/Long, {@code metadata} as a list of {@code {key, value}}. Decoding
   * with unknown properties refused proves every key the framework writes exists there with a
   * compatible type.
   */
  record GoBatchItem(
      @Nullable String id,
      @Nullable String externalId,
      String kind,
      String code,
      @Nullable String source,
      @Nullable String subject,
      String targetUrl,
      @Nullable String payload,
      @Nullable String payloadContentType,
      boolean dataOnly,
      @Nullable String eventId,
      @Nullable String correlationId,
      @Nullable String clientId,
      @Nullable String subscriptionId,
      @Nullable String serviceAccountId,
      @Nullable String dispatchPoolId,
      @Nullable String messageGroup,
      @Nullable String mode,
      @Nullable Integer sequence,
      @Nullable Long timeoutSeconds,
      @Nullable Long maxRetries,
      @Nullable List<GoMetadata> metadata,
      @Nullable String queue,
      @Nullable String descriptor) {}

  /** Go {@code dispatchjob.Metadata}. */
  record GoMetadata(String key, String value) {}

  /** Go {@code BatchRequest}. */
  record GoBatchRequest(List<GoBatchItem> items) {}

  static PostgresFixture pg;

  @TempDir Path dir;

  final ClientId acme = new ClientId("acme");
  final InstantSource clock = () -> NOW;
  final ThingRepository repo = new ThingRepository();
  final ObjectMapper strict =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  App app;
  Database db;
  UseCases useCases;
  Events events;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void start() throws IOException {
    pg.reset();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .instantSource(clock)
            .module(new DatabaseModule())
            .module(new OutboxModule())
            .module(new UseCaseModule())
            .start();
    db = app.get(Databases.class).defaultInstance();
    pg.execute(ThingRepository.DDL);
    useCases = app.get(UseCases.class);
    events = app.get(Events.class);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  record Create(String id, List<DispatchJob> jobs) {}

  UseCase<Create, ThingEvent> create() {
    return UseCase.named("create-thing", Create.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .execute(
            (c, cmd) -> {
              Thing t = Thing.create(new ThingId(cmd.id()), c.client(), "bolt", "B-1");
              Plan<ThingEvent> plan =
                  Plan.save(
                      t,
                      repo,
                      new ThingEvent(
                          events.metadata(c, CREATED, t.id()), cmd.id(), t.name(), t.code()));
              for (DispatchJob job : cmd.jobs()) {
                plan = plan.andDispatch(job);
              }
              return plan;
            });
  }

  Context ctx() {
    return Context.of(acme).withCorrelationId(new CorrelationId("corr-42"));
  }

  static DispatchJob fullJob() {
    return DispatchJob.builder("orders:billing:export", TARGET)
        .id("0HZXEQ5Y8JY5Z")
        .kind(DispatchJob.Kind.EVENT)
        .source("orders:billing")
        .subject("billing.invoice.inv_1")
        .payload("<invoice id=\"inv_1\"/>", "application/xml")
        .dataOnly(true)
        .externalId("export-inv_1")
        .eventId("0HZXEQ5Y8JY50")
        .platformClientId("0HZXEQ5Y8JY51")
        .subscriptionId("0HZXEQ5Y8JY52")
        .serviceAccountId("0HZXEQ5Y8JY53")
        .dispatchPoolId("0HZXEQ5Y8JY54")
        .messageGroup("invoice:inv_1")
        .mode(DispatchJob.Mode.BLOCK_ON_ERROR)
        .sequence(2)
        .timeoutSeconds(45)
        .maxRetries(5)
        .metadata("invoice", "inv_1")
        .metadata("batch", "nightly")
        .queue(DispatchJob.Queue.HIGH_PRIORITY)
        .descriptor("invoice export")
        .build();
  }

  List<Map<String, @Nullable String>> rows(String sql) {
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      ResultSetMetaData meta = rs.getMetaData();
      List<Map<String, @Nullable String>> out = new ArrayList<>();
      while (rs.next()) {
        Map<String, @Nullable String> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnName(i), rs.getString(i));
        }
        out.add(row);
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aFullJobIsOneRowWithTheBatchItemAsItsPayload() throws Exception {
    UseCaseResult<ThingEvent> r = useCases.run(create(), ctx(), new Create("t-1", List.of(fullJob())));
    assertThat(r).isInstanceOf(UseCaseResult.Committed.class);

    List<Map<String, @Nullable String>> jobs =
        rows("SELECT * FROM outbox_messages WHERE type = 'DISPATCH_JOB'");
    assertThat(jobs).hasSize(1);
    Map<String, @Nullable String> row = jobs.get(0);
    assertThat(row.get("id")).isEqualTo("0HZXEQ5Y8JY5Z");
    assertThat(row.get("message_group")).isEqualTo("invoice:inv_1");
    assertThat(row.get("status")).isEqualTo("0");
    assertThat(row.get("retry_count")).isEqualTo("0");
    assertThat(row.get("client_id")).isEqualTo("acme");
    assertThat(row.get("headers")).isNull();
    assertThat(row.get("error_message")).isNull();
    String payload = row.get("payload");
    assertThat(row.get("payload_size"))
        .isEqualTo(String.valueOf(payload.getBytes(StandardCharsets.UTF_8).length));
    assertThat(payload)
        .isEqualTo(
            "{\"id\":\"0HZXEQ5Y8JY5Z\",\"kind\":\"EVENT\",\"code\":\"orders:billing:export\","
                + "\"source\":\"orders:billing\",\"subject\":\"billing.invoice.inv_1\","
                + "\"targetUrl\":\"https://billing.example.com/hooks/export\","
                + "\"payload\":\"<invoice id=\\\"inv_1\\\"/>\",\"payloadContentType\":\"application/xml\","
                + "\"dataOnly\":true,\"externalId\":\"export-inv_1\",\"eventId\":\"0HZXEQ5Y8JY50\","
                + "\"correlationId\":\"corr-42\",\"clientId\":\"0HZXEQ5Y8JY51\","
                + "\"subscriptionId\":\"0HZXEQ5Y8JY52\",\"serviceAccountId\":\"0HZXEQ5Y8JY53\","
                + "\"dispatchPoolId\":\"0HZXEQ5Y8JY54\",\"messageGroup\":\"invoice:inv_1\","
                + "\"mode\":\"BLOCK_ON_ERROR\",\"sequence\":2,\"timeoutSeconds\":45,\"maxRetries\":5,"
                + "\"metadata\":[{\"key\":\"invoice\",\"value\":\"inv_1\"},{\"key\":\"batch\",\"value\":\"nightly\"}],"
                + "\"queue\":\"HIGH_PRIORITY\",\"descriptor\":\"invoice export\"}");

    // The same transaction wrote the event and the audit row; the job sits between them in order.
    assertThat(rows("SELECT type FROM outbox_messages ORDER BY type"))
        .extracting(m -> m.get("type"))
        .containsExactlyInAnyOrder("EVENT", "DISPATCH_JOB", "AUDIT_LOG");
  }

  @Test
  void aMinimalJobWritesOnlyTheAlwaysFieldsAndGetsAGeneratedId() throws Exception {
    DispatchJob minimal = DispatchJob.builder("orders:billing:export", TARGET).build();
    useCases.run(create(), ctx(), new Create("t-2", List.of(minimal)));

    Map<String, @Nullable String> row =
        rows("SELECT id, message_group, payload FROM outbox_messages WHERE type = 'DISPATCH_JOB'")
            .get(0);
    String id = row.get("id");
    assertThat(id).matches("[0-9A-Z]{13}");
    assertThat(row.get("message_group")).isNull();
    JsonNode tree = strict.readTree(row.get("payload"));
    List<String> keys = new ArrayList<>();
    tree.propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly(
            "id", "kind", "code", "source", "targetUrl", "payload", "payloadContentType",
            "dataOnly", "correlationId");
    assertThat(tree.get("id").asString()).isEqualTo(id);
    assertThat(tree.get("kind").asString()).isEqualTo("TASK");
    assertThat(tree.get("source").asString()).isEqualTo("orders");
    assertThat(tree.get("payload").asString()).isEqualTo("{}");
    assertThat(tree.get("dataOnly").asBoolean()).isFalse();
  }

  @Test
  void everyKeyWrittenExistsInTheGoBatchItemWithACompatibleType() throws Exception {
    useCases.run(create(), ctx(), new Create("t-3", List.of(fullJob())));
    String payload =
        rows("SELECT payload FROM outbox_messages WHERE type = 'DISPATCH_JOB'").get(0).get("payload");
    GoBatchItem item = strict.readValue(payload, GoBatchItem.class);
    assertThat(item.metadata())
        .containsExactly(new GoMetadata("invoice", "inv_1"), new GoMetadata("batch", "nightly"));
    assertThat(item.kind()).isIn("EVENT", "TASK");
    assertThat(item.mode()).isIn("IMMEDIATE", "NEXT_ON_ERROR", "BLOCK_ON_ERROR");
    assertThat(item.queue()).isIn("DEFAULT", "HIGH_PRIORITY");
    assertThat(item.timeoutSeconds()).isEqualTo(45L);
    assertThat(item.clientId()).isEqualTo("0HZXEQ5Y8JY51");
    // Fields the batch endpoint ignores are never sent.
    JsonNode tree = strict.readTree(payload);
    for (String ignored :
        Set.of(
            "headers", "retryStrategy", "scheduledFor", "expiresAt", "idempotencyKey",
            "connectionId", "causationId", "clientCode")) {
      assertThat(tree.has(ignored)).as(ignored).isFalse();
    }
  }

  @Test
  void theProcessorsClaimQueryReturnsTheRowAndItsRequestBodyDecodesAsABatchRequest()
      throws Exception {
    DispatchJob second =
        DispatchJob.builder("orders:billing:notify", URI.create("https://n.example.com/x"))
            .messageGroup("invoice:inv_1")
            .build();
    useCases.run(create(), ctx(), new Create("t-4", List.of(fullJob(), second)));

    // internal/outbox/postgres/postgres.go, the claim, verbatim apart from the batch size.
    List<Map<String, @Nullable String>> claimed =
        rows(
            """
            WITH claimed AS (
              SELECT id FROM outbox_messages
               WHERE status = 0
               ORDER BY message_group, created_at
               LIMIT 100
               FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_messages m
               SET status = 9, updated_at = NOW()
              FROM claimed
             WHERE m.id = claimed.id
             RETURNING m.id, m.type, m.message_group, m.payload, m.status, m.retry_count,
                       m.error_message, m.created_at, m.updated_at
            """);
    List<String> payloads = new ArrayList<>();
    for (Map<String, @Nullable String> row : claimed) {
      if ("DISPATCH_JOB".equals(row.get("type"))) {
        assertThat(row.get("message_group")).isEqualTo("invoice:inv_1");
        payloads.add(row.get("payload"));
      }
    }
    assertThat(payloads).hasSize(2);

    // http_dispatcher.go: json.Marshal(map[string]any{"items": payloads}) with each payload a
    // json.RawMessage, i.e. the stored text placed unchanged into the array.
    String body = "{\"items\":[" + String.join(",", payloads) + "]}";
    GoBatchRequest request = strict.readValue(body, GoBatchRequest.class);
    assertThat(request.items())
        .extracting(GoBatchItem::code)
        .containsExactlyInAnyOrder("orders:billing:export", "orders:billing:notify");
    assertThat(request.items()).allSatisfy(i -> assertThat(i.correlationId()).isEqualTo("corr-42"));
  }

  @Test
  void aFailedAttemptWritesNoJob() {
    useCases.run(create(), ctx(), new Create("t-5", List.of()));
    // Same thing id again: a unique violation, so the whole attempt rolls back.
    UseCaseResult<ThingEvent> r =
        useCases.run(create(), ctx(), new Create("t-5", List.of(fullJob())));
    assertThat(r).isInstanceOf(UseCaseResult.Failed.class);
    assertThat(((UseCaseResult.Failed<ThingEvent>) r).error().code()).isEqualTo("UNIQUE_VIOLATION");
    assertThat(pg.query("SELECT count(*) FROM outbox_messages WHERE type = 'DISPATCH_JOB'"))
        .isEqualTo("0");
  }

  @Test
  void aSinkWithoutDispatchJobSupportFailsTheAttemptAtOutbox() {
    UseCases recording = new UseCases(db, new RecordingSink(), clock);
    UseCaseResult<ThingEvent> r =
        recording.run(create(), ctx(), new Create("t-6", List.of(fullJob())));
    assertThat(r).isInstanceOf(UseCaseResult.Failed.class);
    UseCaseError error = ((UseCaseResult.Failed<ThingEvent>) r).error();
    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("OUTBOX");
    assertThat(pg.query("SELECT count(*) FROM thing")).isEqualTo("0");
  }

  @Test
  void aPlanWithoutJobsDoesNotAskTheSinkForThem() {
    UseCases recording = new UseCases(db, new RecordingSink(), clock);
    assertThat(recording.run(create(), ctx(), new Create("t-7", List.of())))
        .isInstanceOf(UseCaseResult.Committed.class);
  }
}
