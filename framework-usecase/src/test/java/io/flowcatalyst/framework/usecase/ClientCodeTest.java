package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The payloads' {@code clientCode} (write-path contract section 7, amended 2026-11-18): an event
 * and an audit row of a client's context name that client, as a dispatch job does; of the
 * platform's own context ({@link ClientId#PLATFORM}) they name none, because {@code _platform} is
 * no platform client's identifier and the platform could not resolve it (it drops an audit row it
 * cannot resolve and refuses an event's batch from a credential that is not an anchor).
 */
class ClientCodeTest {

  static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");
  static final ClientId ACME = new ClientId("acme");

  record Cancelled(EventMetadata metadata, String invoiceId) implements DomainEvent {}

  final OutboxWriter writer = new OutboxWriter(new Ids(() -> NOW), () -> NOW);

  Map<String, Object> eventPayload(ClientId client) {
    Context cx = Context.of(client).withPrincipal(new PrincipalId("svc-1"));
    EventMetadata meta =
        new EventMetadata(
            "0ABCDEFGHJKMS", "1.0", "shop:billing:invoice:cancelled", "shop:billing",
            "billing.invoice.inv_1", NOW, cx.correlationId().value(), "svc-1", null, null,
            "billing:invoice:inv_1");
    return writer.eventPayload(cx, new Cancelled(meta, "inv_1"));
  }

  Map<String, Object> auditPayload(ClientId client) {
    return writer.auditPayload(
        new AuditEntry(
            "Invoice", "inv_1", "sweep-invoices", null, "svc-1", NOW, "shop:billing", "corr-1", null,
            client));
  }

  @Test
  void anEventAndAuditRowOfAClientsContextNameThatClientWhereTheyAlwaysDid() {
    Map<String, Object> event = eventPayload(ACME);
    assertThat(event).containsEntry("clientCode", "acme");
    // after messageGroup, before contextData
    assertThat(new ArrayList<>(event.keySet()))
        .containsSubsequence("messageGroup", "clientCode", "contextData");
    assertThat(new ArrayList<>(event.keySet()).indexOf("clientCode"))
        .isEqualTo(new ArrayList<>(event.keySet()).indexOf("messageGroup") + 1);

    Map<String, Object> audit = auditPayload(ACME);
    assertThat(audit).containsEntry("clientCode", "acme");
    assertThat(new ArrayList<>(audit.keySet())).last().isEqualTo("clientCode");
  }

  @Test
  void anEventAndAuditRowOfThePlatformsOwnContextNameNoClient() {
    Map<String, Object> event = eventPayload(ClientId.PLATFORM);
    assertThat(event).doesNotContainKey("clientCode");
    assertThat(new ArrayList<>(event.keySet())).endsWith("messageGroup", "contextData");

    Map<String, Object> audit = auditPayload(ClientId.PLATFORM);
    assertThat(audit).doesNotContainKey("clientCode");
    assertThat(new ArrayList<>(audit.keySet())).last().isEqualTo("correlationId");
  }

  @Test
  void aClientCodeOtherThanThePlatformsIsWrittenAsItIs() {
    // only the one value is left out: a code that merely looks platform-like is the application's
    ClientId other = new ClientId("_platform2");
    assertThat(eventPayload(other)).containsEntry("clientCode", "_platform2");
    assertThat(auditPayload(other)).containsEntry("clientCode", "_platform2");
  }
}
