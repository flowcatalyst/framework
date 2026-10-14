package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.ServiceError;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Typed operations served (OpenAPI contract section 3): outcomes, permissions, parameters, bodies. */
class OperationRuntimeTest {

  enum Colour {
    RED,
    GREEN
  }

  record Thing(String id, @Nullable String note, BigDecimal price, Colour colour) {}

  record ThingBody(String name, BigDecimal price, @Nullable String note) {
    static final Validator<ThingBody> V =
        Validator.of(ThingBody.class)
            .field("name", ThingBody::name, Rules.notBlank(), Rules.maxLength(10))
            .field("price", ThingBody::price, Rules.range(0, 100));
  }

  record Search(String q, @Nullable String client, int limit, List<Colour> colours, boolean archived) {}

  record Stamp(ClientId client, Instant at, Duration timeout) {}

  record Failure(int httpStatus, String code, String message, Map<String, String> details) implements ServiceError {}

  static final PathParam<Integer> ID = PathParam.integer("id");
  static final QueryParam<String> Q = QueryParam.string("q");
  static final QueryParam<@Nullable String> CLIENT = QueryParam.string("client").optional();
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(20);
  static final QueryParam<List<Colour>> COLOURS = QueryParam.enumOf("colour", Colour.class).list();
  static final QueryParam<Boolean> ARCHIVED = QueryParam.bool("archived").defaultValue(false);

  static final Operation<NoBody, Thing> READ =
      Operation.get("/things/{id}", Thing.class).id("readThing").path(ID).permission("t:thing:read").build();
  static final Operation<ThingBody, Thing> CREATE =
      Operation.post("/things", ThingBody.V, Thing.class).id("createThing").status(201).permission("t:thing:write").build();
  static final Operation<NoBody, NoBody> REMOVE =
      Operation.delete("/things/{id}", NoBody.class).id("removeThing").path(ID).build();
  static final Operation<NoBody, Search> SEARCH =
      Operation.get("/search", Search.class).id("searchThings").query(Q, CLIENT, LIMIT, COLOURS, ARCHIVED).build();
  static final Operation<NoBody, Thing> FAIL =
      Operation.post("/fail", Thing.class).id("failThing").error(409, "TAKEN", "taken").build();
  static final Operation<NoBody, Thing> TEAPOT =
      Operation.post("/teapot", Thing.class).id("teapotThing").build();
  static final Operation<NoBody, Thing> THROWS =
      Operation.get("/throws", Thing.class).id("throwingThing").build();
  static final Operation<NoBody, Stamp> STAMP =
      Operation.get("/stamp", Stamp.class).id("stamp").build();
  static final Operation<NoBody, Thing> LOGOUTISH =
      Operation.post("/bodyless", Thing.class).id("bodyless").build();

  final AtomicInteger calls = new AtomicInteger();

  /** Resolves a context whose permissions come from a header; 401 when it is absent. */
  static final ContextResolver RESOLVER =
      request -> {
        String perms = request.header("X-Perms");
        if (perms == null) {
          throw new HttpException(401, "UNAUTHENTICATED", "sign in");
        }
        return Context.of(new ClientId("acme")).withPermissions(Set.copyOf(Arrays.asList(perms.split(","))));
      };

  TestClient client() {
    Routes routes = new Routes();
    routes.declare(List.of(READ, CREATE, REMOVE, SEARCH, FAIL, TEAPOT, THROWS, STAMP, LOGOUTISH));
    routes.bind(READ, r -> {
      calls.incrementAndGet();
      return Outcome.ok(new Thing("t" + r.path(ID), null, new BigDecimal("12.50"), Colour.RED));
    });
    routes.bind(CREATE, r -> {
      calls.incrementAndGet();
      ThingBody b = r.body();
      return Outcome.ok(new Thing("t1", b.note(), b.price(), Colour.GREEN))
          .header("Location", "/things/1")
          .header("Set-Cookie", "a=1")
          .header("Set-Cookie", "b=2");
    });
    routes.bind(REMOVE, r -> Outcome.noContent());
    routes.bind(SEARCH, r -> Outcome.ok(
        new Search(r.query(Q), r.query(CLIENT), r.query(LIMIT), r.query(COLOURS), r.query(ARCHIVED))));
    routes.bind(FAIL, r -> Outcome.failed(new Failure(409, "TAKEN", "that name is taken", Map.of("field", "name"))));
    routes.bind(TEAPOT, r -> Outcome.failed(418, "TEAPOT", "short and stout"));
    routes.bind(THROWS, r -> {
      throw new HttpException(404, "NOT_FOUND", "no such thing");
    });
    routes.bind(STAMP, r -> Outcome.ok(new Stamp(new ClientId("acme"), Instant.parse("2026-10-14T10:00:00Z"), Duration.ofMinutes(5))));
    routes.bind(LOGOUTISH, r -> Outcome.ok(new Thing("x", null, BigDecimal.ONE, Colour.RED)));
    routes.get("/plain", r -> Response.ok(new Thing("p", null, new BigDecimal("1.5"), Colour.RED)));
    return TestClient.of(routes, RESOLVER);
  }

