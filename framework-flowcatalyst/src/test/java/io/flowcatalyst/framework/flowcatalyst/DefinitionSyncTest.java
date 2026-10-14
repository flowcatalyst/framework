package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.flowcatalyst.CategoryResult.Outcome;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Category;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Mode;
import io.flowcatalyst.framework.core.App;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DefinitionSyncTest {

  @TempDir Path dir;

  private FakePlatform platform;

  private static final List<String> ALL_RESOURCES =
      List.of(
          "roles",
          "event-types",
          "connections",
          "dispatch-pools",
          "subscriptions",
          "processes",
          "scheduled-jobs");

  @BeforeEach
  void startPlatform() {
    platform = FakePlatform.start();
  }

  @AfterEach
  void stopPlatform() {
    platform.close();
  }

  private static Contributor orders() {
    return Contributor.of(
        "orders",
        d ->
            d.eventType("orders:sales:order:placed", "Order placed", "A customer placed an order.")
                .permission("orders:sales:order:read", "Read orders")
                .permission("orders:sales:order:write", "Write orders")
                .permission("orders:sales:order:orphan", "Granted by no role")
                .role("clerk", "orders:sales:order:read", "orders:sales:order:write")
                .connection("main", "Main")
                .dispatchPool(new Definition.DispatchPool("fast", "Fast", null, 300, 5))
                .subscription(
                    Definition.Subscription.of(
                            "on-placed",
                            "On placed",
                            "https://orders.example.com/hooks/placed",
                            "orders:sales:order:placed")
                        .viaConnection("main")
                        .inPool("fast")
                        .withMode(Definition.DeliveryMode.BLOCK_ON_ERROR))
                .process(
                    new Definition.Process(
                        "orders:sales:checkout", "Checkout", null, "graph TD; A-->B", null, List.of("sales")))
                .scheduledJob(Definition.ScheduledJob.of("nightly", "Nightly", "0 0 2 * * *").tracking(true))
                .docs("guide", "# Guide\ntext"));
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

  private FakePlatform.Recorded call(String resource) {
    return platform.syncRequests().stream()
        .filter(r -> r.isSyncOf(resource))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no sync call for " + resource));
  }

  @Test
  void applySendsEveryCategoryInDependencyOrderWithRemoveUnlistedOff() {
    try (App app = start(Contributor.env(platform), orders())) {
      SyncReport report = app.get(DefinitionSync.class).run(Mode.APPLY);

      assertThat(platform.syncedResources()).isEqualTo(ALL_RESOURCES);
      for (FakePlatform.Recorded r : platform.syncRequests()) {
        assertThat(r.path()).startsWith("/api/applications/orders/");
        assertThat(r.headers().get("authorization")).startsWith("Bearer ");
        assertThat(platform.issued(r.headers().get("authorization").substring("Bearer ".length()))).isTrue();
        if (!r.isSyncOf("scheduled-jobs")) {
          assertThat(r.query()).isEqualTo("removeUnlisted=false");
        }
      }
      assertThat(report.mode()).isEqualTo(Mode.APPLY);
      assertThat(report.hasFailures()).isFalse();
      assertThat(report.categories()).extracting(CategoryResult::category).containsExactly(
          Category.ROLES, Category.EVENT_TYPES, Category.CONNECTIONS, Category.DISPATCH_POOLS,
          Category.SUBSCRIPTIONS, Category.PROCESSES, Category.SCHEDULED_JOBS, Category.DOCS);
      CategoryResult roles = report.of(Category.ROLES);
      assertThat(roles.outcome()).isEqualTo(Outcome.SYNCED);
      assertThat(roles.created()).isEqualTo(1);
      assertThat(roles.codes()).containsExactly("clerk");
      assertThat(report.of(Category.DOCS).outcome()).isEqualTo(Outcome.SKIPPED);
      assertThat(report.of(Category.DOCS).message()).contains("no docs sync");
    }
  }

  @Test
  void theBodiesCarryTheDeclaredDefinitions() {
    try (App app = start(Contributor.env(platform), orders())) {
      app.get(DefinitionSync.class).run(Mode.APPLY);

      JsonNode role = call("roles").json().get("roles").get(0);
      assertThat(role.get("name").asString()).isEqualTo("clerk");
      // the orphan permission is declared but granted by no role, so it is not sent at all
      assertThat(role.get("permissions").valueStream().map(JsonNode::asString))
          .containsExactly("orders:sales:order:read", "orders:sales:order:write");

      JsonNode eventType = call("event-types").json().get("eventTypes").get(0);
      assertThat(eventType.get("code").asString()).isEqualTo("orders:sales:order:placed");
      assertThat(eventType.get("name").asString()).isEqualTo("Order placed");
      assertThat(eventType.get("description").asString()).isEqualTo("A customer placed an order.");

      assertThat(call("connections").json().get("connections").get(0).get("code").asString()).isEqualTo("main");

      JsonNode pool = call("dispatch-pools").json().get("pools").get(0);
      assertThat(pool.get("code").asString()).isEqualTo("fast");
      assertThat(pool.get("rateLimit").asInt()).isEqualTo(300);
      assertThat(pool.get("concurrency").asInt()).isEqualTo(5);

      JsonNode subscription = call("subscriptions").json().get("subscriptions").get(0);
      assertThat(subscription.get("code").asString()).isEqualTo("on-placed");
      assertThat(subscription.get("target").asString()).isEqualTo("https://orders.example.com/hooks/placed");
      assertThat(subscription.get("connectionCode").asString()).isEqualTo("main");
      assertThat(subscription.get("dispatchPoolCode").asString()).isEqualTo("fast");
      assertThat(subscription.get("mode").asString()).isEqualTo("BLOCK_ON_ERROR");
      assertThat(subscription.get("eventTypes").get(0).get("eventTypeCode").asString())
          .isEqualTo("orders:sales:order:placed");
      assertThat(subscription.has("sharedConnection")).isFalse();

      JsonNode process = call("processes").json().get("processes").get(0);
      assertThat(process.get("code").asString()).isEqualTo("orders:sales:checkout");
      assertThat(process.get("body").asString()).isEqualTo("graph TD; A-->B");

      JsonNode jobBody = call("scheduled-jobs").json();
      assertThat(jobBody.get("archiveUnlisted").asBoolean()).isFalse();
      JsonNode job = jobBody.get("jobs").get(0);
      assertThat(job.get("code").asString()).isEqualTo("nightly");
      assertThat(job.get("crons").get(0).asString()).isEqualTo("0 0 2 * * *");
      assertThat(job.get("tracksCompletion").asBoolean()).isTrue();
      assertThat(job.has("clientId")).isFalse();

      // the Java SDK has no docs category: nothing is sent for them
      assertThat(platform.requests()).noneMatch(r -> r.path().contains("/docs/"));
    }
  }

  @Test
  void aDryRunWritesNothingAndDoesNotEvenCallThePlatform() {
    try (App app = start(Contributor.env(platform), orders())) {
      SyncReport report = app.get(DefinitionSync.class).run(Mode.DRY_RUN);

      assertThat(platform.syncRequests()).isEmpty();
      assertThat(platform.requests()).allMatch(r -> r.path().equals("/oauth/token"));
      assertThat(report.mode()).isEqualTo(Mode.DRY_RUN);
      assertThat(report.of(Category.ROLES).outcome()).isEqualTo(Outcome.WOULD_SYNC);
      assertThat(report.of(Category.ROLES).codes()).containsExactly("clerk");
      assertThat(report.of(Category.SUBSCRIPTIONS).codes()).containsExactly("on-placed");
      assertThat(report.of(Category.EVENT_TYPES).sent()).isEqualTo(1);
      assertThat(report.of(Category.DOCS).outcome()).isEqualTo(Outcome.SKIPPED);
      assertThat(report.describe()).anyMatch(l -> l.contains("WOULD_SYNC") && l.contains("clerk"));
    }
  }

  @Test
  void aFailingCategoryDoesNotStopTheOthersAndEveryFailureIsReported() {
    platform.respond("event-types", 500, "{\"error\":\"boom\"}");
    platform.respond("connections", 400, "{\"error\":\"bad connection\"}");
    platform.respond("scheduled-jobs", 500, "{\"error\":\"scheduler down\"}");
    try (App app = start(Contributor.env(platform), orders())) {
      Throwable t = catchThrowable(() -> app.get(DefinitionSync.class).run(Mode.APPLY));

      assertThat(t).isInstanceOf(SyncException.class);
      SyncReport report = ((SyncException) t).report();
      // every category was attempted, in order, despite the failures before it
      assertThat(platform.syncedResources()).isEqualTo(ALL_RESOURCES);
      assertThat(report.hasFailures()).isTrue();
      assertThat(report.categories().stream().filter(r -> r.outcome() == Outcome.FAILED))
          .extracting(CategoryResult::category)
          .containsExactly(Category.EVENT_TYPES, Category.CONNECTIONS, Category.SCHEDULED_JOBS);
      assertThat(report.of(Category.EVENT_TYPES).message()).isNotBlank();
      assertThat(report.of(Category.ROLES).outcome()).isEqualTo(Outcome.SYNCED);
      assertThat(report.of(Category.DISPATCH_POOLS).outcome()).isEqualTo(Outcome.SYNCED);
      assertThat(report.of(Category.SUBSCRIPTIONS).outcome()).isEqualTo(Outcome.SYNCED);
      assertThat(report.of(Category.PROCESSES).outcome()).isEqualTo(Outcome.SYNCED);
      assertThat(t.getMessage()).contains("EVENT_TYPES").contains("CONNECTIONS").contains("SCHEDULED_JOBS");
    }
  }

  @Test
  void refusedCredentialsFailEveryCategoryWithoutLosingTheReport() {
    platform.failTokens(401);
    try (App app = start(Contributor.env(platform), orders())) {
      Throwable t = catchThrowable(() -> app.get(DefinitionSync.class).run(Mode.APPLY));

      assertThat(t).isInstanceOf(SyncException.class);
      SyncReport report = ((SyncException) t).report();
      assertThat(report.categories().stream().filter(r -> r.outcome() == Outcome.FAILED)).hasSize(7);
      assertThat(report.of(Category.ROLES).message()).contains("credentials");
      assertThat(platform.syncRequests()).isEmpty();
    }
  }

  @Test
  void nothingDeclaredMeansNothingIsSent() {
    try (App app = start(Contributor.env(platform))) {
      SyncReport report = app.get(DefinitionSync.class).run(Mode.APPLY);

      assertThat(platform.syncRequests()).isEmpty();
      assertThat(report.categories()).allMatch(r -> r.outcome() == Outcome.SKIPPED);
    }
  }

  @Test
  void permissionsAloneAreNotSentBecauseOnlyRolesCarryThem() {
    Contributor only = Contributor.of("orders", d -> d.permission("orders:sales:order:read", "Read"));
    try (App app = start(Contributor.env(platform), only)) {
      app.get(DefinitionSync.class).run(Mode.APPLY);

      assertThat(platform.syncRequests()).isEmpty();
    }
  }

  @Test
  void syncingIsRepeatable() {
    try (App app = start(Contributor.env(platform), orders())) {
      DefinitionSync sync = app.get(DefinitionSync.class);
      sync.run(Mode.APPLY);
      sync.run(Mode.APPLY);

      assertThat(platform.syncRequests()).hasSize(14);
    }
  }

  @Test
  void runWhileDisabledIsRefused() {
    try (App app = start(Map.of(), FlowCatalystOptions.disabled())) {
      assertThatIllegalStateException()
          .isThrownBy(() -> app.get(DefinitionSync.class).run(Mode.APPLY))
          .withMessageContaining("disabled");
    }
  }

  @Test
  void syncOnBootIsOffByDefault() {
    try (App app = start(Contributor.env(platform), orders())) {
      assertThat(app.get(FlowCatalystOptions.class).syncOnBoot()).isFalse();
      assertThat(platform.syncRequests()).isEmpty();
    }
  }

  @Test
  void syncOnBootSyncsWhenAsked() {
    Map<String, String> env = Contributor.env(platform);
    try (App app = start(env, FlowCatalystOptions.defaults().withSyncOnBoot(true), orders())) {
      assertThat(platform.syncedResources()).isEqualTo(ALL_RESOURCES);
    }
  }

  @Test
  void aFailedSyncOnBootDoesNotStopTheApplication() {
    platform.respond("roles", 500, "{\"error\":\"boom\"}");
    Map<String, String> env = Contributor.env(platform);
    try (App app = start(env, FlowCatalystOptions.defaults().withSyncOnBoot(true), orders())) {
      assertThat(platform.syncedResources()).isEqualTo(ALL_RESOURCES);
      assertThat(app.get(PlatformClient.class).isEnabled()).isTrue();
    }
  }
}
