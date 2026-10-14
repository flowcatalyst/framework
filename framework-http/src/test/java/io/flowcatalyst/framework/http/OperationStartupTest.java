package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The start-up problems of OpenAPI contract sections 2 and 3, reported when routes are compiled. */
class OperationStartupTest {

  record View(String id) {}

  static final PathParam<String> ID = PathParam.string("id");

  static Operation.Builder<NoBody, View> get(String pattern) {
    return Operation.get(pattern, View.class);
  }

  static List<String> problems(Consumer<Routes> setUp) {
    Routes routes = new Routes().forModule("m");
    setUp.accept(routes);
    Throwable t = catchThrowable(() -> routes.compile(r -> Context.of(new ClientId("acme")), MonotonicClock.system()));
    assertThat(t).isInstanceOf(StartupException.class);
    return ((StartupException) t).problems().stream().map(Problem::message).toList();
  }

  static List<String> descriptorProblems(Operation<?, ?> op) {
    return problems(r -> {
      r.declare(List.of(op));
      r.bind(op, x -> Outcome.failed(500, "X", "x"));
    });
  }

  static <B, R> OperationHandler<B, R> handler() {
    return r -> Outcome.failed(500, "X", "x");
  }

  @Test
  void aValidOperationCompiles() {
    Operation<NoBody, View> op = get("/v/{id}").id("getView").path(ID).build();
    Routes routes = new Routes();
    routes.declare(List.of(op));
    routes.bind(op, handler());

    assertThat(op.problems()).isEmpty();
    assertThat(routes.compile(null, MonotonicClock.system()).describeRoutes()).contains("GET /v/{id} ((unattributed))");
  }

  @Test
  void aBoundOperationThatWasNotDeclaredFailsStartUp() {
    Operation<NoBody, View> op = get("/v").id("getView").build();

    assertThat(problems(r -> {
          r.declare(List.of());
          r.bind(op, handler());
        }))
        .containsExactly("operation getView is bound but was not declared (OpenApi.declare in register)");
  }

  @Test
  void aHandBuiltTableThatKnowsNoDeclarationsServesBoundOperations() {
    Operation<NoBody, View> op = get("/v").id("getView").build();
    Routes routes = new Routes();
    routes.bind(op, r -> Outcome.ok(new View("1")));

    assertThat(TestClient.of(routes).get("/v").send().body()).isEqualTo("{\"id\":\"1\"}");
  }

  @Test
  void aDeclaredOperationThatIsNotBoundFailsStartUp() {
    Operation<NoBody, View> op = get("/v").id("getView").build();

    assertThat(problems(r -> r.declare(List.of(op))))
        .containsExactly("operation getView is declared but not bound (Routes.bind in boot)");
  }

  @Test
  void anOperationBoundTwiceFailsStartUp() {
    Operation<NoBody, View> op = get("/v").id("getView").build();

    assertThat(problems(r -> {
          r.declare(List.of(op));
          r.bind(op, handler());
          r.bind(op, handler());
        }))
        .containsExactly("operation getView is bound twice");
  }

  @Test
  void twoOperationsWithOneIdFailStartUp() {
    Operation<NoBody, View> a = get("/a").id("getView").build();
    Operation<NoBody, View> b = get("/b").id("getView").build();

    assertThat(problems(r -> {
          r.declare(List.of(a, b));
          r.bind(a, handler());
          r.bind(b, handler());
        }))
        .containsExactly("two operations have the id 'getView': GET /a and GET /b");
  }

  @Test
  void anOperationAndAPlainRouteWithOneShapeCollide() {
    Operation<NoBody, View> op = get("/v/{id}").id("getView").path(ID).build();

    assertThat(problems(r -> {
          r.declare(List.of(op));
          r.bind(op, handler());
          r.get("/v/{other}", x -> Response.noContent());
        }))
        .singleElement()
        .asString()
        .startsWith("duplicate route: GET /v/{other}");
  }

  @Test
  void optionsThatRequireAContextOnAPublicOperationFailStartUp() {
    Operation<NoBody, View> op = get("/v").id("getView").build();

    assertThat(problems(r -> {
          r.declare(List.of(op));
          r.bind(op, handler(), RouteOptions.requiresContext());
        }))
        .singleElement()
        .asString()
        .contains("bound with options that require a context, but the operation does not");
  }

  @Test
  void aContextOperationWithoutAResolverFailsStartUp() {
    Operation<NoBody, View> op = get("/v").id("getView").requiresContext().build();
    Routes routes = new Routes();
    routes.declare(List.of(op));
    routes.bind(op, handler());

    Throwable t = catchThrowable(() -> routes.compile(null, MonotonicClock.system()));
    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("requires a context but no ContextResolver");
  }

  // ---- descriptor problems ------------------------------------------------------------------------

  @Test
  void anOperationNeedsAnId() {
    assertThat(descriptorProblems(get("/v").build()))
        .containsExactly("operation GET /v: an operation needs an id (.id(\"listOrders\"))");
  }

