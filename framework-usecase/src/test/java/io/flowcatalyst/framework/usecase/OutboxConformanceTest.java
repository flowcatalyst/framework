package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.Migrations;
import io.flowcatalyst.framework.database.PostgresFixture;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/outbox/outbox.json} (the rows the outbox writer stores) and
 * {@code conformance/migrations/outbox.json} (the history of the outbox migration set), which the
 * Rust implementation reads.
 */
class OutboxConformanceTest {

  private static final String FILE = "outbox/outbox.json";
  private final ObjectMapper json = Json.newMapper();

  record Line(String sku, int quantity) {}

  record Placed(
      EventMetadata metadata,
      String orderId,
      String customer,
      List<Line> lines,
      long totalCents,
      @Nullable String note,
      Instant placedAt)
      implements DomainEvent {}

  record Cancelled(EventMetadata metadata, String orderId, String reason) implements DomainEvent {}

  record Subject(String value) implements AggregateId {}

  @Test
  void insertStatementIsTheSharedFile() {
    Conformance.check("sql/outbox/insert.sql", OutboxWriter.INSERT_SQL);
  }

  @Test
  void outboxVectors() throws Exception {
    ObjectNode doc = JsonVectors.read(FILE);
    try (PostgresFixture pg = PostgresFixture.start()) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(pg.jdbcUrl());
      config.setUsername(pg.username());
      config.setPassword(pg.password());
      try (HikariDataSource ds = new HikariDataSource(config)) {
        Migrations.apply(
            "main",
            ds,
            List.of(new MigrationSet(OutboxModule.ID, OutboxConformanceTest.class.getClassLoader(), "db/migration/outbox")));
        io.flowcatalyst.framework.conformance.Conformance.check(
            "migrations/outbox.json", history(ds, OutboxModule.ID));

        for (JsonNode c : doc.get("cases")) {
          ObjectNode row = (ObjectNode) c;
          row.set("rows", write(ds, row));
        }
      }
    }
    for (JsonNode c : doc.get("invalidDispatchJobs")) {
      ObjectNode row = (ObjectNode) c;
      row.remove("error");
      try {
        dispatchJob(row);
        row.putNull("error");
      } catch (IllegalArgumentException e) {
        row.put("error", e.getMessage());
      }
    }
    JsonVectors.check(FILE, doc);
  }

  private ArrayNode write(HikariDataSource ds, ObjectNode c) throws Exception {
    Instant now = Instant.parse(c.get("now").asString());
    JsonNode ctx = c.get("context");
    Context context =
        new Context(
            new ClientId(ctx.get("client").asString()),
            ctx.has("principal") ? new PrincipalId(ctx.get("principal").asString()) : null,
            ctx.has("application") ? new ApplicationCode(ctx.get("application").asString()) : null,
            new CorrelationId(ctx.get("correlation").asString()),
            ctx.has("causation") ? ctx.get("causation").asString() : null,
            ctx.has("execution") ? ctx.get("execution").asString() : null);
    List<DomainEvent> events = new ArrayList<>();
    for (JsonNode e : c.get("events")) {
      events.add(event(context, now, e));
    }
    List<DispatchJob> jobs = new ArrayList<>();
    for (JsonNode j : c.get("dispatchJobs")) {
      jobs.add(dispatchJob(j));
    }
    JsonNode a = c.get("audit");
    Map<String, Object> data = null;
    if (a.has("data")) {
      data = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> d : a.get("data").properties()) {
        data.put(d.getKey(), d.getValue().isNumber() ? d.getValue().numberValue() : d.getValue().asString());
      }
    }
    AuditEntry audit =
        new AuditEntry(
            a.get("entityType").asString(),
            a.get("entityId").asString(),
            a.get("operation").asString(),
            data,
            context.principal() == null ? null : context.principal().value(),
            now,
            a.get("source").asString(),
            context.correlationId().value(),
            context.application() == null ? null : context.application().value(),
            context.client());

    OutboxWriter writer = new OutboxWriter(new Ids(() -> now), () -> now);
    try (Connection conn = ds.getConnection()) {
      try (Statement s = conn.createStatement()) {
        s.execute("TRUNCATE outbox_messages");
      }
      conn.setAutoCommit(false);
      Tx tx = new Tx(conn);
      writer.writeEvents(tx, context, events);
      if (!jobs.isEmpty()) {
        writer.writeDispatchJobs(tx, context, jobs);
      }
      writer.writeAudit(tx, audit);
      conn.commit();
      conn.setAutoCommit(true);
      return rows(conn);
    }
  }

  private DomainEvent event(Context context, Instant now, JsonNode e) {
    String[] t = e.get("type").asString().split(":");
    EventType type = new EventType(t[0], t[1], t[2], t[3]);
    Subject subject = new Subject(e.get("subjectId").asString());
    EventMetadata meta =
        new EventMetadata(
            e.get("eventId").asString(),
            "1.0",
            type.type(),
            type.source(),
            type.subject(subject),
            now,
            context.correlationId().value(),
            context.principal() == null ? null : context.principal().value(),
            context.causationId(),
            context.executionId(),
            type.messageGroup(subject));
    if (e.has("messageGroup")) {
      meta = meta.withMessageGroup(e.get("messageGroup").asString());
    }
    JsonNode f = e.get("fields");
    return switch (e.get("kind").asString()) {
      case "placed" -> {
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : f.get("lines")) {
          lines.add(new Line(l.get("sku").asString(), l.get("quantity").asInt()));
        }
        yield new Placed(
            meta,
            f.get("orderId").asString(),
            f.get("customer").asString(),
            lines,
            f.get("totalCents").asLong(),
            f.get("note").isNull() ? null : f.get("note").asString(),
            Instant.parse(f.get("placedAt").asString()));
      }
      case "cancelled" -> new Cancelled(meta, f.get("orderId").asString(), f.get("reason").asString());
      default -> throw new IllegalArgumentException("unknown event kind " + e);
    };
  }

  private static DispatchJob dispatchJob(JsonNode j) {
    DispatchJob.Builder b = DispatchJob.builder(j.get("code").asString(), URI.create(j.get("targetUrl").asString()));
    if (j.has("id")) b.id(j.get("id").asString());
    if (j.has("kind")) b.kind(DispatchJob.Kind.valueOf(j.get("kind").asString()));
    if (j.has("source")) b.source(j.get("source").asString());
    if (j.has("subject")) b.subject(j.get("subject").asString());
    if (j.has("jsonPayload")) b.jsonPayload(j.get("jsonPayload"));
    if (j.has("payloadText")) b.payload(j.get("payloadText").asString(), j.get("contentType").asString());
    if (j.has("dataOnly")) b.dataOnly(j.get("dataOnly").asBoolean());
    if (j.has("externalId")) b.externalId(j.get("externalId").asString());
    if (j.has("eventId")) b.eventId(j.get("eventId").asString());
    if (j.has("platformClientId")) b.platformClientId(j.get("platformClientId").asString());
    if (j.has("subscriptionId")) b.subscriptionId(j.get("subscriptionId").asString());
    if (j.has("serviceAccountId")) b.serviceAccountId(j.get("serviceAccountId").asString());
    if (j.has("dispatchPoolId")) b.dispatchPoolId(j.get("dispatchPoolId").asString());
    if (j.has("messageGroup")) b.messageGroup(j.get("messageGroup").asString());
    if (j.has("mode")) b.mode(DispatchJob.Mode.valueOf(j.get("mode").asString()));
    if (j.has("sequence")) b.sequence(j.get("sequence").asInt());
    if (j.has("timeoutSeconds")) b.timeoutSeconds(j.get("timeoutSeconds").asInt());
    if (j.has("maxRetries")) b.maxRetries(j.get("maxRetries").asInt());
    if (j.has("metadata")) {
      for (JsonNode pair : j.get("metadata")) {
        b.metadata(pair.get(0).asString(), pair.get(1).asString());
      }
    }
    if (j.has("queue")) b.queue(DispatchJob.Queue.valueOf(j.get("queue").asString()));
    if (j.has("descriptor")) b.descriptor(j.get("descriptor").asString());
    return b.build();
  }

  private ArrayNode rows(Connection conn) throws Exception {
    ArrayNode out = json.createArrayNode();
    try (Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT id, type, message_group, status, retry_count, client_id, payload_size, headers::text,"
                    + " error_message, " + utc("created_at") + ", " + utc("updated_at") + ", payload"
                    + " FROM outbox_messages ORDER BY ctid")) {
      while (rs.next()) {
        ObjectNode r = out.addObject();
        String type = rs.getString(2);
        r.put("id", type.equals("AUDIT_LOG") ? "<tsid>" : rs.getString(1));
        r.put("type", type);
        r.put("message_group", rs.getString(3));
        r.put("status", rs.getInt(4));
        r.put("retry_count", rs.getInt(5));
        r.put("client_id", rs.getString(6));
        r.put("payload_size", rs.getInt(7));
        r.put("headers", rs.getString(8));
        r.put("error_message", rs.getString(9));
        r.put("created_at", rs.getString(10));
        r.put("updated_at", rs.getString(11));
        String payload = rs.getString(12);
        ObjectNode parsed = (ObjectNode) json.readTree(payload);
        for (String nested : List.of("data", "operationData")) {
          if (parsed.has(nested)) {
            parsed.set(nested, json.readTree(parsed.get(nested).asString()));
          }
        }
        r.set("payload", parsed);
        r.put("payloadText", payload);
      }
    }
    return out;
  }

  /** A timestamptz column as UTC text with microseconds, whatever the session's time zone. */
  static String utc(String column) {
    return "to_char(" + column + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
  }

  /** The history rows of one module as JSON, without applied_at. */
  static String history(HikariDataSource ds, String module) throws Exception {
    ObjectMapper m = Json.newMapper();
    ObjectNode doc = m.createObjectNode();
    doc.put(
        "description",
        "The fw_schema_history rows the Java migration runner writes for the set of module "
            + module
            + " (applied_at left out). A Rust run of the same set writes the same rows, and the Rust runner accepts a database holding them.");
    doc.put("module", module);
    ArrayNode rows = doc.putArray("rows");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT version, description, checksum FROM fw_schema_history WHERE module = '"
                    + module
                    + "' ORDER BY version")) {
      while (rs.next()) {
        ObjectNode r = rows.addObject();
        r.put("version", rs.getInt(1));
        r.put("description", rs.getString(2));
        r.put("checksum", rs.getString(3));
      }
    }
    return m.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n";
  }
}
