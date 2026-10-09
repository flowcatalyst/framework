package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncCommandTest {

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

  private static Contributor orders() {
    return Contributor.of(
        "orders",
        d ->
            d.eventType("orders:sales:order:placed", "Order placed", null)
                .role("clerk")
                .connection("main", "Main"));
  }

  private AppBuilder builder(Map<String, String> env) {
    return App.builder()
        .configDirectory(dir)
        .environment(env)
        .module(new FlowCatalystModule())
        .module(orders());
  }

  private record Outcome(int code, String output) {}

  private Outcome run(Map<String, String> env, String... args) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (App app = builder(env).start();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      int code = new SyncCommand().run(app, List.of(args), out);
      return new Outcome(code, bytes.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void noArgumentsApplies() {
    Outcome o = run(Contributor.env(platform));

    assertThat(o.code()).isZero();
    assertThat(platform.syncedResources()).containsExactly("roles", "event-types", "connections");
    assertThat(o.output()).contains("SYNCED").contains("sync complete");
  }

  @Test
  void dryRunReportsWhatWouldBeSentAndSendsNothing() {
    Outcome o = run(Contributor.env(platform), "--dry-run");

    assertThat(o.code()).isZero();
    assertThat(platform.syncRequests()).isEmpty();
    assertThat(o.output()).contains("WOULD_SYNC").contains("clerk").contains("dry run: nothing was sent");
  }

  @Test
  void anUnknownArgumentIsRefusedWithUsage() {
    Outcome o = run(Contributor.env(platform), "--dry-run", "--force");

    assertThat(o.code()).isEqualTo(2);
    assertThat(o.output()).contains("unknown argument: --force").contains("usage: sync [--dry-run]");
    assertThat(platform.syncRequests()).isEmpty();
  }

  @Test
  void aFailedCategoryExitsWithOneAndStillPrintsTheReport() {
    platform.respond("event-types", 500, "{\"error\":\"boom\"}");

    Outcome o = run(Contributor.env(platform));

    assertThat(o.code()).isEqualTo(1);
    assertThat(o.output()).contains("EVENT_TYPES").contains("FAILED").contains("ROLES").contains("sync failed");
    assertThat(platform.syncedResources()).containsExactly("roles", "event-types", "connections");
  }

  @Test
  void disabledExitsWithTwo() {
    Outcome o = run(Map.of("FLOWCATALYST_ENABLED", "false"));

    assertThat(o.code()).isEqualTo(2);
    assertThat(o.output()).contains("disabled");
  }

  @Test
  void launchRunsSyncWithoutBootingAnythingAndTheFirstArgumentSelectsIt() {
    Launch launch = builder(Contributor.env(platform)).launch("sync", "--dry-run");

    assertThat(launch).isEqualTo(new Launch.Ran(0));
    // no boot, so not even the first-token health probe ran: the platform saw nothing at all
    assertThat(platform.requests()).isEmpty();
  }

  @Test
  void launchApplyTalksToThePlatformThenExits() {
    Launch launch = builder(Contributor.env(platform)).launch("sync");

    assertThat(launch).isEqualTo(new Launch.Ran(0));
    assertThat(platform.syncedResources()).containsExactly("roles", "event-types", "connections");
  }

  @Test
  void anyOtherCommandStartsTheApplicationNormally() {
    Launch launch = builder(Contributor.env(platform)).launch("serve");

    assertThat(launch).isInstanceOf(Launch.Started.class);
    ((Launch.Started) launch).app().close();
    assertThat(platform.syncRequests()).isEmpty();
  }
}
