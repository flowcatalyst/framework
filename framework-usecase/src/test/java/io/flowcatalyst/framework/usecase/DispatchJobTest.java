package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link DispatchJob}: defaults and the platform's rules, checked when built (section 7a). */
class DispatchJobTest {

  static final URI TARGET = URI.create("https://billing.example.com/hooks/export");

  static DispatchJob.Builder job() {
    return DispatchJob.builder("orders:billing:export", TARGET);
  }

  @Test
  void defaults() {
    DispatchJob j = job().build();
    assertThat(j.kind()).isEqualTo(DispatchJob.Kind.TASK);
    assertThat(j.source()).isEqualTo("orders");
    assertThat(j.payload()).isEqualTo("{}");
    assertThat(j.payloadContentType()).isEqualTo("application/json");
    assertThat(j.dataOnly()).isFalse();
    assertThat(j.id()).isNull();
    assertThat(j.platformClientId()).isNull();
    assertThat(j.mode()).isNull();
    assertThat(j.sequence()).isNull();
    assertThat(j.timeoutSeconds()).isNull();
    assertThat(j.maxRetries()).isNull();
    assertThat(j.metadata()).isEmpty();
  }

  @Test
  void jsonPayloadUsesTheFrameworkMapper() {
    record Body(String invoiceId, int lines) {}
    DispatchJob j = job().jsonPayload(new Body("inv_1", 3)).build();
    assertThat(j.payload()).isEqualTo("{\"invoiceId\":\"inv_1\",\"lines\":3}");
    assertThat(j.payloadContentType()).isEqualTo("application/json");
  }

  @Test
  void theCodeNeedsAnApplicationSegment() {
    for (String bad : new String[] {"export", ":export", "Orders:export", "orders:", "orders: x", "1x:y", "o".repeat(250) + ":abcdef"}) {
      assertThatIllegalArgumentException()
          .as(bad)
          .isThrownBy(() -> DispatchJob.builder(bad, TARGET).build());
    }
    assertThat(DispatchJob.builder("o:x", TARGET).build().source()).isEqualTo("o");
  }

