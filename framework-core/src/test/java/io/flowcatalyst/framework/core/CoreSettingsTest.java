package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.metrics.Metrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core's service settings are configuration (config-and-code contract, section 3.1): {@code
 * app.shutdown.drain}, {@code app.health.*} and {@code app.metrics.*}, with their defaults, ranges
 * and environment variables.
 */
class CoreSettingsTest {

  @TempDir Path dir;

  private AppBuilder builder(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env);
  }

  private AppBuilder builder() {
    return builder(Map.of());
  }

  private void file(String yaml) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), yaml);
  }

  private static StartupException failure(AppBuilder b) {
    Throwable t = catchThrowable(b::start);
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  private static long series(Metrics metrics, String name) {
    return metrics.snapshot().families().stream()
        .filter(f -> f.name().equals(name))
        .mapToLong(f -> f.samples().size())
        .sum();
  }

  @Test
  void defaults() {
    App app = builder().start();
    assertThat(app.shutdownDrain()).isEqualTo(Duration.ofSeconds(10));
    assertThat(app.get(Metrics.class).readable()).isTrue();
    var counter = app.get(Metrics.class).counter("demo_total", "a demo", "k");
    for (int i = 0; i < 1001; i++) {
      counter.labels("v" + i).inc();
    }
    assertThat(series(app.get(Metrics.class), "demo_total")).isEqualTo(1000);
    app.close();
  }

  @Test
  void theDefaultHealthTimeoutIsTwoSeconds() {
    Mod m = Mod.of("m").boot(a -> a.health().add("hang", () -> Thread.sleep(Duration.ofSeconds(30))));
    App app = builder().module(m).start();
    long t0 = System.nanoTime();
    HealthReport report = app.health().check();
    Duration took = Duration.ofNanos(System.nanoTime() - t0);
    assertThat(report.results().get("hang")).isInstanceOf(HealthResult.TimedOut.class);
    assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1900)).isLessThan(Duration.ofSeconds(10));
  }

  @Test
  void everyKeyInAFileIsReadAndNoneFailsAsRemoved() throws IOException {
    file(
        """
        app:
          shutdown: { drain: 5s }
          health: { timeout: 1s, critical: [a], informational: [b] }
          metrics: { driver: noop, max-series: 10 }
        """);
    App app = builder().start();
    assertThat(app.shutdownDrain()).isEqualTo(Duration.ofSeconds(5));
    assertThat(app.get(Metrics.class).readable()).isFalse();
    app.close();
  }

  @Test
  void theEnvironmentVariablesAreTheOldNames() {
    App app =
        builder(Map.of("APP_SHUTDOWN_DRAIN", "250ms", "APP_METRICS_DRIVER", "noop", "APP_HEALTH_TIMEOUT", "100ms"))
            .module(Mod.of("m").boot(a -> a.health().add("hang", () -> Thread.sleep(Duration.ofSeconds(30)))))
            .start();
    assertThat(app.shutdownDrain()).isEqualTo(Duration.ofMillis(250));
    assertThat(app.get(Metrics.class).readable()).isFalse();
    long t0 = System.nanoTime();
    assertThat(app.health().check().results().get("hang")).isInstanceOf(HealthResult.TimedOut.class);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
    app.close();
  }

  @Test
  void maxSeriesBoundsAFamily() {
    Metrics metrics = builder(Map.of("APP_METRICS_MAX_SERIES", "2")).start().get(Metrics.class);
    var counter = metrics.counter("demo_total", "a demo", "k");
    counter.labels("a").inc();
    counter.labels("b").inc();
    counter.labels("c").inc();
    assertThat(series(metrics, "demo_total")).isEqualTo(2);
  }

  @Test
  void maxSeriesRange() {
    builder(Map.of("APP_METRICS_MAX_SERIES", "1")).start().close();
    builder(Map.of("APP_METRICS_MAX_SERIES", "100000")).start().close();
    for (String bad : new String[] {"0", "100001"}) {
      StartupException e = failure(builder(Map.of("APP_METRICS_MAX_SERIES", bad)));
      assertThat(e.problems()).singleElement().satisfies(p -> {
        assertThat(p.module()).isEqualTo("core");
        assertThat(p.key()).isEqualTo("app.metrics.max-series");
        assertThat(p.message()).contains("1 to 100000");
      });
    }
  }

  @Test
  void anUnknownMetricsDriverFailsStartUp() {
    StartupException e = failure(builder(Map.of("APP_METRICS_DRIVER", "prometheus")));
    assertThat(e.problems()).singleElement().satisfies(p -> assertThat(p.key()).isEqualTo("app.metrics.driver"));
  }

  @Test
  void theHealthOverridesComeFromConfiguration() throws IOException {
    file("app:\n  health:\n    critical: [platform]\n    informational: [cache, typo]\n");
    Mod m =
        Mod.of("m")
            .boot(
                a -> {
                  a.health().add("platform", () -> {}, Criticality.INFORMATIONAL);
                  a.health().add("cache", () -> {});
                });
    App app = builder().module(m).start();
    assertThat(app.health().criticality("platform")).isEqualTo(Criticality.CRITICAL);
    assertThat(app.health().criticality("cache")).isEqualTo(Criticality.INFORMATIONAL);
    assertThat(app.health().unmatchedOverrides()).containsExactly("typo");
    app.close();
  }
}
