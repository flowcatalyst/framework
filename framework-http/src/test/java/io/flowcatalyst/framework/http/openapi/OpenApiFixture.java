package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.Rules.custom;
import static io.flowcatalyst.framework.http.Rules.each;
import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.minLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.notNull;
import static io.flowcatalyst.framework.http.Rules.oneOf;
import static io.flowcatalyst.framework.http.Rules.pattern;
import static io.flowcatalyst.framework.http.Rules.range;
import static io.flowcatalyst.framework.http.Rules.size;
import static io.flowcatalyst.framework.http.Rules.valid;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The fixture API of the conformance vector {@code conformance/openapi/fixture.json} (contract
 * section 10): every row of the type table, a sealed interface, a generic page, each rule of 5.4,
 * each implied status and both security cases. {@code conformance/openapi/README.md} describes it
 * for the Go emitter, which must reproduce the file byte for byte.
 */
final class OpenApiFixture {

  private OpenApiFixture() {}

  enum Colour {
    RED,
    GREEN,
    BLUE
  }

  /** An application value type: a registered schema, written by Jackson as a string. */
  record Money(String value) {
    @JsonCreator
    Money {}

    @JsonValue
    @Override
    public String value() {
      return value;
    }
  }

  record Scalars(
      String text,
      boolean flag,
      @Nullable Boolean maybeFlag,
      int count,
      @Nullable Integer maybeCount,
      long big,
      @Nullable Long maybeBig,
      double ratio,
      @Nullable Double maybeRatio,
      BigDecimal amount,
      Instant at,
      LocalDate day,
      Duration timeout,
      UUID uuid,
      byte[] blob,
      JsonNode anything,
      Colour colour,
      Money price) {}

  record Ids(ClientId client, PrincipalId principal, ApplicationCode application, CorrelationId correlation) {}

  record Line(String sku, int quantity) {
    static final Validator<Line> V =
        Validator.of(Line.class).field("sku", Line::sku, notBlank()).field("quantity", Line::quantity, range(1, 99));
  }

