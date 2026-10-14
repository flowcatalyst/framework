package io.flowcatalyst.framework.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The openapi declaration-phase command (contract section 9): no configuration, no services. */
class OpenApiCommandTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private PrintStream original = System.out;

  @BeforeEach
  void capture() {
    original = System.out;
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restore() {
    System.setOut(original);
  }

  private String out() {
    return captured.toString(StandardCharsets.UTF_8);
  }

  record Database(String url) {}

  static Module module(String id, Consumer<AppBuilder> register) {
    return new Module() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public void register(AppBuilder app) {
        register.accept(app);
      }

      @Override
      public void boot(App app) {
        throw new IllegalStateException("boot must not run for a declaration-phase command");
      }
    };
  }

  /** The fixture, an HTTP-like module claiming the command, and a module that would need a database. */
  AppBuilder app(Consumer<AppBuilder> fixture) {
    return App.builder()
        .configDirectory(dir.resolve("no-config-here"))
        .environment(Map.of())
        .module(module("http", a -> a.command(OpenApi.command())))
        .module(
            module(
                "db",
                a -> {
                  a.declare(Setting.string("database.url").asRequired().describedAs("never set in this test"));
                  a.provide(Database.class, lookup -> {
                    throw new IllegalStateException("no database is reachable");
                  });
                }))
        .module(module("fixture", fixture));
  }

  @Test
  void itPrintsTheDocumentWithNoConfigurationEnvironmentOrDatabase() {
    Launch launch = app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)).launch("openapi");

    assertThat(launch).isEqualTo(new Launch.Ran(0));
    assertThat(out()).isEqualTo(OpenApiFixture.fixture());
  }

  @Test
  void itWritesTheFileWithOut() throws Exception {
    Path file = dir.resolve("web/openapi.json");

    Launch launch = app(a -> OpenApiFixture.register(a, OpenApiFixture.ALL)).launch("openapi", "--out", file.toString());

    assertThat(launch).isEqualTo(new Launch.Ran(0));
    assertThat(Files.readString(file)).isEqualTo(OpenApiFixture.fixture());
    assertThat(out()).isEqualTo("openapi: wrote " + file + "\n");
  }

  @Test
  void everyProblemIsPrintedAndTheExitCodeIsOne() {
    record Bad(java.util.Optional<String> a) {}
    Operation<NoBody, Bad> bad = Operation.get("/bad", Bad.class).id("getBad").build();
    Operation<NoBody, Bad> unnamed = Operation.get("/unnamed", Bad.class).build();

    Launch launch = app(a -> OpenApi.declare(a, bad, unnamed)).launch("openapi");

    assertThat(launch).isEqualTo(new Launch.Ran(1));
    assertThat(out().lines().toList())
        .startsWith("openapi: the document cannot be written (3 problems)")
        .contains(
            "  - the document has no info: call OpenApi.info(app, title, version) once, in register",
            "  - GET /unnamed: an operation needs an id (.id(\"listOrders\"))");
  }

  @Test
  void unknownArgumentsPrintTheUsage() {
    Launch launch = app(a -> OpenApiFixture.register(a, List.of())).launch("openapi", "--format", "yaml");

    assertThat(launch).isEqualTo(new Launch.Ran(2));
    assertThat(out()).isEqualTo("usage: openapi [--out <file>]\n");
  }
}
