package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Background} loops (background-and-metrics contract, section 1). */
class BackgroundTest {

  static final Duration FAST = Duration.ofMillis(10);

  @TempDir Path dir;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  private AppBuilder builder(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env);
  }

  private App start(Mod... modules) {
    AppBuilder b = builder(Map.of()).shutdownDrain(Duration.ofMillis(300));
    for (Mod m : modules) {
      b.module(m);
    }
    return b.start();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 5s");
      }
      Thread.sleep(5);
    }
  }

  private static double counter(App app, String loop, String outcome) {
    return sample(app, "fw_background_runs_total", List.of(loop, outcome));
  }

  private static double sample(App app, String name, List<String> labels) {
    for (MetricsSnapshot.Family f : app.get(Metrics.class).snapshot().families()) {
      if (f.name().equals(name)) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(labels)) {
            return f.type() == MetricsSnapshot.Type.HISTOGRAM ? s.count() : s.value();
          }
        }
      }
    }
    return 0;
  }

  private static HealthResult health(App app, String loop) {
    return app.health().check().results().get("background:" + loop);
  }

  @Test
  void loopsRegisteredInBootStartOnlyAfterEveryStartedHook() throws Exception {
    AtomicBoolean secondStarted = new AtomicBoolean();
    AtomicBoolean sawWholeApp = new AtomicBoolean();
    Mod first =
        Mod.of("first")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "probe",
                            LoopOptions.every(Duration.ofHours(1)),
                            tick -> sawWholeApp.set(secondStarted.get())));
    Mod second =
        Mod.of("second")
            .started(
                app -> {
                  Thread.sleep(100);
                  secondStarted.set(true);
                });
    try (App app = start(first, second)) {
      await(() -> counter(app, "probe", "success") == 1);
      assertThat(sawWholeApp).isTrue();
      assertThat(app.get(Background.class).names()).containsExactly("probe");
    }
  }

  @Test
  void runsAtAFixedDelayAndNeverOverlap() throws Exception {
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    AtomicInteger runs = new AtomicInteger();
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "busy",
                            LoopOptions.every(FAST),
                            tick -> {
                              int now = concurrent.incrementAndGet();
                              maxConcurrent.accumulateAndGet(now, Math::max);
                              Thread.sleep(20);
                              concurrent.decrementAndGet();
                              assertThat(tick.run()).isEqualTo(runs.incrementAndGet());
                              assertThat(tick.fencingToken()).isEqualTo(OptionalLong.empty());
                            }));
    try (App app = start(m)) {
      await(() -> runs.get() >= 4);
    }
    assertThat(maxConcurrent).hasValue(1);
  }

  @Test
  void theFirstRunWaitsForTheInitialDelay() throws Exception {
    long[] firstAt = new long[1];
    long startedAt = System.nanoTime();
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "late",
                            LoopOptions.every(Duration.ofHours(1)).initialDelay(Duration.ofMillis(200)),
                            tick -> firstAt[0] = System.nanoTime()));
    try (App app = start(m)) {
      await(() -> firstAt[0] != 0);
    }
    assertThat(firstAt[0] - startedAt).isGreaterThanOrEqualTo(Duration.ofMillis(200).toNanos());
  }

  @Test
  void aFailedRunIsCountedAndTheLoopGoesOnHealthGoesDownAfterTheThresholdAndRecovers()
      throws Exception {
    AtomicBoolean fail = new AtomicBoolean(true);
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "flaky",
                            LoopOptions.every(FAST).downAfterFailures(3),
                            tick -> {
                              if (fail.get()) {
                                throw new IllegalStateException("secret detail: password=hunter2");
                              }
                            }));
    try (App app = start(m)) {
      await(() -> counter(app, "flaky", "failure") >= 3);
      HealthResult down = health(app, "flaky");
      assertThat(down).isInstanceOf(HealthResult.Down.class);
      String reason = ((HealthResult.Down) down).reason();
      assertThat(reason).contains("runs in a row failed").contains("IllegalStateException");
      assertThat(reason).doesNotContain("hunter2").doesNotContain("secret");
      // Informational by default: the application stays ready.
      assertThat(app.health().check().isReady()).isTrue();
      fail.set(false);
      await(() -> counter(app, "flaky", "success") >= 1);
      assertThat(health(app, "flaky")).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void aCriticalLoopThatFailsMakesTheApplicationNotReady() throws Exception {
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "vital",
                            LoopOptions.every(FAST).downAfterFailures(1).critical(),
                            tick -> {
                              throw new IllegalStateException("x");
                            }));
    try (App app = start(m)) {
      await(() -> counter(app, "vital", "failure") >= 1);
      assertThat(app.health().check().isReady()).isFalse();
    }
  }

  @Test
  void anErrorEndsTheLoopAndTheHealthCheckSaysSo() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "doomed",
                            LoopOptions.every(FAST),
                            tick -> {
                              runs.incrementAndGet();
                              throw new StackOverflowError();
                            }));
    try (App app = start(m)) {
      await(() -> runs.get() == 1 && health(app, "doomed") instanceof HealthResult.Down);
      assertThat(((HealthResult.Down) health(app, "doomed")).reason())
          .contains("ended after StackOverflowError");
      Thread.sleep(100);
      assertThat(runs).hasValue(1);
    }
  }

  @Test
  void aRunLastingLongerThanStuckAfterMakesTheCheckDown() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(1);
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "slow",
                            LoopOptions.every(Duration.ofHours(1)).stuckAfter(Duration.ofMillis(50)),
                            tick -> {
                              entered.countDown();
                              release.await();
                            }));
    try (App app = start(m)) {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      assertThat(((HealthResult.Down) health(app, "slow")).reason()).contains("has been running for");
      release.countDown();
      await(() -> counter(app, "slow", "success") == 1);
      assertThat(health(app, "slow")).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void closeStopsLoopsBeforeAnyModuleShutdownHook() throws Exception {
    AtomicBoolean running = new AtomicBoolean();
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "worker",
                            LoopOptions.every(FAST),
                            tick -> {
                              running.set(true);
                              Thread.sleep(30);
                              running.set(false);
                            }))
            .shutdown(app -> log.add("shutdown hook; loop running=" + running.get()));
    App app = start(m);
    await(() -> running.get());
    app.close();
    assertThat(log).containsExactly("shutdown hook; loop running=false");
  }

  @Test
  void aWaitingLoopStopsAtOnceAndATaskSeesStopping() throws Exception {
    AtomicBoolean sawStopping = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.get(Background.class)
                      .loop("idle", LoopOptions.every(Duration.ofHours(1)), tick -> {});
                  app.get(Background.class)
                      .loop(
                          "polite",
                          LoopOptions.every(Duration.ofHours(1)),
                          tick -> {
                            entered.countDown();
                            while (!tick.stopping()) {
                              Thread.onSpinWait();
                            }
                            sawStopping.set(true);
                          });
                });
    App app = start(m);
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    long before = System.nanoTime();
    app.close();
    assertThat(System.nanoTime() - before).isLessThan(Duration.ofMillis(250).toNanos());
    assertThat(sawStopping).isTrue();
  }

  @Test
  void aTaskThatIgnoresTheStopIsInterruptedAfterTheDrain() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "stubborn",
                            LoopOptions.every(Duration.ofHours(1)),
                            tick -> {
                              entered.countDown();
                              try {
                                Thread.sleep(Duration.ofSeconds(30));
                              } catch (InterruptedException e) {
                                interrupted.set(true);
                              }
                            }));
    App app = start(m); // drain 300ms
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    long before = System.nanoTime();
    app.close();
    long took = System.nanoTime() - before;
    assertThat(took).isBetween(Duration.ofMillis(250).toNanos(), Duration.ofSeconds(3).toNanos());
    await(interrupted::get);
  }

  @Test
  void registrationRules() throws Exception {
    try (App app = start()) {
      Background bg = app.get(Background.class);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> bg.loop("Bad", LoopOptions.every(FAST), t -> {}));
      assertThatIllegalArgumentException()
          .isThrownBy(() -> bg.loop("x".repeat(101), LoopOptions.every(FAST), t -> {}));
      bg.loop("one", LoopOptions.every(Duration.ofHours(1)), t -> {});
      assertThatIllegalArgumentException()
          .isThrownBy(() -> bg.loop("one", LoopOptions.every(FAST), t -> {}));
      app.close();
      assertThatIllegalStateException()
          .isThrownBy(() -> bg.loop("two", LoopOptions.every(FAST), t -> {}));
    }
  }

  @Test
  void optionRanges() {
    assertThatIllegalArgumentException().isThrownBy(() -> LoopOptions.every(Duration.ofMillis(9)));
    assertThatIllegalArgumentException().isThrownBy(() -> LoopOptions.every(Duration.ofHours(25)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> LoopOptions.every(FAST).initialDelay(Duration.ofMillis(-1)));
    assertThatIllegalArgumentException().isThrownBy(() -> LoopOptions.every(FAST).downAfterFailures(0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> LoopOptions.every(FAST).stuckAfter(Duration.ZERO));
  }

  @Test
  void aLoopAddedAfterStartUpBeginsAtOnce() throws Exception {
    try (App app = start()) {
      AtomicInteger runs = new AtomicInteger();
      app.get(Background.class)
          .loop("late-comer", LoopOptions.every(Duration.ofHours(1)), t -> runs.incrementAndGet());
      await(() -> runs.get() == 1);
    }
  }

  @Test
  void aCommandNeverStartsLoops() {
    AtomicInteger runs = new AtomicInteger();
    Mod m =
        Mod.of("m")
            .register(
                b ->
                    b.command(
                        new Command() {
                          @Override
                          public String name() {
                            return "noop";
                          }

                          @Override
                          public int run(App app, List<String> args, java.io.PrintStream out) {
                            app.get(Background.class)
                                .loop("in-command", LoopOptions.every(FAST), t -> runs.incrementAndGet());
                            return 0;
                          }
                        }));
    assertThat(builder(Map.of()).module(m).launch("noop")).isEqualTo(new Launch.Ran(0));
    assertThat(runs).hasValue(0);
  }

  @Test
  void gateOutcomesSkippedIsNotAFailureLostIsAndTheTokenReachesTheTask() throws Exception {
    List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger calls = new AtomicInteger();
    List<OptionalLong> tokens = Collections.synchronizedList(new ArrayList<>());
    LoopGate gate =
        (loop, work) -> {
          int n = calls.incrementAndGet();
          if (n == 1) {
            work.run(42L);
            return new LoopGate.Ran();
          }
          if (n == 2) {
            return new LoopGate.Skipped("someone else");
          }
          work.run(null);
          return new LoopGate.Lost();
        };
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "gated",
                            LoopOptions.every(FAST).gatedBy(gate).downAfterFailures(1000),
                            tick -> tokens.add(tick.fencingToken())));
    try (App app = start(m)) {
      await(() -> counter(app, "gated", "lost") >= 2);
      outcomes.add("success=" + counter(app, "gated", "success"));
      outcomes.add("skipped=" + counter(app, "gated", "skipped"));
      assertThat(outcomes).containsExactly("success=1.0", "skipped=1.0");
      assertThat(counter(app, "gated", "failure")).isZero();
      assertThat(tokens.get(0)).isEqualTo(OptionalLong.of(42));
      assertThat(tokens.get(1)).isEqualTo(OptionalLong.empty());
      // A skipped run is not timed; success and lost runs are.
      assertThat(sample(app, "fw_background_run_duration_seconds", List.of("gated")))
          .isEqualTo(calls.get() - 1);
    }
  }

  @Test
  void lostRunsCountTowardsTheHealthThreshold() throws Exception {
    Mod m =
        Mod.of("m")
            .boot(
                app ->
                    app.get(Background.class)
                        .loop(
                            "losing",
                            LoopOptions.every(FAST)
                                .gatedBy(
                                    (loop, work) -> {
                                      work.run(1L);
                                      return new LoopGate.Lost();
                                    })
                                .downAfterFailures(2),
                            tick -> {}));
    try (App app = start(m)) {
      await(() -> counter(app, "losing", "lost") >= 2);
      assertThat(((HealthResult.Down) health(app, "losing")).reason())
          .contains("lost its gate while running");
    }
  }
}
