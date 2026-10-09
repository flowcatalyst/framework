package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandTest {

  @TempDir Path dir;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  record Greeter(String text) {}

  private interface Body {
    int run(App app, List<String> args) throws Exception;
  }

  private Command command(String name, Body body) {
    return new Command() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int run(App app, List<String> args, PrintStream out) throws Exception {
        log.add("run " + name + " " + args);
        return body.run(app, args);
      }
    };
  }

  private Mod module(String id, Command... commands) {
    return Mod.of(id)
        .register(
            b -> {
              b.provide(Greeter.class, lookup -> new Greeter("hello"));
              for (Command c : commands) {
                b.command(c);
              }
            })
        .boot(a -> log.add("boot " + id))
        .started(a -> log.add("started " + id))
        .shutdown(a -> log.add("shutdown " + id));
  }

  private AppBuilder builder(Mod... modules) {
    AppBuilder b = App.builder().configDirectory(dir).environment(Map.of());
    for (Mod m : modules) {
      b.module(m);
    }
    return b;
  }

  @Test
  void aClaimedCommandRunsWithServicesBeforeAnyBootAndTheAppIsClosed() {
    Command sync = command("sync", (app, args) -> {
      assertThat(app.get(Greeter.class).text()).isEqualTo("hello");
      return 7;
    });

    Launch launch = builder(module("m", sync)).launch("sync", "--dry-run", "x");

    assertThat(launch).isEqualTo(new Launch.Ran(7));
    assertThat(log).containsExactly("run sync [--dry-run, x]");
  }

  @Test
  void anUnclaimedFirstArgumentFallsThroughToANormalStart() {
    Command sync = command("sync", (app, args) -> 0);

    Launch launch = builder(module("m", sync)).launch("serve");

    assertThat(launch).isInstanceOf(Launch.Started.class);
    assertThat(log).containsExactly("boot m", "started m");
    ((Launch.Started) launch).app().close();
    assertThat(log).contains("shutdown m");
  }

  @Test
  void noArgumentsStartsNormally() {
    Launch launch = builder(module("m", command("sync", (app, args) -> 0))).launch();

    assertThat(launch).isInstanceOf(Launch.Started.class);
    ((Launch.Started) launch).app().close();
  }

  @Test
  void aCommandThatThrowsExitsWithOne() {
    Launch launch =
        builder(module("m", command("sync", (app, args) -> {
              throw new IllegalStateException("boom");
            })))
            .launch("sync");

    assertThat(launch).isEqualTo(new Launch.Ran(1));
  }

  @Test
  void twoModulesClaimingOneNameFailStartUpNamingBoth() {
    Throwable t =
        catchThrowable(
            () ->
                builder(
                        module("a", command("sync", (app, args) -> 0)),
                        module("b", command("sync", (app, args) -> 0)))
                    .launch("sync"));

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("module 'a'").contains("sync").contains("already claimed");
    assertThat(((StartupException) t).problems()).extracting(Problem::module).contains("b");
  }

  @Test
  void aBadCommandNameFailsStartUp() {
    Throwable t =
        catchThrowable(() -> builder(module("a", command("Sync Now", (app, args) -> 0))).launch());

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("command name must match");
  }

  @Test
  void startStillWorksWithoutLaunch() {
    try (App app = builder(module("m", command("sync", (a, b) -> 0))).start()) {
      assertThat(log).containsExactly("boot m", "started m");
      assertThat(app.get(Greeter.class)).isNotNull();
    }
  }
}
