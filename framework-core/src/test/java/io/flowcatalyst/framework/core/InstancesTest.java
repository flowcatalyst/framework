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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Named instances built from configuration with one {@link InstanceFactory} (core contract section
 * 2, amended by the config-and-code contract section 2.1: no driver names).
 */
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

  /**
   * The one factory: instances named {@code fail*} fail, {@code close*} record their closing, one
   * with an {@code inner} key wraps an instance built before it, {@code strict*} require {@code
   * text}; every other reads {@code text} (default hello) and {@code repeat.times}.
   */
  private final InstanceFactory<Greeter> factory =
      (name, cfg, lookup, built) -> {
        if (name.startsWith("fail")) {
          throw new IOException("cannot reach the thing");
        }
        if (name.startsWith("close")) {
          return new ClosingGreeter(name);
        }
        Optional<String> inner = cfg.findString("inner");
        if (inner.isPresent()) {
          Greeter wrapped = built.get(inner.get());
          return () -> "wrapped(" + wrapped.greet() + ")";
        }
        String text = name.startsWith("strict") ? cfg.string("text") : cfg.string("text", "hello");
        int times = cfg.section("repeat").integer("times", 1);
        return () -> (name + ":" + text).repeat(times);
      };

  private Mod module(String id) {
    return Mod.of(id)
        .register(b -> b.provide(Greeters.class, l -> new Greeters(Instances.build(Greeter.class, "greeters", l, factory))));
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
            a: { text: one }
            b: {}
        """);
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThat(g.names()).containsExactly("a", "b");
    assertThat(g.get("a").greet()).isEqualTo("a:one");
    assertThat(g.defaultInstance().greet()).isEqualTo("b:hello");
    assertThat(g.defaultName()).contains("b");
  }

  @Test
  void aSingleInstanceIsTheDefaultWhenDefaultIsOmitted() throws IOException {
    yaml("greeters:\n  instances:\n    only: { text: hi }\n");
    assertThat(app(Map.of()).start().get(Greeters.class).instances().defaultInstance().greet())
        .isEqualTo("only:hi");
  }

  @Test
  void severalInstancesWithNoDefaultFail() throws IOException {
    yaml("greeters:\n  instances:\n    a: { text: x }\n    b: { text: y }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.default").contains("a, b");
  }

  @Test
  void defaultNamingAMissingInstanceFails() throws IOException {
    yaml("greeters:\n  default: zzz\n  instances:\n    a: { text: x }\n");
    assertThat(failure(Map.of()).getMessage()).contains("'zzz'").contains("known: a");
  }

  @Test
  void defaultCanComeFromTheEnvironment() throws IOException {
    yaml("greeters:\n  default: a\n  instances:\n    a: { text: x }\n    b: { text: y }\n");
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
    yaml("greeters:\n  instances:\n    a: { text: x }\n    b: { text: y }\n  default: a\n");
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> g.get("c"))
        .withMessageContaining("'c'")
        .withMessageContaining("a, b");
  }

  @Test
  void aDriverKeyIsJustAnUnknownKeyNow() throws IOException {
    yaml("greeters:\n  instances:\n    a: { driver: plain, text: x }\n");
    StartupException e = failure(Map.of());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("greeters.instances.a.driver");
      assertThat(p.message()).contains("unknown key");
    });
  }

  @Test
  void unknownKeyInAnInstanceSectionFailsEvenWhenNested() throws IOException {
    yaml(
        """
        greeters:
          instances:
            strict: { text: x, txt: typo, repeat: { times: 2, timez: 3 } }
        """);
    StartupException e = failure(Map.of());
    assertThat(e.problems()).hasSize(2);
    assertThat(e.getMessage())
        .contains("greeters.instances.strict.txt")
        .contains("greeters.instances.strict.repeat.timez")
        .contains("unknown key");
  }

  @Test
  void unknownKeyInTheCapabilitySectionFails() throws IOException {
    yaml("greeters:\n  defualt: a\n  instances:\n    a: { text: x }\n");
    assertThat(failure(Map.of()).getMessage()).contains("greeters.defualt");
  }

  @Test
  void envVarsOverrideInstanceKeys() throws IOException {
    yaml("greeters:\n  instances:\n    strict: { text: file }\n");
    Instances<Greeter> g =
        app(Map.of("GREETERS_INSTANCES_STRICT_TEXT", "env", "GREETERS_INSTANCES_STRICT_REPEAT_TIMES", "2"))
            .start()
            .get(Greeters.class)
            .instances();
    assertThat(g.get("strict").greet()).isEqualTo("strict:envstrict:env");
  }

  @Test
  void aKeyGivenOnlyByEnvironmentIsAcceptedButDoesNotCreateAnInstance() throws IOException {
    yaml("greeters:\n  instances:\n    strict: {}\n");
    Instances<Greeter> g =
        app(Map.of("GREETERS_INSTANCES_STRICT_TEXT", "t", "GREETERS_INSTANCES_B_TEXT", "plain"))
            .start()
            .get(Greeters.class)
            .instances();
    assertThat(g.names()).containsExactly("strict");
  }

  @Test
  void missingRequiredKeyInAFactoryNamesTheKeyAndTheEnvVar() throws IOException {
    yaml("greeters:\n  instances:\n    strict: {}\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage())
        .contains("module 'greetings'")
        .contains("greeters.instances.strict.text")
        .contains("GREETERS_INSTANCES_STRICT_TEXT");
  }

  @Test
  void factoryFailureIsReportedWithItsCause() throws IOException {
    yaml("greeters:\n  instances:\n    failing: {}\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.instances.failing").contains("cannot reach the thing");
    assertThat(e.getSuppressed()).hasSize(1);
  }

  @Test
  void problemsAcrossInstancesAreReportedTogether() throws IOException {
    yaml(
        """
        greeters:
          instances:
            fail-b: {}
            strict: {}
            d: { typo: 1 }
        """);
    assertThat(failure(Map.of()).problems()).hasSize(4); // fail-b, strict's text, d's unknown key, default missing
  }

  @Test
  void instancesBuiltBeforeAFailureAreClosed() throws IOException {
    yaml(
        """
        greeters:
          default: close-x
          instances:
            close-x: {}
            close-y: {}
            fail-z: {}
        """);
    failure(Map.of());
    assertThat(log).containsExactly("close close-y", "close close-x");
  }

  @Test
  void aFactoryCanSeeInstancesBuiltBeforeIt() throws IOException {
    yaml(
        """
        greeters:
          default: w
          instances:
            base: { text: hi }
            w: { inner: base }
        """);
    Instances<Greeter> g = app(Map.of()).start().get(Greeters.class).instances();
    assertThat(g.get("w").greet()).isEqualTo("wrapped(base:hi)");
  }

  @Test
  void instancesAreClosedWithTheApplication() throws IOException {
    yaml("greeters:\n  instances:\n    close-x: {}\n    close-y: {}\n  default: close-x\n");
    App app = app(Map.of()).start();
    app.close();
    assertThat(log).containsExactly("close close-y", "close close-x");
  }

  @Test
  void typedGettersOnInstanceConfigParseErrorsAreProblems() throws IOException {
    yaml("greeters:\n  instances:\n    strict: { text: x, repeat: { times: many } }\n");
    StartupException e = failure(Map.of());
    assertThat(e.getMessage()).contains("greeters.instances.strict.repeat.times").contains("an integer");
    assertThat(SettingType.INTEGER.describe()).isEqualTo("an integer");
  }
}