  @Test
  void theTargetMustBeAnAbsoluteHttpUrlWithAHostAndNoUserInfo() {
    for (String bad :
        new String[] {
          "/relative", "ftp://h/x", "mailto:a@b.c", "https:///nohost", "https://user:pw@h.example/x",
          "file:///etc/passwd"
        }) {
      assertThatIllegalArgumentException()
          .as(bad)
          .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create(bad)).build());
    }
    assertThat(DispatchJob.builder("orders:x", URI.create("HTTP://h.example:8080/a?b=c")).build())
        .isNotNull();
  }

  @Test
  void platformIdsFitTheirColumns() {
    assertThatIllegalArgumentException().isThrownBy(() -> job().id("x".repeat(18)).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().id("").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().id("a b").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().platformClientId("acme corp").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().dispatchPoolId("p".repeat(18)).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().subscriptionId("s:1").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().serviceAccountId("").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().eventId("e/1").build());
    assertThat(job().id("0HZXEQ5Y8JY5Z").platformClientId("clt_0HZXEQ5Y8J").build().id())
        .isEqualTo("0HZXEQ5Y8JY5Z");
  }

  @Test
  void textFieldsFitTheirColumns() {
    String tooLong = "x".repeat(256);
    assertThatIllegalArgumentException().isThrownBy(() -> job().source(tooLong).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().source(" ").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().subject(tooLong).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().externalId("").build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().messageGroup(tooLong).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().descriptor(tooLong).build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> job().payload("x", "t".repeat(101)).build());
    assertThat(job().messageGroup("x".repeat(255)).build().messageGroup()).hasSize(255);
  }

  @Test
  void numbersTheplatformWouldTurnIntoDefaultsAreRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> job().sequence(0).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().timeoutSeconds(0).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().maxRetries(0).build());
    assertThatIllegalArgumentException().isThrownBy(() -> job().maxRetries(-1).build());
    assertThat(job().maxRetries(1).timeoutSeconds(1).sequence(1).build().maxRetries()).isEqualTo(1);
  }

  @Test
  void metadataKeepsInsertionOrderAndRefusesBlankKeysAndTooMany() {
    DispatchJob j = job().metadata("b", "2").metadata("a", "1").metadata("b", "3").build();
    assertThat(j.metadata()).containsExactly(Map.entry("b", "3"), Map.entry("a", "1"));
    assertThatIllegalArgumentException().isThrownBy(() -> job().metadata(" ", "x").build());
    DispatchJob.Builder many = job();
    for (int i = 0; i <= DispatchJob.METADATA_MAX; i++) {
      many.metadata("k" + i, "v");
    }
    assertThatIllegalArgumentException().isThrownBy(many::build);
  }

  @Test
  void theIdempotencyKeyIsNotBlankAndAtMost100Bytes() {
    DispatchJob.Builder b = DispatchJob.builder("orders:x", URI.create("https://a.example.com"));
    assertThat(b.idempotencyKey("k".repeat(100)).build().idempotencyKey()).hasSize(100);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create("https://a.example.com")).idempotencyKey(" ").build())
        .withMessageContaining("idempotencyKey");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create("https://a.example.com")).idempotencyKey("k".repeat(101)).build())
        .withMessageContaining("idempotencyKey");
    // bytes, not characters: 50 two-byte characters fit, 51 do not
    assertThat(DispatchJob.builder("orders:x", URI.create("https://a.example.com")).idempotencyKey("\u00e9".repeat(50)).build().idempotencyKey()).hasSize(50);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create("https://a.example.com")).idempotencyKey("\u00e9".repeat(51)).build());
  }

  @Test
  void theConnectionIdIsAPlatformId() {
    assertThat(DispatchJob.builder("orders:x", URI.create("https://a.example.com")).connectionId("con_0HZXEQ5Y8J").build().connectionId())
        .isEqualTo("con_0HZXEQ5Y8J");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create("https://a.example.com")).connectionId("con 1").build())
        .withMessageContaining("connectionId");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DispatchJob.builder("orders:x", URI.create("https://a.example.com")).connectionId("c".repeat(18)).build())
        .withMessageContaining("connectionId");
  }

  @Test
  void theWritersItemNamesTheContextsClientByCodeUnlessThePlatformsOrAnExplicitId() {
    OutboxWriter writer = new OutboxWriter(new io.flowcatalyst.framework.core.Ids(() -> java.time.Instant.EPOCH), () -> java.time.Instant.EPOCH);
    io.flowcatalyst.framework.context.Context acme =
        io.flowcatalyst.framework.context.Context.of(new io.flowcatalyst.framework.context.ClientId("acme"));
    io.flowcatalyst.framework.context.Context platform =
        io.flowcatalyst.framework.context.Context.of(io.flowcatalyst.framework.context.ClientId.PLATFORM);

    Map<String, Object> item = writer.dispatchJobPayload(acme, "j1", job().build());
    assertThat(item).containsEntry("clientCode", "acme").doesNotContainKey("clientId");
    // right after where clientId goes, before the subscription (the batch item's order)
    assertThat(new java.util.ArrayList<>(item.keySet())).endsWith("correlationId", "clientCode");

    assertThat(writer.dispatchJobPayload(platform, "j2", job().build())).doesNotContainKeys("clientCode", "clientId");
    assertThat(writer.dispatchJobPayload(acme, "j3", job().platformClientId("clt_0HZXEQ5Y8J").build()))
        .containsEntry("clientId", "clt_0HZXEQ5Y8J")
        .doesNotContainKey("clientCode");

    Map<String, Object> keyed =
        writer.dispatchJobPayload(acme, "j4", job().externalId("inv_1").descriptor("d").connectionId("con_1").idempotencyKey("k1").build());
    assertThat(keyed).containsEntry("connectionId", "con_1").containsEntry("idempotencyKey", "k1");
    assertThat(new java.util.ArrayList<>(keyed.keySet())).endsWith("descriptor", "connectionId", "idempotencyKey");
    assertThat(writer.dispatchJobPayload(acme, "j5", job().externalId("inv_1").build())).doesNotContainKeys("idempotencyKey", "connectionId");
  }
}
