package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Declaration;
import io.flowcatalyst.framework.config.Setting;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Declaration-phase commands (core contract section 5, amended for the OpenAPI contract). */
class DeclarationCommandTest {

  @TempDir Path dir;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  record Greeter(String text) {}

  record Marker(String value) {}

  static final Setting<String> REQUIRED =
      Setting.string("demo.required").asRequired().describedAs("a setting nobody sets");

  private interface Body {
    int run(Declarations declarations, List<String> args) throws Exception;
  }

  private DeclarationCommand declared(String name, Body body) {
    return new DeclarationCommand() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int run(Declarations declarations, List<String> args, PrintStream out) throws Exception {
        log.add("run " + name + " " + args);
        return body.run(declarations, args);
      }
    };
  }

  /** A module whose provider, config and boot would all fail: none of them may be reached. */
  private Mod module(String id, DeclarationCommand... commands) {
    return Mod.of(id)
        .register(
            b -> {
              b.declare(REQUIRED);
              b.contribute(Marker.class, new Marker(id));
              b.provide(
                  Greeter.class,
                  lookup -> {
                    log.add("provider " + id);
                    throw new IllegalStateException("no database here");
                  });
              for (DeclarationCommand c : commands) {
                b.command(c);
              }
            })
        .boot(a -> log.add("boot " + id))
        .started(a -> log.add("started " + id));
  }

  private AppBuilder builder(Mod... modules) {
    // a directory with no application.yaml and an environment with nothing in it
    AppBuilder b = App.builder().configDirectory(dir.resolve("absent")).environment(Map.of());
    for (Mod m : modules) {
      b.module(m);
    }
    return b;
  }

  @Test
  void aDeclarationCommandRunsAfterRegisterOnlyWithNoConfigurationAndNoServices() {
    AtomicReference<Declarations> seen = new AtomicReference<>();
    DeclarationCommand dump = declared("dump", (d, args) -> {
      seen.set(d);
      return 3;
    });

    Launch launch = builder(module("m", dump)).launch("dump", "--out", "x.json");

    assertThat(launch).isEqualTo(new Launch.Ran(3));
    // the required setting is missing and the provider throws: neither was looked at
    assertThat(log).containsExactly("run dump [--out, x.json]");
    Declarations d = seen.get();
    assertThat(d.moduleIds()).containsExactly("core", "m");
    assertThat(d.contributions(Marker.class)).containsExactly(new Marker("m"));
    assertThat(d.contributions(Greeter.class)).isEmpty();
    assertThat(d.settings()).extracting(Declaration::module).contains("m");
    assertThat(d.settings()).extracting(x -> x.setting().key()).contains("demo.required");
  }

  @Test
  void theSameBuilderWithoutTheCommandFailsOnTheMissingSetting() {
    Throwable t = catchThrowable(() -> builder(module("m", declared("dump", (d, a) -> 0))).launch());

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("demo.required");
  }

  @Test
  void aDeclarationCommandThatThrowsExitsWithOne() {
    Launch launch =
        builder(module("m", declared("dump", (d, a) -> {
              throw new IllegalStateException("boom");
            })))
            .launch("dump");

    assertThat(launch).isEqualTo(new Launch.Ran(1));
  }

  @Test
  void registerProblemsStillFailTheCommand() {
    Throwable t =
        catchThrowable(
            () -> builder(module("m", declared("dump", (d, a) -> 0)), module("m")).launch("dump"));

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("duplicate module id");
    assertThat(log).isEmpty();
  }

  @Test
  void commandNamesAreOneNamespaceAcrossBothKinds() {
    Mod a = module("a", declared("sync", (d, x) -> 0));
    Mod b =
        Mod.of("b")
            .register(
                x ->
                    x.command(
                        new Command() {
                          @Override
                          public String name() {
                            return "sync";
                          }

                          @Override
                          public int run(App app, List<String> args, PrintStream out) {
                            return 0;
                          }
                        }));

    Throwable t = catchThrowable(() -> builder(a, b).launch("sync"));

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("command already claimed by module 'a'");
  }

  @Test
  void aBadDeclarationCommandNameFailsStartUp() {
    Throwable t = catchThrowable(() -> builder(module("a", declared("Dump It", (d, x) -> 0))).launch("x"));

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("command name must match");
  }

  @Test
  void declarationsReturnsWhatTheModulesDeclaredWithoutStarting() {
    Mod n = Mod.of("n").register(b -> b.contribute(Marker.class, new Marker("n")));
    Declarations d = builder(module("m"), n).declarations();

    assertThat(d.contributions(Marker.class)).containsExactly(new Marker("m"), new Marker("n"));
    assertThat(log).isEmpty();
  }

  @Test
  void declarationsCanBeTakenOnlyOnce() {
    AppBuilder b = builder(module("m"));
    b.declarations();

    assertThat(catchThrowable(b::declarations)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void declarationsOfABuilderWithRegisterProblemsThrows() {
    Throwable t = catchThrowable(() -> builder(module("m"), module("m")).declarations());

    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("duplicate module id");
  }
}
