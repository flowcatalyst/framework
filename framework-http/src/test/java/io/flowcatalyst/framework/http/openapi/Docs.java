package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.TypeRef;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Small helpers for emitter tests: one-operation documents, parsed. */
final class Docs {

  private Docs() {}

  static Operation<NoBody, ?> getting(Class<?> response) {
    return Operation.get("/x", response).id("getX").build();
  }

  static <R> Operation<NoBody, R> getting(TypeRef<R> response) {
    return Operation.get("/x", response).id("getX").build();
  }

  /** A register hook with info, the given operations and the session and bearer schemes. */
  static Consumer<AppBuilder> app(Operation<?, ?>... operations) {
    return app(x -> {}, operations);
  }

  static Consumer<AppBuilder> app(Consumer<AppBuilder> more, Operation<?, ?>... operations) {
    return a -> {
      OpenApi.info(a, "test", "1");
      OpenApi.declare(a, operations);
      OpenApi.securityScheme(a, "session", SecurityScheme.cookie("__Host-fw_session"));
      OpenApi.securityScheme(a, "bearer", SecurityScheme.bearer("JWT"));
      more.accept(a);
    };
  }

  static JsonNode doc(Consumer<AppBuilder> register) {
    return Json.mapper().readTree(OpenApiFixture.json(register));
  }

  static JsonNode doc(Operation<?, ?>... operations) {
    return doc(app(operations));
  }

  static JsonNode schema(JsonNode doc, String component) {
    JsonNode s = doc.path("components").path("schemas").path(component);
    if (s.isMissingNode()) {
      throw new AssertionError("no component " + component + " in " + doc.path("components").path("schemas").propertyNames());
    }
    return s;
  }

  static List<String> problems(Operation<?, ?>... operations) {
    return OpenApiFixture.problems(app(operations));
  }

  static List<String> problems(Consumer<AppBuilder> more, Operation<?, ?>... operations) {
    return OpenApiFixture.problems(app(more, operations));
  }

  static String compact(JsonNode node) {
    return node.toString();
  }
}
