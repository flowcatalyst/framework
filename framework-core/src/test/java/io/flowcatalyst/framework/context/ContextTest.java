package io.flowcatalyst.framework.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
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

  @Test
  void aContextWithoutPermissionsMayDoNothing() {
    Context c = Context.of(new ClientId("clt_1"));
    assertThat(c.permissions()).isEmpty();
    assertThat(c.hasPermission("orders:order:create")).isFalse();
    assertThat(c.hasPermission("")).isFalse();
  }

  @Test
  void permissionsMatchSegmentBySegmentWithAHeldWildcard() {
    Context c = Context.of(new ClientId("clt_1")).withPermissions(Set.of("orders:order:create", "billing:*:view"));
    assertThat(c.hasPermission("orders:order:create")).isTrue();
    assertThat(c.hasPermission("billing:invoice:view")).isTrue();
    assertThat(c.hasPermission("billing:invoice:edit")).isFalse();
    // segment counts must be equal: a wildcard never stands for more or fewer segments
    assertThat(c.hasPermission("billing:invoice:line:view")).isFalse();
    assertThat(c.hasPermission("billing:view")).isFalse();
    // a wildcard in the requirement grants nothing by itself
    assertThat(c.hasPermission("orders:*:create")).isFalse();
    assertThat(c.hasPermission("orders:order")).isFalse();
    assertThat(c.hasPermission("  ")).isFalse();
  }

  @Test
  void aHeldEverythingPermissionStillNeedsTheSameShape() {
    Context c = Context.of(new ClientId("clt_1")).withPermissions(Set.of("*:*:*"));
    assertThat(c.hasPermission("a:b:c")).isTrue();
    assertThat(c.hasPermission("a:b")).isFalse();
    // a held single "*" matches any one-segment permission, but never a blank requirement
    Context any = Context.of(new ClientId("clt_1")).withPermissions(Set.of("*"));
    assertThat(any.hasPermission("admin")).isTrue();
    assertThat(any.hasPermission("")).isFalse();
    assertThat(any.hasPermission("  ")).isFalse();
  }

  @Test
  void permissionsSurviveTheOtherWithMethodsAndAreCopied() {
    java.util.HashSet<String> given = new java.util.HashSet<>(Set.of("a:b"));
    Context c = Context.of(new ClientId("clt_1")).withPermissions(given);
    given.add("c:d");
    Context d = c.withPrincipal(new PrincipalId("prn_1")).withCorrelationId(new CorrelationId("x")).withCausationId("e");
    assertThat(d.permissions()).containsExactly("a:b");
    assertThat(new Context(new ClientId("c"), null, null, new CorrelationId("x"), null, null).permissions()).isEmpty();
  }

  // ---- the principal kind (core contract section 3, amended 2026-10-14) ----------------------

  @Test
  void aKindWithoutAPrincipalIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Context(
                    new ClientId("c"), null, PrincipalKind.SERVICE, null, new CorrelationId("x"), null, null, Set.of()))
        .withMessageContaining("principal kind");
  }

  @Test
  void theConstructorsThatPredateTheKindLeaveItUnknown() {
    PrincipalId p = new PrincipalId("prn_1");
    CorrelationId corr = new CorrelationId("x");
    ClientId c = new ClientId("c");
    assertThat(new Context(c, p, null, corr).principalKind()).isNull();
    assertThat(new Context(c, p, null, corr, "cause", "run").principalKind()).isNull();
    assertThat(new Context(c, p, null, corr, "cause", "run", Set.of("a:b")).principalKind()).isNull();
    assertThat(new Context(c, p, null, corr, "cause", "run", Set.of("a:b")).findPrincipalKind()).isEmpty();
  }

  @Test
  void withPrincipalAndKindSetsBothAndThePlainOneClearsTheKind() {
    Context service =
        Context.of(new ClientId("c")).withPrincipal(new PrincipalId("prn_svc"), PrincipalKind.SERVICE);
    assertThat(service.principal()).isEqualTo(new PrincipalId("prn_svc"));
    assertThat(service.principalKind()).isEqualTo(PrincipalKind.SERVICE);
    assertThat(service.findPrincipalKind()).contains(PrincipalKind.SERVICE);
    // a kind never outlives the principal it described
    Context other = service.withPrincipal(new PrincipalId("prn_user"));
    assertThat(other.principal()).isEqualTo(new PrincipalId("prn_user"));
    assertThat(other.principalKind()).isNull();
  }

  @Test
  void theKindSurvivesTheOtherWithMethods() {
    Context c =
        Context.of(new ClientId("c"))
            .withPrincipal(new PrincipalId("prn_1"), PrincipalKind.USER)
            .withApplication(new ApplicationCode("shop"))
            .withCorrelationId(new CorrelationId("y"))
            .withCausationId("dj_1")
            .withExecutionId("dj_1")
            .withPermissions(Set.of("a:b"));
    assertThat(c.principalKind()).isEqualTo(PrincipalKind.USER);
    assertThat(c.causationId()).isEqualTo("dj_1");
    assertThat(c).isEqualTo(
        new Context(
            new ClientId("c"),
            new PrincipalId("prn_1"),
            PrincipalKind.USER,
            new ApplicationCode("shop"),
            new CorrelationId("y"),
            "dj_1",
            "dj_1",
            Set.of("a:b")));
  }

  @Test
  void thereAreExactlyTwoKinds() {
    assertThat(PrincipalKind.values()).containsExactly(PrincipalKind.USER, PrincipalKind.SERVICE);
  }

  @Test
  void forClientChangesOnlyTheClient() {
    Context base =
        Context.of(new ClientId("_platform"))
            .withPrincipal(new PrincipalId("prn_svc"), PrincipalKind.SERVICE)
            .withApplication(new ApplicationCode("tickets"))
            .withCorrelationId(new CorrelationId("corr-1"))
            .withCausationId("job-9")
            .withExecutionId("run-3")
            .withPermissions(Set.of("tickets:ticket:flag"));

    Context acme = base.forClient(new ClientId("acme"));

    assertThat(acme.client()).isEqualTo(new ClientId("acme"));
    assertThat(acme).usingRecursiveComparison().ignoringFields("client").isEqualTo(base);
    assertThat(base.client()).isEqualTo(new ClientId("_platform")); // the original is unchanged
  }

  @Test
  void perClientRunsEachClientWithItsOwnContextAndOneFailureDoesNotStopTheRest() {
    Context base = Context.of(new ClientId("_platform")).withPrincipal(new PrincipalId("prn_svc"), PrincipalKind.SERVICE);
    java.util.List<String> seen = new java.util.ArrayList<>();

    var outcomes =
        PerClient.<String>each(
            base,
            java.util.List.of(new ClientId("acme"), new ClientId("globex"), new ClientId("acme"), new ClientId("initech")),
            ctx -> {
              seen.add(ctx.client().value() + "/" + ctx.principal());
              if (ctx.client().value().equals("globex")) {
                throw new IllegalStateException("globex is down");
              }
              return "ok " + ctx.client();
            });

    assertThat(seen).containsExactly("acme/prn_svc", "globex/prn_svc", "initech/prn_svc"); // acme once
    assertThat(outcomes.keySet()).extracting(ClientId::value).containsExactly("acme", "globex", "initech");
    assertThat(outcomes.get(new ClientId("acme"))).isEqualTo(new PerClient.Outcome.Done<>("ok acme"));
    assertThat(outcomes.get(new ClientId("globex"))).isInstanceOf(PerClient.Outcome.Failed.class);
    assertThat(outcomes.get(new ClientId("initech"))).isEqualTo(new PerClient.Outcome.Done<>("ok initech"));
  }

  @Test
  void perClientStopsStartingClientsOnceTheThreadIsInterrupted() {
    Context base = Context.of(new ClientId("_platform"));
    try {
      var outcomes =
          PerClient.<String>each(
              base,
              java.util.List.of(new ClientId("a"), new ClientId("b"), new ClientId("c")),
              ctx -> {
                Thread.currentThread().interrupt(); // shutdown arrives during the first client
                return ctx.client().value();
              });

      assertThat(outcomes.keySet()).extracting(ClientId::value).containsExactly("a");
    } finally {
      Thread.interrupted(); // clear the flag for the next test
    }
  }
}
