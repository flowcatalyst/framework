package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link AppBuilder#override}: replacing a provided service, in test mode only (MA item 2). */
class OverrideTest {

  @TempDir Path dir;

  interface Mailer {
    String send(String to);
  }

  record SmtpMailer(String host) implements Mailer {
    @Override
    public String send(String to) {
      return "smtp:" + to;
    }
  }

  record Notifier(Mailer mailer) {}

  private final AtomicInteger providerRuns = new AtomicInteger();
  private final List<String> closed = Collections.synchronizedList(new ArrayList<>());

  /** Provides Mailer (counting runs) and a Notifier built from it. */
  private Mod mailModule() {
    return Mod.of("mail")
        .register(
            b -> {
              b.provide(
                  Mailer.class,
                  l -> {
                    providerRuns.incrementAndGet();
                    return new SmtpMailer("smtp.example.com");
                  });
              b.provide(Notifier.class, l -> new Notifier(l.get(Mailer.class)));
            });
  }

  private AppBuilder builder(String appEnv) {
    return App.builder()
        .configDirectory(dir)
        .environment(appEnv == null ? Map.of() : Map.of("APP_ENV", appEnv));
  }

  private static StartupException failure(AppBuilder b) {
    Throwable t = catchThrowable(b::start);
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  /** A fake that records being closed, to prove the application leaves it to the test. */
  final class FakeMailer implements Mailer, AutoCloseable {
    @Override
    public String send(String to) {
      return "fake:" + to;
    }

    @Override
    public void close() {
      closed.add("fake");
    }
  }

  @Test
  void inTestModeTheOverrideReplacesTheServiceForEveryLookupAndTheProviderNeverRuns() {
    FakeMailer fake = new FakeMailer();
    try (App app = builder("test").module(mailModule()).override(Mailer.class, fake).start()) {
      assertThat(app.get(Mailer.class)).isSameAs(fake);
      // Another provider that asked for Mailer got the override too.
      assertThat(app.get(Notifier.class).mailer()).isSameAs(fake);
      assertThat(providerRuns).hasValue(0);
    }
    assertThat(closed).isEmpty();
  }

  @Test
  void outsideTestModeStartUpFailsBeforeAnyModuleIsRegistered() {
    AtomicInteger registered = new AtomicInteger();
    Mod counting = Mod.of("counting").register(b -> registered.incrementAndGet());
    for (String env : new String[] {null, "local", "production", "TEST", " test"}) {
      StartupException e =
          failure(
              builder(env)
                  .module(counting)
                  .module(mailModule())
                  .override(Mailer.class, new FakeMailer()));
      assertThat(e.problems()).hasSize(1);
      assertThat(e.problems().get(0).key()).isEqualTo(Mailer.class.getName());
      assertThat(e.problems().get(0).message())
          .contains("allowed only when APP_ENV is 'test'")
          .contains("it is '" + (env == null ? "local" : env) + "'");
    }
    assertThat(registered).hasValue(0);
    assertThat(providerRuns).hasValue(0);
  }

  @Test
  void theCheckReadsTheEnvironmentWhateverOrderTheBuilderWasCalledIn() {
    FakeMailer fake = new FakeMailer();
    AppBuilder b =
        App.builder()
            .configDirectory(dir)
            .module(mailModule())
            .override(Mailer.class, fake)
            .environment(Map.of("APP_ENV", "test"));
    try (App app = b.start()) {
      assertThat(app.get(Mailer.class)).isSameAs(fake);
    }
  }

  @Test
  void overridingATypeNobodyProvidesFailsStartUp() {
    StartupException e =
        failure(
            builder("test")
                .module(mailModule())
                .override(SmtpMailer.class, new SmtpMailer("other")));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().get(0).key()).isEqualTo(SmtpMailer.class.getName());
    assertThat(e.problems().get(0).message()).contains("no module provides this type");
  }

  @Test
  void frameworkTypesCannotBeOverridden() {
    StartupException e =
        failure(builder("test").override(InstantSource.class, InstantSource.system()));
    assertThat(e.problems().get(0).message()).contains("framework type cannot be overridden");
  }

  @Test
  void overridingOneTypeTwiceIsRefusedAtOnce() {
    AppBuilder b = builder("test").override(Mailer.class, new FakeMailer());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> b.override(Mailer.class, new FakeMailer()))
        .withMessageContaining("already overridden");
  }

  @Test
  void anInstanceOfTheWrongTypeIsRefusedAtOnce() {
    @SuppressWarnings({"unchecked", "rawtypes"})
    Class<Object> raw = (Class) Mailer.class;
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder("test").override(raw, "not a mailer"));
  }

  @Test
  void commandsSeeTheOverrideToo() {
    FakeMailer fake = new FakeMailer();
    List<Object> seen = new ArrayList<>();
    Mod withCommand =
        Mod.of("cmd")
            .register(
                b ->
                    b.command(
                        new Command() {
                          @Override
                          public String name() {
                            return "show";
                          }

                          @Override
                          public int run(App app, List<String> args, java.io.PrintStream out) {
                            seen.add(app.get(Mailer.class));
                            return 0;
                          }
                        }));
    Launch launch =
        builder("test")
            .module(mailModule())
            .module(withCommand)
            .override(Mailer.class, fake)
            .launch("show");
    assertThat(launch).isEqualTo(new Launch.Ran(0));
    assertThat(seen).containsExactly(fake);
  }
}
