package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
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

/** The module's behaviour in code (config-and-code contract, section 3.8). */
class FlowCatalystOptionsTest {

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

  private AppBuilder builder(Map<String, String> env, FlowCatalystModule module) {
    return App.builder().configDirectory(dir).environment(env).module(module);
  }

  @Test
  void theDefaultsAreTheOldConfigurationDefaults() {
    FlowCatalystOptions d = FlowCatalystOptions.defaults();
    assertThat(d.enabled()).isTrue();
    assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(d.outboxStaleAfter()).isEqualTo(Duration.ofMinutes(5));
    assertThat(d.syncOnBoot()).isFalse();
    assertThat(d.receivers())
        .isEqualTo(
            new ReceiverOptions(
                true,
                "/fc",
                true,
                Duration.ofSeconds(25),
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                Duration.ofSeconds(1),
                Duration.ofDays(30),
                Duration.ofHours(1)));
    assertThat(FlowCatalystOptions.disabled().enabled()).isFalse();
    assertThat(ReceiverOptions.disabled().enabled()).isFalse();
  }

  @Test
  void everyRangeIsCheckedWhenBuilt() {
    FlowCatalystOptions f = FlowCatalystOptions.defaults();
    assertThat(f.withTimeout(Duration.ofHours(1)).timeout()).isEqualTo(Duration.ofHours(1));
    assertThatIllegalArgumentException().isThrownBy(() -> f.withTimeout(Duration.ZERO)).withMessageContaining("timeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> f.withTimeout(Duration.ofHours(1).plusNanos(1)))
        .withMessageContaining("timeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> f.withOutboxStaleAfter(Duration.ofSeconds(-1)))
        .withMessageContaining("outboxStaleAfter");

    ReceiverOptions r = ReceiverOptions.defaults();
    assertThat(r.withJobLockLease(Duration.ofSeconds(1)).jobLockLease()).isEqualTo(Duration.ofSeconds(1));
    assertThat(r.withJobLockLease(Duration.ofHours(24)).jobLockLease()).isEqualTo(Duration.ofHours(24));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> r.withJobLockLease(Duration.ofMillis(999)))
        .withMessageContaining("jobLockLease");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> r.withJobLockLease(Duration.ofHours(24).plusNanos(1)))
        .withMessageContaining("jobLockLease");
    assertThat(r.withCompletionRetryDelay(Duration.ZERO).completionRetryDelay()).isZero();
    assertThat(r.withCompletionRetryDelay(Duration.ofMinutes(1)).completionRetryDelay()).isEqualTo(Duration.ofMinutes(1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> r.withCompletionRetryDelay(Duration.ofMillis(-1)))
        .withMessageContaining("completionRetryDelay");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> r.withCompletionRetryDelay(Duration.ofMinutes(1).plusNanos(1)))
        .withMessageContaining("completionRetryDelay");
    assertThatIllegalArgumentException().isThrownBy(() -> r.withJobTimeout(Duration.ZERO)).withMessageContaining("jobTimeout");
    assertThat(r.withPath("/hooks/flowcatalyst").path()).isEqualTo("/hooks/flowcatalyst");
    assertThatIllegalArgumentException().isThrownBy(() -> r.withPath("/a/{x}")).withMessageContaining("path");
  }

  @Test
  void theOptionsAreAServiceAndThePlatformClientKnowsItsTimeout() {
    FlowCatalystOptions options = FlowCatalystOptions.defaults().withTimeout(Duration.ofSeconds(7));
    try (App app = builder(Contributor.env(platform), new FlowCatalystModule(options)).start()) {
      assertThat(app.get(FlowCatalystOptions.class)).isEqualTo(options);
      assertThat(app.get(PlatformClient.class).timeout()).isEqualTo(Duration.ofSeconds(7));
    }
  }

  static final Setting<Boolean> PLATFORM_ON = Setting.bool("demo.platform-on").withDefault(true);

  static Module demo() {
    return new Module() {
      @Override
      public String id() {
        return "demo";
      }

      @Override
      public void register(AppBuilder app) {
        app.declare(PLATFORM_ON);
      }
    };
  }

  @Test
  void whetherThePlatformIsUsedMayBeAnApplicationSetting() {
    FlowCatalystModule module =
        new FlowCatalystModule(
            config -> config.get(PLATFORM_ON) ? FlowCatalystOptions.defaults() : FlowCatalystOptions.disabled());
    // No platform settings at all: a disabled module needs none.
    try (App app = builder(Map.of("DEMO_PLATFORM_ON", "false"), module).module(demo()).start()) {
      assertThat(app.get(PlatformClient.class).isEnabled()).isFalse();
    }
    try (App app =
        builder(Contributor.env(platform), new FlowCatalystModule(config -> config.get(PLATFORM_ON)
                ? FlowCatalystOptions.defaults()
                : FlowCatalystOptions.disabled()))
            .module(demo())
            .start()) {
      assertThat(app.get(PlatformClient.class).isEnabled()).isTrue();
    }
  }

  @Test
  void theRemovedKeysFailStartUpNamingTheCode() throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        flowcatalyst:
          enabled: true
          timeout: 10s
          sync: { on-boot: true }
          outbox: { stale-after: 1m }
          receivers:
            enabled: false
            path: /fc
            require-signature: false
            dispatch-timeout: 5s
            job-timeout: 1m
            job-lock-lease: 1m
            completion-retry-delay: 1s
            once-retention: 1d
            once-purge-interval: 1h
        """);
    Throwable t = catchThrowable(() -> builder(Contributor.env(platform), new FlowCatalystModule()).start().close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder(
            "flowcatalyst.enabled",
            "flowcatalyst.timeout",
            "flowcatalyst.sync.on-boot",
            "flowcatalyst.outbox.stale-after",
            "flowcatalyst.receivers.enabled",
            "flowcatalyst.receivers.path",
            "flowcatalyst.receivers.require-signature",
            "flowcatalyst.receivers.dispatch-timeout",
            "flowcatalyst.receivers.job-timeout",
            "flowcatalyst.receivers.job-lock-lease",
            "flowcatalyst.receivers.completion-retry-delay",
            "flowcatalyst.receivers.once-retention",
            "flowcatalyst.receivers.once-purge-interval");
    assertThat(t.getMessage())
        .contains("FlowCatalystOptions.withTimeout(Duration)")
        .contains("ReceiverOptions.disabled()");
  }

  @Test
  void aRemovedKeyInTheEnvironmentAndAnUnknownKeyFailStartUp() throws IOException {
    Map<String, String> env = Contributor.env(platform);
    env.put("FLOWCATALYST_ENABLED", "false");
    Throwable fromEnv = catchThrowable(() -> builder(env, new FlowCatalystModule()).start().close());
    assertThat(fromEnv).isInstanceOf(StartupException.class).hasMessageContaining("environment variable FLOWCATALYST_ENABLED");

    Files.writeString(dir.resolve("application.yaml"), "flowcatalyst:\n  aplication: orders\n");
    Throwable unknown = catchThrowable(() -> builder(Contributor.env(platform), new FlowCatalystModule()).start().close());
    assertThat(unknown)
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("flowcatalyst.aplication")
        .hasMessageContaining("flowcatalyst.application");
  }
}
