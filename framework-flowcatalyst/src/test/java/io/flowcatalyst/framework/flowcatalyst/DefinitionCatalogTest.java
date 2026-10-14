package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionCatalogTest {

  @TempDir Path dir;

  private FakePlatform platform;

  @BeforeEach
  void startPlatform() {
    platform = FakePlatform.start();
  }

  @AfterEach
  void stopPlatform() {
    platform.close();
  }

  private App start(Map<String, String> env, Contributor... contributors) {
    return start(env, FlowCatalystOptions.defaults(), contributors);
  }

  private App start(Map<String, String> env, FlowCatalystOptions options, Contributor... contributors) {
    var builder = App.builder().configDirectory(dir).environment(env).module(new FlowCatalystModule(options));
    for (Contributor c : contributors) {
      builder.module(c);
    }
    return builder.start();
  }

  private StartupException failure(Contributor... contributors) {
    Throwable t = catchThrowable(() -> start(Contributor.env(platform), contributors).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  private static Problem problemFor(StartupException e, String key) {
    return e.problems().stream()
        .filter(p -> key.equals(p.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no problem for " + key + " in " + e.problems()));
  }

  @Test
  void validDefinitionsFromSeveralModulesAreCollectedInOrder() {
    Contributor sales =
        Contributor.of(
            "sales",
            d ->
                d.eventType("orders:sales:order:placed", "Order placed", "d")
                    .permission("orders:sales:order:read", "Read orders")
                    .role("clerk", "orders:sales:order:read"));
    Contributor billing =
        Contributor.of(
            "billing",
            d ->
                d.eventType("orders:billing:invoice:issued", "Invoice issued", null)
                    .connection("main", "Main")
                    .docs("guide", "# Guide"));

    try (App app = start(Contributor.env(platform), sales, billing)) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);
      assertThat(catalog.eventTypes())
          .extracting(Definition.EventType::code)
          .containsExactly("orders:sales:order:placed", "orders:billing:invoice:issued");
      assertThat(catalog.permissions()).hasSize(1);
      assertThat(catalog.roles()).extracting(Definition.Role::name).containsExactly("clerk");
      assertThat(catalog.connections()).hasSize(1);
      assertThat(catalog.docs()).extracting(Definition.Doc::slug).containsExactly("guide");
    }
  }

  @Test
  void anEventTypeOfAnotherApplicationNamesTheModuleAndTheCode() {
    StartupException e =
        failure(Contributor.of("sales", d -> d.eventType("billing:sales:order:placed", "X", null)));

    Problem p = problemFor(e, "billing:sales:order:placed");
    assertThat(p.module()).isEqualTo("sales");
    assertThat(p.message()).contains("must start with the application 'orders'");
  }

  @Test
  void everyProblemIsReportedTogether() {
    Contributor sales =
        Contributor.of(
            "sales",
            d ->
                d.eventType("orders:sales:order", "Three parts", null)
                    .eventType("orders:Sales:order:placed", "Upper case", null)
                    .permission("billing:x:y:z", "wrong app")
                    .role("orders:clerk")
                    .role("clerk", "not-a-permission")
                    .subscription(Definition.Subscription.of("hook", "Hook", "/relative", "orders:sales:order:placed"))
                    .subscription(Definition.Subscription.of("empty", "Empty", "https://x.example/h"))
                    .scheduledJob(Definition.ScheduledJob.of("five", "Five fields", "0 2 * * *"))
                    .process(new Definition.Process("orders:checkout", "Two parts", null, null, null, null))
                    .docs("Not A Slug", "text")
                    .dispatchPool(new Definition.DispatchPool("pool", "Pool", null, 0, null)));
    Contributor billing =
        Contributor.of("billing", d -> d.connection("main", "Main").connection("main", "Again"));

    StartupException e = failure(sales, billing);

    assertThat(problemFor(e, "orders:sales:order").message()).contains("{application}:{domain}:{aggregate}:{action}");
    assertThat(problemFor(e, "orders:Sales:order:placed").module()).isEqualTo("sales");
    assertThat(problemFor(e, "billing:x:y:z").message()).contains("must start with the application 'orders'");
    assertThat(problemFor(e, "orders:clerk").message()).contains("must not include the application prefix");
    assertThat(problemFor(e, "not-a-permission").message()).contains("permission of role 'clerk'");
    assertThat(problemFor(e, "hook").message()).contains("subscription-target-base-url");
    assertThat(problemFor(e, "empty").message()).contains("at least one event type");
    assertThat(problemFor(e, "five").message()).contains("6 fields");
    assertThat(problemFor(e, "orders:checkout").message()).contains("{application}:{subdomain}:{process}");
    assertThat(problemFor(e, "Not A Slug").message()).contains("kebab-case");
    assertThat(problemFor(e, "pool").message()).contains("rateLimit must be positive");
    Problem duplicate = problemFor(e, "main");
    assertThat(duplicate.module()).isEqualTo("billing");
    assertThat(duplicate.message()).contains("duplicate connection").contains("module 'billing'");
    assertThat(e.problems()).hasSize(12);
  }

  @Test
  void aDuplicateAcrossModulesNamesBothModules() {
    StartupException e =
        failure(
            Contributor.of("a", d -> d.eventType("orders:x:y:z", "One", null)),
            Contributor.of("b", d -> d.eventType("orders:x:y:z", "Two", null)));

    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().getFirst().module()).isEqualTo("b");
    assertThat(e.problems().getFirst().message()).contains("already declared by module 'a'");
  }

  @Test
  void aPathTargetIsAcceptedWhenABaseUrlIsConfigured() {
    Map<String, String> env = Contributor.env(platform);
    env.put("FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL", "https://orders.example.com");
    Contributor sales =
        Contributor.of(
            "sales",
            d -> d.subscription(Definition.Subscription.of("hook", "Hook", "/hooks/placed", "orders:sales:order:placed")));

    try (App app = start(env, sales)) {
      assertThat(app.get(DefinitionCatalog.class).subscriptions()).hasSize(1);
    }
  }

  @Test
  void disabledSkipsValidationAndTheCatalogIsEmpty() {
    Contributor bad = Contributor.of("sales", d -> d.eventType("garbage", "X", null));

    try (App app = start(Map.of(), FlowCatalystOptions.disabled(), bad)) {
      assertThat(app.get(DefinitionCatalog.class).eventTypes()).isEmpty();
    }
  }

  @Test
  void definitionsOutsideRegisterAreRejected() {
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(() -> Definitions.in(App.builder()))
        .withMessageContaining("register");
  }

  @Test
  void aRoleKeepsItsPermissionsAsDeclared() {
    assertThat(Definition.Role.of("r", "a:b:c:d", "a:b:c:e").permissions())
        .isEqualTo(List.of("a:b:c:d", "a:b:c:e"));
  }
}
