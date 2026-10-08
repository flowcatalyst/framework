package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.flowcatalyst.framework.config.SettingType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstancesTest {

  @TempDir Path dir;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  /** A capability for the tests. */
  interface Greeter {
    String greet();
  }

  /** The registry the capability's module provides. */
  record Greeters(Instances<Greeter> instances) implements AutoCloseable {
    @Override
    public void close() {
      instances.close();
    }
  }

  private void yaml(String text) throws IOException {
    Files.writeString(dir.resolve("application.yaml"), text);
  }

  private Mod module(String id) {
    return Mod.of(id)
        .register(
            b -> {
              Drivers.Registrar drivers = b.drivers();
              drivers.register(
                  Greeter.class,
                  "plain",
                  (name, cfg, lookup, built) -> {
                    String text = cfg.string("text", "hello");
                    return () -> name + ":" + text;
                  });
              drivers.register(
                  Greeter.class,
                  "strict",
                  (name, cfg, lookup, built) -> {
                    String text = cfg.string("text");
                    int times = cfg.section("repeat").integer("times", 1);
                    return () -> (name + ":" + text).repeat(times);
                  });
              drivers.register(
                  Greeter.class,
                  "closing",
                  (name, cfg, lookup, built) -> new ClosingGreeter(name));
              drivers.register(
                  Greeter.class,
                  "failing",
                  (name, cfg, lookup, built) -> {
                    throw new IOException("cannot reach the thing");
                  });
              drivers.register(
                  Greeter.class,
                  "wrapper",
                  (name, cfg, lookup, built) -> {
                    Greeter inner = built.get(cfg.string("inner"));
                    return () -> "wrapped(" + inner.greet() + ")";
                  });
              b.provide(Greeters.class, l -> new Greeters(Instances.build(Greeter.class, "greeters", l)));
            });
  }

  private final class ClosingGreeter implements Greeter, AutoCloseable {
    private final String name;

    ClosingGreeter(String name) {
      this.name = name;
    }

    @Override
    public String greet() {
      return name;
    }

    @Override
    public void close() {
      log.add("close " + name);
    }
  }

  private AppBuilder app(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env).module(module("greetings"));
  }

  private StartupException failure(Map<String, String> env) {
    Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> app(env).start());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void buildsEveryConfiguredInstanceAndTheDefault() throws IOException {
    yaml(
        """
        greeters:
          default: b
          instances:
            a: { driver: plain, text: one }
            b: { driver: plain }
        """);
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThat(g.names()).containsExactly("a", "b");
    assertThat(g.get("a").greet()).isEqualTo("a:one");
    assertThat(g.defaultInstance().greet()).isEqualTo("b:hello");
    assertThat(g.defaultName()).contains("b");
  }

  @Test
  void aSingleInstanceIsTheDefaultWhenDefaultIsOmitted() throws IOException {
    yaml("greeters:\n  instances:\n    only: { driver: plain }\n");
    assertThat(app(Map.of()).start().get(Greeters.class).instances().defaultInstance().greet())
        .isEqualTo("only:hello");
  }

  @Test
  void severalInstancesWithNoDefaultFail() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: plain }\n    b: { driver: plain }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.default").contains("a, b");
  }

  @Test
  void defaultNamingAMissingInstanceFails() throws IOException {
    yaml("greeters:\n  default: zzz\n  instances:\n    a: { driver: plain }\n");
    assertThat(failure(Map.of()).getMessage()).contains("'zzz'").contains("known: a");
  }

  @Test
  void defaultCanComeFromTheEnvironment() throws IOException {
    yaml("greeters:\n  default: a\n  instances:\n    a: { driver: plain }\n    b: { driver: plain }\n");
    Instances<Greeter> g =
        app(Map.of("GREETERS_DEFAULT", "b")).start().get(Greeters.class).instances();
    assertThat(g.defaultInstance().greet()).startsWith("b:");
  }

  @Test
  void aCapabilityWithNoInstancesIsValidAndGetThrows() {
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThat(g.names()).isEmpty();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> g.get("x"))
        .withMessageContaining("known instances: (none)");
    assertThatIllegalStateException().isThrownBy(g::defaultInstance);
    assertThat(g.defaultName()).isEmpty();
  }

  @Test
  void getOfAnUnknownNameNamesTheKnownInstances() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: plain }\n    b: { driver: plain }\n  default: a\n");
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> g.get("c"))
        .withMessageContaining("'c'")
        .withMessageContaining("a, b");
  }

  @Test
  void unknownDriverListsTheRegisteredNames() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: nope }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("module 'greetings'")
        .contains("greeters.instances.a.driver")
        .contains("unknown driver 'nope'")
        .contains("closing, failing, plain, strict, wrapper");
  }

  @Test
  void aDefaultDriverIsUsedWhenAnInstanceNamesNone() throws IOException {
    yaml("greeters:\n  instances:\n    a: { text: x }\n    b: { driver: other, text: y }\n  default: a\n");
    Mod m =
        Mod.of("other")
            .register(
                b -> {
                  b.drivers()
                      .register(Other.class, "only", (n, c, l, built) -> {
                        String t = c.string("text");
                        return () -> n + ":" + t;
                      });
                  b.drivers()
                      .register(Other.class, "other", (n, c, l, built) -> {
                        String t = c.string("text");
                        return () -> "o" + t;
                      });
                  b.provide(
                      OtherHolder.class,
                      l -> new OtherHolder(Instances.build(Other.class, "greeters", l, "only")));
                });
    Instances<Other> i =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(m)
            .start()
            .get(OtherHolder.class)
            .instances();
    assertThat(i.get("a").value()).isEqualTo("a:x");
    assertThat(i.get("b").value()).isEqualTo("oy");
  }

  /** A second capability, so a default driver can be exercised alone. */
  interface Other {
    String value();
  }

  record OtherHolder(Instances<Other> instances) {}

  @Test
  void missingDriverKeyIsReported() throws IOException {
    yaml("greeters:\n  instances:\n    a: { text: x }\n");
    assertThat(failure(Map.of()).getMessage()).contains("greeters.instances.a.driver");
  }

  @Test
  void unknownKeyInAnInstanceSectionFailsEvenWhenNested() throws IOException {
    yaml(
        """
        greeters:
          instances:
            a: { driver: strict, text: x, txt: typo, repeat: { times: 2, timez: 3 } }
        """);
    StartupException e = failure(Map.of());
    assertThat(e.problems()).hasSize(2);
    assertThat(e.getMessage())
        .contains("greeters.instances.a.txt")
        .contains("greeters.instances.a.repeat.timez")
        .contains("unknown key");
  }

  @Test
  void unknownKeyInTheCapabilitySectionFails() throws IOException {
    yaml("greeters:\n  defualt: a\n  instances:\n    a: { driver: plain }\n");
    assertThat(failure(Map.of()).getMessage()).contains("greeters.defualt");
  }

  @Test
  void envVarsOverrideInstanceKeys() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: strict, text: file }\n");
    Instances<Greeter> g =
        app(Map.of("GREETERS_INSTANCES_A_TEXT", "env", "GREETERS_INSTANCES_A_REPEAT_TIMES", "2"))
            .start()
            .get(Greeters.class)
            .instances();
    assertThat(g.get("a").greet()).isEqualTo("a:enva:env");
  }

  @Test
  void aKeyGivenOnlyByEnvironmentIsAcceptedButDoesNotCreateAnInstance() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: strict }\n");
    Instances<Greeter> g =
        app(Map.of("GREETERS_INSTANCES_A_TEXT", "t", "GREETERS_INSTANCES_B_DRIVER", "plain"))
            .start()
            .get(Greeters.class)
            .instances();
    assertThat(g.names()).containsExactly("a");
  }

  @Test
  void missingRequiredKeyInAFactoryNamesTheKeyAndTheEnvVar() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: strict }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("module 'greetings'")
        .contains("greeters.instances.a.text")
        .contains("GREETERS_INSTANCES_A_TEXT");
  }

  @Test
  void factoryFailureIsReportedWithItsCause() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: failing }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.instances.a").contains("cannot reach the thing");
    assertThat(e.getSuppressed()).hasSize(1);
  }

  @Test
  void problemsAcrossInstancesAreReportedTogether() throws IOException {
    yaml(
        """
        greeters:
          instances:
            a: { driver: nope }
            b: { driver: failing }
            c: { driver: strict }
            d: { driver: plain, typo: 1 }
        """);
    assertThat(failure(Map.of()).problems()).hasSize(5); // a, b, c, d unknown key, default missing
  }

  @Test
  void instancesBuiltBeforeAFailureAreClosed() throws IOException {
    yaml(
        """
        greeters:
          default: x
          instances:
            x: { driver: closing }
            y: { driver: closing }
            z: { driver: failing }
        """);
    // ClosingGreeter logs through the test instance; the driver in module() builds it.
    failure(Map.of());
    assertThat(log).containsExactly("close y", "close x");
  }

  @Test
  void aFactoryCanSeeInstancesBuiltBeforeIt() throws IOException {
    yaml(
        """
        greeters:
          default: w
          instances:
            base: { driver: plain, text: hi }
            w: { driver: wrapper, inner: base }
        """);
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThat(g.get("w").greet()).isEqualTo("wrapped(base:hi)");
  }

  @Test
  void instancesAreClosedWithTheApplication() throws IOException {
    yaml("greeters:\n  instances:\n    x: { driver: closing }\n    y: { driver: closing }\n  default: x\n");
    App app = app(Map.of()).start();
    app.close();
    assertThat(log).containsExactly("close y", "close x");
  }

  @Test
  void registeringOneDriverNameTwiceFailsStartUp() {
    Mod a = Mod.of("a").register(b -> b.drivers().register(Greeter.class, "plain", (n, c, l, m) -> () -> "a"));
    Mod b = Mod.of("b").register(x -> x.drivers().register(Greeter.class, "plain", (n, c, l, m) -> () -> "b"));
    Throwable t =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> App.builder().configDirectory(dir).environment(Map.of()).module(a).module(b).start());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("module 'b'").contains("plain").contains("registered twice");
  }

  @Test
  void typedGettersOnInstanceConfigParseErrorsAreProblems() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: strict, text: x, repeat: { times: many } }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.instances.a.repeat.times").contains("an integer");
    assertThat(SettingType.INTEGER.describe()).isEqualTo("an integer");
  }
}
