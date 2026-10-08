package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.sdk.outbox.CreateAuditLogDto;
import io.flowcatalyst.sdk.outbox.CreateEventDto;
import io.flowcatalyst.sdk.outbox.JdbcOutboxDriver;
import io.flowcatalyst.sdk.outbox.OutboxManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the rows written here are the rows the FlowCatalyst Java SDK writes: the same event
 * and audit entry go through both writers into the same table, and the rows are compared column
 * by column and the payloads as JSON trees. The SDK is a test-scope dependency only.
 */
class OutboxCompatibilityTest {

  static final EventType PLACED = new EventType("shop", "sales", "order", "placed");
  static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");

  record Detail(String sku, int quantity, boolean gift) {}

  record Placed(
      EventMetadata metadata,
      String orderId,
      Detail detail,
      List<String> tags,
      Instant placedAt,
      double total,
      String missing)
      implements DomainEvent {}

  static PostgresFixture pg;

  @TempDir Path dir;

  final ObjectMapper json = Json.newMapper();
  App app;
  Database db;

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
            .instantSource(() -> NOW)
            .module(new DatabaseModule())
            .module(new OutboxModule())
            .start();
    db = app.get(Databases.class).defaultInstance();
  }

  @AfterEach
  void stop() {
    app.close();
  }

  private JsonNode column(String sql) throws SQLException {
    try (Connection c = db.dataSource().getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return json.readTree(rs.getString(1));
    }
  }

  private Map<String, String> row(String id) throws SQLException {
    try (Connection c = db.dataSource().getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT type, coalesce(message_group, '<null>') AS message_group, status, retry_count, "
                    + "client_id, payload_size, coalesce(headers::text, '<null>') AS headers, "
                    + "coalesce(error_message, '<null>') AS error_message, payload, "
                    + "created_at = updated_at AS same_time FROM outbox_messages WHERE id = '"
                    + id
                    + "'")) {
      assertThat(rs.next()).isTrue();
      return Map.of(
          "type", rs.getString("type"),
          "message_group", rs.getString("message_group"),
          "status", rs.getString("status"),
          "retry_count", rs.getString("retry_count"),
          "client_id", rs.getString("client_id"),
          "payload_size", rs.getString("payload_size"),
          "headers", rs.getString("headers"),
          "error_message", rs.getString("error_message"),
          "payload", rs.getString("payload"),
          "same_time", rs.getString("same_time"));
    }
  }

  /** Parses the string-valued {@code data} / {@code operationData} so trees compare whole. */
  private JsonNode expanded(String payload, String stringField) {
    tools.jackson.databind.node.ObjectNode node = (tools.jackson.databind.node.ObjectNode) json.readTree(payload);
    if (node.has(stringField)) {
      node.set(stringField, json.readTree(node.get(stringField).asString()));
    }
    return node;
  }

  @Test
  void anEventRowIsWhatTheSdkWritesForTheSameEvent() throws Exception {
    Context context =
        Context.of(new ClientId("acme"))
            .withPrincipal(new PrincipalId("usr_1"))
            .withApplication(new ApplicationCode("shop"))
            .withCausationId("evt_cause")
            .withExecutionId("job_7");
    Events events = new Events(() -> NOW, app.get(Ids.class));
    Placed event =
        new Placed(
            events.metadata(context, PLACED, new ThingId("ord_1")),
            "ord_1",
            new Detail("SKU-9", 3, true),
            List.of("rush", "gift"),
            Instant.parse("2026-10-07T08:00:00Z"),
            12.5,
            null);

    // Ours.
    OutboxWriter writer = app.get(OutboxWriter.class);
    try (Connection c = db.dataSource().getConnection()) {
      writer.writeEvents(new Tx(c), context, List.of(event));
    }

    // The SDK's, from the same logical event.
    EventMetadata m = event.metadata();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = json.convertValue(withoutMetadata(event), Map.class);
    CreateEventDto dto =
        CreateEventDto.create(m.type(), data)
            .withSource(m.source())
            .withSubject(m.subject())
            .withCorrelationId(m.correlationId())
            .withCausationId("evt_cause")
            .withDeduplicationId(m.eventId())
            .withMessageGroup(m.messageGroup())
            .withClientCode("acme")
            .withContextData(
                List.of(
                    new CreateEventDto.ContextEntry("principalId", "usr_1"),
                    new CreateEventDto.ContextEntry("executionId", "job_7")));
    String sdkId = new OutboxManager(new JdbcOutboxDriver(db.dataSource()), "acme").createEvent(dto);

    Map<String, String> ours = row(m.eventId());
    Map<String, String> sdk = row(sdkId);
    for (String col :
        List.of(
            "type", "message_group", "status", "retry_count", "client_id", "headers", "error_message",
            "same_time")) {
      assertThat(ours.get(col)).as(col).isEqualTo(sdk.get(col));
    }
    assertThat(expanded(ours.get("payload"), "data")).isEqualTo(expanded(sdk.get("payload"), "data"));
    assertThat(ours.get("payload_size")).isEqualTo(sdk.get("payload_size"));
    // Not just equal trees: the very same text, key order included.
    assertThat(ours.get("payload")).isEqualTo(sdk.get("payload"));
    // The data really is a nested JSON string with the record's fields and without the metadata.
    JsonNode inner = json.readTree(json.readTree(ours.get("payload")).get("data").asString());
    assertThat(inner.propertyNames())
        .containsExactlyInAnyOrder("orderId", "detail", "tags", "placedAt", "total");
    assertThat(inner.get("placedAt").asString()).isEqualTo("2026-10-07T08:00:00Z");
  }

  private tools.jackson.databind.node.ObjectNode withoutMetadata(Placed event) {
    tools.jackson.databind.node.ObjectNode tree = json.valueToTree(event);
    tree.remove("metadata");
    return tree;
  }

  @Test
  void anAuditRowIsWhatTheSdkWritesForTheSameEntry() throws Exception {
    Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("email", "a@b.c");
    data.put("roles", List.of("admin", "user"));
    data.put("count", 2);
    AuditEntry entry =
        new AuditEntry(
            "Order",
            "ord_1",
            "place-order",
            data,
            "usr_1",
            NOW,
            "shop:sales",
            "corr-1",
            "shop",
            new ClientId("acme"));

    OutboxWriter writer = app.get(OutboxWriter.class);
    try (Connection c = db.dataSource().getConnection()) {
      writer.writeAudit(new Tx(c), entry);
    }
    CreateAuditLogDto dto =
        CreateAuditLogDto.create("Order", "ord_1", "place-order")
            .withOperationData(data)
            .withPrincipalId("usr_1")
            .withPerformedAt(NOW)
            .withSource("shop:sales")
            .withCorrelationId("corr-1")
            .withApplicationCode("shop")
            .withClientCode("acme");
    String sdkId = new OutboxManager(new JdbcOutboxDriver(db.dataSource()), "acme").createAuditLog(dto);

    String ourId;
    try (Connection c = db.dataSource().getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT id FROM outbox_messages WHERE id <> '" + sdkId + "'")) {
      assertThat(rs.next()).isTrue();
      ourId = rs.getString(1);
    }
    Map<String, String> ours = row(ourId);
    Map<String, String> sdk = row(sdkId);
    for (String col :
        List.of(
            "type", "message_group", "status", "retry_count", "client_id", "headers", "error_message",
            "same_time", "payload_size")) {
      assertThat(ours.get(col)).as(col).isEqualTo(sdk.get(col));
    }
    assertThat(expanded(ours.get("payload"), "operationData"))
        .isEqualTo(expanded(sdk.get("payload"), "operationData"));
    assertThat(ours.get("payload")).isEqualTo(sdk.get("payload"));
  }

  @Test
  void anAuditEntryWithoutCommandDataOrPrincipalOmitsThoseFieldsLikeTheSdk() throws Exception {
    AuditEntry entry =
        new AuditEntry(
            "Order", "ord_1", "place-order", null, null, NOW, "shop:sales", "corr-1", null, new ClientId("acme"));
    try (Connection c = db.dataSource().getConnection()) {
      app.get(OutboxWriter.class).writeAudit(new Tx(c), entry);
    }
    CreateAuditLogDto dto =
        CreateAuditLogDto.create("Order", "ord_1", "place-order")
            .withPerformedAt(NOW)
            .withSource("shop:sales")
            .withCorrelationId("corr-1")
            .withClientCode("acme");
    String sdkId = new OutboxManager(new JdbcOutboxDriver(db.dataSource()), "acme").createAuditLog(dto);

    JsonNode sdkPayload = column("SELECT payload FROM outbox_messages WHERE id = '" + sdkId + "'");
    JsonNode ourPayload =
        column("SELECT payload FROM outbox_messages WHERE id <> '" + sdkId + "'");
    assertThat(ourPayload).isEqualTo(sdkPayload);
    assertThat(ourPayload.has("operationData")).isFalse();
    assertThat(ourPayload.has("principalId")).isFalse();
  }

  @Test
  void anEventWithoutOptionalMetadataOmitsThoseFieldsLikeTheSdk() throws Exception {
    Context context = Context.of(new ClientId("acme"));
    Events events = new Events(() -> NOW, app.get(Ids.class));
    Placed event =
        new Placed(
            events.metadata(context, PLACED, new ThingId("ord_2")),
            "ord_2",
            new Detail("S", 1, false),
            List.of(),
            NOW,
            0,
            null);
    try (Connection c = db.dataSource().getConnection()) {
      app.get(OutboxWriter.class).writeEvents(new Tx(c), context, List.of(event));
    }
    EventMetadata m = event.metadata();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = json.convertValue(withoutMetadata(event), Map.class);
    String sdkId =
        new OutboxManager(new JdbcOutboxDriver(db.dataSource()), "acme")
            .createEvent(
                CreateEventDto.create(m.type(), data)
                    .withSource(m.source())
                    .withSubject(m.subject())
                    .withCorrelationId(m.correlationId())
                    .withDeduplicationId(m.eventId())
                    .withMessageGroup(m.messageGroup())
                    .withClientCode("acme"));

    JsonNode sdkPayload = column("SELECT payload FROM outbox_messages WHERE id = '" + sdkId + "'");
    JsonNode ourPayload = column("SELECT payload FROM outbox_messages WHERE id = '" + m.eventId() + "'");
    assertThat(ourPayload).isEqualTo(sdkPayload);
    assertThat(ourPayload.has("causationId")).isFalse();
    assertThat(ourPayload.has("contextData")).isFalse();
  }
}