  @Test
  void anIdMustBeACamelCaseIdentifier() {
    assertThat(descriptorProblems(get("/v").id("Get-View").build()))
        .containsExactly("operation Get-View: operation id 'Get-View' must match [a-z][A-Za-z0-9]*");
  }

  @Test
  void tagsAreLowercaseWords() {
    assertThat(descriptorProblems(get("/v").id("getView").tag("Views").build()))
        .containsExactly("operation getView: tag 'Views' must match [a-z][a-z0-9-]*");
  }

  @Test
  void everyPatternParameterIsDeclaredAndEveryDeclaredOneIsInThePattern() {
    assertThat(descriptorProblems(get("/v/{id}").id("getView").build()))
        .containsExactly("operation getView: {id} in the pattern /v/{id} is not declared (.path(PathParam...))");
    assertThat(descriptorProblems(get("/v").id("getView").path(ID).build()))
        .containsExactly("operation getView: path parameter 'id' is not in the pattern /v");
    assertThat(descriptorProblems(get("/v/{id}").id("getView").path(ID, ID).build()))
        .containsExactly("operation getView: path parameter 'id' is declared twice");
  }

  @Test
  void aQueryParameterIsDeclaredOnce() {
    QueryParam<String> q = QueryParam.string("q");
    assertThat(descriptorProblems(get("/v").id("getView").query(q, q).build()))
        .containsExactly("operation getView: query parameter 'q' is declared twice");
  }

  @Test
  void aMalformedPatternIsAProblemNotAnException() {
    assertThat(descriptorProblems(get("v").id("getView").build()))
        .containsExactly("operation getView: route pattern must start with '/': v");
  }

  @Test
  void theSuccessStatusFollowsTheResponse() {
    assertThat(descriptorProblems(get("/v").id("getView").status(204).build()))
        .containsExactly("operation getView: status 204 needs a NoBody response");
    assertThat(descriptorProblems(Operation.delete("/v", NoBody.class).id("deleteView").status(200).build()))
        .containsExactly("operation deleteView: a NoBody response is answered 204, not 200");
    assertThat(descriptorProblems(get("/v").id("getView").status(302).build()))
        .containsExactly("operation getView: the success status is 200, 201, 202 or 204, not 302");
    assertThat(Operation.delete("/v", NoBody.class).id("deleteView").build().status()).isEqualTo(204);
    assertThat(get("/v").id("getView").build().status()).isEqualTo(200);
  }

  @Test
  void declaredErrorsAreFailuresWithCodesAndMessages() {
    assertThat(descriptorProblems(get("/v").id("getView").error(200, "OK", "fine").build()))
        .containsExactly("operation getView: error OK has status 200; an error status is 400 to 599");
    assertThat(descriptorProblems(get("/v").id("getView").error(409, "taken", "taken").build()))
        .containsExactly("operation getView: error code 'taken' must match [A-Z][A-Z0-9_]*");
    assertThat(descriptorProblems(get("/v").id("getView").error(409, "TAKEN", "a").error(409, "TAKEN", "b").build()))
        .containsExactly("operation getView: error 409 TAKEN is declared twice");
    assertThat(descriptorProblems(get("/v").id("getView").error(409, "TAKEN", " ").build()))
        .containsExactly("operation getView: error TAKEN needs a message");
  }

  @Test
  void aPermissionIsNotBlankAndImpliesAContext() {
    assertThat(descriptorProblems(get("/v").id("getView").permission(" ").build()))
        .containsExactly("operation getView: the permission must not be blank");
    assertThat(get("/v").id("getView").permission("a:b").build().requiresContext()).isTrue();
  }

  @Test
  void theDocumentedErrorStatusesFollowTheShape() {
    record Body(String x) {}
    Validator<Body> v = Validator.of(Body.class);
    assertThat(get("/v").id("a").build().documentedErrorStatuses()).isEmpty();
    assertThat(get("/v/{id}").id("a").path(ID).build().documentedErrorStatuses()).containsExactly(400);
    assertThat(get("/v").id("a").query(QueryParam.string("q")).build().documentedErrorStatuses()).containsExactly(400);
    assertThat(get("/v").id("a").requiresContext().build().documentedErrorStatuses()).containsExactly(401, 403);
    assertThat(Operation.post("/v", v, View.class).id("a").build().documentedErrorStatuses()).containsExactly(400, 413, 415);
    assertThat(get("/v").id("a").error(409, "X", "x").error(404, "Y", "y").build().documentedErrorStatuses())
        .containsExactly(404, 409);
  }

  @Test
  void parameterNamesAndTypesAreCheckedAtOnce() {
    assertThat(catchThrowable(() -> PathParam.string("Bad-Name"))).isInstanceOf(IllegalArgumentException.class);
    assertThat(catchThrowable(() -> QueryParam.string("q").optional().list())).isInstanceOf(IllegalStateException.class);
    assertThat(catchThrowable(() -> QueryParam.string("q").list().optional())).isInstanceOf(IllegalStateException.class);
  }
}
