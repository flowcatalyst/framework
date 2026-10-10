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
}
