package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module's settings are service settings, so configuration (config-and-code contract, section
 * 3.5): the defaults, every range bound, the environment variable names, the keys accepted in a
 * file, and a misspelt key under the claimed component.
 */
class FlowCatalystSettingsTest {

  @TempDir Path dir;
  FakePlatform platform;

  @BeforeEach
  void setUp() {
    platform = FakePlatform.start();
  }

  @AfterEach
  void tearDown() {
    platform.close();
  }

  private App start(String... env) {
    return Receiving.start(dir, Receiving.env(platform, env), () -> NOW);
  }

  private StartupException failure(String... env) {
    Throwable t = catchThrowable(() -> start(env).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void theDefaults() {
    try (App app = start()) {
      var c = app.config();
      assertThat(c.get(FlowCatalystModule.ENABLED)).isTrue();
      assertThat(c.get(FlowCatalystModule.TIMEOUT)).isEqualTo(Duration.ofSeconds(30));
      assertThat(c.get(FlowCatalystModule.SYNC_ON_BOOT)).isFalse();
      assertThat(c.get(FlowCatalystModule.OUTBOX_STALE_AFTER)).isEqualTo(Duration.ofMinutes(5));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_ENABLED)).isTrue();
      assertThat(c.get(FlowCatalystModule.RECEIVERS_PATH)).isEqualTo("/fc");
      assertThat(c.get(FlowCatalystModule.RECEIVERS_REQUIRE_SIGNATURE)).isTrue();
      assertThat(c.get(FlowCatalystModule.RECEIVERS_DISPATCH_TIMEOUT)).isEqualTo(Duration.ofSeconds(25));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_JOB_TIMEOUT)).isEqualTo(Duration.ofMinutes(10));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_JOB_LOCK_LEASE)).isEqualTo(Duration.ofMinutes(10));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_COMPLETION_RETRY_DELAY)).isEqualTo(Duration.ofSeconds(1));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_ONCE_RETENTION)).isEqualTo(Duration.ofDays(30));
      assertThat(c.get(FlowCatalystModule.RECEIVERS_ONCE_PURGE_INTERVAL)).isEqualTo(Duration.ofHours(1));
    }
  }

  @Test
  void everyKeyInAFileIsReadAndNoneFailsAsRemoved() throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        flowcatalyst:
          enabled: true
          timeout: 10s
          sync: { on-boot: false }
          outbox: { stale-after: 2m }
          receivers:
            enabled: true
            path: /hooks/flowcatalyst
            require-signature: true
            dispatch-timeout: 20s
            job-timeout: 5m
            job-lock-lease: 2m
            completion-retry-delay: 500ms
            once-retention: 7d
            once-purge-interval: 30m
        """);
    try (App app = start()) {
      assertThat(app.config().get(FlowCatalystModule.TIMEOUT)).isEqualTo(Duration.ofSeconds(10));
      assertThat(app.config().get(FlowCatalystModule.RECEIVERS_PATH)).isEqualTo("/hooks/flowcatalyst");
      assertThat(app.config().get(FlowCatalystModule.RECEIVERS_ONCE_RETENTION)).isEqualTo(Duration.ofDays(7));
    }
  }

  @Test
  void theEnvironmentVariablesAreTheOldNames() {
    try (App app =
        start(
            "FLOWCATALYST_TIMEOUT", "3s",
            "FLOWCATALYST_OUTBOX_STALE_AFTER", "1m",
            "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "30s",
            "FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", "5m")) {
      assertThat(app.config().get(FlowCatalystModule.TIMEOUT)).isEqualTo(Duration.ofSeconds(3));
      assertThat(app.config().get(FlowCatalystModule.OUTBOX_STALE_AFTER)).isEqualTo(Duration.ofMinutes(1));
      assertThat(app.config().get(FlowCatalystModule.RECEIVERS_JOB_LOCK_LEASE)).isEqualTo(Duration.ofSeconds(30));
      assertThat(app.config().get(FlowCatalystModule.RECEIVERS_ONCE_PURGE_INTERVAL)).isEqualTo(Duration.ofMinutes(5));
    }
  }

  @Test
  void everyBoundJustInsideStarts() {
    start(
            "FLOWCATALYST_TIMEOUT", "1ms",
            "FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT", "1ms",
            "FLOWCATALYST_RECEIVERS_JOB_TIMEOUT", "1ms",
            "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "1s",
            "FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY", "0s",
            "FLOWCATALYST_RECEIVERS_ONCE_RETENTION", "1ms",
            "FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", "1ms")
        .close();
    start(
            "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "24h",
            "FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY", "1m",
            "FLOWCATALYST_RECEIVERS_PATH", "/a/b.c~d-e_f")
        .close();
  }

  @Test
  void everyBoundJustOutsideFailsNamingTheKey() {
    String[][] cases = {
      {"FLOWCATALYST_TIMEOUT", "0s", "flowcatalyst.timeout"},
      {"FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT", "0s", "flowcatalyst.receivers.dispatch-timeout"},
      {"FLOWCATALYST_RECEIVERS_JOB_TIMEOUT", "0s", "flowcatalyst.receivers.job-timeout"},
      {"FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "999ms", "flowcatalyst.receivers.job-lock-lease"},
      {"FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", "86400001ms", "flowcatalyst.receivers.job-lock-lease"},
      {"FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY", "60001ms", "flowcatalyst.receivers.completion-retry-delay"},
      {"FLOWCATALYST_RECEIVERS_ONCE_RETENTION", "0s", "flowcatalyst.receivers.once-retention"},
      {"FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", "0s", "flowcatalyst.receivers.once-purge-interval"},
      {"FLOWCATALYST_RECEIVERS_PATH", "/fc/", "flowcatalyst.receivers.path"},
      {"FLOWCATALYST_RECEIVERS_PATH", "/fc/{x}", "flowcatalyst.receivers.path"},
    };
    for (String[] c : cases) {
      StartupException e = failure(c[0], c[1]);
      assertThat(e.problems()).as(c[0] + "=" + c[1]).extracting(Problem::key).containsExactly(c[2]);
    }
  }

  @Test
  void aMisspeltKeyUnderTheComponentFailsStartUpListingTheKeys() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "flowcatalyst:\n  receivers:\n    enabeld: false\n");
    StartupException e = failure();
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("flowcatalyst.receivers.enabeld");
      assertThat(p.module()).isEqualTo("flowcatalyst");
      assertThat(p.message()).contains("flowcatalyst.receivers.enabled");
    });
  }

  @Test
  void receiversOffInAFileMountsNothing() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "flowcatalyst:\n  receivers:\n    enabled: false\n");
    try (App app = start()) {
      assertThat(Receiving.client(app).post("/fc/dispatch").body("{}").send().status()).isEqualTo(404);
    }
  }
}