  // ---- outcomes ----------------------------------------------------------------------------------

  @Test
  void anOkOutcomeIsWrittenWithTheOperationsStatusAndItsHeadersInOrder() {
    TestResponse r =
        client().post("/things").header("X-Perms", "t:thing:write").jsonBody("{\"name\":\"box\",\"price\":\"9.99\",\"note\":\"n\"}").send();

    assertThat(r.status()).isEqualTo(201);
    assertThat(r.header("Content-Type")).isEqualTo("application/json");
    assertThat(r.header("Location")).isEqualTo("/things/1");
    assertThat(r.headerValues("Set-Cookie")).containsExactly("a=1", "b=2");
    assertThat(r.body()).isEqualTo("{\"id\":\"t1\",\"note\":\"n\",\"price\":\"9.99\",\"colour\":\"GREEN\"}");
  }

  @Test
  void nullComponentsAreOmittedOnTypedOperationsButPlainRoutesKeepThem() {
    TestClient c = client();
    String typed = c.get("/things/7").header("X-Perms", "t:thing:read").send().body();
    String plain = c.get("/plain").send().body();

    assertThat(typed).isEqualTo("{\"id\":\"t7\",\"price\":\"12.50\",\"colour\":\"RED\"}");
    assertThat(plain).isEqualTo("{\"id\":\"p\",\"note\":null,\"price\":1.5,\"colour\":\"RED\"}");
  }

  @Test
  void bigDecimalIsReadFromAStringOrANumberAndWrittenAsAString() {
    TestClient c = client();
    TestResponse fromNumber =
        c.post("/things").header("X-Perms", "t:thing:write").jsonBody("{\"name\":\"box\",\"price\":9.990}").send();

    assertThat(fromNumber.status()).isEqualTo(201);
    assertThat(fromNumber.tree().get("price").isString()).isTrue();
    assertThat(fromNumber.tree().get("price").asString()).isEqualTo("9.990");
  }

  @Test
  void aNoBodyResponseIs204WithoutABody() {
    TestResponse r = client().delete("/things/3").send();

    assertThat(r.status()).isEqualTo(204);
    assertThat(r.bytes()).isEmpty();
  }

  @Test
  void contextIdsInstantsAndDurationsAreStrings() {
    assertThat(client().get("/stamp").send().body())
        .isEqualTo("{\"client\":\"acme\",\"at\":\"2026-10-14T10:00:00Z\",\"timeout\":\"PT5M\"}");
  }

