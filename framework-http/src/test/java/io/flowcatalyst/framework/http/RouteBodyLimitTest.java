package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.flowcatalyst.framework.pipeline.Middleware;
import org.junit.jupiter.api.Test;

/** A route's own request body limit ({@link RouteOptions#maxBodyBytes(long)}) in place of the global one. */
class RouteBodyLimitTest {

  record Note(String text) {
    static final Validator<Note> V = Validator.of(Note.class).field("text", Note::text, Rules.notBlank());
  }

  record Echo(int length) {}

  static final Operation<Note, Echo> NOTE =
      Operation.post("/notes", Note.V, Echo.class).id("addNote").build();

  /** A global limit of 16 bytes; /photo takes 100, /tiny 4, /plain the global 16. */
  private static TestClient client() {
    Routes routes = new Routes();
    Handler length = r -> Response.text(200, Integer.toString(r.bodyBytes().length));
    routes.post("/photo", length, RouteOptions.none().maxBodyBytes(100));
    routes.post("/tiny", length, RouteOptions.none().maxBodyBytes(4));
    routes.post("/plain", length);
    routes.post(
        "/streamed",
        r -> {
          try (var in = r.body()) {
            return Response.text(200, Integer.toString(in.readAllBytes().length));
          }
        },
        RouteOptions.none().maxBodyBytes(100));
    Middleware<Request, Response> passThrough = (request, next) -> next.handle(request);
    routes.post("/kept", length, RouteOptions.none().maxBodyBytes(100).with(passThrough).acceptingForms());
    routes.bind(NOTE, r -> Outcome.ok(new Echo(r.body().text().length())), RouteOptions.none().maxBodyBytes(200));
    return TestClient.of(routes).withMaxBodyBytes(16);
  }

  @Test
  void aRouteMayTakeABodyLargerThanTheGlobalLimit() {
    TestResponse ok = client().post("/photo").body("x".repeat(80)).send();
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body()).isEqualTo("80");
    // read without a declared length too
    assertThat(client().post("/photo").body("x".repeat(80)).declaredLength(-1).send().body()).isEqualTo("80");
    assertThat(client().post("/photo").body("x".repeat(101)).send().status()).isEqualTo(413);
    assertThat(client().post("/photo").body("x".repeat(101)).declaredLength(-1).send().status()).isEqualTo(413);
  }

  @Test
  void theLimitHoldsForABodyReadAsAStream() {
    assertThat(client().post("/streamed").body("x".repeat(80)).declaredLength(-1).send().body()).isEqualTo("80");
    assertThat(client().post("/streamed").body("x".repeat(101)).declaredLength(-1).send().status()).isEqualTo(413);
  }

  @Test
  void aRouteMayTakeLessThanTheGlobalLimitByDeclaredLengthOrWhileReading() {
    TestResponse declared = client().post("/tiny").body("12345").send();
    assertThat(declared.status()).isEqualTo(413);
    assertThat(declared.body()).contains("PAYLOAD_TOO_LARGE");
    assertThat(client().post("/tiny").body("12345").declaredLength(-1).send().status()).isEqualTo(413);
    assertThat(client().post("/tiny").body("1234").send().status()).isEqualTo(200);
  }

  @Test
  void otherRoutesAndUnknownPathsKeepTheGlobalLimit() {
    assertThat(client().post("/plain").body("x".repeat(17)).send().status()).isEqualTo(413);
    assertThat(client().post("/plain").body("x".repeat(16)).send().status()).isEqualTo(200);
    assertThat(client().post("/nowhere").body("x".repeat(17)).send().status()).isEqualTo(413);
    assertThat(client().post("/nowhere").body("x".repeat(16)).send().status()).isEqualTo(404);
  }

  @Test
  void middlewareAndFormsKeepTheLimitAndATypedOperationTakesItToo() {
    assertThat(client().post("/kept").body("x".repeat(80)).send().status()).isEqualTo(200);
    TestResponse note =
        client().post("/notes").jsonBody("{\"text\":\"" + "n".repeat(120) + "\"}").send();
    assertThat(note.status()).isEqualTo(200);
    assertThat(note.body()).isEqualTo("{\"length\":120}");
  }

  @Test
  void theLimitIsNotNegativeAndIsReported() {
    assertThatIllegalArgumentException().isThrownBy(() -> RouteOptions.none().maxBodyBytes(-1));
    assertThat(RouteOptions.none().maxBodyBytes()).isEmpty();
    assertThat(RouteOptions.requiresContext().maxBodyBytes(0).maxBodyBytes()).hasValue(0);
    assertThat(RouteOptions.none().maxBodyBytes(5).acceptingForms().maxBodyBytes()).hasValue(5);
  }
}
