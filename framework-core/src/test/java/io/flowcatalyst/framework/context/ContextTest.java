package io.flowcatalyst.framework.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ContextTest {

  @Test
  void idsRejectBlankValues() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientId(""));
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientId("  "));
    assertThatIllegalArgumentException().isThrownBy(() -> new PrincipalId(" "));
    assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId(""));
    assertThat(new ClientId("clt_1").value()).isEqualTo("clt_1");
  }

  @Test
  void clientIdsNeverContainAColonAndAreAtMost100Characters() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientId("a:b"));
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientId("a b"));
    assertThatIllegalArgumentException().isThrownBy(() -> new ClientId("x".repeat(101)));
    assertThat(new ClientId("x".repeat(100)).value()).hasSize(100);
    assertThat(new ClientId("clt_0Ab.c-9").value()).isEqualTo("clt_0Ab.c-9");
  }

  @Test
  void applicationCodeMatchesThePattern() {
    new ApplicationCode("billing");
    new ApplicationCode("a1-b2");
    for (String bad : new String[] {"", "Billing", "1a", "a_b", "a b", "-a", "a.b"}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> new ApplicationCode(bad));
    }
  }

  @Test
  void contextAlwaysHasAClientAndACorrelationId() {
    Context c = Context.of(new ClientId("clt_1"));
    assertThat(c.client()).isEqualTo(new ClientId("clt_1"));
    assertThat(c.correlationId().value()).isNotBlank();
    assertThat(c.findPrincipal()).isEmpty();
    assertThat(c.findApplication()).isEmpty();
    assertThat(Context.of(new ClientId("clt_1")).correlationId()).isNotEqualTo(c.correlationId());
  }

  @Test
  void withMethodsCopyAndKeepTheRest() {
    Context c = Context.of(new ClientId("clt_1"));
    Context d =
        c.withPrincipal(new PrincipalId("usr_9"))
            .withApplication(new ApplicationCode("billing"))
            .withCorrelationId(new CorrelationId("abc"));
    assertThat(d.client()).isEqualTo(c.client());
    assertThat(d.findPrincipal()).contains(new PrincipalId("usr_9"));
    assertThat(d.findApplication()).contains(new ApplicationCode("billing"));
    assertThat(d.correlationId().value()).isEqualTo("abc");
    assertThat(c.findPrincipal()).isEmpty();
  }

  @Test
  void causationAndExecutionIdsSurviveTheOtherWithMethods() {
    Context c =
        Context.of(new ClientId("clt_1")).withCausationId("evt_1").withExecutionId("job_7");
    Context d = c.withPrincipal(new PrincipalId("usr_9")).withApplication(new ApplicationCode("x"));
    assertThat(d.causationId()).isEqualTo("evt_1");
    assertThat(d.executionId()).isEqualTo("job_7");
    assertThat(Context.of(new ClientId("clt_1")).causationId()).isNull();
  }
}
