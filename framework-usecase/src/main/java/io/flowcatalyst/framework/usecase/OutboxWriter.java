package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.Ids;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes events and audit rows to the {@code outbox_messages} table, inside the attempt's
 * transaction (write-path contract, section 7). The table and the payloads are those of the
 * FlowCatalyst Java SDK, so the platform's outbox processor reads them unchanged.
 *
 * <ul>
 *   <li>An event row: type {@code EVENT}, row id = the event's id, the event's message group,
 *       status {@code 0}, the {@code client_id} of the context, and the SDK's event payload:
 *       {@code specVersion, type, source, subject, data} (the record's fields as a JSON string,
 *       without the metadata), {@code correlationId, causationId, deduplicationId} (the event
 *       id), {@code messageGroup}, {@code clientCode} and {@code contextData} (the principal
 *       and the execution id, when present).
 *   <li>The audit row: type {@code AUDIT_LOG}, no message group, the SDK's audit payload.
 * </ul>
 *
 * Provided by {@link OutboxModule}.
 */
public final class OutboxWriter implements Sink {

  /** The SDK's insert, so a row from here and a row from the SDK are the same shape. */
  private static final String INSERT_SQL =
      """
      INSERT INTO outbox_messages (
        id, type, message_group, payload, status, created_at, updated_at,
        client_id, payload_size, headers
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String TYPE_EVENT = "EVENT";
  private static final String TYPE_AUDIT_LOG = "AUDIT_LOG";
  private static final int STATUS_PENDING = 0;

  private final ObjectMapper mapper = Json.newMapper();
  private final Ids ids;
  private final InstantSource clock;

  /**
   * Creates the writer.
   *
   * @param ids generates the audit rows' ids
   * @param clock the wall-clock source for {@code created_at}
   */
  public OutboxWriter(Ids ids, InstantSource clock) {
    this.ids = ids;
    this.clock = clock;
  }

  @Override
  public void writeEvents(Tx tx, Context context, List<DomainEvent> events) throws SQLException {
    List<Row> rows = new ArrayList<>(events.size());
    for (DomainEvent event : events) {
      String payload = serialize(eventPayload(context, event));
      rows.add(
          new Row(
              event.metadata().eventId(),
              TYPE_EVENT,
              event.metadata().messageGroup(),
              payload,
              context.client().value()));
    }
    insert(tx, rows);
  }

  @Override
  public void writeAudit(Tx tx, AuditEntry entry) throws SQLException {
    String payload = serialize(auditPayload(entry));
    insert(tx, List.of(new Row(ids.generate(), TYPE_AUDIT_LOG, null, payload, entry.client().value())));
  }

  // ---- payloads (the SDK's CreateEventDto and CreateAuditLogDto shapes) ----------------------

  Map<String, Object> eventPayload(Context context, DomainEvent event) {
    EventMetadata meta = event.metadata();
    ObjectNode data = mapper.valueToTree(event);
    data.remove("metadata");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("specVersion", meta.specVersion());
    payload.put("type", meta.type());
    payload.put("source", meta.source());
    payload.put("subject", meta.subject());
    payload.put("data", mapper.writeValueAsString(data));
    payload.put("correlationId", meta.correlationId());
    if (meta.causationId() != null) {
      payload.put("causationId", meta.causationId());
    }
    payload.put("deduplicationId", meta.eventId());
    payload.put("messageGroup", meta.messageGroup());
    payload.put("clientCode", context.client().value());
    List<Map<String, String>> contextData = new ArrayList<>();
    if (meta.principalId() != null) {
      contextData.add(entry("principalId", meta.principalId()));
    }
    if (meta.executionId() != null) {
      contextData.add(entry("executionId", meta.executionId()));
    }
    if (!contextData.isEmpty()) {
      payload.put("contextData", contextData);
    }
    return payload;
  }

  Map<String, Object> auditPayload(AuditEntry entry) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entityType", entry.entityType());
    payload.put("entityId", entry.entityId());
    payload.put("operation", entry.operation());
    if (entry.data() != null) {
      payload.put("operationData", mapper.writeValueAsString(entry.data()));
    }
    if (entry.principalId() != null) {
      payload.put("principalId", entry.principalId());
    }
    payload.put("performedAt", entry.performedAt().toString());
    payload.put("source", entry.source());
    payload.put("correlationId", entry.correlationId());
    if (entry.applicationCode() != null) {
      payload.put("applicationCode", entry.applicationCode());
    }
    payload.put("clientCode", entry.client().value());
    return payload;
  }

  private static Map<String, String> entry(String key, String value) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("key", key);
    entry.put("value", value);
    return entry;
  }

  private String serialize(Map<String, Object> payload) {
    return mapper.writeValueAsString(payload);
  }

  // ---- insert ------------------------------------------------------------------------------

  private record Row(
      String id, String type, @Nullable String group, String payload, String clientId) {}

  private void insert(Tx tx, List<Row> rows) throws SQLException {
    Timestamp now = Timestamp.from(clock.instant());
    try (PreparedStatement statement = tx.connection().prepareStatement(INSERT_SQL)) {
      for (Row row : rows) {
        statement.setString(1, row.id());
        statement.setString(2, row.type());
        statement.setString(3, row.group());
        statement.setString(4, row.payload());
        statement.setInt(5, STATUS_PENDING);
        statement.setTimestamp(6, now);
        statement.setTimestamp(7, now);
        statement.setString(8, row.clientId());
        statement.setInt(9, row.payload().getBytes(StandardCharsets.UTF_8).length);
        statement.setNull(10, Types.OTHER);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }
}