  record Collections(
      List<String> tags,
      Collection<Integer> counts,
      Set<Colour> colours,
      Map<String, Long> totals,
      List<Line> lines,
      Map<String, List<String>> groups) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Shape.Square.class, name = "square"),
    @JsonSubTypes.Type(value = Shape.Circle.class, name = "circle")
  })
  sealed interface Shape {
    record Circle(double radius) implements Shape {}

    record Square(double side, @Nullable String label) implements Shape {}

    @JsonIgnore
    default boolean isRound() {
      return this instanceof Circle;
    }
  }

  record CursorPage<T>(List<T> items, boolean hasMore, @Nullable String nextCursor) {}

  record Widget(
      String id, String name, Shape shape, Scalars scalars, Ids ids, Collections collections, @Nullable String note) {}

  record WidgetBody(
      String name,
      String code,
      @Nullable String note,
      int quantity,
      double ratio,
      List<String> tags,
      String colour,
      Shape shape,
      Line line,
      List<Line> lines,
      String free) {
    static final Validator<WidgetBody> V =
        Validator.of(WidgetBody.class)
            .field("name", WidgetBody::name, notNull(), notBlank(), maxLength(100))
            .field("code", WidgetBody::code, minLength(3), maxLength(12), pattern("[A-Z][A-Z0-9-]*"))
            .field("note", WidgetBody::note, maxLength(500))
            .field("quantity", WidgetBody::quantity, range(1, 1000))
            .field("ratio", WidgetBody::ratio, range(0.5, 2.5))
            .field("tags", WidgetBody::tags, size(1, 5), each(maxLength(20)))
            .field("colour", WidgetBody::colour, oneOf("red", "green"))
            .nested("line", WidgetBody::line, Line.V)
            .field("lines", WidgetBody::lines, each(valid(Line.V)))
            .field("free", WidgetBody::free, custom("NO_SPACES", "must not contain spaces", s -> !s.contains(" ")));
  }

  record NoteBody(@Nullable String note) {
    static final Validator<NoteBody> V = Validator.of(NoteBody.class).field("note", NoteBody::note, maxLength(500));
  }

  record Pong(Instant at) {}

  static final PathParam<String> ID = PathParam.string("id");
  static final PathParam<Integer> INDEX = PathParam.integer("index");
  static final PathParam<Colour> COLOUR = PathParam.enumOf("colour", Colour.class);
  static final PathParam<Long> VERSION = PathParam.longInt("version");

  static final QueryParam<@Nullable Colour> COLOUR_FILTER = QueryParam.enumOf("colour", Colour.class).optional();
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(20);
  static final QueryParam<@Nullable String> CURSOR = QueryParam.string("cursor").optional();
  static final QueryParam<List<Colour>> STATUS = QueryParam.enumOf("status", Colour.class).list();
  static final QueryParam<Boolean> ARCHIVED = QueryParam.bool("includeArchived").defaultValue(false);
  static final QueryParam<Long> AFTER = QueryParam.longInt("after");

  static final Operation<NoBody, CursorPage<Widget>> LIST =
      Operation.get("/api/widgets", new TypeRef<CursorPage<Widget>>() {})
          .id("listWidgets")
          .summary("List widgets")
          .tag("widgets")
          .query(COLOUR_FILTER, LIMIT, CURSOR, STATUS, ARCHIVED)
          .build();

  static final Operation<NoBody, Widget> READ =
      Operation.get("/api/widgets/{id}", Widget.class)
          .id("getWidget")
          .summary("Read a widget")
          .tag("widgets")
          .path(ID)
          .requiresContext()
          .error(404, "WIDGET_NOT_FOUND", "no widget has this id")
          .build();

  static final Operation<WidgetBody, Widget> CREATE =
      Operation.post("/api/widgets", WidgetBody.V, Widget.class)
          .id("createWidget")
          .summary("Create a widget")
          .tag("widgets")
          .status(201)
          .permission("fixture:widget:manage")
          .error(409, "NAME_TAKEN", "a widget has this name")
          .error(409, "CODE_TAKEN", "a widget has this code")
          .build();

  static final Operation<WidgetBody, Widget> REPLACE =
      Operation.put("/api/widgets/{id}", WidgetBody.V, Widget.class)
          .id("replaceWidget")
          .tag("widgets")
          .path(ID)
          .permission("fixture:widget:manage")
          .build();

  static final Operation<NoteBody, Widget> NOTE =
      Operation.patch("/api/widgets/{id}/note", NoteBody.V, Widget.class)
          .id("noteWidget")
          .tag("widgets")
          .path(ID)
          .status(202)
          .permission("fixture:widget:manage")
          .build();

  static final Operation<NoBody, NoBody> REMOVE =
      Operation.delete("/api/widgets/{id}", NoBody.class)
          .id("deleteWidget")
          .tag("widgets")
          .path(ID)
          .permission("fixture:widget:manage")
          .build();

  static final Operation<NoBody, Shape> SHAPE =
      Operation.get("/api/widgets/{id}/shapes/{index}", Shape.class)
          .id("getWidgetShape")
          .tag("shapes")
          .path(ID, INDEX)
          .query(AFTER)
          .requiresContext()
          .build();

  static final Operation<NoBody, Pong> PING =
      Operation.post("/api/ping", Pong.class)
          .id("ping")
          .summary("Answer at once")
          .description("A public operation without a body or parameters: only the default response.")
          .build();

  static final Operation<NoBody, Widget> BY_COLOUR =
      Operation.get("/api/colours/{colour}/{version}", Widget.class)
          .id("getWidgetByColour")
          .path(VERSION, COLOUR)
          .requiresContext()
          .build();

  static final List<Operation<?, ?>> ALL = List.of(LIST, READ, CREATE, REPLACE, NOTE, REMOVE, SHAPE, PING, BY_COLOUR);

  /** The fixture application's register hook. */
  static void register(AppBuilder app, List<Operation<?, ?>> operations) {
    OpenApi.info(app, "fixture", "1");
    OpenApi.declare(app, operations.toArray(Operation<?, ?>[]::new));
    OpenApi.schema(app, Money.class, Schema.string().pattern("^-?[0-9]+\\.[0-9]{2}$").description("an amount with two decimals"));
    OpenApi.securityScheme(app, "basic", SecurityScheme.httpBasic());
    OpenApi.securityScheme(app, "bearer", SecurityScheme.bearer("JWT"));
    OpenApi.securityScheme(app, "session", SecurityScheme.cookie("__Host-fw_session"));
  }

  /** A builder with one module that registers with {@code register}. */
  static AppBuilder app(Consumer<AppBuilder> register) {
    return App.builder()
        .environment(Map.of())
        .module(
            new Module() {
              @Override
              public String id() {
                return "fixture";
              }

              @Override
              public void register(AppBuilder app) {
                register.accept(app);
              }
            });
  }

  static OpenApiDocument document(Consumer<AppBuilder> register) {
    return OpenApi.document(app(register).declarations());
  }

  static String json(Consumer<AppBuilder> register) {
    return switch (document(register)) {
      case OpenApiDocument.Written w -> w.json();
      case OpenApiDocument.Refused r -> throw new AssertionError("refused:\n  " + String.join("\n  ", r.problems()));
    };
  }

  static List<String> problems(Consumer<AppBuilder> register) {
    return switch (document(register)) {
      case OpenApiDocument.Written w -> throw new AssertionError("expected problems, got a document");
      case OpenApiDocument.Refused r -> r.problems();
    };
  }

  static String fixture() {
    return json(app -> register(app, ALL));
  }
}
