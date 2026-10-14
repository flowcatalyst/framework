package io.flowcatalyst.framework.http.jetty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Launch;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The HTTP module knows the declared operations and claims the openapi command. */
class OperationsOverJettyTest {

  @TempDir Path dir;

  record Greeting(String text, @Nullable String note) {}

  static final PathParam<String> NAME = PathParam.string("name");
  static final Operation<NoBody, Greeting> GREET =
      Operation.get("/greet/{name}", Greeting.class).id("greet").path(NAME).build();

  static Module api(boolean bind) {
    return new Module() {
      @Override
      public String id() {
        return "api";
      }

      @Override
      public void register(AppBuilder app) {
        OpenApi.declare(app, GREET);
        OpenApi.info(app, "greeter", "1");
      }

      @Override
      public void boot(App app) {
        if (bind) {
          app.get(Routes.class).forModule(id()).bind(GREET, r -> Outcome.ok(new Greeting("hello " + r.path(NAME), null)));
        }
      }
    };
  }

  AppBuilder builder(Module api) {
    return App.builder().configDirectory(dir).environment(Apps.env()).module(new JettyHttpModule()).module(api);
  }

  @Test
  void aDeclaredAndBoundOperationIsServed() throws Exception {
    try (App app = builder(api(true)).start()) {
      HttpResponse<String> r =
          HttpClient.newHttpClient()
              .send(HttpRequest.newBuilder(URI.create(Apps.url(app, "/greet/ada"))).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).isEqualTo("{\"text\":\"hello ada\"}");
    }
  }

  @Test
  void aDeclaredOperationThatNoModuleBindsFailsStartUp() {
    Throwable t = catchThrowable(() -> builder(api(false)).start());

    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("operation greet is declared but not bound");
  }

  @Test
  void theOpenapiCommandWritesTheDocumentWithoutStartingTheServer() {
    PrintStream original = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    Launch launch;
    try {
      // HTTP_PORT is not given: port 8080 might be taken; nothing may bind
      launch = App.builder().configDirectory(dir).environment(Map.of()).module(new JettyHttpModule()).module(api(true)).launch("openapi");
    } finally {
      System.setOut(original);
    }

    assertThat(launch).isEqualTo(new Launch.Ran(0));
    String doc = out.toString(StandardCharsets.UTF_8);
    assertThat(doc).startsWith("{\n  \"openapi\": \"3.1.0\",").contains("\"operationId\": \"greet\"");
  }
}
