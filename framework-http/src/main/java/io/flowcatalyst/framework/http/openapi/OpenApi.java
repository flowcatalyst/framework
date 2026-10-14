package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.DeclarationCommand;
import io.flowcatalyst.framework.core.Declarations;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.http.Operation;
import java.util.ArrayList;
import java.util.List;

/**
 * Declares typed operations and what the OpenAPI document needs about them, in a module's {@code
 * register} (OpenAPI contract section 3), so the document is written without configuration, a
 * service or a database. Operations are bound to handlers in {@code boot} with {@code
 * Routes.bind}.
 *
 * <pre>{@code
 * // register(AppBuilder app)
 * OpenApi.declare(app, LIST, READ, PLACE);
 * OpenApi.info(app, "orders-demo", "1");   // once per application
 * }</pre>
 */
public final class OpenApi {

  private OpenApi() {}

  /**
   * Declares operations. Each must then be bound exactly once with {@code Routes.bind}.
   *
   * @param app the builder, inside {@code register}
   * @param operations the operations
   */
  public static void declare(AppBuilder app, Operation<?, ?>... operations) {
    app.contribute(Contribution.class, new Contribution.Operations(app.moduleId(), List.of(operations)));
  }

  /**
   * The document's title and version; once per application (a missing or a second one is a dump
   * problem).
   *
   * @param app the builder, inside {@code register}
   * @param title the title, for example the application code
   * @param version the API version
   */
  public static void info(AppBuilder app, String title, String version) {
    app.contribute(Contribution.class, new Contribution.Info(app.moduleId(), title, version));
  }

  /**
   * Gives a type its schema instead of the type walk's. A union ({@link Schema#oneOf}) becomes a
   * component under the type's name; any other schema (an application value type such as {@code
   * Money}) is written inline wherever the type appears. Jackson annotations on a registered type
   * are not checked: the schema is the application's word for what Jackson writes.
   *
   * @param app the builder, inside {@code register}
   * @param type the type
   * @param schema its schema
   */
  public static void schema(AppBuilder app, Class<?> type, Schema schema) {
    app.contribute(Contribution.class, new Contribution.TypeSchema(app.moduleId(), type, schema));
  }

  /**
   * Gives a record, enum or sealed interface another component name, to resolve two types that
   * produce one name.
   *
   * @param app the builder, inside {@code register}
   * @param type the type
   * @param name the component name
   */
  public static void name(AppBuilder app, Class<?> type, String name) {
    app.contribute(Contribution.class, new Contribution.TypeName(app.moduleId(), type, name));
  }

  /**
   * Replaces the schema of one record component (whether it is required still follows {@code
   * @Nullable}), for example a property whose value is fixed: {@code Schema.bool().constant(true)}.
   *
   * @param app the builder, inside {@code register}
   * @param record the record class
   * @param component the record component's name
   * @param schema its schema
   */
  public static void property(AppBuilder app, Class<? extends Record> record, String component, Schema schema) {
    app.contribute(Contribution.class, new Contribution.PropertySchema(app.moduleId(), record, component, schema));
  }

  /**
   * Contributes a security scheme: called by the module that resolves the {@code Context}
   * ({@code framework-auth} contributes {@code session} and {@code bearer}; an application with its
   * own resolver contributes its own).
   *
   * @param app the builder, inside {@code register}
   * @param name the scheme name, {@code [A-Za-z][A-Za-z0-9_-]*}
   * @param scheme the scheme
   */
  public static void securityScheme(AppBuilder app, String name, SecurityScheme scheme) {
    app.contribute(Contribution.class, new Contribution.Security(app.moduleId(), name, scheme));
  }

  /**
   * The operations modules declared, for the HTTP module to pass to {@code Routes.declare}.
   *
   * @param lookup the service lookup
   * @return the operations, in registration order
   */
  public static List<Operation<?, ?>> operations(Lookup lookup) {
    return operations(lookup.contributions(Contribution.class));
  }

  /**
   * The operations modules declared.
   *
   * @param declarations the declarations
   * @return the operations, in registration order
   */
  public static List<Operation<?, ?>> operations(Declarations declarations) {
    return operations(declarations.contributions(Contribution.class));
  }

  private static List<Operation<?, ?>> operations(List<Contribution> all) {
    List<Operation<?, ?>> out = new ArrayList<>();
    for (Contribution c : all) {
      if (c instanceof Contribution.Operations ops) {
        out.addAll(ops.operations());
      }
    }
    return List.copyOf(out);
  }

  /**
   * The {@code openapi} declaration-phase command: {@code java -jar app.jar openapi} writes the
   * document to standard output, {@code openapi --out web/openapi.json} to a file; every problem is
   * printed and the exit code is 1. The HTTP module claims it.
   *
   * @return the command
   */
  public static DeclarationCommand command() {
    return new OpenApiCommand();
  }

  /**
   * Writes the document from the declarations, or lists every problem.
   *
   * @param declarations what the modules declared
   * @return the document text or the problems
   */
  public static OpenApiDocument document(Declarations declarations) {
    return Emitter.emit(declarations.contributions(Contribution.class));
  }
}
