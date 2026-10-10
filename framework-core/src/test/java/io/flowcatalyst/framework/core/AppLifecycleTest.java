package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.config.Setting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLifecycleTest {

  @TempDir Path dir;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  record Alpha(String name) {}

  record Beta(Alpha alpha) {}

  record Gamma(Beta beta) {}

  record Svc1(Object c) {}

  /** A service that records its closing. */
  class Closing implements AutoCloseable {
    private final String name;

    Closing(String name) {
      this.name = name;
    }

    @Override
    public void close() {
      log.add("close " + name);
    }
  }

  final class CloseA extends Closing {
    CloseA() {
      super("a");
    }
  }

  final class CloseB extends Closing {
    CloseB() {
      super("b");
    }
  }

  private AppBuilder builder() {
    return App.builder().configDirectory(dir).environment(Map.of());
  }

  private AppBuilder builder(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env);
  }

  private static StartupException failure(AppBuilder b) {
    Throwable t = org.assertj.core.api.Assertions.catchThrowable(b::start);
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  // ---- the happy path ---------------------------------------------------------------------

  @Test
  void registersThenBootsInOrderAndRecordsBuildOrder() {
    Mod a =
        Mod.of("a")
            .register(
                b -> {
                  log.add("register a");
                  b.provide(Gamma.class, l -> new Gamma(l.get(Beta.class)));
                })
            .boot(app -> log.add("boot a"));
    Mod b =
        Mod.of("b")
            .register(
                x -> {
                  log.add("register b");
                  x.provide(Beta.class, l -> new Beta(l.get(Alpha.class)));
                  x.provide(Alpha.class, l -> new Alpha("alpha"));
                })
            .boot(app -> log.add("boot b"));
    App app = builder().module(a).module(b).start();
    assertThat(log).containsExactly("register a", "register b", "boot a", "boot b");
    // The core module's services come first (it registers first). Then: Gamma was asked first, so
    // it needs Beta, which needs Alpha: Alpha finishes first.
    assertThat(app.buildOrder())
        .containsExactly(
            io.flowcatalyst.framework.metrics.Metrics.class,
            Background.class,
            Alpha.class,
            Beta.class,
            Gamma.class);
    assertThat(app.get(Gamma.class).beta().alpha().name()).isEqualTo("alpha");
    assertThat(app.moduleIds()).containsExactly("core", "a", "b");
  }

  @Test
  void servicesAreBuiltOnceAndEagerly() {
    AtomicInteger built = new AtomicInteger();
    Mod m =
        Mod.of("m")
            .register(
                b -> {
                  b.provide(Alpha.class, l -> new Alpha("n" + built.incrementAndGet()));
                  b.provide(Beta.class, l -> new Beta(l.get(Alpha.class)));
                });
    App app = builder().module(m).start();
    assertThat(built).hasValue(1);
    assertThat(app.get(Alpha.class)).isSameAs(app.get(Beta.class).alpha());
  }

  @Test
  void builtInServicesAreAvailable() {
    App app = builder().module(Mod.of("m")).start();
    assertThat(app.get(io.flowcatalyst.framework.config.Config.class)).isSameAs(app.config());
    assertThat(app.get(java.time.InstantSource.class)).isNotNull();
    assertThat(app.get(MonotonicClock.class)).isNotNull();
    assertThat(app.get(Drivers.class)).isNotNull();
  }

  @Test
  void lookupOfUnprovidedTypeAfterStartThrows() {
    App app = builder().start();
    assertThatIllegalArgumentException().isThrownBy(() -> app.get(Alpha.class));
  }

  @Test
  void contributionsAreCollectedInModuleOrder() {
    Mod a = Mod.of("a").register(b -> b.contribute(String.class, "from-a"));
    Mod b = Mod.of("b").register(x -> {
      x.contribute(String.class, "from-b");
      x.provide(Alpha.class, l -> new Alpha(String.join(",", l.contributions(String.class))));
    });
    assertThat(builder().module(a).module(b).start().get(Alpha.class).name())
        .isEqualTo("from-a,from-b");
  }

  @Test
  void declaredSettingsAreReadAfterStartUp() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "my:\n  port: 8080\n");
    java.util.concurrent.atomic.AtomicReference<Setting<Integer>> port =
        new java.util.concurrent.atomic.AtomicReference<>();
    Mod m = Mod.of("m").register(b -> port.set(b.declare(Setting.integer("my.port"))));
    App app = builder().module(m).start();
    assertThat(app.config().get(port.get())).isEqualTo(8080);
  }

  @Test
  void registerPhaseMethodsAreRejectedOutsideRegister() {
    AppBuilder b = builder();
    assertThatIllegalStateException().isThrownBy(() -> b.provide(Alpha.class, l -> new Alpha("x")));
    assertThatIllegalStateException().isThrownBy(() -> b.declare(Setting.string("a.b")));
    assertThatIllegalStateException().isThrownBy(b::drivers);
    assertThatIllegalStateException().isThrownBy(b::moduleId);
    b.start();
    assertThatIllegalStateException().isThrownBy(b::start);
    assertThatIllegalStateException().isThrownBy(() -> b.module(Mod.of("x")));
  }

  // ---- start-up failures ------------------------------------------------------------------

  @Test
  void duplicateModuleIdFails() {
    StartupException e = failure(builder().module(Mod.of("a")).module(Mod.of("a")));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.getMessage()).contains("duplicate module id").contains("'a'");
  }

  @Test
  void moduleNamedCoreCollidesWithTheBuiltInModule() {
    assertThat(failure(builder().module(Mod.of("core"))).getMessage())
        .contains("duplicate module id");
  }

  @Test
  void invalidModuleIdFails() {
    assertThat(failure(builder().module(Mod.of("Bad_Id"))).getMessage())
        .contains("[a-z][a-z0-9-]*");
  }

  @Test
  void twoProvidersForOneTypeFails() {
    Mod a = Mod.of("a").register(b -> b.provide(Alpha.class, l -> new Alpha("a")));
    Mod b = Mod.of("b").register(x -> x.provide(Alpha.class, l -> new Alpha("b")));
    StartupException e = failure(builder().module(a).module(b));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().get(0).module()).isEqualTo("b");
    assertThat(e.getMessage()).contains("already provided by module 'a'");
  }

  @Test
  void providingAFrameworkTypeFails() {
    Mod a =
        Mod.of("a")
            .register(
                b ->
                    b.provide(
                        io.flowcatalyst.framework.config.Config.class, l -> l.get(
                            io.flowcatalyst.framework.config.Config.class)));
    assertThat(failure(builder().module(a)).getMessage()).contains("provided by the framework");
  }

  @Test
  void lookupOfUnprovidedTypeNamesTheAskingModule() {
    Mod a = Mod.of("a").register(b -> b.provide(Beta.class, l -> new Beta(l.get(Alpha.class))));
    StartupException e = failure(builder().module(a));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().get(0).module()).isEqualTo("a");
    assertThat(e.getMessage()).contains("Alpha").contains("no module provides it");
  }

  @Test
  void cycleBetweenProvidersNamesTheCycle() {
    record X(Object o) {}
    record Y(Object o) {}
    Mod a =
        Mod.of("a")
            .register(
                b -> {
                  b.provide(X.class, l -> new X(l.get(Y.class)));
                  b.provide(Y.class, l -> new Y(l.get(X.class)));
                });
    StartupException e = failure(builder().module(a));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.getMessage()).contains("cycle").contains("X -> Y -> X");
  }

  @Test
  void configErrorFailsBeforeAnythingIsBuilt() {
    AtomicInteger built = new AtomicInteger();
    Mod a =
        Mod.of("a")
            .register(
                b -> {
                  b.declare(Setting.string("a.needed").asRequired());
                  b.provide(Alpha.class, l -> {
                    built.incrementAndGet();
                    return new Alpha("x");
                  });
                });
    StartupException e = failure(builder().module(a));
    assertThat(e.getMessage()).contains("module 'a'").contains("a.needed");
    assertThat(built).hasValue(0);
  }

  @Test
  void duplicateSettingDeclarationFails() {
    Mod a = Mod.of("a").register(b -> b.declare(Setting.string("x.y")));
    Mod b = Mod.of("b").register(x -> x.declare(Setting.string("x.y")));
    StartupException e = failure(builder().module(a).module(b));
    assertThat(e.getMessage()).contains("declared twice").contains("module 'b'");
  }

  @Test
  void settingsSharingAnEnvironmentVariableFailStartUpNamingBothModules() {
    Mod a = Mod.of("a").register(b -> b.declare(Setting.string("x.y-z")));
    Mod b = Mod.of("b").register(x -> x.declare(Setting.string("x.y_z")));
    StartupException e = failure(builder().module(a).module(b));
    assertThat(e.getMessage())
        .contains("module 'b'")
        .contains("module 'a'")
        .contains("X_Y_Z")
        .contains("x.y-z")
        .contains("x.y_z");
  }

  @Test
  void exceptionFromRegisterProviderAndBootAreReported() {
    Mod r = Mod.of("r").register(b -> { throw new IllegalStateException("register boom"); });
    StartupException e1 = failure(builder().module(r));
    assertThat(e1.getMessage()).contains("module 'r'").contains("register boom");

    Mod p = Mod.of("p").register(b -> b.provide(Alpha.class, l -> { throw new java.io.IOException("provider boom"); }));
    StartupException e2 = failure(builder().module(p));
    assertThat(e2.getMessage()).contains("module 'p'").contains("provider boom");
    assertThat(e2.getSuppressed()).hasSize(1);

    Mod bt = Mod.of("bt").boot(a -> { throw new IllegalStateException("boot boom"); });
    StartupException e3 = failure(builder().module(bt));
    assertThat(e3.getMessage()).contains("module 'bt'").contains("boot boom");
  }

  @Test
  void aStartupExceptionFromBootHasItsProblemsReportedIndividually() {
    Mod bt =
        Mod.of("bt")
            .boot(
                a -> {
                  throw new StartupException(
                      List.of(Problem.of("x", "k1", "first problem"), Problem.of("y", "k2", "second problem")));
                });
    StartupException e = failure(builder().module(bt));
    assertThat(e.problems()).extracting(Problem::message).containsExactly("first problem", "second problem");
    assertThat(e.problems()).extracting(Problem::module).containsExactly("x", "y");
  }

  @Test
  void oneStartReportsEveryProblemFound() {
    Mod a =
        Mod.of("a")
            .register(
                b -> {
                  b.provide(Alpha.class, l -> { throw new IllegalStateException("one"); });
                  b.provide(Beta.class, l -> { throw new IllegalStateException("two"); });
                  b.provide(Gamma.class, l -> new Gamma(l.get(Beta.class)));
                });
    Mod b = Mod.of("b").register(x -> x.provide(Svc1.class, l -> new Svc1(l.get(CloseA.class))));
    StartupException e = failure(builder().module(a).module(b));
    // Gamma fails because Beta did: that is not a new problem.
    assertThat(e.problems()).hasSize(3);
    assertThat(e.getMessage()).contains("one").contains("two").contains("CloseA");
  }

  @Test
  void failedStartClosesBuiltServicesLastBuiltFirst() {
    Mod m =
        Mod.of("m")
            .register(
                b -> {
                  b.provide(CloseA.class, l -> new CloseA());
                  b.provide(CloseB.class, l -> new CloseB());
                  b.provide(Alpha.class, l -> new Alpha("not closeable"));
                  b.provide(Beta.class, l -> { throw new IllegalStateException("fail"); });
                });
    failure(builder().module(m));
    assertThat(log).containsExactly("close b", "close a");
  }

  @Test
  void failedBootClosesServicesAndShutsDownBootedModulesInReverse() {
    Mod a =
        Mod.of("a")
            .register(b -> b.provide(Closing.class, l -> new CloseA()))
            .boot(app -> log.add("boot a"))
            .shutdown(app -> log.add("shutdown a"));
    Mod b =
        Mod.of("b")
            .boot(app -> log.add("boot b"))
            .shutdown(app -> log.add("shutdown b"));
    Mod c =
        Mod.of("c")
            .boot(app -> { throw new IllegalStateException("boot c failed"); })
            .shutdown(app -> log.add("shutdown c"));
    StartupException e = failure(builder().module(a).module(b).module(c));
    assertThat(e.getMessage()).contains("module 'c'");
    assertThat(log)
        .containsExactly("boot a", "boot b", "shutdown b", "shutdown a", "close a");
  }

  // ---- started and find -------------------------------------------------------------------

  @Test
  void startedRunsAfterEveryBootInModuleOrder() {
    Mod a = Mod.of("a").boot(app -> log.add("boot a")).started(app -> log.add("started a"));
    Mod b = Mod.of("b").boot(app -> log.add("boot b")).started(app -> log.add("started b"));
    builder().module(a).module(b).start();
    assertThat(log).containsExactly("boot a", "boot b", "started a", "started b");
  }

  @Test
  void aStartedFailureNamesTheModuleAndShutsDownBootedModules() {
    Mod a =
        Mod.of("a")
            .register(b -> b.provide(Closing.class, l -> new CloseA()))
            .boot(app -> log.add("boot a"))
            .started(app -> log.add("started a"))
            .shutdown(app -> log.add("shutdown a"));
    Mod b =
        Mod.of("b")
            .boot(app -> log.add("boot b"))
            .started(app -> { throw new IllegalStateException("started b failed"); })
            .shutdown(app -> log.add("shutdown b"));
    StartupException e = failure(builder().module(a).module(b));
    assertThat(e.getMessage()).contains("module 'b'").contains("started failed");
    assertThat(log)
        .containsExactly(
            "boot a", "boot b", "started a", "shutdown b", "shutdown a", "close a");
  }

  @Test
  void startedDoesNotRunWhenABootFailed() {
    Mod a = Mod.of("a").started(app -> log.add("started a"));
    Mod b = Mod.of("b").boot(app -> { throw new IllegalStateException("nope"); });
    failure(builder().module(a).module(b));
    assertThat(log).isEmpty();
  }

  @Test
  void findReturnsTheServiceOrEmpty() {
    Mod a = Mod.of("a").register(b -> b.provide(Alpha.class, l -> new Alpha("x")));
    App app = builder().module(a).start();
    assertThat(app.find(Alpha.class)).contains(new Alpha("x"));
    assertThat(app.find(Beta.class)).isEmpty();
  }

  // ---- shutdown ---------------------------------------------------------------------------

  @Test
  void shutdownRunsModuleHooksInReverseThenServicesInReverseBuildOrder() {
    Mod a =
        Mod.of("a")
            .register(
                b -> {
                  b.provide(CloseB.class, l -> new CloseB());
                  b.provide(CloseA.class, l -> { l.get(CloseB.class); return new CloseA(); });
                })
            .shutdown(app -> log.add("shutdown a"));
    Mod b =
        Mod.of("b")
            .register(x -> x.provide(Alpha.class, l -> new Alpha("a")))
            .shutdown(app -> log.add("shutdown b"));
    App app = builder().module(a).module(b).start();
    app.close();
    assertThat(log).containsExactly("shutdown b", "shutdown a", "close a", "close b");
  }

  @Test
  void closeIsIdempotent() {
    Mod a =
        Mod.of("a")
            .register(b -> b.provide(CloseA.class, l -> new CloseA()))
            .shutdown(app -> log.add("shutdown"));
    App app = builder().module(a).start();
    app.close();
    app.close();
    assertThat(log).containsExactly("shutdown", "close a");
  }

  @Test
  void aFailingShutdownStepDoesNotStopTheRest() {
    Mod a =
        Mod.of("a")
            .register(b -> b.provide(Closing.class, l -> new CloseA()))
            .shutdown(app -> log.add("shutdown a"));
    Mod b = Mod.of("b").shutdown(app -> { throw new IllegalStateException("bad"); });
    builder().module(a).module(b).start().close();
    assertThat(log).containsExactly("shutdown a", "close a");
  }

  @Test
  void aShutdownStepThatOverrunsTheDrainIsInterruptedAndTheNextRuns() throws Exception {
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
    Mod fast = Mod.of("fast").shutdown(app -> log.add("fast done"));
    // slow is registered last, so it shuts down first.
    App app = builder(Map.of("APP_SHUTDOWN_DRAIN", "100ms")).module(fast).module(slow).start();
    long t0 = System.nanoTime();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    assertThat(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(log).containsExactly("fast done");
  }

  @Test
  void shutdownHookIsInstalledOnRequestAndRemovedOnClose() {
    App app = builder().shutdownHook(true).start();
    app.close(); // must not leave the hook behind or throw
    app.close();
  }

  // ---- health -----------------------------------------------------------------------------

  @Test
  void healthReportsUpDownAndTimedOut() {
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.health().add("ok", () -> {});
                  app.health().add("bad", () -> { throw new IllegalStateException("broken"); });
                  app.health().add("hang", () -> Thread.sleep(Duration.ofSeconds(30)));
                });
    App app = builder(Map.of("APP_HEALTH_TIMEOUT", "200ms")).module(m).start();
    HealthReport report = app.health().check();
    assertThat(report.results().keySet()).containsExactly("ok", "bad", "hang");
    assertThat(report.results().get("ok")).isInstanceOf(HealthResult.Up.class);
    assertThat(report.results().get("bad"))
        .isEqualTo(new HealthResult.Down("IllegalStateException: broken"));
    assertThat(report.results().get("hang")).isInstanceOf(HealthResult.TimedOut.class);
    assertThat(report.isUp()).isFalse();
  }

  @Test
  void healthWithNoChecksIsUp() {
    assertThat(builder().start().health().check().isUp()).isTrue();
  }

  @Test
  void duplicateHealthCheckNameIsRejected() {
    App app = builder().start();
    app.health().add("x", () -> {});
    assertThatThrownBy(() -> app.health().add("x", () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anInformationalCheckThatIsDownIsReportedButTheAppStaysReady() {
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.health().add("db", () -> {});
                  app.health()
                      .add(
                          "platform",
                          () -> { throw new IllegalStateException("unreachable"); },
                          Criticality.INFORMATIONAL);
                });
    App app = builder().module(m).start();
    HealthReport report = app.health().check();
    assertThat(report.results().get("platform"))
        .isEqualTo(new HealthResult.Down("IllegalStateException: unreachable"));
    assertThat(report.isUp()).isFalse();
    assertThat(report.isReady()).isTrue();
    assertThat(report.informational()).containsExactly("platform");
    assertThat(report.criticality("platform")).isEqualTo(Criticality.INFORMATIONAL);
    assertThat(report.criticality("db")).isEqualTo(Criticality.CRITICAL);
  }

  @Test
  void aCriticalCheckThatIsDownOrTimesOutMakesTheAppNotReady() {
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.health().add("info", () -> {}, Criticality.INFORMATIONAL);
                  app.health().add("hang", () -> Thread.sleep(Duration.ofSeconds(30)));
                });
    App app = builder(Map.of("APP_HEALTH_TIMEOUT", "200ms")).module(m).start();
    HealthReport report = app.health().check();
    assertThat(report.results().get("hang")).isInstanceOf(HealthResult.TimedOut.class);
    assertThat(report.isReady()).isFalse();
  }

  @Test
  void configurationOverridesTheCriticalityAModuleAskedFor() {
    Mod m =
        Mod.of("m")
            .boot(
                app -> {
                  app.health().add("platform", () -> {}, Criticality.INFORMATIONAL);
                  app.health().add("cache", () -> {});
                  app.health().add("other", () -> {});
                });
    App app =
        builder(Map.of("APP_HEALTH_CRITICAL", "platform", "APP_HEALTH_INFORMATIONAL", "cache"))
            .module(m)
            .start();
    assertThat(app.health().criticality("platform")).isEqualTo(Criticality.CRITICAL);
    assertThat(app.health().criticality("cache")).isEqualTo(Criticality.INFORMATIONAL);
    assertThat(app.health().criticality("other")).isEqualTo(Criticality.CRITICAL);
    assertThat(app.health().criticality("missing")).isNull();
    assertThat(app.health().check().informational()).containsExactly("cache");
  }

  @Test
  void aCheckNamedInBothOverrideListsFailsStartUp() {
    StartupException e =
        failure(builder(Map.of("APP_HEALTH_CRITICAL", "a,b", "APP_HEALTH_INFORMATIONAL", "b")));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().get(0).key()).isEqualTo("app.health.informational");
    assertThat(e.problems().get(0).message()).contains("'b'");
  }

  @Test
  void overrideNamesThatMatchNoCheckAreReportedNotFatal() {
    App app =
        builder(Map.of("APP_HEALTH_INFORMATIONAL", "typo,x"))
            .module(Mod.of("m").boot(a -> a.health().add("x", () -> {})))
            .start();
    assertThat(app.health().unmatchedOverrides()).containsExactly("typo");
    assertThat(app.health().criticality("x")).isEqualTo(Criticality.INFORMATIONAL);
  }
}