  @Test
  void aFailedOutcomeIsTheErrorEnvelopeOfItsServiceError() {
    TestResponse r = client().post("/fail").send();

    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body()).isEqualTo("{\"error\":\"TAKEN\",\"message\":\"that name is taken\",\"details\":{\"field\":\"name\"}}");
  }

  @Test
  void anUndocumentedFailureStatusIsAnsweredAsGivenAndWarnedOnce() {
    OperationRoute.WARNED.remove("teapotThing 418");
    TestClient c = client();
    TestResponse r = c.post("/teapot").send();
    c.post("/teapot").send();

    assertThat(r.status()).isEqualTo(418);
    assertThat(r.tree().get("error").asString()).isEqualTo("TEAPOT");
    assertThat(OperationRoute.WARNED).contains("teapotThing 418");
    assertThat(OperationRoute.WARNED).doesNotContain("failThing 409");
  }

  @Test
  void aThrownHttpExceptionStillMapsToItsEnvelope() {
    TestResponse r = client().get("/throws").send();

    assertThat(r.status()).isEqualTo(404);
    assertThat(r.tree().get("error").asString()).isEqualTo("NOT_FOUND");
  }

  @Test
  void aBodylessPostIgnoresABodySentAnyway() {
    TestResponse r = client().post("/bodyless").header("Content-Type", "text/plain").body("ignored").send();

    assertThat(r.status()).isEqualTo(200);
  }

  // ---- permissions -------------------------------------------------------------------------------

  @Test
  void aMissingPermissionIs403ForbiddenAndTheHandlerDoesNotRun() {
    TestResponse r = client().get("/things/7").header("X-Perms", "t:thing:write").send();

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.body()).isEqualTo("{\"error\":\"FORBIDDEN\",\"message\":\"this needs the permission t:thing:read\"}");
    assertThat(calls).hasValue(0);
  }

  @Test
  void aWildcardPermissionMatches() {
    assertThat(client().get("/things/7").header("X-Perms", "t:*:read").send().status()).isEqualTo(200);
    assertThat(client().get("/things/7").header("X-Perms", "t:*").send().status()).isEqualTo(403);
  }

  @Test
  void anOperationWithAPermissionRequiresAContext() {
    TestResponse r = client().get("/things/7").send();

    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void thePermissionIsCheckedBeforeTheBodyIsRead() {
    TestResponse r = client().post("/things").header("X-Perms", "x:y:z").jsonBody("{not json").send();

    assertThat(r.status()).isEqualTo(403);
    assertThat(calls).hasValue(0);
  }

  // ---- parameters --------------------------------------------------------------------------------

  @Test
  void aPathParameterThatDoesNotParseIs400NamingIt() {
    TestResponse r = client().get("/things/seven").header("X-Perms", "t:thing:read").send();

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body())
        .isEqualTo("{\"error\":\"VALIDATION\",\"message\":\"request is invalid\",\"details\":{\"id\":\"INVALID: must be an integer\"}}");
    assertThat(calls).hasValue(0);
  }

  @Test
  void queryParametersAreRequiredOptionalDefaultedAndRepeated() {
    TestClient c = client();
    assertThat(c.get("/search?q=box").send().body())
        .isEqualTo("{\"q\":\"box\",\"limit\":20,\"colours\":[],\"archived\":false}");
    assertThat(c.get("/search?q=box&client=acme&limit=5&colour=RED&colour=GREEN&archived=true").send().body())
        .isEqualTo("{\"q\":\"box\",\"client\":\"acme\",\"limit\":5,\"colours\":[\"RED\",\"GREEN\"],\"archived\":true}");
  }

  @Test
  void everyParameterProblemIsReportedAtOnce() {
    TestResponse r = client().get("/search?limit=many&colour=PINK&archived=yes").send();

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").propertyNames())
        .containsExactlyInAnyOrder("q", "limit", "colour", "archived");
    assertThat(r.tree().get("details").get("q").asString()).isEqualTo("REQUIRED: is required");
    assertThat(r.tree().get("details").get("colour").asString()).isEqualTo("INVALID: must be one of: RED, GREEN");
    assertThat(r.tree().get("details").get("archived").asString()).isEqualTo("INVALID: must be true or false");
  }

  // ---- bodies ------------------------------------------------------------------------------------

  @Test
  void aBodyThatIsNotJsonIs415() {
    TestResponse r = client().post("/things").header("X-Perms", "t:thing:write").header("Content-Type", "text/plain").body("x").send();

    assertThat(r.status()).isEqualTo(415);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void malformedJsonAndUnknownPropertiesAre400InvalidJsonWithoutTheValue() {
    TestClient c = client();
    TestResponse malformed = c.post("/things").header("X-Perms", "t:thing:write").jsonBody("{\"name\":").send();
    TestResponse unknown =
        c.post("/things").header("X-Perms", "t:thing:write").jsonBody("{\"name\":\"a\",\"price\":1,\"secret\":\"s3cr3t\"}").send();

    assertThat(malformed.status()).isEqualTo(400);
    assertThat(malformed.tree().get("details").get("body").asString()).startsWith("INVALID_JSON");
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.tree().get("details").get("secret").asString()).isEqualTo("INVALID_JSON: unknown property");
    assertThat(unknown.body()).doesNotContain("s3cr3t");
  }

  @Test
  void validationFailuresAreTheEnvelopeWithEveryField() {
    TestResponse r =
        client().post("/things").header("X-Perms", "t:thing:write").jsonBody("{\"name\":\"   \",\"price\":\"101\"}").send();

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body())
        .isEqualTo(
            "{\"error\":\"VALIDATION\",\"message\":\"request is invalid\",\"details\":{\"name\":\"NOT_BLANK: must not be blank\","
                + "\"price\":\"OUT_OF_RANGE: must be between 0 and 100\"}}");
    assertThat(calls).hasValue(0);
  }

  @Test
  void anOperationRequestRefusesAParameterTheOperationDoesNotDeclare() {
    Routes routes = new Routes();
    routes.declare(List.of(REMOVE));
    List<String> seen = new ArrayList<>();
    routes.bind(REMOVE, r -> {
      try {
        r.query(Q);
      } catch (IllegalArgumentException e) {
        seen.add(e.getMessage());
      }
      return Outcome.noContent();
    });
    TestClient.of(routes).delete("/things/1").send();

    assertThat(seen).singleElement().asString().contains("does not declare");
  }
}
