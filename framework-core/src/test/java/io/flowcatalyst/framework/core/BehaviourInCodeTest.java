package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core's behaviour in code and the key rules (config-and-code contract, sections 3.1 and 5):
 * {@link HealthPolicy}, {@link MetricsOptions}, {@code shutdownDrain}, removed keys and claimed
 * components.
 */
class BehaviourInCodeTest {

  @TempDir Path dir;

  private AppBuilder builder(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env);
  }

  private AppBuilder builder() {
    return builder(Map.of());
  }

  private void file(String name, String yaml) throws IOException {
    Files.writeString(dir.resolve(name), yaml);
  }

  private static StartupException failure(AppBuilder b) {
    Throwable t = catchThrowable(b::start);
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  // ---- HealthPolicy ------------------------------------------------------------------------

  @Test
  void healthPolicyDefaultsAreTheOldConfigurationDefaults() {
    HealthPolicy p = HealthPolicy.defaults();
    assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.critical()).isEmpty();
    assertThat(p.informational()).isEmpty();
  }

  @Test
  void healthPolicyTimeoutRange() {
    assertThat(HealthPolicy.defaults().withTimeout(Duration.ofNanos(1)).timeout()).isEqualTo(Duration.ofNanos(1));
    assertThat(HealthPolicy.defaults().withTimeout(Duration.ofMinutes(5)).timeout()).isEqualTo(Duration.ofMinutes(5));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HealthPolicy.defaults().withTimeout(Duration.ZERO))
        .withMessageContaining("timeout");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HealthPolicy.defaults().withTimeout(Duration.ofMinutes(5).plusNanos(1)))
        .withMessageContaining("timeout");
  }

  @Test
  void healthPolicyRefusesANameInBothListsAndBlankNames() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HealthPolicy.defaults().withCritical("a", "b").withInformational("b"))
        .withMessageContaining("'b'");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HealthPolicy.defaults().withCritical(" "))
        .withMessageContaining("blank");
  }

  @Test
  void theHealthPolicyTimeoutApplies() {
    Mod m = Mod.of("m").boot(app -> app.health().add("hang", () -> Thread.sleep(Duration.ofSeconds(30))));
    App app = builder().health(HealthPolicy.defaults().withTimeout(Duration.ofMillis(200))).module(m).start();
    long t0 = System.nanoTime();
    HealthReport report = app.health().check();
    // the default would wait 2s: well under it means the policy's 200ms applied
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
    assertThat(report.results().get("hang")).isInstanceOf(HealthResult.TimedOut.class);
  }

  @Test
  void theHealthPolicyOverridesTheCriticalityAModuleAskedFor() {
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.health().add("platform", () -> {}, Criticality.INFORMATIONAL);
                  app.health().add("cache", () -> {});
                  app.health().add("other", () -> {});
                });
    App app =
        builder()
            .health(HealthPolicy.defaults().withCritical("platform").withInformational("cache", "typo"))
            .module(m)
            .start();
    assertThat(app.health().criticality("platform")).isEqualTo(Criticality.CRITICAL);
    assertThat(app.health().criticality("cache")).isEqualTo(Criticality.INFORMATIONAL);
    assertThat(app.health().criticality("other")).isEqualTo(Criticality.CRITICAL);
    assertThat(app.health().unmatchedOverrides()).containsExactly("typo");
  }

  // ---- shutdown drain ----------------------------------------------------------------------

  @Test
  void shutdownDrainDefaultsToTenSecondsAndIsChecked() {
    assertThat(builder().start().shutdownDrain()).isEqualTo(Duration.ofSeconds(10));
    assertThat(builder().shutdownDrain(Duration.ofHours(1)).start().shutdownDrain()).isEqualTo(Duration.ofHours(1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder().shutdownDrain(Duration.ZERO))
        .withMessageContaining("shutdownDrain");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder().shutdownDrain(Duration.ofHours(1).plusNanos(1)))
        .withMessageContaining("shutdownDrain");
  }

  @Test
  void aShutdownStepThatOverrunsTheDrainSetInCodeIsInterrupted() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    Mod slow =
        Mod.of("slow")
            .shutdown(
                app -> {
                  try {
                    Thread.sleep(Duration.ofSeconds(30));
                  } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                  }
                });
    App app = builder().shutdownDrain(Duration.ofMillis(100)).module(slow).start();
    long t0 = System.nanoTime();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
  }

  // ---- MetricsOptions ----------------------------------------------------------------------

  @Test
  void metricsOptionsDefaultsAndRange() {
    assertThat(MetricsOptions.defaults()).isEqualTo(MetricsOptions.memory(1000));
    assertThat(MetricsOptions.defaults().driver()).isEqualTo(Metrics.Driver.MEMORY);
    assertThat(MetricsOptions.noop().driver()).isEqualTo(Metrics.Driver.NOOP);
    assertThat(MetricsOptions.memory(1).maxSeries()).isEqualTo(1);
    assertThat(MetricsOptions.memory(100_000).maxSeries()).isEqualTo(100_000);
    assertThatIllegalArgumentException().isThrownBy(() -> MetricsOptions.memory(0)).withMessageContaining("maxSeries");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MetricsOptions.memory(100_001))
        .withMessageContaining("maxSeries");
  }

  @Test
  void theMetricsDriverIsChosenInCode() {
    assertThat(builder().start().get(Metrics.class).readable()).isTrue();
    assertThat(builder().metrics(MetricsOptions.noop()).start().get(Metrics.class).readable()).isFalse();
  }

  @Test
  void maxSeriesFromCodeBoundsAFamily() {
    Metrics metrics = builder().metrics(MetricsOptions.memory(2)).start().get(Metrics.class);
    var counter = metrics.counter("demo_total", "a demo", "k");
    counter.labels("a").inc();
    counter.labels("b").inc();
    counter.labels("c").inc();
    long series =
        metrics.snapshot().families().stream()
            .filter(f -> f.name().equals("demo_total"))
            .mapToLong(f -> f.samples().size())
            .sum();
    assertThat(series).isEqualTo(2);
  }

  // ---- options as a function of configuration ----------------------------------------------

  static final Setting<Duration> APP_HEALTH = Setting.duration("demo.health-timeout").withDefault(Duration.ofSeconds(1));

  @Test
  void optionsMayBeAFunctionOfTheApplicationsOwnSettings() throws IOException {
    file("application.yaml", "demo:\n  health-timeout: 300ms\n");
    App app =
        builder(Map.of("DEMO_HEALTH_TIMEOUT", "250ms"))
            .health(config -> HealthPolicy.defaults().withTimeout(config.get(APP_HEALTH)))
            .shutdownDrain(config -> config.get(APP_HEALTH).multipliedBy(4))
            .metrics(config -> MetricsOptions.noop())
            .module(Mod.of("demo").register(b -> b.declare(APP_HEALTH)))
            .start();
    assertThat(app.shutdownDrain()).isEqualTo(Duration.ofMillis(1000));
    assertThat(app.get(Metrics.class).readable()).isFalse();
  }

  @Test
  void aFunctionThatGivesAValueOutOfRangeFailsStartUp() {
    StartupException e = failure(builder().shutdownDrain(config -> Duration.ZERO));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.module()).isEqualTo("core");
      assertThat(p.message()).contains("shutdownDrain");
    });
    StartupException h = failure(builder().health(config -> HealthPolicy.defaults().withTimeout(Duration.ZERO)));
    assertThat(h.problems()).singleElement().satisfies(p -> assertThat(p.message()).contains("timeout"));
  }

  // ---- removed keys ------------------------------------------------------------------------

  @Test
  void everyRemovedCoreKeyInAFileFailsStartUpNamingWhereItWent() throws IOException {
    file(
        "application.yaml",
        """
        app:
          shutdown: { drain: 5s }
          health: { timeout: 1s, critical: [a], informational: [b] }
          metrics: { driver: noop, max-series: 10 }
        """);
    StartupException e = failure(builder());
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder(
            "app.shutdown.drain",
            "app.health.timeout",
            "app.health.critical",
            "app.health.informational",
            "app.metrics.driver",
            "app.metrics.max-series");
    assertThat(e.problems()).allSatisfy(p -> assertThat(p.message()).startsWith("moved to code: "));
    assertThat(e.getMessage())
        .contains("AppBuilder.shutdownDrain(Duration)")
        .contains("AppBuilder.health(HealthPolicy)")
        .contains("AppBuilder.metrics(MetricsOptions)")
        .contains("application.yaml");
  }

  @Test
  void aRemovedKeyInTheEnvironmentFailsStartUp() {
    StartupException e = failure(builder(Map.of("APP_SHUTDOWN_DRAIN", "100ms", "APP_METRICS_DRIVER", "noop")));
    assertThat(e.problems()).extracting(Problem::key).containsExactlyInAnyOrder("app.shutdown.drain", "app.metrics.driver");
    assertThat(e.getMessage()).contains("environment variable APP_SHUTDOWN_DRAIN").contains("MetricsOptions");
  }

  @Test
  void aRemovedKeyOfAModuleThatIsNotListedStillFails() throws IOException {
    file("application.yaml", "rate-limits:\n  default: api\n  instances:\n    api: { limit: 5, colour: red }\n");
    StartupException e = failure(builder());
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder("rate-limits.default", "rate-limits.instances.api.limit", "rate-limits.instances.api.colour");
    assertThat(e.getMessage()).contains("MemoryRateLimiter.create");
  }

  @Test
  void aWildcardIsExpandedWithTheInstanceNamesInTheFilesForTheEnvironment() throws IOException {
    // an instance named only in a file (the key itself is not removed: the whole component is)
    file("application.yaml", "rate-limits:\n  instances:\n    api: {}\n");
    StartupException e =
        failure(builder(Map.of("RATE_LIMITS_INSTANCES_API_BURST", "4", "RATE_LIMITS_INSTANCES_OTHER_BURST", "4")));
    assertThat(e.problems())
        .filteredOn(p -> p.source() != null && p.source().startsWith("environment"))
        .singleElement()
        .satisfies(p -> {
          assertThat(p.key()).isEqualTo("rate-limits.instances.api.burst");
          assertThat(p.source()).isEqualTo("environment variable RATE_LIMITS_INSTANCES_API_BURST");
          assertThat(p.message()).contains("RateLimiter.create");
        });
  }

  @Test
  void aOneSegmentPatternIsNotLookedUpInTheEnvironment() {
    builder(Map.of("LOCKS", "x", "SECRETS", "y", "RATE_LIMITS", "z")).start().close();
  }

  @Test
  void aKeyThatAModuleDeclaresIsNeverARemovedKey() throws IOException {
    Setting<Duration> own = Setting.duration("http.request-timeout");
    file("application.yaml", "http:\n  request-timeout: 3s\n");
    App app = builder().module(Mod.of("mine").register(b -> b.declare(own))).start();
    assertThat(app.config().get(own)).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void anEmptySectionAboveDeclaredKeysIsNotReported() throws IOException {
    Setting<String> own = Setting.string("app.name");
    file("application.yaml", "app:\n");
    builder().module(Mod.of("mine").register(b -> b.declare(own))).start().close();
  }

  // ---- claimed components ------------------------------------------------------------------

  @Test
  void anUnknownKeyUnderAClaimedComponentFailsStartUp() throws IOException {
    file("application.yaml", "app:\n  colour: blue\nother:\n  colour: green\n");
    StartupException e = failure(builder());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("app.colour");
      assertThat(p.module()).isEqualTo("core");
      assertThat(p.message()).contains("unknown key").contains("'app'");
    });
  }

  @Test
  void aModuleClaimsAComponentAndItsDeclaredKeysAreListed() throws IOException {
    Setting<String> host = Setting.string("mail.host");
    file("application.yaml", "mail:\n  host: smtp\n  hots: smtp\n");
    StartupException e =
        failure(builder().module(Mod.of("mail").register(b -> {
          b.declare(host);
          b.claimComponent("mail");
        })));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("mail.hots");
      assertThat(p.module()).isEqualTo("mail");
      assertThat(p.message()).contains("mail.host");
    });
  }

  @Test
  void twoModulesClaimingOneComponentFailStartUp() {
    StartupException e =
        failure(
            builder()
                .module(Mod.of("a").register(b -> b.claimComponent("mail")))
                .module(Mod.of("b").register(b -> b.claimComponent("mail"))));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.module()).isEqualTo("b");
      assertThat(p.message()).contains("already claimed by module 'a'");
    });
  }

  @Test
  void claimingTheCoreComponentFailsAndBadNamesAreRefused() {
    StartupException e = failure(builder().module(Mod.of("a").register(b -> b.claimComponent("app"))));
    assertThat(e.getMessage()).contains("already claimed by module 'core'");
    StartupException bad = failure(builder().module(Mod.of("a").register(b -> b.claimComponent("Mail.x"))));
    assertThat(bad.getMessage()).contains("component");
  }

  @Test
  void claimComponentIsOnlyForTheRegisterPhase() {
    assertThatIllegalStateException().isThrownBy(() -> builder().claimComponent("mail"));
  }
}
