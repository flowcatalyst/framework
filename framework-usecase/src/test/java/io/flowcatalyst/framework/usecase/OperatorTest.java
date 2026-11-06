package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.Actor;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.Ids;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An impersonated field request records the worker as the subject and the actor as the operator. */
class OperatorTest {

  private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  private final OutboxWriter writer = new OutboxWriter(new Ids(() -> NOW), () -> NOW);

  private static Context worker() {
    return Context.of(new ClientId("acme")).withPrincipal(new PrincipalId("fwu_7"), PrincipalKind.FIELD_WORKER);
  }

  private EventMetadata metadata(Context context) {
    return new Events(() -> NOW, new Ids(() -> NOW))
        .metadata(context, new EventType("orders", "gate", "visit", "opened"), new AggregateId() {
          @Override
          public String value() {
            return "1";
          }
        });
  }

  @Test
  void theEventCarriesTheOperatorInContextDataOnlyWhenThereIsOne() {
    EventMetadata plain = metadata(worker());
    assertThat(plain.operatorId()).isNull();
    Context acting = worker().withActor(new Actor("prn_ADMIN", "principal", "Admin"));
    EventMetadata meta = metadata(acting);
    assertThat(meta.principalId()).isEqualTo("fwu_7");
    assertThat(meta.operatorId()).isEqualTo("prn_ADMIN");
    assertThat(meta.operatorType()).isEqualTo("principal");
  }

  @Test
  void theAuditRowCarriesTheOperatorInOperationData() {
    AuditEntry entry =
        new AuditEntry("Visit", "1", "Open", null, "fwu_7", NOW, "orders:gate", "corr", null, new ClientId("acme"), "prn_ADMIN", "principal");
    Map<String, Object> payload = writer.auditPayload(entry);
    assertThat(payload.get("principalId")).isEqualTo("fwu_7");
    assertThat((String) payload.get("operationData")).isEqualTo("{\"operatorId\":\"prn_ADMIN\",\"operatorType\":\"principal\"}");
    AuditEntry plain = new AuditEntry("Visit", "1", "Open", null, "fwu_7", NOW, "orders:gate", "corr", null, new ClientId("acme"));
    assertThat(writer.auditPayload(plain)).doesNotContainKey("operationData");
  }

  @Test
  void aNewPrincipalDropsTheActor() {
    Context acting = worker().withActor(new Actor("prn_ADMIN", "principal", null));
    assertThat(acting.findActor()).isPresent();
    assertThat(acting.withPrincipal(new PrincipalId("fwu_8")).findActor()).isEmpty();
    assertThat(acting.forClient(new ClientId("other")).findActor()).isPresent();
  }
}
